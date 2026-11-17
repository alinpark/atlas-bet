"""Stockage SQLite local des simulations DEMO hors ligne.

Les paris réels, les plafonds et la pause ne sont PAS gérés ici : le registre unique
est la base PostgreSQL du backend (API /api/v1 et bot Telegram).
"""
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone


class Ledger:
    def __init__(self, path, clock=None):
        self.path = path
        self.clock = clock or (lambda: datetime.now(timezone.utc))

    @contextmanager
    def transaction(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            db.execute("BEGIN IMMEDIATE")
            with db:
                yield db
        finally:
            db.close()
