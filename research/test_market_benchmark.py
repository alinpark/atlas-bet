import unittest

from research.market_benchmark import bootstrap, de_vig, odds_rows, summary


class MarketBenchmarkTest(unittest.TestCase):
    def test_de_vig_and_odds_rows_use_the_same_keys_as_the_sport_model(self):
        fair = de_vig({'home': 2.0, 'draw': 4.0, 'away': 4.0})
        self.assertAlmostEqual(fair['home'], 0.5)
        csv_text = ('Date,HomeTeam,AwayTeam,PSH,PSD,PSA,PSCH,PSCD,PSCA,B365H,B365D,B365A,AvgH,AvgD,AvgA\n'
                    '18/10/2025,Paris SG,Lens,1.5,4.5,6,1.45,4.6,6.5,1.5,4.4,6.2,1.48,4.4,6\n')
        rows = odds_rows(csv_text, 'F1')
        self.assertEqual(list(rows), [('F1', '2025-10-18', 'parissg', 'lens')])
        self.assertEqual(rows[('F1', '2025-10-18', 'parissg', 'lens')]['b365']['away'], 6.2)

    def test_summary_and_bootstrap_need_enough_days(self):
        bets = [{'date': f'2025-08-{d:02d}', 'pick': 'draw' if d % 2 else 'home', 'odds': 2.0,
                 'profit': 1.0 if d % 2 else -1.0, 'clv': 0.01} for d in range(1, 31)]
        result = summary(bets)
        self.assertEqual(result['bets'], 30)
        self.assertAlmostEqual(result['draw_share'], 0.5)
        self.assertIsNotNone(result['roi_ci95'])
        self.assertIsNone(bootstrap({'2025-08-01': [1.0]}))


if __name__ == '__main__':
    unittest.main()
