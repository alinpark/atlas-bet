import json
import os
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import urlopen

from models import MARKETS, predict


def live_odds(key, sports):
    token = os.getenv("THE_ODDS_API_KEY")
    if not token:
        raise ValueError("The Odds API : NOT_CONFIGURED")
    if key not in sports or not key.startswith(("soccer_", "tennis_", "basketball_")):
        raise ValueError("Compétition non autorisée")
    query = urlencode({"apiKey": token, "regions": "fr", "markets": "h2h", "oddsFormat": "decimal"})
    try:
        with urlopen(f"https://api.the-odds-api.com/v4/sports/{key}/odds?{query}", timeout=10) as response:
            quota = {name: response.headers.get(name) for name in ("x-requests-remaining", "x-requests-used")}
            return {"source": "The Odds API", "observed": datetime.now(timezone.utc).isoformat(),
                    "quota": quota, "events": json.load(response)}
    except HTTPError as error:
        raise ValueError(f"The Odds API : HTTP {error.code}") from None
    except (URLError, TimeoutError, ValueError):
        raise ValueError("The Odds API : réponse indisponible ou invalide") from None


def demo(observed=None):
    observed = observed or datetime.now(timezone.utc)
    return [
        {"event": f"DEMO-{sport}", "source": "DEMO / SYNTHÉTIQUE", "sport": sport,
         "observed": observed.isoformat(), "start": (observed+timedelta(hours=1)).isoformat(),
         "market": "1X2" if sport == "soccer" else "winner",
         "odds": {outcome: "2.00" for outcome in outcomes},
         "probabilities": predict(sport, 1650, 1500), "status": "UNVALIDATED"}
        for sport, outcomes in MARKETS.items()
    ]


def normalize_odds(batch):
    """Convert complete, fresh h2h price sets into immutable records."""
    supported_books = {"betclic_fr", "netbet_fr", "pmu_fr", "unibet_fr", "winamax_fr"}
    observed = datetime.fromisoformat(batch["observed"])
    for event in batch["events"]:
        try:
            sport = event["sport_key"].split("_", 1)[0]
            if sport not in MARKETS:
                continue
            start = datetime.fromisoformat(event["commence_time"])
            home, away = event["home_team"], event["away_team"]
            if start <= observed or home == away:
                continue
            names = {home: "home", away: "away"}
            if sport == "soccer":
                names["Draw"] = "draw"
        except (KeyError, TypeError, ValueError, AttributeError):
            continue
        for book in event.get("bookmakers", []):
            if book.get("key") not in supported_books:
                continue
            for market in book.get("markets", []):
                if market.get("key") != "h2h":
                    continue
                try:
                    update = datetime.fromisoformat(market.get("last_update") or book["last_update"])
                    if not observed - timedelta(minutes=15) <= update <= observed:
                        continue
                    outcomes = market["outcomes"]
                    prices = {names[row["name"]]: str(row["price"]) for row in outcomes}
                    if len(outcomes) != len(MARKETS[sport]) or set(prices) != set(MARKETS[sport]):
                        continue
                    if any(not (price.is_finite() and 1 < price <= 10000)
                           for price in map(Decimal, prices.values())):
                        continue
                    yield {"event": f"odds-api:{event['id']}:{book['key']}", "source": "The Odds API",
                           "sport": sport, "competition": event["sport_key"], "bookmaker": book["key"],
                           "source_event_id": event["id"], "home": home, "away": away,
                           "start": start.isoformat(), "observed": observed.isoformat(),
                           "updated": update.isoformat(), "odds": prices, "status": "UNVALIDATED",
                           "market": "h2h"}
                except (KeyError, TypeError, ValueError, InvalidOperation):
                    continue



