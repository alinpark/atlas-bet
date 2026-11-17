import math
from decimal import Decimal
from datetime import datetime, timezone

MARKETS = {"soccer": ("home", "draw", "away"), "tennis": ("home", "away"), "basketball": ("home", "away")}


def instant(value):
    parsed = datetime.fromisoformat(value) if isinstance(value, str) else value
    if not isinstance(parsed, datetime) or parsed.utcoffset() is None:
        raise ValueError("Horodatage avec fuseau requis")
    return parsed.astimezone(timezone.utc)


def predict(sport, home, away):
    """Baseline Elo independent of bookmaker prices. No real validation."""
    if sport not in MARKETS or any(not math.isfinite(r) or not 0 <= r <= 4000 for r in (home, away)):
        raise ValueError("Sport ou classement invalide")
    advantage = {"soccer": 65, "basketball": 80, "tennis": 0}[sport]
    strength = 10 ** ((home + advantage - away) / 400)
    draw = 0.7 * math.sqrt(strength) if sport == "soccer" else 0
    total = strength + draw + 1
    probabilities = {"home": strength / total, "away": 1 / total}
    if draw:
        probabilities["draw"] = draw / total
    return probabilities


def edge(probability, odds):
    return Decimal(str(probability)) * Decimal(str(odds)) - 1


def evaluate(samples):
    """Score predictions whose features were captured before their event."""
    count, brier, loss = 0, 0.0, 0.0
    bins = [[0, 0.0, 0] for _ in range(10)]
    for sample in samples:
        observed, start, settled = (instant(sample[key]) for key in ("observed", "start", "settled"))
        if not observed < start <= settled:
            raise ValueError("Chronologie invalide")
        probabilities = predict(sample["sport"], sample["home_rating"], sample["away_rating"])
        outcome = sample["winner"]
        if outcome not in probabilities:
            raise ValueError("Issue invalide")
        count += 1
        brier += sum((p - (key == outcome)) ** 2 for key, p in probabilities.items())
        loss -= math.log(probabilities[outcome])
        for key, p in probabilities.items():
            bucket = bins[min(9, int(10 * p))]
            bucket[0] += 1
            bucket[1] += p
            bucket[2] += key == outcome
    return {"count": count, "brier": brier / count if count else None,
            "log_loss": loss / count if count else None,
            "calibration": [{"count": n, "forecast": p/n, "frequency": wins/n}
                            for n, p, wins in bins if n],
            "ece": sum(abs(p - wins) for n, p, wins in bins) / sum(n for n, _, _ in bins) if count else None,
            "status": "UNVALIDATED"}
