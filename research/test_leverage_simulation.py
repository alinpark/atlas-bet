import unittest
from datetime import datetime

from research.leverage_simulation import flat, run


def bet(hour, odds, won):
    return {'kickoff': datetime(2025, 9, 1, hour), 'odds': odds, 'won': won}


class LeverageSimulationTest(unittest.TestCase):
    def test_two_simultaneous_bets_take_half_of_total_capital_each(self):
        # 200 € : deux paris de 100 € à la même heure ; un gagné à 1,30, un perdu → 130 €.
        result = run([bet(20, 1.30, True), bet(20, 1.25, False)])
        self.assertEqual(result['bets'], 2)
        self.assertAlmostEqual(result['final'], 130.0)

    def test_one_loss_wipes_out_many_wins(self):
        # Sept victoires à 1,20 puis une défaite : moins que le capital de départ.
        bets = [bet(h, 1.20, True) for h in range(0, 14, 2)] + [bet(16, 1.20, False)]
        result = run(bets)
        self.assertLess(result['final'], 200)

    def test_ruin_stops_betting(self):
        result = run([bet(h, 1.2, False) for h in range(0, 24, 3)])
        self.assertTrue(result['ruined'])
        self.assertLess(result['bets'], 8)

    def test_flat_stake_roi(self):
        stats = flat([bet(1, 1.5, True), bet(3, 1.5, False)])
        self.assertAlmostEqual(stats['roi'], -0.25)


if __name__ == '__main__':
    unittest.main()
