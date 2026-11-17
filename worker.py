"""Scheduled collection and optional Telegram polling in separate processes."""
import argparse
import logging
import os
import time

from ledger import Ledger
from providers import live_odds, normalize_odds
from telegram_bot import telegram_poll
from workflow import ingest


def run(mode, ledger, competitions, interval):
    delay = 1
    while True:
        if mode == "collect":
            failed = False
            cooldown = interval
            for competition in competitions:
                try:
                    batches = list(normalize_odds(live_odds(competition, competitions)))
                    ingest(ledger, batches)
                    logging.info("Marchés ingérés : %d", len(batches))
                except Exception as error:
                    failed = True
                    logging.exception("Échec de collecte")
                    if isinstance(error, ValueError) and str(error).endswith(("HTTP 401", "HTTP 429")):
                        cooldown = max(cooldown, 3600)
                        break
            time.sleep(max(cooldown, delay) if failed else interval)
            delay = min(delay * 2, 3600) if failed else 1
            continue
        try:
            telegram_poll(ledger)
            delay = 1
        except Exception:
            logging.exception("Échec Telegram, reprise dans %d s", delay)
            time.sleep(delay)
            delay = min(delay * 2, 300)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("collect", "telegram"))
    args = parser.parse_args()
    competitions = tuple(filter(None, os.getenv("SPORTS_ODDS_COMPETITIONS", "").split(",")))
    if args.mode == "collect" and (not competitions or not os.getenv("THE_ODDS_API_KEY")):
        parser.error("Collecte : NOT_CONFIGURED")
    if args.mode == "telegram" and not all(os.getenv(key) for key in
                                           ("TELEGRAM_BOT_TOKEN", "TELEGRAM_ALLOWED_CHAT_IDS")):
        parser.error("Telegram : NOT_CONFIGURED")
    interval = int(os.getenv("SPORTS_COLLECT_INTERVAL", "900"))
    if interval < 60:
        parser.error("Intervalle minimal : 60 secondes")
    logging.basicConfig(level=logging.INFO)
    run(args.mode, Ledger(os.getenv("SPORTS_DB", "atlas-bet.sqlite3")), competitions, interval)


if __name__ == "__main__":
    main()
