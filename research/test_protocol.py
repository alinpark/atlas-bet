from copy import deepcopy
from datetime import datetime, timedelta, timezone
import unittest
from research.protocol import entry, reference, unit_profit


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.at = datetime(2025, 10, 18, 14, tzinfo=timezone.utc)
        self.start = self.at + timedelta(hours=1)
        self.raw = {'timestamp': self.at.isoformat(), 'data': [{
            'id': 'one', 'sport_key': 'soccer_france_ligue_one', 'home_team': 'Home', 'away_team': 'Away',
            'commence_time': self.start.isoformat(), 'bookmakers': []}]}
        for key in ['pinnacle', 'winamax_fr', 'betclic_fr', 'unibet_fr']:
            prices = [2, 4, 4] if key == 'pinnacle' else [2.2, 3.5, 3.5]
            self.raw['data'][0]['bookmakers'].append({'key': key, 'last_update': self.at.isoformat(),
                'markets': [{'key': 'h2h', 'outcomes': [{'name': name, 'price': price}
                 for name, price in zip(['Home', 'Draw', 'Away'], prices)]}]})

    def choose(self):
        return entry(self.raw, 'one', self.at, self.start)

    def test_deterministic_selection_reference_and_stress(self):
        obs = self.choose()
        self.assertTrue(obs['eligible'])
        self.assertEqual(obs['bookmaker'], 'betclic_fr')
        self.raw['data'][0]['bookmakers'].reverse()
        self.assertEqual(obs, self.choose())
        close = self.start - timedelta(minutes=10)
        raw = deepcopy(self.raw)
        raw['timestamp'] = close.isoformat()
        for book in raw['data'][0]['bookmakers']:
            book['last_update'] = close.isoformat()
        result = reference(obs, raw, close)
        self.assertAlmostEqual(result['clv'], .1)
        self.assertAlmostEqual(result['clv_stress'], .089)

    def test_no_future_snapshot_or_price_or_final_schedule(self):
        self.raw['timestamp'] = (self.at + timedelta(seconds=1)).isoformat()
        self.assertFalse(self.choose()['eligible'])
        self.raw['timestamp'] = self.at.isoformat()
        self.raw['data'][0]['bookmakers'][0]['last_update'] = (self.at + timedelta(seconds=1)).isoformat()
        self.assertFalse(self.choose()['eligible'])
        self.raw['data'][0]['bookmakers'][0]['last_update'] = self.at.isoformat()
        self.raw['data'][0]['commence_time'] = (self.start + timedelta(hours=1)).isoformat()
        self.assertEqual(self.choose()['reason'], 'SCHEDULE_CHANGED_OR_NOT_KNOWN')

    def test_stale_incomplete_and_unsynchronised_prices(self):
        self.raw['data'][0]['bookmakers'][1]['last_update'] = (self.at - timedelta(minutes=6)).isoformat()
        self.assertEqual(self.choose()['reason'], 'FEWER_THAN_THREE_FR')
        self.raw['data'][0]['bookmakers'][1]['last_update'] = self.at.isoformat()
        self.raw['data'][0]['bookmakers'][0]['markets'][0]['outcomes'].pop()
        self.assertEqual(self.choose()['reason'], 'PINNACLE_UNAVAILABLE')

    def test_duplicate_event_and_nonfinite_prices(self):
        self.raw['data'].append(deepcopy(self.raw['data'][0]))
        self.assertEqual(self.choose()['reason'], 'EVENT_MISSING_OR_DUPLICATED')
        self.raw['data'].pop()
        self.raw['data'][0]['bookmakers'][0]['markets'][0]['outcomes'][0]['price'] = float('nan')
        self.assertFalse(self.choose()['eligible'])

    def test_no_later_search_when_no_edge(self):
        for book in self.raw['data'][0]['bookmakers'][1:]:
            book['markets'][0]['outcomes'][0]['price'] = 2.0
        self.assertEqual(self.choose()['reason'], 'NO_EDGE_AT_FIXED_WINDOW')
        self.assertEqual(entry(self.raw, 'one', self.at + timedelta(minutes=5), self.start)['reason'],
                         'DECISION_NOT_AT_FIXED_WINDOW')

    def test_settlement_unknown_reported_void_win_loss(self):
        obs = self.choose()
        self.assertIsNone(unit_profit(obs, None))
        self.assertIsNone(unit_profit(obs, {'event_id': 'one', 'status': 'POSTPONED'}))
        self.assertIsNone(unit_profit(obs, {'event_id': 'one', 'status': 'VOID'}))
        self.assertEqual(unit_profit(obs, {'event_id': 'one', 'status': 'VOID', 'settlement_rule_verified': True}), 0)
        result = {'event_id': 'one', 'status': 'SETTLED_90', 'verified': True, 'winner': 'Home'}
        self.assertAlmostEqual(unit_profit(obs, result), 1.2)
        result['winner'] = 'Away'
        self.assertEqual(unit_profit(obs, result), -1)
        result['event_id'] = 'another'
        self.assertIsNone(unit_profit(obs, result))


if __name__ == '__main__':
    unittest.main()
