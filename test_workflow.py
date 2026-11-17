import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from ledger import Ledger
from providers import demo
from telegram_bot import telegram_poll
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

    @patch.dict("os.environ", {"TELEGRAM_BOT_TOKEN": "mock", "TELEGRAM_ALLOWED_CHAT_IDS": "1"})
    def test_telegram_restricts_chat_and_persists_offset(self):
        sent = []

        def fake_call(token, method, payload):
            if method == "getUpdates":
                if payload["offset"] >= 3:
                    return []
                return [
                    {"update_id": i, "message": {"chat": {"id": i, "type": "private"}, "text": text}}
                    for i, text in ((1, "/pause"), (2, "/resume"))
                ]
            sent.append(payload["chat_id"])

        self.assertEqual(telegram_poll(self.ledger, fake_call)["offset"], 3)
        self.assertEqual(telegram_poll(self.ledger, fake_call)["updates"], 0)
        self.assertTrue(self.ledger.status()["paused"])
        self.assertEqual(sent, [1])

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


class PriorityRegressionTest(unittest.TestCase):
    @patch.dict("os.environ", {"TELEGRAM_BOT_TOKEN": "dummy-test-token", "TELEGRAM_ALLOWED_CHAT_IDS": "1"})
    def test_failed_telegram_reply_advances_offset_and_allows_pause(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = Ledger(Path(directory) / "telegram.sqlite3")

            def transport(token, method, payload):
                if method == "getUpdates":
                    return [{"update_id": i, "message": {"chat": {"id": 1, "type": "private"}, "text": command}}
                            for i, command in ((1, "/risk"), (2, "/pause")) if i >= payload["offset"]]
                with ledger.transaction() as db:
                    saved = db.execute("SELECT value FROM settings WHERE key='telegram_offset'").fetchone()
                self.assertGreaterEqual(int(saved[0]), 2)
                raise ValueError(f"403 {token}")

            with self.assertLogs(level="WARNING") as logs:
                self.assertEqual(telegram_poll(ledger, transport)["offset"], 3)
            self.assertNotIn("dummy-test-token", "\n".join(logs.output))
            self.assertTrue(ledger.status()["paused"])
            self.assertEqual(telegram_poll(ledger, transport)["updates"], 0)

    def test_collector_continues_after_competition_failure(self):
        from worker import run

        with patch("worker.live_odds", side_effect=[ValueError("HTTP 500"), {"id": "good"}]), \
             patch("worker.normalize_odds", return_value=[{"event": "good"}]), \
             patch("worker.ingest") as save, \
             patch("worker.time.sleep", side_effect=KeyboardInterrupt) as sleep, \
             self.assertLogs(level="ERROR"), self.assertRaises(KeyboardInterrupt):
            run("collect", None, ("bad", "good"), 900)
        save.assert_called_once_with(None, [{"event": "good"}])
        sleep.assert_called_once_with(900)

    def test_collector_waits_an_hour_after_auth_or_quota_error(self):
        from worker import run

        for code in (401, 429):
            with self.subTest(code=code), \
                 patch("worker.live_odds", side_effect=ValueError(f"HTTP {code}")) as request, \
                 patch("worker.time.sleep", side_effect=KeyboardInterrupt) as sleep, \
                 self.assertLogs(level="ERROR"), self.assertRaises(KeyboardInterrupt):
                run("collect", None, ("first", "second"), 900)
            request.assert_called_once()
            sleep.assert_called_once_with(3600)

    def test_cap_persists_and_increase_waits_for_paris_midnight(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cap.sqlite3"
            now = datetime(2026, 12, 31, 22, 59, tzinfo=timezone.utc)
            ledger = Ledger(path, lambda: now)
            ledger.set_cap(50000)
            reopened = Ledger(path, lambda: now)
            self.assertEqual(reopened.status()["cap"], 50000)
            reopened.set_cap(100000)
            self.assertEqual(ledger.status()["cap"], 50000)
            with self.assertRaises(ValueError):
                ledger.record("excess", "Test", 50001)
            now += timedelta(minutes=1)
            self.assertEqual(ledger.status()["cap"], 100000)
            with ledger.transaction() as db:
                actions = [row[0] for row in db.execute("SELECT action FROM audit ORDER BY sequence")]
            self.assertEqual(actions, ["cap_changed", "cap_scheduled", "cap_effective"])

    def test_worker_retries_unexpected_errors_with_backoff(self):
        import sqlite3
        from unittest.mock import call
        from worker import run

        with patch("worker.telegram_poll", side_effect=[
                     sqlite3.OperationalError("database is locked"), KeyError("result"), KeyboardInterrupt
                 ]), patch("worker.time.sleep") as sleep, \
             self.assertLogs(level="ERROR"), self.assertRaises(KeyboardInterrupt):
            run("telegram", None, (), 900)
        self.assertEqual(sleep.call_args_list, [call(1), call(2)])

    def test_cli_reports_sqlite_error_without_traceback(self):
        import io
        import sqlite3
        from contextlib import redirect_stderr
        from atlas_bet import main

        errors = io.StringIO()
        with patch("sys.argv", ["atlas_bet", "risk"]), \
             patch("atlas_bet.Ledger", side_effect=sqlite3.OperationalError("database is locked")), \
             redirect_stderr(errors), self.assertRaises(SystemExit) as exit_code:
            main()
        self.assertEqual(exit_code.exception.code, 2)
        self.assertIn("database is locked", errors.getvalue())
        self.assertNotIn("Traceback", errors.getvalue())

class PriceBaselineTest(unittest.TestCase):
    def test_favorite_and_devig_on_known_sample(self):
        event = demo()[1]
        row = dict(event, odds={"home": "1.50", "away": "3.00"},
                   settled=(datetime.now(timezone.utc) + timedelta(hours=2)).isoformat(),
                   winner="home", home_rating=1500, away_rating=1400)
        baseline = temporal_report([row])["price_baselines"]
        self.assertEqual(baseline["roi"]["favorite"], "0.50")
        self.assertAlmostEqual(baseline["de_vig_brier"], 2/9)
