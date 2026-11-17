import io
import sqlite3
import unittest
from contextlib import redirect_stderr
from unittest.mock import patch

from models import edge, evaluate
from providers import demo


class DemoTest(unittest.TestCase):
    def test_probabilities_and_evaluation(self):
        events = demo()
        self.assertEqual(len(events), 3)
        for event in events:
            self.assertAlmostEqual(sum(event["probabilities"].values()), 1)
            self.assertEqual(event["source"], "DEMO / SYNTHÉTIQUE")
        row = dict(events[0], settled="2099-01-01T00:00:00+00:00", winner="home", home_rating=1650, away_rating=1500)
        self.assertEqual(evaluate([row])["count"], 1)
        with self.assertRaises(ValueError):
            evaluate([dict(row, observed=row["start"])])
        self.assertEqual(edge("0.5", "2"), 0)


class EvaluationChronologyTest(unittest.TestCase):
    def test_evaluate_compares_absolute_instants(self):
        row = {"sport": "tennis", "home_rating": 1500, "away_rating": 1500, "winner": "home",
               "observed": "2026-11-17T21:00:00+02:00", "start": "2026-11-17T20:00:00Z",
               "settled": "2026-11-17T21:30:00Z"}
        self.assertEqual(evaluate([row])["count"], 1)
        with self.assertRaises(ValueError):
            evaluate([dict(row, observed="2026-11-17T18:00:00-03:00")])


class CliTest(unittest.TestCase):
    def test_real_bet_commands_are_gone(self):
        from atlas_bet import main

        for command in ("record", "risk", "pause", "telegram-poll"):
            with self.subTest(command=command), patch("sys.argv", ["atlas_bet", command]), \
                 redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                main()

    def test_cli_reports_sqlite_error_without_traceback(self):
        from atlas_bet import main

        errors = io.StringIO()
        with patch("sys.argv", ["atlas_bet", "scan"]), \
             patch("atlas_bet.scan", side_effect=sqlite3.OperationalError("database is locked")), \
             redirect_stderr(errors), self.assertRaises(SystemExit) as exit_code:
            main()
        self.assertEqual(exit_code.exception.code, 2)
        self.assertIn("database is locked", errors.getvalue())
        self.assertNotIn("Traceback", errors.getvalue())
