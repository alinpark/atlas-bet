"""Backtests des pistes 1 et 2 (17 novembre 2026). Critères FIXÉS AVANT l'exécution.

Piste 1 — comparaison avec une référence « sharp » :
  probabilité juste = cotes Pinnacle avant match, marge retirée (proportionnelle) ;
  pari de 1 unité sur une issue si cote du bookmaker × probabilité juste − 1 ≥ 3 % ;
  bookmakers testés : « avg » (moyenne du marché, approximation réaliste d'un bookmaker
  grand public) et « b365 » (un bookmaker réel). « max » (meilleure cote du marché) n'est
  qu'une borne optimiste, NON décisionnelle.
Piste 2 — modèle Elo + Poisson sur des ligues moins suivies (Ligue 2, Championship,
  2. Bundesliga, Serie B, Liga 2), ajusté sur 2020/21–2021/22 (2019/20 = initialisation),
  pari si p × cote moyenne − 1 ≥ 5 %.

Période de décision : 2022/23 → saison en cours (aucun réglage n'y est fait).
Critère, pour chacun des 3 tests PRINCIPAUX (piste 1 avg, piste 1 b365, piste 2), toutes
ligues confondues : ≥ 300 paris, borne basse de l'IC 95 % du ROI > 0 ET CLV moyen > 0
(CLV = cote prise × probabilité juste à la clôture Pinnacle − 1). Les résultats par ligue
sont informatifs (tests multiples : ne pas en tirer de conclusion).

Usage : python validation_markets.py data/football-data → docs/validation/markets.json
"""
import json
import sys
from pathlib import Path

from ratings import elo_walk, football_probabilities, parse_football_data, winner
from validation_football import bootstrap, de_vig, fit, log_loss

LEAGUES = {"F1": "Ligue 1", "F2": "Ligue 2", "E1": "Championship", "D2": "2. Bundesliga",
           "I2": "Serie B", "SP2": "Liga 2"}
SECOND_DIVISIONS = ("F2", "E1", "D2", "I2", "SP2")
DECISION = {"2223", "2324", "2425", "2526", "2627"}
FIT = {"2021", "2122"}
LINE_THRESHOLD = 0.03
MODEL_THRESHOLD = 0.05
MIN_BETS = 300


def load(directory, league):
    matches = []
    for path in sorted(Path(directory).glob(f"{league}_*.csv")):
        season = path.stem.split("_")[1]
        if season >= "1920":
            matches += parse_football_data(path.read_text(encoding="utf-8-sig", errors="replace"), season)
    return matches


def summarize(bets):
    profits = [b["profit"] for b in bets]
    clv = [b["clv"] for b in bets if b["clv"] is not None]
    low, high = bootstrap(profits) if len(profits) >= 30 else (None, None)
    return {"bets": len(bets), "roi": sum(profits) / len(profits) if profits else None,
            "roi_ci95": [low, high], "mean_clv": sum(clv) / len(clv) if clv else None,
            "mean_odds": sum(b["odds"] for b in bets) / len(bets) if bets else None}


def passes(summary):
    return (summary["bets"] >= MIN_BETS and summary["roi_ci95"][0] is not None and summary["roi_ci95"][0] > 0
            and summary["mean_clv"] is not None and summary["mean_clv"] > 0)


def closing_fair(match):
    return de_vig(match["pinnacle_close"]) if "pinnacle_close" in match else None


def line_bets(matches, book):
    """Piste 1 : au plus un pari par match (meilleur écart), sans aucun modèle propre."""
    bets = []
    for match in matches:
        if book not in match or "pinnacle" not in match:
            continue
        fair = de_vig(match["pinnacle"])
        prices = match[book]
        pick = max(fair, key=lambda k: prices[k] * fair[k])
        if prices[pick] * fair[pick] - 1 < LINE_THRESHOLD:
            continue
        close = closing_fair(match)
        bets.append({"league": match["league"], "season": match["season"], "odds": prices[pick],
                     "profit": prices[pick] - 1 if winner(match) == pick else -1.0,
                     "clv": prices[pick] * close[pick] - 1 if close else None})
    return bets


