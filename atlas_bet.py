"""Outils hors ligne : démo synthétique, lecture de cotes, évaluation et backtest.

Les paris réels se déclarent uniquement via l'API du backend ou le bot Telegram,
qui partagent le registre PostgreSQL et ses plafonds.
"""
import argparse
import json
import os
import sqlite3

from ledger import Ledger
from models import evaluate
from providers import demo, live_odds
from workflow import accept_demo, ingest, scan, settle_demo, simulation_report, temporal_report


def main():
    parser = argparse.ArgumentParser(description="Atlas Bet — outils hors ligne")
    parser.add_argument("--db", default=os.getenv("SPORTS_DB", "atlas-bet.sqlite3"),
                        help="Base SQLite des simulations DEMO (jamais de paris réels)")
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("demo", "ingest-demo", "scan", "simulation-report"):
        commands.add_parser(command)
    odds = commands.add_parser("odds")
    odds.add_argument("competition")
    for name in ("evaluate", "backtest"):
        commands.add_parser(name).add_argument("file")
    paper = commands.add_parser("simulate")
    paper.add_argument("event")
    paper.add_argument("stake", type=int)
    close = commands.add_parser("settle-demo")
    close.add_argument("event")
    close.add_argument("outcome")
    args = parser.parse_args()
    try:
        if args.command == "demo":
            result = demo()
        elif args.command == "odds":
            result = live_odds(args.competition, os.getenv("SPORTS_ODDS_COMPETITIONS", "").split(","))
        elif args.command in ("evaluate", "backtest"):
            with open(args.file, encoding="utf-8-sig") as stream:
                result = (evaluate if args.command == "evaluate" else temporal_report)(json.load(stream))
        else:
            ledger = Ledger(args.db)
            if args.command == "ingest-demo":
                ingest(ledger, demo(ledger.clock()))
            elif args.command == "simulate":
                accept_demo(ledger, args.event, args.stake)
            elif args.command == "settle-demo":
                settle_demo(ledger, args.event, args.outcome)
            if args.command in ("ingest-demo", "scan"):
                result = scan(ledger)
            else:
                result = simulation_report(ledger)
    except (OSError, ValueError, sqlite3.Error) as error:
        parser.error(str(error))
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
