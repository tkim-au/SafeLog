"""SQLite storage. Each driver is one JSON document (same schema as the car), updated in a single transaction;
request ids make trip finish and exchange idempotent (a repeated POST returns the first answer)."""
from __future__ import annotations

import json
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path


class Store:
    def __init__(self, path: str | Path = "safelog.db"):
        self.path = str(path)
        self._lock = threading.Lock()
        self.db = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS drivers (id TEXT PRIMARY KEY, doc TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS requests (id TEXT PRIMARY KEY, driver TEXT NOT NULL, response TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS consents (driver TEXT PRIMARY KEY, recording INTEGER, insurance INTEGER, data INTEGER, ms INTEGER);
        """)

    @contextmanager
    def tx(self):
        """One write transaction at a time (BEGIN IMMEDIATE): balance check, debit and coupon issue cannot interleave."""
        with self._lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield self
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    def driver(self, did: str) -> dict | None:
        row = self.db.execute("SELECT doc FROM drivers WHERE id = ?", (did,)).fetchone()
        return json.loads(row[0]) if row else None

    def drivers(self) -> list[dict]:
        return [json.loads(r[0]) for r in self.db.execute("SELECT doc FROM drivers ORDER BY rowid")]

    def put_driver(self, d: dict):
        self.db.execute("INSERT INTO drivers(id, doc) VALUES(?, ?) ON CONFLICT(id) DO UPDATE SET doc = excluded.doc",
                        (d["id"], json.dumps(d, ensure_ascii=False)))

    def response(self, rid: str) -> dict | None:
        row = self.db.execute("SELECT response FROM requests WHERE id = ?", (rid,)).fetchone()
        return json.loads(row[0]) if row else None

    def put_response(self, rid: str, driver: str, resp: dict):
        self.db.execute("INSERT OR REPLACE INTO requests(id, driver, response) VALUES(?, ?, ?)", (rid, driver, json.dumps(resp, ensure_ascii=False)))

    def set_consent(self, did: str, recording: bool, insurance: bool, data: bool, ms: int):
        self.db.execute("INSERT OR REPLACE INTO consents VALUES(?, ?, ?, ?, ?)", (did, int(recording), int(insurance), int(data), ms))

    def consent(self, did: str) -> dict:
        row = self.db.execute("SELECT recording, insurance, data FROM consents WHERE driver = ?", (did,)).fetchone()
        return {"recording": bool(row[0]), "insurance": bool(row[1]), "data": bool(row[2])} if row else {"recording": True, "insurance": False, "data": False}
