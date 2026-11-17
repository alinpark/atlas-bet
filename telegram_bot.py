"""Private Telegram polling for ledger reports and pause controls."""
import json
import logging
import os
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def telegram_call(token, method, payload):
    request = Request(f"https://api.telegram.org/bot{token}/{method}",
                      data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=30) as response:
            result = json.load(response)
    except (HTTPError, URLError, TimeoutError, ValueError):
        raise ValueError(f"Telegram indisponible ({method})") from None
    if not result.get("ok"):
        raise ValueError(f"Telegram a refusé {method}")
    return result["result"]


def reply(ledger, message, allowed):
    chat = message.get("chat", {})
    if chat.get("type") != "private" or chat.get("id") not in allowed:
        return None
    command = message.get("text", "").strip().split("@", 1)[0]
    if command in ("/pause", "/resume"):
        ledger.pause(command == "/pause")
    if command in ("/risk", "/pause", "/resume"):
        status = ledger.status()
        fields = (("Pertes", "loss"), ("Exposition", "exposure"), ("Plafond", "cap"))
        amounts = "\n".join(f"{label} : {status[key] / 100:.2f} EUR" for label, key in fields)
        return amounts + ("\nPause active" if status["paused"] else "")
    if command == "/report":
        report = ledger.report("manual")
        return f"Paris déclarés : {len(report['bets'])}\nRésultat net : {report['net'] / 100:.2f} EUR"
    return "/risk · /report · /pause · /resume"


def telegram_poll(ledger, call=telegram_call):
    token = os.getenv("TELEGRAM_BOT_TOKEN", "")
    allowed = {int(item) for item in os.getenv("TELEGRAM_ALLOWED_CHAT_IDS", "").split(",") if item}
    if not token or not allowed:
        raise ValueError("Telegram : NOT_CONFIGURED")
    with ledger.transaction() as db:
        saved = db.execute("SELECT value FROM settings WHERE key='telegram_offset'").fetchone()
    offset = int(saved[0]) if saved else 0
    updates = call(token, "getUpdates", {"offset": offset, "timeout": 20, "allowed_updates": ["message"]})
    for update in updates:
        message = update.get("message", {})
        response = reply(ledger, message, allowed)
        offset = update["update_id"] + 1
        with ledger.transaction() as db:
            db.execute("INSERT OR REPLACE INTO settings VALUES ('telegram_offset',?)", (str(offset),))
        if response is not None:
            try:
                call(token, "sendMessage", {"chat_id": message["chat"]["id"], "text": response})
            except Exception:
                logging.warning("Réponse Telegram non envoyée pour update %d", offset - 1)
    return {"updates": len(updates), "offset": offset}