def model_bets(rows, params):
    bets = []
    for row in rows:
        if "avg" not in row:
            continue
        probabilities = football_probabilities(row["home_rating"], row["away_rating"], params)
        pick = max(probabilities, key=lambda k: probabilities[k] * row["avg"][k])
        if probabilities[pick] * row["avg"][pick] - 1 < MODEL_THRESHOLD:
            continue
        close = closing_fair(row)
        bets.append({"league": row["league"], "season": row["season"], "odds": row["avg"][pick],
                     "profit": row["avg"][pick] - 1 if winner(row) == pick else -1.0,
                     "clv": row["avg"][pick] * close[pick] - 1 if close else None})
    return bets


def main(directory, output="docs/validation/markets.json"):
    report = {"criteria": {"decision_seasons": sorted(DECISION), "line_threshold": LINE_THRESHOLD,
                           "model_threshold": MODEL_THRESHOLD, "min_bets": MIN_BETS,
                           "rule": "bets >= 300 AND roi_ci95_low > 0 AND mean_clv > 0 (tests principaux)"},
              "piste1": {}, "piste2": {}}
    all_matches = {}
    for league in LEAGUES:
        matches = [dict(m, league=league) for m in load(directory, league)]
        all_matches[league] = matches
    decision = [m for league in LEAGUES for m in all_matches[league] if m["season"] in DECISION]
    for book in ("avg", "b365", "max"):
        bets = line_bets(decision, book)
        entry = {"overall": summarize(bets),
                 "by_league": {LEAGUES[l]: summarize([b for b in bets if b["league"] == l]) for l in LEAGUES}}
        entry["decision"] = "INFORMATIF (borne optimiste)" if book == "max" else (
            "VALIDÉ" if passes(entry["overall"]) else "NON VALIDÉ")
        report["piste1"][book] = entry
    # Piste 2 : Elo par ligue, paramètres ajustés sur les saisons FIT des 2es divisions.
    rows = []
    for league in SECOND_DIVISIONS:
        walked, _ = elo_walk(all_matches[league])
        rows += [r for r in walked if r["season"] != "1920"]
    train = [r for r in rows if r["season"] in FIT]
    test = [r for r in rows if r["season"] in DECISION]
    train_loss, params = fit(train)
    market_loss = sum(-__import__("math").log(de_vig(r["avg"])[winner(r)]) for r in test if "avg" in r)
    with_odds = [r for r in test if "avg" in r]
    bets = model_bets(test, params)
    report["piste2"] = {"model": params, "train_matches": len(train), "train_log_loss": train_loss,
                        "test_matches": len(with_odds), "test_log_loss": log_loss(with_odds, params),
                        "market_log_loss": market_loss / len(with_odds), "overall": summarize(bets),
                        "by_league": {LEAGUES[l]: summarize([b for b in bets if b["league"] == l])
                                      for l in SECOND_DIVISIONS}}
    report["piste2"]["decision"] = "VALIDÉ" if passes(report["piste2"]["overall"]) else "NON VALIDÉ"
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(json.dumps(report, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return report


def _fmt(summary):
    roi = "—" if summary["roi"] is None else f"{summary['roi'] * 100:+.1f} %"
    ci = summary["roi_ci95"]
    ci_text = "" if ci[0] is None else f" [IC {ci[0] * 100:+.1f} ; {ci[1] * 100:+.1f}]"
    clv = "—" if summary["mean_clv"] is None else f"{summary['mean_clv'] * 100:+.2f} %"
    return f"{summary['bets']:>5} paris, ROI {roi}{ci_text}, CLV {clv}"


if __name__ == "__main__":
    result = main(sys.argv[1] if len(sys.argv) > 1 else "data/football-data")
    for book, entry in result["piste1"].items():
        print(f"Piste 1 [{book}] {entry['decision']} : {_fmt(entry['overall'])}")
        for league, summary in entry["by_league"].items():
            print(f"    {league:<14} {_fmt(summary)}")
    p2 = result["piste2"]
    print(f"Piste 2 {p2['decision']} : {_fmt(p2['overall'])} ; log loss {p2['test_log_loss']:.4f} "
          f"vs marché {p2['market_log_loss']:.4f} ; modèle {p2['model']}")
    for league, summary in p2["by_league"].items():
        print(f"    {league:<14} {_fmt(summary)}")
