import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from ledger import Ledger
from providers import demo
from workflow import accept_demo, ingest, scan, settle_demo, simulation_report, temporal_report


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "work.sqlite3"
        self.now = datetime.now(timezone.utc)
        self.ledger = Ledger(self.path, lambda: self.now)

    def test_ingestion_scan_and_settlement(self):
        events = demo(self.now)
        ingest(self.ledger, events)
        ingest(self.ledger, events)
        with self.ledger.transaction() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM snapshots").fetchone()[0], 3)
        selection = scan(self.ledger)[0]
        event = selection["event"]
        accept_demo(self.ledger, event, 100)
        accept_demo(self.ledger, event, 100)
        with self.assertRaises(ValueError):
            accept_demo(self.ledger, event, 200)
        self.now += timedelta(hours=2)
        self.assertEqual(scan(self.ledger, self.now), [])
        settle_demo(self.ledger, event, selection["outcome"])
        reopened = Ledger(self.path, lambda: self.now)
        self.assertEqual(simulation_report(reopened)["net"], 100)
        with self.assertRaises(ValueError):
            settle_demo(reopened, event, "void")

    def test_temporal_report_rejects_duplicates_and_naive_timestamps(self):
        event = demo()[0]
        row = dict(event, settled=(self.now + timedelta(hours=2)).isoformat(),
                   winner="home", home_rating=1650, away_rating=1500)
        self.assertEqual(temporal_report([row])["sports"]["soccer"]["count"], 1)
        for samples in ([row, row], [dict(row, observed="2026-01-01")]):
            with self.assertRaises(ValueError):
                temporal_report(samples)


class WalkForwardTest(unittest.TestCase):
    def test_only_results_known_before_forecast_are_used(self):
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        rows = []
        for i in range(11):
            day = start + timedelta(days=i)
            rows.append({"event": str(i), "sport": "tennis", "observed": day.isoformat(),
                         "start": (day + timedelta(hours=1)).isoformat(),
                         "settled": (day + timedelta(hours=2)).isoformat(),
                         "winner": "away", "home_rating": 1700, "away_rating": 1500})
        self.assertEqual(temporal_report(rows)["walk_forward"]["evaluated"], 1)
        rows[0]["settled"] = (start + timedelta(days=20)).isoformat()
        self.assertEqual(temporal_report(rows)["walk_forward"]["evaluated"], 0)


class ProviderTest(unittest.TestCase):
    def test_only_fresh_complete_french_markets(self):
        from providers import normalize_odds
        now = datetime(2026, 11, 17, tzinfo=timezone.utc)
        prices = [{"name": name, "price": 2} for name in ("A", "B", "Draw")]
        market = {"key": "h2h", "outcomes": prices}
        book = {"key": "betclic_fr", "last_update": now.isoformat(), "markets": [market]}
        event = {"id": "source-id", "sport_key": "soccer_demo", "home_team": "A", "away_team": "B",
                 "commence_time": (now + timedelta(hours=1)).isoformat(), "bookmakers": [book]}
        batch = {"observed": now.isoformat(), "events": [event]}
        self.assertEqual(len(list(normalize_odds(batch))), 1)
        market["outcomes"] = prices[:2]
        self.assertEqual(list(normalize_odds(batch)), [])
        market["outcomes"] = prices
        book["last_update"] = (now - timedelta(minutes=16)).isoformat()
        self.assertEqual(list(normalize_odds(batch)), [])


class PriceBaselineTest(unittest.TestCase):
    def test_favorite_and_devig_on_known_sample(self):
        event = demo()[1]
        row = dict(event, odds={"home": "1.50", "away": "3.00"},
                   settled=(datetime.now(timezone.utc) + timedelta(hours=2)).isoformat(),
                   winner="home", home_rating=1500, away_rating=1400)
        baseline = temporal_report([row])["price_baselines"]
        self.assertEqual(baseline["roi"]["favorite"], "0.50")
        self.assertAlmostEqual(baseline["de_vig_brier"], 2/9)
