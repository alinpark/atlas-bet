import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from ledger import Ledger
from models import edge, evaluate
from providers import demo


class DemoTest(unittest.TestCase):
    def test_probabilities_and_evaluation(self):
        events = demo()
        self.assertEqual(len(events), 3)
        for event in events:
            self.assertAlmostEqual(sum(event["probabilities"].values()), 1)
        row = dict(events[0], settled="9999", winner="home", home_rating=1650, away_rating=1500)
        self.assertEqual(evaluate([row])["count"], 1)
        with self.assertRaises(ValueError):
            evaluate([dict(row, observed=row["start"])])
        self.assertEqual(edge("0.5", "2"), 0)

    def test_risk_and_persistence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.db"
            now = datetime(2026, 11, 30, 22, tzinfo=timezone.utc)
            ledger = Ledger(path, lambda: now, cap=20000)
            ledger.record("a", "test", 18000)
            ledger.record("a", "test", 18000)
            with self.assertRaises(ValueError):
                ledger.record("b", "test", 5000)
            now = datetime(2026, 11, 30, 23, tzinfo=timezone.utc)
            self.assertEqual(ledger.status()["exposure"], 18000)
            ledger.settle("a", 0)
            ledger.pause(True)
            with self.assertRaises(ValueError):
                ledger.record("b", "test", 1000)
            ledger.record("paper", "DEMO", 1000, "paper")
            ledger.settle("paper", 2000)
            reopened = Ledger(path, lambda: now, cap=20000)
            self.assertEqual(reopened.status()["loss"], 18000)
            self.assertEqual(reopened.report("paper")["roi"], 1)
