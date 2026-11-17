"""Validation hors échantillon du modèle football (Ligue 1) contre le marché.

Critères FIXÉS AVANT d'exécuter le test (ne pas les ajuster après coup) :
- Entraînement : saisons 2016/17 à 2021/22 (2015/16 sert seulement à initialiser l'Elo).
- Test : 2022/23 jusqu'à la saison en cours, jamais utilisé pour l'ajustement.
- Pari simulé : 1 unité sur l'issue d'EV maximal si p × cote moyenne avant match − 1 ≥ 5 %.
- VALIDATED seulement si ≥ 150 paris simulés ET borne basse de l'intervalle de confiance
  à 95 % du ROI (bootstrap, 2 000 tirages, graine fixe) > 0.
Sinon le modèle reste UNVALIDATED et aucune suggestion réelle n'est émise.

Usage : python validation_football.py data/football-data  (écrit docs/validation/football_ligue1.json)
"""
import json
import math
import random
import sys
from pathlib import Path

from ratings import ELO, elo_walk, football_probabilities, parse_football_data, winner

TRAIN = {"1617", "1718", "1819", "1920", "2021", "2122"}
WARMUP = {"1516"}
THRESHOLD = 0.05
MIN_BETS = 150
# Grille d'ajustement (entraînement seulement). Première exécution : optimum en bordure
# (1,3 / 1,0 / 250) → grille élargie ; les critères de test ci-dessus sont inchangés.
GRID = {"home_goals": (1.1, 1.2, 1.3, 1.4, 1.5, 1.6), "away_goals": (0.8, 0.9, 1.0, 1.1, 1.2, 1.3),
        "scale": (120.0, 160.0, 200.0, 250.0, 300.0, 400.0)}


def load(directory):
    matches = []
    for path in sorted(Path(directory).glob("F1_*.csv")):
        matches += parse_football_data(path.read_text(encoding="utf-8-sig", errors="replace"), path.stem[3:])
    return matches


def log_loss(rows, params):
    return sum(-math.log(football_probabilities(r["home_rating"], r["away_rating"], params)[winner(r)])
               for r in rows) / len(rows)


def fit(rows):
    best = None
    for home in GRID["home_goals"]:
        for away in GRID["away_goals"]:
            for scale in GRID["scale"]:
                params = {"version": "poisson-elo-v2", "home_goals": home, "away_goals": away, "scale": scale}
                loss = log_loss(rows, params)
                if best is None or loss < best[0]:
                    best = (loss, params)
    return best


def de_vig(odds):
    inverse = {key: 1 / value for key, value in odds.items()}
    total = sum(inverse.values())
    return {key: value / total for key, value in inverse.items()}


def bootstrap(profits, draws=2000, seed=20261117):
    rng = random.Random(seed)
    n = len(profits)
    means = sorted(sum(rng.choice(profits) for _ in range(n)) / n for _ in range(draws))
    return means[int(0.025 * draws)], means[int(0.975 * draws) - 1]


def evaluate(rows, params):
    loss = market_loss = brier = 0.0
    bins = [[0, 0.0, 0] for _ in range(10)]
    profits, clv = [], []
    for row in rows:
        probabilities = football_probabilities(row["home_rating"], row["away_rating"], params)
        result = winner(row)
        loss -= math.log(probabilities[result])
        brier += sum((p - (key == result)) ** 2 for key, p in probabilities.items())
        market_loss -= math.log(de_vig(row["avg"])[result])
        for key, p in probabilities.items():
            bucket = bins[min(9, int(p * 10))]
            bucket[0] += 1
            bucket[1] += p
            bucket[2] += key == result
        pick = max(probabilities, key=lambda key: probabilities[key] * row["avg"][key])
        if probabilities[pick] * row["avg"][pick] - 1 >= THRESHOLD:
            profits.append(row["avg"][pick] - 1 if pick == result else -1.0)
            if "close" in row:
                clv.append(row["avg"][pick] / row["close"][pick] - 1)
    n = len(rows)
    roi = sum(profits) / len(profits) if profits else None
    low, high = bootstrap(profits) if len(profits) >= 30 else (None, None)
    total = sum(b[0] for b in bins)
    return {"matches": n, "log_loss": loss / n, "market_log_loss": market_loss / n, "brier": brier / n,
            "ece": sum(abs(p - w) for _, p, w in bins) / total,
            "calibration": [{"forecast": p / c, "frequency": w / c, "count": c} for c, p, w in bins if c],
            "bets": len(profits), "roi": roi, "roi_ci95": [low, high],
            "mean_clv": sum(clv) / len(clv) if clv else None, "clv_count": len(clv)}


def main(directory, output="docs/validation/football_ligue1.json"):
    rows, _ = elo_walk(load(directory))
    rows = [r for r in rows if "avg" in r and r["season"] not in WARMUP]
    train = [r for r in rows if r["season"] in TRAIN]
    test = [r for r in rows if r["season"] not in TRAIN]
    train_loss, params = fit(train)
    report = evaluate(test, params)
    validated = (report["bets"] >= MIN_BETS and report["roi_ci95"][0] is not None and report["roi_ci95"][0] > 0)
    result = {"model": params, "elo": ELO, "train_matches": len(train), "train_log_loss": train_loss,
              "test_seasons": sorted({r["season"] for r in test}), "test": report,
              "criteria": {"threshold_ev": THRESHOLD, "min_bets": MIN_BETS, "rule": "roi_ci95_low > 0"},
              "status": "VALIDATED" if validated else "UNVALIDATED"}
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    summary = main(sys.argv[1] if len(sys.argv) > 1 else "data/football-data")
    test = summary["test"]
    print(json.dumps({"status": summary["status"], "model": summary["model"], "matches": test["matches"],
                      "log_loss": round(test["log_loss"], 4), "market_log_loss": round(test["market_log_loss"], 4),
                      "ece": round(test["ece"], 4), "bets": test["bets"], "roi": test["roi"],
                      "roi_ci95": test["roi_ci95"], "mean_clv": test["mean_clv"]}, indent=1))
