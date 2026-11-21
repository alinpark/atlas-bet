"""Pure hist-fr-pinnacle-v1 rules, offline only. No provider calls or production writes.

The caller must supply the decision time and kickoff known before that decision.
Final fixture schedules must never be used to reconstruct that knowledge.
"""
from datetime import datetime, timedelta
import math

from research.coverage_audit import FR


def instant(value):
    value = datetime.fromisoformat(value.replace('Z', '+00:00')) if isinstance(value, str) else value
    if value.tzinfo is None:
        raise ValueError('Timezone required')
    return value


def market(book, home, away, decision, snapshot_at):
    markets = [m for m in book.get('markets', []) if m.get('key') == 'h2h']
    if len(markets) != 1:
        return None
    m = markets[0]
    stamp = m.get('last_update', book.get('last_update'))
    if not stamp:
        return None
    updated = instant(stamp)
    if updated > snapshot_at or not timedelta(0) <= decision - updated <= timedelta(minutes=15):
        return None
    outcomes = m.get('outcomes', [])
    if len(outcomes) != 3 or {x['name'] for x in outcomes} != {home, away, 'Draw'}:
        return None
    prices = {x['name']: float(x['price']) for x in outcomes}
    if any(not math.isfinite(x) or not 1 < x <= 10000 for x in prices.values()):
        return None
    return prices, updated


def view(snapshot, event_id, decision_at, known_start, minutes):
    decision, start = instant(decision_at), instant(known_start)
    if start - decision != timedelta(minutes=minutes):
        return None, 'DECISION_NOT_AT_FIXED_WINDOW'
    at = instant(snapshot['timestamp'])
    if not timedelta(0) <= decision - at <= timedelta(minutes=10):
        return None, 'SNAPSHOT_MISSING_STALE_OR_FUTURE'
    events = [e for e in snapshot.get('data', []) if e['id'] == event_id]
    if len(events) != 1:
        return None, 'EVENT_MISSING_OR_DUPLICATED'
    event = events[0]
    if event.get('sport_key') != 'soccer_france_ligue_one':
        return None, 'WRONG_COMPETITION'
    if instant(event['commence_time']) != start:
        return None, 'SCHEDULE_CHANGED_OR_NOT_KNOWN'
    books = event.get('bookmakers', [])
    if len({b['key'] for b in books}) != len(books):
        return None, 'DUPLICATE_BOOKMAKER'
    valid = {}
    for b in books:
        value = market(b, event['home_team'], event['away_team'], decision, at)
        if value:
            valid[b['key']] = value
    if 'pinnacle' not in valid:
        return None, 'PINNACLE_UNAVAILABLE'
    ref, stamp = valid['pinnacle']
    total = sum(1 / p for p in ref.values())
    fair = {name: (1 / p) / total for name, p in ref.items()}
    return {'event': event, 'markets': valid, 'fair': fair, 'stamp': stamp}, None


def entry(snapshot, event_id, decision_at, known_start):
    data, reason = view(snapshot, event_id, decision_at, known_start, 60)
    if reason:
        return {'eligible': False, 'reason': reason}
    books = {key: value for key, value in data['markets'].items()
             if key in FR and abs((value[1] - data['stamp']).total_seconds()) <= 300}
    if len(books) < 3:
        return {'eligible': False, 'reason': 'FEWER_THAN_THREE_FR'}
    choices = []
    for book, (prices, updated) in books.items():
        for name, price in prices.items():
            edge = price * data['fair'][name] - 1
            if edge >= .03:
                choices.append((edge, book, name, price, updated))
    if not choices:
        return {'eligible': False, 'reason': 'NO_EDGE_AT_FIXED_WINDOW'}
    # Biggest edge, then bookmaker key, then literal outcome name, ascending.
    edge, book, outcome, price, updated = sorted(choices, key=lambda x: (-x[0], x[1], x[2]))[0]
    return {'eligible': True, 'event_id': event_id, 'decision_at': instant(decision_at).isoformat(),
            'known_start': instant(known_start).isoformat(), 'bookmaker': book, 'outcome': outcome,
            'odds': price, 'edge': edge, 'updated_at': updated.isoformat(),
            'home': data['event']['home_team'], 'away': data['event']['away_team']}


def reference(observation, snapshot, decision_at):
    if not observation.get('eligible'):
        raise ValueError('An admissible entry is required')
    data, reason = view(snapshot, observation['event_id'], decision_at, observation['known_start'], 10)
    if reason:
        return {'clv': None, 'reason': reason}
    event = data['event']
    if event['home_team'] != observation['home'] or event['away_team'] != observation['away']:
        return {'clv': None, 'reason': 'PARTICIPANTS_CHANGED'}
    p = data['fair'][observation['outcome']]
    return {'clv': observation['odds'] * p - 1,
            'clv_stress': observation['odds'] * .99 * p - 1, 'reason': None}


def unit_profit(observation, result):
    """A result is an independently checked, event-matched settlement, not a raw score."""
    if not observation.get('eligible'):
        raise ValueError('An admissible entry is required')
    if not result or result.get('event_id') != observation['event_id']:
        return None
    if result.get('status') == 'VOID' and result.get('settlement_rule_verified') is True:
        return 0.0
    if result.get('status') != 'SETTLED_90' or result.get('verified') is not True:
        return None
    winner = result.get('winner')
    if winner not in (observation['home'], observation['away'], 'Draw'):
        return None
    return observation['odds'] - 1 if winner == observation['outcome'] else -1.0
