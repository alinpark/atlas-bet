"""Stratégie demandée par l'utilisateur : 50 % du capital sur des favoris « quasi gagnants ».

Règles FIXÉES AVANT l'exécution (28/11/2026), argent fictif uniquement :
- Pari : le favori du marché (cote moyenne la plus basse) si sa cote moyenne ≤ 1,40.
- Cote obtenue : Bet365 (un vrai bookmaker), cote moyenne si Bet365 manque.
- Mise : 50 % du capital TOTAL (disponible + mises en cours) au coup d'envoi, dans la limite
  du disponible : 200 € → deux paris de 100 € (exemple de l'utilisateur), comme le simulateur
  web. Règlement 2 h après le coup d'envoi.
- Capital de départ : 200 €. Ruine = capital total < 10 €.
- Données : 8 championnats (football-data.co.uk), août 2019 → juin 2026, ordre date + heure.
- Pour ne pas choisir un point de départ flatteur : une série de 3 mois démarrant le 1er
  de CHAQUE mois (août 2019 → mars 2026), distribution complète publiée.
- Référence : mêmes paris à 10 € fixes (rendement moyen, sans effet de levier).
- Variante « Atlas d'accord » : en plus, probabilité du moteur sportif ≥ 70 % sur ce favori,
  calculée seulement juillet 2025 → juin 2026 (hors période d'entraînement du moteur).

Usage : python research/leverage_simulation.py
"""
import csv
import io
import json
import math
import statistics
import sys
from datetime import date, datetime, timedelta
from itertools import groupby
from pathlib import Path

sys.path[:0] = ['.', 'ai-engine']
from app.sport_model import State, goal_rate, parse_csv, probabilities, team_key  # noqa: E402

LEAGUES = ['F1', 'E0', 'D1', 'SP1', 'I1', 'N1', 'P1', 'B1']
MAX_ODDS = 1.40
FRACTION = 0.5
START = 200.0
RUIN = 10.0
SIDES = ('home', 'draw', 'away')


def load(cache):
    matches = []
    for path in sorted(Path(cache).glob('*.csv')):
        league = path.stem.split('-')[0]
        if league not in LEAGUES:
            continue
        for r in csv.DictReader(io.StringIO(path.read_bytes().decode('utf-8-sig', errors='replace'))):
            try:
                d, m, y = r['Date'].split('/')
                day = date(int(y) + (2000 if len(y) == 2 else 0), int(m), int(d))
                if day < date(2019, 8, 1):
                    continue
                hh, mm = (r.get('Time') or '15:00').split(':')
                kickoff = datetime(day.year, day.month, day.day, int(hh), int(mm))
                avg = {s: float(r[c]) for s, c in zip(SIDES, ('AvgH', 'AvgD', 'AvgA'))}
                try:
                    book = {s: float(r[c]) for s, c in zip(SIDES, ('B365H', 'B365D', 'B365A'))}
                except (KeyError, ValueError):
                    book = avg
                if not all(v > 1 for v in list(avg.values()) + list(book.values())):
                    continue
                hg, ag = int(r['FTHG']), int(r['FTAG'])
            except (KeyError, ValueError, TypeError):
                continue
            pick = min(SIDES, key=lambda s: avg[s])
            if avg[pick] > MAX_ODDS:
                continue
            actual = 'home' if hg > ag else 'away' if hg < ag else 'draw'
            matches.append({'league': league, 'kickoff': kickoff, 'day': day.isoformat(),
                            'home': team_key(r['HomeTeam']), 'away': team_key(r['AwayTeam']),
                            'pick': pick, 'odds': book[pick], 'won': pick == actual})
    return sorted(matches, key=lambda m: (m['kickoff'], m['league'], m['home']))


