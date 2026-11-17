"""Persistent simulation and timestamped model evaluation."""
import hashlib
import json
import math
import random
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP

from models import MARKETS, edge, evaluate, instant, predict

SCHEMA = """
CREATE TABLE IF NOT EXISTS snapshots (
    digest TEXT PRIMARY KEY, event TEXT NOT NULL, observed TEXT NOT NULL,
    start TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS snapshots_event ON snapshots(event, observed);
CREATE TABLE IF NOT EXISTS simulations (
    event TEXT PRIMARY KEY, digest TEXT NOT NULL REFERENCES snapshots(digest),
    outcome TEXT NOT NULL, odds TEXT NOT NULL, stake INTEGER NOT NULL CHECK(stake > 0),
    payout INTEGER, result TEXT
);
"""


def validate_snapshot(snapshot):
    outcomes = MARKETS.get(snapshot["sport"])
    odds = snapshot["odds"]
    if outcomes is None or set(odds) != set(outcomes):
        raise ValueError("Marché incomplet ou incompatible")
    if instant(snapshot["observed"]) >= instant(snapshot["start"]):
        raise ValueError("Cote observée après le début")
    try:
        prices = [Decimal(str(value)) for value in odds.values()]
    except InvalidOperation as error:
        raise ValueError("Cote invalide") from error
    if any(not value.is_finite() or not 1 < value <= 10000 for value in prices):
        raise ValueError("Cote invalide")


def ingest(ledger, snapshots):
    with ledger.transaction() as db:
        db.executescript(SCHEMA)
    with ledger.transaction() as db:
        for snapshot in snapshots:
            validate_snapshot(snapshot)
            payload = json.dumps(snapshot, sort_keys=True, allow_nan=False)
            digest = hashlib.sha256(payload.encode()).hexdigest()
            db.execute("INSERT OR IGNORE INTO snapshots VALUES (?,?,?,?,?)",
                       (digest, snapshot["event"], instant(snapshot["observed"]).isoformat(),
                        instant(snapshot["start"]).isoformat(), payload))


def scan(ledger, now=None, max_age=timedelta(minutes=15)):
    now = (now or ledger.clock()).astimezone(timezone.utc)
    with ledger.transaction() as db:
        db.executescript(SCHEMA)
    with ledger.transaction() as db:
        rows = db.execute("""
            SELECT s.digest,s.payload FROM snapshots s
            WHERE s.digest=(SELECT x.digest FROM snapshots x WHERE x.event=s.event
                            ORDER BY x.observed DESC,x.digest DESC LIMIT 1)
            AND s.start > ? AND s.observed BETWEEN ? AND ?
            ORDER BY s.start,s.event
        """, (now.isoformat(), (now-max_age).isoformat(), now.isoformat()))
        selections = []
        for digest, payload in rows:
            snapshot = json.loads(payload)
            # Real predictions are not authorized until externally validated.
            if snapshot["source"] != "DEMO / SYNTHÉTIQUE":
                continue
            probabilities, odds = snapshot["probabilities"], snapshot["odds"]
            if set(probabilities) != set(odds) or any(not 0 < p < 1 for p in probabilities.values()):
                continue
            if abs(sum(probabilities.values()) - 1) > 1e-9:
                continue
            outcome = max(odds, key=lambda key: edge(probabilities[key], odds[key]))
            advantage = edge(probabilities[outcome], odds[outcome])
            if advantage > 0:
                selections.append({"event": snapshot["event"], "digest": digest, "outcome": outcome,
                                   "odds": odds[outcome], "ev": str(advantage),
                                   "status": "DEMO / SIMULATION UNIQUEMENT"})
    return selections


def accept_demo(ledger, event, stake=100):
    if type(stake) is not int or not 0 < stake <= 200000:
        raise ValueError("Mise virtuelle invalide")
    with ledger.transaction() as db:
        db.executescript(SCHEMA)
    with ledger.transaction() as db:
        prior = db.execute("SELECT stake FROM simulations WHERE event=?", (event,)).fetchone()
    if prior:
        if prior["stake"] != stake:
            raise ValueError("Simulation déjà enregistrée avec une autre mise")
        return
    selection = next((row for row in scan(ledger) if row["event"] == event), None)
    if selection is None:
        raise ValueError("Sélection démo absente ou expirée")
    with ledger.transaction() as db:
        db.execute("INSERT OR IGNORE INTO simulations VALUES (?,?,?,?,?,NULL,NULL)",
                   (event, selection["digest"], selection["outcome"], selection["odds"], stake))


