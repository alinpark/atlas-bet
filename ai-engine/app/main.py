import math
import os
from hmac import compare_digest
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import AwareDatetime, BaseModel, Field

from models import evaluate, predict
from providers import demo, live_odds, normalize_odds
from ratings import FOOTBALL, elo_walk, football_probabilities, parse_football_data, winner
from workflow import temporal_report

app = FastAPI(title="Atlas Bet AI", version="1.1.0")
Sport = Literal["soccer", "tennis", "basketball"]
MODELS = {"soccer": FOOTBALL["version"], "tennis": "surface-elo-v1", "basketball": "home-elo-v1"}


def authorize(x_engine_token: str = Header(default="")):
    expected = os.getenv("BET_ENGINE_TOKEN", "")
    if not expected or not compare_digest(x_engine_token.encode(), expected.encode()):
        raise HTTPException(401, "Authentification requise")


class PredictRequest(BaseModel):
    eventId: str = Field(min_length=1, max_length=128)
    featureTimestamp: AwareDatetime
    startsAt: AwareDatetime
    homeRating: float = Field(ge=0, le=4000)
    awayRating: float = Field(ge=0, le=4000)
    homeSurfaceRating: float | None = Field(default=None, ge=0, le=4000)
    awaySurfaceRating: float | None = Field(default=None, ge=0, le=4000)
    provenance: str = Field(min_length=1, max_length=128)


def probabilities(sport, request):
    home, away = request.homeRating, request.awayRating
    if sport == "soccer":
        return football_probabilities(home, away)
    if sport == "tennis" and request.homeSurfaceRating is not None and request.awaySurfaceRating is not None:
        home = 0.6 * home + 0.4 * request.homeSurfaceRating
        away = 0.6 * away + 0.4 * request.awaySurfaceRating
    return predict(sport, home, away)


@app.get("/health")
def health():
    return {"status": "UP"}


class SportingRequest(BaseModel):
    competition: str = Field(min_length=1, max_length=100)
    home: str = Field(min_length=1, max_length=120)
    away: str = Field(min_length=1, max_length=120)
    startsAt: AwareDatetime


@app.post('/analysis/football', dependencies=[Depends(authorize)])
def sporting_analysis(request: SportingRequest):
    from app.free_football import analyze
    try:
        return analyze(request.model_dump(mode='json'))
    except (ValueError, KeyError, TypeError):
        raise HTTPException(503, 'Analyse sportive indisponible ; aucune prévision de remplacement')


class Season(BaseModel):
    season: str = Field(pattern=r"^\d{4}$")
    csv: str = Field(max_length=2_000_000)


@app.post("/ratings/football-data", dependencies=[Depends(authorize)])
def football_data(seasons: list[Season]):
    """Classements Elo du jour et résultats récents depuis les CSV football-data.co.uk (résultats seuls)."""
    try:
        matches = [match for item in seasons for match in parse_football_data(item.csv, item.season)]
    except (ValueError, KeyError) as error:
        raise HTTPException(422, "CSV football-data illisible") from error
    if not matches:
        raise HTTPException(422, "Aucun match terminé")
    _, ratings = elo_walk(matches)
    last = max(match["date"] for match in matches)
    current = max(item.season for item in seasons)
    return {"ratings": {team: round(value, 2) for team, value in ratings.items()},
            "teams": sorted({m["home"] for m in matches if m["season"] == current}),
            "asOf": last, "matches": len(matches),
            "results": [{"date": m["date"], "home": m["home"], "away": m["away"], "winner": winner(m)}
                        for m in matches if m["season"] == current]}


@app.get("/models/active", dependencies=[Depends(authorize)])
def active():
    return {"versions": MODELS, "modelStatus": "UNVALIDATED", "calibrationVersion": None}


@app.get("/models/metrics", dependencies=[Depends(authorize)])
def metrics():
    return {"versions": MODELS, "modelStatus": "UNVALIDATED", "calibrationVersion": None, "metrics": None,
            "reason": "Aucune validation empirique sur données réelles ; métriques disponibles via POST /backtest."}


@app.post("/predict/{sport}", dependencies=[Depends(authorize)])
def forecast(sport: Sport, request: PredictRequest):
    if request.featureTimestamp >= request.startsAt:
        raise HTTPException(422, "Features hors chronologie")
    return {
        "eventId": request.eventId, "market": "1X2" if sport == "soccer" else "winner",
        "modelVersion": MODELS[sport], "modelStatus": "UNVALIDATED",
        "calibrationVersion": None, "featureTimestamp": request.featureTimestamp,
        "provenance": request.provenance, "dataQuality": "UNVERIFIED",
        "outcomes": [{"outcome": name, "p": p, "pLower": None, "pUpper": None}
                     for name, p in probabilities(sport, request).items()],
    }


@app.post("/evaluate", dependencies=[Depends(authorize)])
def evaluation(samples: list[dict]):
    try:
        return evaluate(samples)
    except (ValueError, TypeError, KeyError) as error:
        raise HTTPException(422, "Echantillon invalide") from error


@app.post("/backtest", dependencies=[Depends(authorize)])
def backtest(samples: list[dict]):
    try:
        return temporal_report(samples)
    except (ValueError, TypeError, KeyError) as error:
        raise HTTPException(422, "Echantillon invalide") from error


@app.get("/collect/demo", dependencies=[Depends(authorize)])
def fixture_collection():
    return demo()


@app.get("/collect/odds/{competition}", dependencies=[Depends(authorize)])
def collect(competition: str):
    allowed = os.getenv("SPORTS_ODDS_COMPETITIONS", "").split(",")
    try:
        batch = live_odds(competition, allowed)
        return {"quota": batch["quota"], "snapshots": list(normalize_odds(batch))}
    except ValueError as error:
        raise HTTPException(503, str(error)) from None
