import argparse
import json
import os
import sqlite3

from ledger import Ledger
from models import evaluate
from providers import demo, live_odds, normalize_odds
from telegram_bot import telegram_poll
from workflow import accept_demo, ingest, scan, settle_demo, simulation_report, temporal_report


def main():
    parser = argparse.ArgumentParser(description="Atlas Bet")
    parser.add_argument("--db", default=os.getenv("SPORTS_DB", "atlas-bet.sqlite3"))
    parser.add_argument("--cap", type=int, help="Modifier explicitement le plafond en centimes avec risk")
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("demo", "risk", "pause", "resume", "ingest-demo", "scan", "simulation-report", "telegram-poll"):
        commands.add_parser(command)
    odds = commands.add_parser("odds")
    odds.add_argument("competition")
    odds.add_argument("--persist", action="store_true")
    backtest = commands.add_parser("evaluate")
    backtest.add_argument("file")
    record = commands.add_parser("record")
    record.add_argument("reference")
    record.add_argument("label")
    record.add_argument("stake", type=int)
    record.add_argument("--kind", choices=("manual", "paper"), default="manual")
    settlement = commands.add_parser("settle")
    settlement.add_argument("reference")
    settlement.add_argument("payout", type=int)
    report = commands.add_parser("report")
    report.add_argument("kind", choices=("manual", "paper"))
    temporal = commands.add_parser("backtest")
    temporal.add_argument("file")
    paper = commands.add_parser("simulate")
    paper.add_argument("event")
    paper.add_argument("stake", type=int)
    close = commands.add_parser("settle-demo")
    close.add_argument("event")
    close.add_argument("outcome")
    args = parser.parse_args()
    if args.cap is not None and args.command != "risk":
        parser.error("--cap doit être utilisé avec risk")
    try:
        if args.command == "demo":
            result = demo()
        elif args.command == "odds":
            result = live_odds(args.competition, os.getenv("SPORTS_ODDS_COMPETITIONS", "").split(","))
            if args.persist:
                snapshots = list(normalize_odds(result))
                ingest(Ledger(args.db), snapshots)
                result = {"source": result["source"], "quota": result["quota"], "stored": len(snapshots)}
        elif args.command in ("evaluate", "backtest"):
            with open(args.file, encoding="utf-8-sig") as stream:
                result = (evaluate if args.command == "evaluate" else temporal_report)(json.load(stream))
        else:
            ledger = Ledger(args.db, cap=args.cap)
            if args.command == "ingest-demo":
                ingest(ledger, demo(ledger.clock()))
            elif args.command == "simulate":
                accept_demo(ledger, args.event, args.stake)
            elif args.command == "settle-demo":
                settle_demo(ledger, args.event, args.outcome)
            elif args.command == "record":
                ledger.record(args.reference, args.label, args.stake, args.kind)
            elif args.command == "settle":
                ledger.settle(args.reference, args.payout)
            elif args.command in ("pause", "resume"):
                ledger.pause(args.command == "pause")
            if args.command == "report":
                result = ledger.report(args.kind)
            elif args.command in ("ingest-demo", "scan"):
                result = scan(ledger)
            elif args.command in ("simulate", "settle-demo", "simulation-report"):
                result = simulation_report(ledger)
            elif args.command == "telegram-poll":
                result = telegram_poll(ledger)
            else:
                result = ledger.status()
    except (OSError, ValueError, sqlite3.Error) as error:
        parser.error(str(error))
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()