def settle_demo(ledger, event, result):
    with ledger.transaction() as db:
        db.executescript(SCHEMA)
    with ledger.transaction() as db:
        row = db.execute("""
            SELECT m.*,s.payload,s.start FROM simulations m
            JOIN snapshots s ON s.digest=m.digest WHERE m.event=?
        """, (event,)).fetchone()
        if row is None or result not in (*json.loads(row["payload"])["odds"], "void"):
            raise ValueError("Simulation ou résultat inconnu")
        if row["result"] not in (None, result):
            raise ValueError("Règlement incompatible")
        if result != "void" and ledger.clock() < instant(row["start"]):
            raise ValueError("Événement démo non commencé")
        payout = row["stake"] if result == "void" else 0
        if row["outcome"] == result:
            payout = int((row["stake"] * Decimal(row["odds"])).quantize(Decimal("1"), rounding=ROUND_HALF_UP))
        db.execute("UPDATE simulations SET result=?,payout=? WHERE event=? AND result IS NULL",
                   (result, payout, event))


def simulation_report(ledger):
    with ledger.transaction() as db:
        db.executescript(SCHEMA)
    with ledger.transaction() as db:
        rows = [dict(row) for row in db.execute("SELECT * FROM simulations ORDER BY event")]
    settled = [row for row in rows if row["payout"] is not None]
    stake = sum(row["stake"] for row in settled)
    net = sum(row["payout"] - row["stake"] for row in settled)
    return {"source": "DEMO / SYNTHÉTIQUE", "bets": rows, "stake": stake, "net": net,
            "roi": net / stake if stake else None}


def temporal_report(samples):
    """Evaluate fixed baselines using only pre-event observations."""
    verified = []
    seen = set()
    for row in sorted(samples, key=lambda item: instant(item["start"])):
        observed, start, settled = (instant(row[key]) for key in ("observed", "start", "settled"))
        if not observed < start <= settled or row["event"] in seen:
            raise ValueError("Chronologie invalide ou événement dupliqué")
        seen.add(row["event"])
        verified.append(dict(row, observed=observed, start=start, settled=settled))
    by_sport = {}
    for sport in MARKETS:
        rows = [row for row in verified if row["sport"] == sport]
        uniform = 1 - 1 / len(MARKETS[sport])
        by_sport[sport] = dict(evaluate(rows), uniform_brier=uniform if rows else None)
    return {"status": "UNVALIDATED", "overall": evaluate(verified), "sports": by_sport,
            "walk_forward": walk_forward(verified), "price_baselines": price_baselines(verified)}


def walk_forward(samples):
    """Fit temperature by sport on settled history before each forecast."""
    temperatures = (0.7, 1.0, 1.4, 2.0)
    ordered = sorted(samples, key=lambda row: row["observed"])
    pending = sorted(samples, key=lambda row: row["settled"])
    losses = {sport: [0.0] * len(temperatures) for sport in MARKETS}
    counts = dict.fromkeys(MARKETS, 0)
    predictions = {}
    index, score, uniform, evaluated = 0, 0.0, 0.0, 0

    def probabilities(row):
        raw = predict(row["sport"], row["home_rating"], row["away_rating"])
        for temperature in temperatures:
            weights = {key: value ** (1 / temperature) for key, value in raw.items()}
            yield weights[row["winner"]] / sum(weights.values())

    for row in ordered:
        while index < len(pending) and pending[index]["settled"] < row["observed"]:
            historic = pending[index]
            sport = historic["sport"]
            for candidate, probability in enumerate(predictions[historic["event"]]):
                losses[sport][candidate] -= math.log(probability)
            counts[sport] += 1
            index += 1
        sport = row["sport"]
        candidates = list(probabilities(row))
        predictions[row["event"]] = candidates
        if counts[sport] >= 10:
            selected = min(range(len(temperatures)), key=lambda i: losses[sport][i])
            score -= math.log(candidates[selected])
            uniform += math.log(len(MARKETS[sport]))
            evaluated += 1
    return {"evaluated": evaluated, "min_training_per_sport": 10,
            "log_loss": score / evaluated if evaluated else None,
            "uniform_log_loss": uniform / evaluated if evaluated else None}




def price_baselines(samples):
    """Compare fixed one-unit selections on complete pre-event odds."""
    random_source = random.Random(42)
    nets = dict.fromkeys(("favorite", "random"), Decimal(0))
    brier, count = 0.0, 0
    for row in samples:
        if "odds" not in row:
            continue
        validate_snapshot(row)
        prices = {key: Decimal(str(row["odds"][key])) for key in MARKETS[row["sport"]]}
        reciprocal = {key: float(1 / value) for key, value in prices.items()}
        total = sum(reciprocal.values())
        brier += sum((value / total - (key == row["winner"])) ** 2 for key, value in reciprocal.items())
        selections = {"favorite": min(prices, key=prices.get), "random": random_source.choice(tuple(prices))}
        for method, selection in selections.items():
            nets[method] += prices[selection] - 1 if selection == row["winner"] else -1
        count += 1
    return {"count": count, "de_vig_brier": brier / count if count else None,
            "roi": {key: str(net / count) if count else None for key, net in nets.items()}}