def run(bets, start=START):
    cash, open_bets, low, history = start, [], start, []
    for b in bets:
        for o in [o for o in open_bets if o['settle'] <= b['kickoff']]:
            cash += o['stake'] * o['odds'] if o['won'] else 0.0
            open_bets.remove(o)
        total = cash + sum(o['stake'] for o in open_bets)
        low = min(low, total)
        if total < RUIN:
            break
        stake = round(min(cash, total * FRACTION), 2)
        if stake < 0.01:
            continue
        cash -= stake
        open_bets.append({'settle': b['kickoff'] + timedelta(hours=2), 'stake': stake,
                          'odds': b['odds'], 'won': b['won']})
        history.append(stake)
    for o in open_bets:
        cash += o['stake'] * o['odds'] if o['won'] else 0.0
    return {'final': round(cash, 2), 'bets': len(history), 'lowest': round(min(low, cash), 2),
            'ruined': cash < RUIN}


def flat(bets):
    n = len(bets)
    profit = sum((b['odds'] - 1) if b['won'] else -1 for b in bets)
    return {'bets': n, 'win_rate': sum(b['won'] for b in bets) / n if n else None,
            'mean_odds': sum(b['odds'] for b in bets) / n if n else None,
            'roi': profit / n if n else None}


def windows(bets, months=3):
    out = []
    first = date(2019, 8, 1)
    while True:
        end_month = first.month + months
        end = date(first.year + (end_month - 1) // 12, (end_month - 1) % 12 + 1, 1)
        if end > date(2026, 7, 1):
            break
        chunk = [b for b in bets if first <= b['kickoff'].date() < end]
        if chunk:
            out.append({'start': first.isoformat(), **run(chunk)})
        first = date(first.year + first.month // 12, first.month % 12 + 1, 1)
    finals = [w['final'] for w in out]
    return {'series': len(out), 'median_final': statistics.median(finals),
            'ruined_share': sum(w['ruined'] for w in out) / len(out),
            'below_start_share': sum(f < START for f in finals) / len(out),
            'doubled_share': sum(f >= 2 * START for f in finals) / len(out),
            'best': max(out, key=lambda w: w['final']), 'worst': min(out, key=lambda w: w['final']),
            'detail': out}


def atlas_filter(bets, cache):
    weights = json.loads(Path('ai-engine/app/sport_weights.json').read_text(encoding='utf-8'))['weights']
    rows = []
    for path in sorted(Path(cache).glob('*.csv')):
        league = path.stem.split('-')[0]
        if league in LEAGUES:
            rows.extend(parse_csv(path.read_bytes().decode('utf-8-sig', errors='replace'), league))
    wanted = {(b['league'], b['day'], b['home'], b['away']): b for b in bets
              if '2025-07-01' <= b['day'] <= '2026-06-30'}
    kept, state = [], State()
    for day, group in groupby(sorted(rows, key=lambda r: r['date']), key=lambda r: r['date']):
        matches = list(group)
        for r in matches:
            b = wanted.get((r['league'], day, r['home'], r['away']))
            if b:
                hx, ax, hp, ap = state.features(r['home'], r['away'], day)
                if min(hp['matches'], ap['matches']) >= 8 and min(hp['shots_matches'], ap['shots_matches']) >= 6:
                    if probabilities(goal_rate(weights, hx), goal_rate(weights, ax))[b['pick']] >= 0.70:
                        kept.append(b)
        for r in matches:
            state.update(r)
    return sorted(kept, key=lambda m: (m['kickoff'], m['league'], m['home']))


def main(cache='backend/target/sport-training', output='docs/validation/LEVERAGE_50_SIMULATION.json'):
    bets = load(cache)
    season = [b for b in bets if '2025-07-01' <= b['day'] <= '2026-06-30']
    atlas = atlas_filter(bets, cache)
    report = {'rules': __doc__.strip().splitlines()[2:14],
              'all_favourites_2019_2026': {'flat_10_eur': flat(bets), 'three_month_series': windows(bets),
                                           'single_run_from_aug_2019': run(bets)},
              'season_2025_26': {'flat_10_eur': flat(season), 'single_run': run(season)},
              'atlas_agrees_2025_26': {'flat_10_eur': flat(atlas), 'single_run': run(atlas)}}
    series = report['all_favourites_2019_2026']['three_month_series']
    series_detail = series.pop('detail')
    Path(output).write_text(json.dumps({**report, 'series_detail': series_detail}, indent=1,
                                       ensure_ascii=False, default=str) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    print(json.dumps(main(), indent=1, ensure_ascii=False, default=str))
