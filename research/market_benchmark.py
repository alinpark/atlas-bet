"""Moteur sportif sport-form-v1 face au marché — protocole FIXÉ AVANT l'exécution (28/11/2026).

Question : le moteur sportif apporte-t-il une information que les cotes n'ont pas déjà ?
- Coefficients gelés lus dans ai-engine/app/sport_weights.json (jamais réajustés ici).
- Caractéristiques reconstruites exactement comme scripts/train_sport_model.py.
- Marché : Pinnacle avant match (PSH/PSD/PSA), marge retirée proportionnellement.
- Mélange : p = w × modèle + (1 − w) × marché, w ∈ {0 ; 0,05 ; … ; 1} choisi par log loss
  sur la période d'ÉTALONNAGE 2025-01-01 → 2025-06-30 uniquement.
- Période de TEST : 2025-07-01 → 2026-06-30, jamais utilisée pour un choix.
- Stratégie principale : une issue par match, celle qui maximise p_mélange × cote Bet365 − 1,
  pariée si ≥ 3 % (Bet365 = approximation d'un bookmaker grand public ; les bookmakers
  français ne figurent pas dans ces données).
- VALIDÉ seulement si : ≥ 300 paris, borne basse IC95 du ROI > 0 ET borne basse IC95 du CLV > 0
  (bootstrap par date, 5 000 tirages, graine 20261128 ; CLV = cote prise × p Pinnacle clôture − 1).
- Descriptif, sans décision : log loss modèle / marché / mélange ; stratégie « favori du
  modèle » (l'intuition « le Barça doit gagner ») ; part des nuls parmi les paris ; même
  stratégie à la cote moyenne.

Usage : python research/market_benchmark.py   (lit backend/target/sport-training/*.csv)
"""
import csv
import io
import json
import math
import random
import sys
from itertools import groupby
from pathlib import Path

sys.path[:0] = ['.', 'ai-engine']
from app.sport_model import State, goal_rate, probabilities, team_key  # noqa: E402

LEAGUES = ['F1', 'E0', 'D1', 'SP1', 'I1', 'N1', 'P1', 'B1']
CALIBRATION = ('2025-01-01', '2025-06-30')
TEST = ('2025-07-01', '2026-06-30')
THRESHOLD = 0.03
MIN_BETS = 300
SEED = 20261128
ODDS = {'pinnacle': ('PSH', 'PSD', 'PSA'), 'close': ('PSCH', 'PSCD', 'PSCA'),
        'b365': ('B365H', 'B365D', 'B365A'), 'avg': ('AvgH', 'AvgD', 'AvgA')}
SIDES = ('home', 'draw', 'away')


def odds_rows(text, league):
    """Mêmes clés (date, équipes) que sport_model.parse_csv, plus les cotes complètes."""
    found = {}
    for r in csv.DictReader(io.StringIO(text.lstrip('﻿'))):
        try:
            d, m, y = r['Date'].split('/')
            day = f"{int(y) + (2000 if len(y) == 2 else 0):04d}-{int(m):02d}-{int(d):02d}"
            prices = {}
            for name, columns in ODDS.items():
                values = [float(r[c]) for c in columns]
                if all(math.isfinite(v) and v > 1 for v in values):
                    prices[name] = dict(zip(SIDES, values))
            found[(league, day, team_key(r['HomeTeam']), team_key(r['AwayTeam']))] = prices
        except (KeyError, ValueError, TypeError):
            continue
    return found


def de_vig(prices):
    inverse = {k: 1 / v for k, v in prices.items()}
    total = sum(inverse.values())
    return {k: v / total for k, v in inverse.items()}


def bootstrap(by_day, draws=5000):
    days = list(by_day.values())
    if len(days) < 20:
        return None
    rng = random.Random(SEED)
    means = []
    for _ in range(draws):
        total = n = 0
        for _ in days:
            sample = days[rng.randrange(len(days))]
            total += sum(sample)
            n += len(sample)
        means.append(total / n)
    means.sort()
    return [means[int(0.025 * draws)], means[int(0.975 * draws) - 1]]


def summary(bets):
    profits, clv = {}, {}
    for b in bets:
        profits.setdefault(b['date'], []).append(b['profit'])
        clv.setdefault(b['date'], []).append(b['clv'])
    n = len(bets)
    return {'bets': n,
            'roi': sum(b['profit'] for b in bets) / n if n else None,
            'roi_ci95': bootstrap(profits) if n else None,
            'mean_clv': sum(b['clv'] for b in bets) / n if n else None,
            'clv_ci95': bootstrap(clv) if n else None,
            'draw_share': sum(b['pick'] == 'draw' for b in bets) / n if n else None,
            'mean_odds': sum(b['odds'] for b in bets) / n if n else None}


