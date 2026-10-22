"""State storage: approval queue, leads, attribution events, logs.

SQLite so the example runs with zero setup; the schema is plain SQL and maps
1:1 onto PostgreSQL.

Everything that can go public - posts, comment replies, DMs, outreach - is a
row in ONE table, `queue_item`, so there is exactly one approval gate.
"""
import hashlib
import json
import os
import sqlite3
from datetime import datetime

from .config import ROOT

DEFAULT_PATH = os.environ.get("SMT_DB", os.path.join(ROOT, "smt.db"))

SCHEMA = """
CREATE TABLE IF NOT EXISTS trend (
    id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT, source TEXT, kind TEXT,
    topic TEXT, text TEXT, metric REAL, url TEXT
);
CREATE TABLE IF NOT EXISTS hook (
    id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT, source TEXT, original TEXT,
    trigger TEXT, views INTEGER, url TEXT
);
-- kind: post | reply | dm | outreach
-- status: PENDING -> APPROVED -> (AWAITING_MEDIA) -> PUBLISHED | FAILED ; or REJECTED
CREATE TABLE IF NOT EXISTS queue_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT, created_ts TEXT, kind TEXT NOT NULL,
    platform TEXT NOT NULL, format TEXT, pillar TEXT, topic TEXT,
    hook TEXT, hook_trigger TEXT, source_hook_id INTEGER,
    body TEXT NOT NULL, caption TEXT, hashtags TEXT,
    in_reply_to INTEGER REFERENCES inbound(id),
    tracking_code TEXT UNIQUE, link TEXT, media_path TEXT,
    scheduled_for TEXT, status TEXT NOT NULL DEFAULT 'PENDING',
    content_hash TEXT NOT NULL, approved_hash TEXT,
    decided_by TEXT, decided_ts TEXT, decided_via TEXT,
    published_ts TEXT, external_id TEXT, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT
);
CREATE TABLE IF NOT EXISTS inbound (
    id INTEGER PRIMARY KEY AUTOINCREMENT, received_ts TEXT, platform TEXT, channel TEXT,
    external_id TEXT, author TEXT, text TEXT, on_item INTEGER REFERENCES queue_item(id),
    intent TEXT, score REAL, handled INTEGER NOT NULL DEFAULT 0,
    UNIQUE (platform, external_id)
);
CREATE TABLE IF NOT EXISTS lead (
    id INTEGER PRIMARY KEY AUTOINCREMENT, created_ts TEXT, inbound_id INTEGER REFERENCES inbound(id),
    platform TEXT, author TEXT, reason TEXT, score REAL, source_item INTEGER REFERENCES queue_item(id),
    status TEXT NOT NULL DEFAULT 'NEW', value REAL
);
-- kind: view | click | signup | sale.  tracking_code ties the event to the post that caused it.
CREATE TABLE IF NOT EXISTS event (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, kind TEXT, tracking_code TEXT,
    lead_id INTEGER, value REAL NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS error_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, where_ TEXT, error TEXT,
    attempts INTEGER, needs_human INTEGER
);
CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, actor TEXT, action TEXT, detail TEXT
);
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
"""


def connect(path=DEFAULT_PATH):
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(SCHEMA)
    return conn


def create(path=DEFAULT_PATH):
    if path != ":memory:" and os.path.exists(path):
        os.remove(path)
    return connect(path)


def ts(dt):
    return dt.isoformat(timespec="minutes") if dt else None


def parse(s):
    return datetime.fromisoformat(s) if s else None


def content_hash(platform, body, caption, hashtags, media_path=None):
    """Fingerprint of exactly what would be published. Approval is bound to it."""
    blob = json.dumps([platform, body, caption or "", hashtags or "", media_path or ""], ensure_ascii=False)
    return hashlib.sha256(blob.encode()).hexdigest()


def audit(conn, actor, action, detail, now=None):
    conn.execute("INSERT INTO audit_log (ts, actor, action, detail) VALUES (?,?,?,?)",
                 ((now or datetime.now()).isoformat(timespec="seconds"), actor, action, detail))
    conn.commit()


def get_kv(conn, k, default=None):
    row = conn.execute("SELECT v FROM kv WHERE k=?", (k,)).fetchone()
    return row["v"] if row else default


def set_kv(conn, k, v):
    conn.execute("INSERT INTO kv (k, v) VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, v))
    conn.commit()
