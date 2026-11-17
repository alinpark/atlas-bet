import unittest

from ratings import elo_walk, football_probabilities, parse_football_data, winner
from validation_football import bootstrap, de_vig

NEW = """Div,Date,Time,HomeTeam,AwayTeam,FTHG,FTAG,AvgH,AvgD,AvgA,MaxH,MaxD,MaxA,AvgCH,AvgCD,AvgCA
F1,15/08/2025,19:45,Rennes,Marseille,1,0,3.5,3.6,2.05,3.8,3.65,2.12,3.4,3.5,2.1
F1,16/08/2025,17:00,Lens,Lyon,,,2.1,3.4,3.5,2.2,3.5,3.6,,,
"""
OLD = """Div,Date,HomeTeam,AwayTeam,FTHG,FTAG,BbAvH,BbAvD,BbAvA
F1,08/08/15,Lille,Paris SG,0,1,5.5,3.8,1.7
"""


class RatingsTest(unittest.TestCase):
    def test_parses_old_and_new_files_and_skips_unplayed(self):
        new = parse_football_data(NEW, "2526")
        self.assertEqual(len(new), 1)
        self.assertEqual(new[0]["avg"], {"home": 3.5, "draw": 3.6, "away": 2.05})
        self.assertEqual(new[0]["close"]["away"], 2.1)
        old = parse_football_data(OLD, "1516")
        self.assertEqual(old[0]["date"], "2015-08-08")
        self.assertEqual(old[0]["avg"]["away"], 1.7)
        self.assertNotIn("close", old[0])

    def test_elo_uses_only_past_results(self):
        matches = parse_football_data(OLD, "1516") + parse_football_data(NEW, "2526")
        rows, final = elo_walk(matches)
        self.assertEqual(rows[0]["home_rating"], 1500)  # avant tout résultat
        self.assertGreater(final["Paris SG"], final["Lille"])
        # Nouvelle saison : Rennes et Marseille arrivent comme promus.
        self.assertEqual(rows[1]["home_rating"], 1420)
        self.assertEqual(winner(rows[1]), "home")

    def test_probabilities_are_coherent(self):
        probabilities = football_probabilities(1700, 1500)
        self.assertAlmostEqual(sum(probabilities.values()), 1)
        self.assertGreater(probabilities["home"], probabilities["away"])

    def test_line_shopping_bets_only_above_the_sharp_fair_price(self):
        from validation_markets import line_bets, passes
        base = {"league": "F2", "season": "2425", "hg": 1, "ag": 0,
                "pinnacle": {"home": 2.0, "draw": 3.5, "away": 4.0},
                "pinnacle_close": {"home": 1.9, "draw": 3.6, "away": 4.4}}
        rich = dict(base, b365={"home": 2.25, "draw": 3.3, "away": 3.6})
        poor = dict(base, b365={"home": 1.95, "draw": 3.3, "away": 3.6})
        bets = line_bets([rich, poor], "b365")
        self.assertEqual(len(bets), 1)
        self.assertAlmostEqual(bets[0]["profit"], 1.25)
        self.assertGreater(bets[0]["clv"], 0)
        self.assertFalse(passes({"bets": 299, "roi_ci95": [0.01, 0.2], "mean_clv": 0.02}))
        self.assertFalse(passes({"bets": 400, "roi_ci95": [0.01, 0.2], "mean_clv": -0.01}))
        self.assertTrue(passes({"bets": 400, "roi_ci95": [0.01, 0.2], "mean_clv": 0.01}))

    def test_validation_helpers(self):
        self.assertAlmostEqual(sum(de_vig({"home": 2.0, "draw": 3.4, "away": 3.8}).values()), 1)
        low, high = bootstrap([1.0, -1.0] * 50)
        self.assertLess(low, 0)
        self.assertGreater(high, 0)
