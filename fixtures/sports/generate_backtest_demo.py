"""Génère backtest_demo.json : historique DEMO / SYNTHÉTIQUE, reproductible (graine fixe).

Les résultats sont tirés selon les probabilités du modèle de base : ce jeu sert à vérifier
que le backtest tourne et que ses métriques sont recalculables, pas à valider le modèle.
Usage : python fixtures/sports/generate_backtest_demo.py
"""
import json
import random
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from models import MARKETS, predict  # noqa: E402


def generate(count=90, seed=20261117):
    rng = random.Random(seed)
    start = datetime(2026, 1, 5, 18, tzinfo=timezone.utc)
    rows = []
    for index in range(count):
        sport = tuple(MARKETS)[index % 3]
        home, away = rng.randint(1350, 1750), rng.randint(1350, 1750)
        probabilities = predict(sport, home, away)
        outcomes = list(probabilities)
        winner = rng.choices(outcomes, weights=[probabilities[o] for o in outcomes])[0]
        # Cotes synthétiques avec marge de 6 % autour des probabilités du modèle, bruitées.
        odds = {o: f"{max(1.01, 1 / (probabilities[o] * 1.06) * rng.uniform(0.92, 1.08)):.2f}" for o in outcomes}
        kickoff = start + timedelta(days=index // 3, hours=index % 3)
        rows.append({"event": f"DEMO-BT-{index:03d}", "source": "DEMO / SYNTHÉTIQUE", "sport": sport,
                     "observed": (kickoff - timedelta(hours=3)).isoformat(), "start": kickoff.isoformat(),
                     "settled": (kickoff + timedelta(hours=2, minutes=30)).isoformat(),
                     "home_rating": home, "away_rating": away, "winner": winner, "odds": odds})
    return rows


if __name__ == "__main__":
    target = Path(__file__).with_name("backtest_demo.json")
    target.write_text(json.dumps(generate(), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{target} écrit")