def main(cache='backend/target/sport-training', output='docs/validation/SPORT_VS_MARKET.json'):
    weights = json.loads(Path('ai-engine/app/sport_weights.json').read_text(encoding='utf-8'))['weights']
    from app.sport_model import parse_csv
    rows, prices = [], {}
    for path in sorted(Path(cache).glob('*.csv')):
        league = path.stem.split('-')[0]
        if league not in LEAGUES:
            continue
        text = path.read_bytes().decode('utf-8-sig', errors='replace')
        rows.extend(parse_csv(text, league))
        prices.update(odds_rows(text, league))
    state, items = State(), []
    for day, group in groupby(sorted(rows, key=lambda r: r['date']), key=lambda r: r['date']):
        matches = list(group)
        for r in matches:
            hx, ax, hp, ap = state.features(r['home'], r['away'], day)
            if min(hp['matches'], ap['matches']) < 8 or min(hp['shots_matches'], ap['shots_matches']) < 6:
                continue
            if CALIBRATION[0] <= day <= TEST[1]:
                p = probabilities(goal_rate(weights, hx), goal_rate(weights, ax))
                market = prices.get((r['league'], day, r['home'], r['away']), {})
                if {'pinnacle', 'close', 'b365', 'avg'} <= market.keys():
                    actual = 'home' if r['hg'] > r['ag'] else 'away' if r['hg'] < r['ag'] else 'draw'
                    items.append({'date': day, 'league': r['league'], 'model': p, 'actual': actual,
                                  'market': de_vig(market['pinnacle']), 'close': de_vig(market['close']),
                                  'b365': market['b365'], 'avg': market['avg']})
        for r in matches:
            state.update(r)
    calibration = [i for i in items if CALIBRATION[0] <= i['date'] <= CALIBRATION[1]]
    test = [i for i in items if TEST[0] <= i['date'] <= TEST[1]]

    def blend(item, w):
        return {k: w * item['model'][k] + (1 - w) * item['market'][k] for k in SIDES}

    def loss(group, w):
        return sum(-math.log(max(1e-15, blend(i, w)[i['actual']])) for i in group) / len(group)

    grid = [round(0.05 * k, 2) for k in range(21)]
    w = min(grid, key=lambda value: loss(calibration, value))

    def bets(group, book, chooser):
        out = []
        for i in group:
            pick = chooser(i)
            if pick is None:
                continue
            price = i[book][pick]
            out.append({'date': i['date'], 'pick': pick, 'odds': price,
                        'profit': price - 1 if pick == i['actual'] else -1.0,
                        'clv': price * i['close'][pick] - 1})
        return out

    def value(book):
        def chooser(i):
            p = blend(i, w)
            pick = max(SIDES, key=lambda k: p[k] * i[book][k])
            return pick if p[pick] * i[book][pick] - 1 >= THRESHOLD else None
        return chooser

    favourite = lambda i: max(SIDES, key=lambda k: i['model'][k])  # noqa: E731
    primary = summary(bets(test, 'b365', value('b365')))
    validated = (primary['bets'] >= MIN_BETS and primary['roi_ci95'] and primary['roi_ci95'][0] > 0
                 and primary['clv_ci95'] and primary['clv_ci95'][0] > 0)
    report = {
        'protocol': __doc__.strip().splitlines()[0], 'calibration': CALIBRATION, 'test': TEST,
        'matches': {'calibration': len(calibration), 'test': len(test)},
        'blend_weight_model': w,
        'log_loss_test': {'model': loss(test, 1.0), 'market_pinnacle': loss(test, 0.0), 'blend': loss(test, w)},
        'primary_value_b365': primary, 'decision': 'VALIDÉ' if validated else 'NON VALIDÉ',
        'descriptive': {'value_avg': summary(bets(test, 'avg', value('avg'))),
                        'model_favourite_avg': summary(bets(test, 'avg', favourite)),
                        'model_favourite_b365': summary(bets(test, 'b365', favourite))}}
    Path(output).write_text(json.dumps(report, indent=1, ensure_ascii=False) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    print(json.dumps(main(), indent=1, ensure_ascii=False))
