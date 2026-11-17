"""Classements Elo football calculés uniquement à partir des résultats (indépendants des cotes).

Une seule implémentation sert au moteur (classements du jour) et à la validation
historique (classements « as-of », avant chaque match).
"""
import csv
import io
import math
from datetime import datetime

ELO = {"k": 20.0, "home_advantage": 60.0, "start": 1500.0, "promoted": 1420.0, "carry": 0.8}

# Modèle football ajusté sur les saisons d'entraînement 2016/17–2021/22 (validation_football.py,
# docs/validation/football_ligue1.json). Statut : UNVALIDATED (ne bat pas le marché en test).
FOOTBALL = {"version": "poisson-elo-v2", "home_goals": 1.2, "away_goals": 0.9, "scale": 200.0}


def parse_date(text):
    for pattern in ("%d/%m/%Y", "%d/%m/%y"):
        try:
            return datetime.strptime(text.strip(), pattern).date()
        except ValueError:
            continue
    raise ValueError(f"Date illisible : {text!r}")


ODDS_COLUMNS = {
    "avg": (("AvgH", "AvgD", "AvgA"), ("BbAvH", "BbAvD", "BbAvA")),  # moyenne du marché avant match
    "max": (("MaxH", "MaxD", "MaxA"), ("BbMxH", "BbMxD", "BbMxA")),
    "close": (("AvgCH", "AvgCD", "AvgCA"),),  # moyenne à la clôture (depuis 2019/20)
    "pinnacle_close": (("PSCH", "PSCD", "PSCA"),),
    "pinnacle": (("PSH", "PSD", "PSA"),),  # référence « sharp » avant match
    "b365": (("B365H", "B365D", "B365A"),),  # un bookmaker grand public réel
}


def _odds(row, name):
    try:
        price = float((row.get(name) or "").strip())
    except ValueError:
        return None
    return price if price > 1 else None


def parse_football_data(text, season):
    """Lignes d'un CSV football-data.co.uk → matchs triés (résultats + cotes si présentes)."""
    matches = []
    for row in csv.DictReader(io.StringIO(text.lstrip("﻿"))):
        if not row.get("HomeTeam") or not (row.get("FTHG") or "").strip():
            continue
        match = {"season": season, "date": parse_date(row["Date"]).isoformat(),
                 "home": row["HomeTeam"].strip(), "away": row["AwayTeam"].strip(),
                 "hg": int(row["FTHG"]), "ag": int(row["FTAG"])}
        for kind, alternatives in ODDS_COLUMNS.items():
            for columns in alternatives:  # anciens fichiers : colonnes « Bb… »
                prices = [_odds(row, column) for column in columns]
                if None not in prices:
                    match[kind] = dict(zip(("home", "draw", "away"), prices))
                    break
        matches.append(match)
    return sorted(matches, key=lambda m: (m["date"], m["home"]))


def _margin(goals):
    goals = abs(goals)
    return 1.0 if goals <= 1 else 1.5 if goals == 2 else (11 + goals) / 8


def elo_walk(matches, params=ELO):
    """Classements avant chaque match (as-of) puis mise à jour. Renvoie (lignes, classements finaux)."""
    ratings, season, rows = {}, None, []
    for match in sorted(matches, key=lambda m: (m["date"], m["home"])):
        if match["season"] != season:
            if season is not None:
                ratings = {team: params["start"] + params["carry"] * (value - params["start"])
                           for team, value in ratings.items()}
            season = match["season"]
            known = list(ratings)
        for team in (match["home"], match["away"]):
            if team not in ratings:
                ratings[team] = params["start"] if not known else params["promoted"]
        home, away = ratings[match["home"]], ratings[match["away"]]
        rows.append(dict(match, home_rating=home, away_rating=away))
        expected = 1 / (1 + 10 ** (-(home + params["home_advantage"] - away) / 400))
        score = 1.0 if match["hg"] > match["ag"] else 0.5 if match["hg"] == match["ag"] else 0.0
        change = params["k"] * _margin(match["hg"] - match["ag"]) * (score - expected)
        ratings[match["home"]] = home + change
        ratings[match["away"]] = away - change
    return rows, ratings


def football_probabilities(home_rating, away_rating, params=FOOTBALL):
    """1X2 par deux lois de Poisson dont les moyennes dépendent de l'écart de classement."""
    ratio = math.exp(max(-1.5, min(1.5, (home_rating - away_rating) / params["scale"])))
    means = (params["home_goals"] * math.sqrt(ratio), params["away_goals"] / math.sqrt(ratio))
    grids = [[math.exp(-rate) * rate ** i / math.factorial(i) for i in range(15)] for rate in means]
    result = {"home": 0.0, "draw": 0.0, "away": 0.0}
    for i, first in enumerate(grids[0]):
        for j, second in enumerate(grids[1]):
            result["home" if i > j else "away" if i < j else "draw"] += first * second
    total = sum(result.values())
    return {key: value / total for key, value in result.items()}


def winner(match):
    return "home" if match["hg"] > match["ag"] else "away" if match["hg"] < match["ag"] else "draw"
