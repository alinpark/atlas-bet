import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

SCHEMA = """
CREATE TABLE IF NOT EXISTS bets (
    id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('manual','paper')),
    label TEXT NOT NULL, stake INTEGER NOT NULL CHECK(stake > 0),
    opened TEXT NOT NULL, payout INTEGER CHECK(payout >= 0), settled TEXT,
    CHECK((settled IS NULL) = (payout IS NULL))
);
CREATE TABLE IF NOT EXISTS audit (
    sequence INTEGER PRIMARY KEY, action TEXT NOT NULL, reference TEXT, at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


class Ledger:
    def __init__(self, path, clock=None, cap=None):
        self.path = path
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        with self.transaction() as db:
            db.executescript(SCHEMA)
        if cap is not None:
            self.set_cap(cap)

    def current_cap(self, db, now):
        row = db.execute("SELECT value FROM settings WHERE key='cap'").fetchone()
        cap = int(row[0]) if row else 200000
        pending = db.execute("SELECT value FROM settings WHERE key='cap_pending'").fetchone()
        if pending:
            amount, effective = pending[0].split("|", 1)
            if now >= datetime.fromisoformat(effective):
                cap = int(amount)
                db.execute("INSERT OR REPLACE INTO settings VALUES ('cap',?)", (str(cap),))
                db.execute("DELETE FROM settings WHERE key='cap_pending'")
                db.execute("INSERT INTO audit(action,reference,at) VALUES ('cap_effective',?,?)",
                           (amount, now.astimezone(timezone.utc).isoformat()))
        return cap

    def set_cap(self, cap):
        if type(cap) is not int or not 0 < cap <= 200000:
            raise ValueError("Plafond invalide")
        with self.transaction() as db:
            now = self.clock().astimezone(ZoneInfo("Europe/Paris"))
            current = self.current_cap(db, now)
            if cap > current:
                year, month = (now.year + 1, 1) if now.month == 12 else (now.year, now.month + 1)
                effective = now.replace(year=year, month=month, day=1, hour=0, minute=0,
                                        second=0, microsecond=0)
                reference = f"{cap}|{effective.isoformat()}"
                db.execute("INSERT OR REPLACE INTO settings VALUES ('cap_pending',?)", (reference,))
                action = "cap_scheduled"
            else:
                reference = str(cap)
                db.execute("INSERT OR REPLACE INTO settings VALUES ('cap',?)", (reference,))
                db.execute("DELETE FROM settings WHERE key='cap_pending'")
                action = "cap_changed"
            db.execute("INSERT INTO audit(action,reference,at) VALUES (?,?,?)",
                       (action, reference, now.astimezone(timezone.utc).isoformat()))

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

    def timestamp(self):
        return self.clock().astimezone(timezone.utc).isoformat()

    def risk(self, db):
        paris = self.clock().astimezone(ZoneInfo("Europe/Paris"))
        start = paris.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        losses, exposure = db.execute("""
            SELECT COALESCE(SUM(CASE WHEN settled >= ? THEN MAX(0,stake-payout) ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN settled IS NULL THEN stake ELSE 0 END),0)
            FROM bets WHERE kind='manual'
        """, (start.astimezone(timezone.utc).isoformat(),)).fetchone()
        paused = db.execute("SELECT value FROM settings WHERE key='paused'").fetchone()
        cap = self.current_cap(db, paris)
        return {"loss": losses, "exposure": exposure, "cap": cap,
                "remaining": max(0, cap-losses-exposure), "paused": bool(paused and paused[0]=="1")}

    def status(self):
        with self.transaction() as db:
            return self.risk(db)

    def pause(self, paused):
        with self.transaction() as db:
            db.execute("INSERT OR REPLACE INTO settings VALUES ('paused',?)", (str(int(paused)),))
            db.execute("INSERT INTO audit(action,at) VALUES (?,?)",
                       ("pause" if paused else "resume", self.timestamp()))

    def record(self, reference, label, stake, kind="manual"):
        if not reference.strip() or not label.strip() or type(stake) is not int or not 0 < stake <= 10**9:
            raise ValueError("Référence, libellé ou montant invalide")
        with self.transaction() as db:
            existing = db.execute("SELECT label,stake,kind FROM bets WHERE id=?", (reference,)).fetchone()
            if existing:
                if tuple(existing) != (label, stake, kind):
                    raise ValueError("Référence déjà utilisée")
                return
            risk = self.risk(db)
            if kind == "manual" and (risk["paused"] or stake > risk["remaining"]):
                raise ValueError("Pause ou plafond atteint")
            now = self.timestamp()
            db.execute("INSERT INTO bets VALUES (?,?,?,?,?,NULL,NULL)", (reference,kind,label,stake,now))
            db.execute("INSERT INTO audit(action,reference,at) VALUES ('record',?,?)", (reference,now))

    def settle(self, reference, payout):
        if type(payout) is not int or not 0 <= payout <= 10**9:
            raise ValueError("Retour invalide")
        with self.transaction() as db:
            existing = db.execute("SELECT payout FROM bets WHERE id=?", (reference,)).fetchone()
            if existing is None or existing[0] not in (None, payout):
                raise ValueError("Référence inconnue ou règlement incompatible")
            if existing[0] is None:
                now = self.timestamp()
                db.execute("UPDATE bets SET payout=?, settled=? WHERE id=?", (payout,now,reference))
                db.execute("INSERT INTO audit(action,reference,at) VALUES ('settle',?,?)", (reference,now))

    def report(self, kind):
        with self.transaction() as db:
            rows = [dict(row) for row in db.execute(
                "SELECT * FROM bets WHERE kind=? ORDER BY opened,id", (kind,))]
        closed = [row for row in rows if row["settled"]]
        stake = sum(row["stake"] for row in closed)
        net = sum(row["payout"] - row["stake"] for row in closed)
        return {"bets": rows, "settled_stake": stake, "net": net,
                "roi": net / stake if stake else None}

