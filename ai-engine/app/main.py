import math
import os
from hmac import compare_digest
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import AwareDatetime, BaseModel, Field

from models import evaluate, predict
from providers import demo, live_odds, normalize_odds
from workflow import temporal_report

app = FastAPI(title="Atlas Bet AI", version="1.0.0")
Sport = Literal["soccer", "tennis", "basketball"]
MODELS = {"soccer":"poisson-rating-v1", "tennis":"surface-elo-v1", "basketball":"home-elo-v1"}


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
        ratio = math.exp(max(-1.5, min(1.5, (home - away) / 400)))
        means = (1.45 * math.sqrt(ratio), 1.15 / math.sqrt(ratio))
        grids = [[math.exp(-rate) * rate**i / math.factorial(i) for i in range(30)] for rate in means]
        result = dict.fromkeys(("home", "draw", "away"), 0.0)
        for i, first in enumerate(grids[0]):
            for j, second in enumerate(grids[1]):
                result["home" if i > j else "away" if i < j else "draw"] += first * second
        total = sum(result.values())
        return {key: value / total for key, value in result.items()}
    if sport == "tennis" and request.homeSurfaceRating is not None and request.awaySurfaceRating is not None:
        home = 0.6 * home + 0.4 * request.homeSurfaceRating
        away = 0.6 * away + 0.4 * request.awaySurfaceRating
    return predict(sport, home, away)


@app.get("/health")
def health():
    return {"status": "UP"}


@app.get("/models/active", dependencies=[Depends(authorize)])
def active():
    return {"versions": MODELS, "modelStatus": "UNVALIDATED", "calibrationVersion": None}


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
