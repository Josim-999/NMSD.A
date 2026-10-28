"""Local SQLite store: inbox, contacts, opt-outs and audit trail.

The Cloud API has no "list my conversations" endpoint - inbound messages only
ever arrive as webhooks. So the webhook receiver writes everything it is sent
into this database, and the MCP inbox tools read it. Everything stays on the
machine running the server.

Opened with one connection per call: short-lived, WAL mode, so the webhook
receiver and the MCP server can use the same file at the same time.
"""
import contextlib
import json
import os
import sqlite3
import time

SCHEMA_VERSION = 1

SCHEMA = """
CREATE TABLE IF NOT EXISTS message (
    wamid TEXT PRIMARY KEY,
    wa_id TEXT NOT NULL,              -- the other party, digits only
    direction TEXT NOT NULL,          -- 'in' or 'out'
    ts INTEGER NOT NULL,              -- unix seconds
    type TEXT,
    body TEXT,                        -- text, caption or a short description
    media_id TEXT,
    mime_type TEXT,
    filename TEXT,
    reply_to TEXT,                    -- wamid this quoted
    choice_id TEXT,                   -- id of a tapped button or list row
    status TEXT,                      -- latest delivery status for outbound
    error TEXT,
    raw TEXT                          -- the original webhook/API object
);
CREATE INDEX IF NOT EXISTS message_by_contact ON message(wa_id, ts DESC);
CREATE INDEX IF NOT EXISTS message_by_time ON message(ts DESC);

CREATE TABLE IF NOT EXISTS status_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    wamid TEXT NOT NULL, status TEXT NOT NULL, ts INTEGER NOT NULL,
    wa_id TEXT, error_code INTEGER, error_title TEXT, raw TEXT
);
CREATE INDEX IF NOT EXISTS status_by_message ON status_event(wamid, ts DESC);

CREATE TABLE IF NOT EXISTS contact (
    wa_id TEXT PRIMARY KEY,
    name TEXT,
    first_seen INTEGER,
    last_inbound_ts INTEGER,
    last_outbound_ts INTEGER,
    note TEXT
);

CREATE TABLE IF NOT EXISTS optout (
    wa_id TEXT PRIMARY KEY, ts INTEGER NOT NULL, reason TEXT
);

CREATE TABLE IF NOT EXISTS audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL, action TEXT NOT NULL, wa_id TEXT,
    outcome TEXT, detail TEXT
);
CREATE INDEX IF NOT EXISTS audit_by_time ON audit(ts DESC);

CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
"""

# A person writing any of these is asking not to be contacted again. WhatsApp
# and most regulators expect a business to honour it immediately.
OPT_OUT_WORDS = {"stop", "unsubscribe", "cancel", "opt out", "optout", "remove me",
                 "no more messages", "stop promotions", "stop all"}
OPT_IN_WORDS = {"start", "subscribe", "resume", "opt in", "optin", "unstop"}


class Store:
    """Everything this server remembers."""

    def __init__(self, path):
        self.path = os.path.expanduser(path)
        directory = os.path.dirname(self.path)
        if directory:
            os.makedirs(directory, mode=0o700, exist_ok=True)
        self._init()

    def connect(self):
        conn = sqlite3.connect(self.path, timeout=15)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=15000")
        return conn

    @contextlib.contextmanager
    def _open(self):
        """A connection that commits on success and always closes."""
        conn = self.connect()
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    def _init(self):
        fresh = not os.path.exists(self.path)
        with self._open() as conn:
            conn.executescript(SCHEMA)
            conn.execute("INSERT OR IGNORE INTO meta(key, value) VALUES('schema_version', ?)",
                         (str(SCHEMA_VERSION),))
        if fresh:
            try:
                os.chmod(self.path, 0o600)   # message history is personal data
            except OSError:
                pass

    # --- writing -----------------------------------------------------------
    def record_message(self, wamid, wa_id, direction, ts=None, type=None, body=None,
                       media_id=None, mime_type=None, filename=None, reply_to=None,
                       choice_id=None, status=None, error=None, raw=None):
        ts = int(ts or time.time())
        with self._open() as conn:
            conn.execute(
                """INSERT INTO message
                   (wamid, wa_id, direction, ts, type, body, media_id, mime_type,
                    filename, reply_to, choice_id, status, error, raw)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(wamid) DO UPDATE SET
                     body=COALESCE(excluded.body, message.body),
                     status=COALESCE(excluded.status, message.status),
                     media_id=COALESCE(excluded.media_id, message.media_id),
                     raw=COALESCE(excluded.raw, message.raw)""",
                (wamid, wa_id, direction, ts, type, body, media_id, mime_type, filename,
                 reply_to, choice_id, status, error,
                 json.dumps(raw, ensure_ascii=False) if raw is not None else None),
            )
            column = "last_inbound_ts" if direction == "in" else "last_outbound_ts"
            conn.execute(
                f"""INSERT INTO contact(wa_id, first_seen, {column}) VALUES(?,?,?)
                    ON CONFLICT(wa_id) DO UPDATE SET
                      {column}=MAX(COALESCE(contact.{column}, 0), excluded.{column})""",
                (wa_id, ts, ts),
            )
        return wamid

    def record_status(self, wamid, status, ts=None, wa_id=None, error_code=None,
                      error_title=None, raw=None):
        ts = int(ts or time.time())
        with self._open() as conn:
            conn.execute(
                """INSERT INTO status_event(wamid, status, ts, wa_id, error_code, error_title, raw)
                   VALUES (?,?,?,?,?,?,?)""",
                (wamid, status, ts, wa_id, error_code, error_title,
                 json.dumps(raw, ensure_ascii=False) if raw is not None else None),
            )
            conn.execute(
                "UPDATE message SET status=?, error=COALESCE(?, error) WHERE wamid=?",
                (status, error_title, wamid),
            )

    def set_contact_name(self, wa_id, name):
        if not name:
            return
        with self._open() as conn:
            conn.execute(
                """INSERT INTO contact(wa_id, name, first_seen) VALUES(?,?,?)
                   ON CONFLICT(wa_id) DO UPDATE SET name=excluded.name""",
                (wa_id, name, int(time.time())),
            )

    def set_note(self, wa_id, note):
        with self._open() as conn:
            conn.execute(
                """INSERT INTO contact(wa_id, note, first_seen) VALUES(?,?,?)
                   ON CONFLICT(wa_id) DO UPDATE SET note=excluded.note""",
                (wa_id, note, int(time.time())),
            )

    def audit(self, action, wa_id=None, outcome="ok", detail=""):
        with self._open() as conn:
            conn.execute("INSERT INTO audit(ts, action, wa_id, outcome, detail) VALUES (?,?,?,?,?)",
                         (int(time.time()), action, wa_id, outcome, str(detail)[:2000]))

    # --- opt-out -----------------------------------------------------------
    def opt_out(self, wa_id, reason="requested"):
        with self._open() as conn:
            conn.execute("INSERT OR REPLACE INTO optout(wa_id, ts, reason) VALUES (?,?,?)",
                         (wa_id, int(time.time()), reason))

    def opt_in(self, wa_id):
        with self._open() as conn:
            conn.execute("DELETE FROM optout WHERE wa_id=?", (wa_id,))

    def is_opted_out(self, wa_id):
        with self._open() as conn:
            row = conn.execute("SELECT ts, reason FROM optout WHERE wa_id=?", (wa_id,)).fetchone()
        return dict(row) if row else None

    def opt_outs(self):
        with self._open() as conn:
            return [dict(r) for r in conn.execute("SELECT * FROM optout ORDER BY ts DESC")]

    # --- reading -----------------------------------------------------------
    def last_inbound_ts(self, wa_id):
        with self._open() as conn:
            row = conn.execute("SELECT last_inbound_ts FROM contact WHERE wa_id=?", (wa_id,)).fetchone()
        return row["last_inbound_ts"] if row and row["last_inbound_ts"] else None

    def sends_since(self, since_ts):
        with self._open() as conn:
            row = conn.execute("SELECT COUNT(*) n FROM message WHERE direction='out' AND ts>=?",
                               (int(since_ts),)).fetchone()
        return row["n"]

    def conversations(self, limit=20, unread_only=False):
        """Most recently active contacts, newest first."""
        sql = """
            SELECT c.wa_id, c.name, c.note, c.last_inbound_ts, c.last_outbound_ts,
                   (SELECT COUNT(*) FROM message m WHERE m.wa_id=c.wa_id) AS messages,
                   (SELECT body FROM message m WHERE m.wa_id=c.wa_id ORDER BY m.ts DESC LIMIT 1) AS last_body,
                   (SELECT direction FROM message m WHERE m.wa_id=c.wa_id ORDER BY m.ts DESC LIMIT 1) AS last_direction,
                   (SELECT MAX(ts) FROM message m WHERE m.wa_id=c.wa_id) AS last_ts,
                   EXISTS(SELECT 1 FROM optout o WHERE o.wa_id=c.wa_id) AS opted_out
            FROM contact c
        """
        if unread_only:
            sql += " WHERE COALESCE(c.last_inbound_ts,0) > COALESCE(c.last_outbound_ts,0)"
        sql += " ORDER BY COALESCE(last_ts, c.first_seen) DESC LIMIT ?"
        with self._open() as conn:
            return [dict(r) for r in conn.execute(sql, (int(limit),))]

    def messages(self, wa_id, limit=30, since_ts=None, before_ts=None):
        sql = "SELECT * FROM message WHERE wa_id=?"
        args = [wa_id]
        if since_ts:
            sql += " AND ts>=?"
            args.append(int(since_ts))
        if before_ts:
            sql += " AND ts<?"
            args.append(int(before_ts))
        sql += " ORDER BY ts DESC LIMIT ?"
        args.append(int(limit))
        with self._open() as conn:
            rows = [dict(r) for r in conn.execute(sql, args)]
        rows.reverse()                     # oldest first reads like a chat
        return rows

    def search(self, query, limit=30, wa_id=None):
        sql = "SELECT * FROM message WHERE body LIKE ? ESCAPE '\\'"
        args = ["%" + str(query).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"]
        if wa_id:
            sql += " AND wa_id=?"
            args.append(wa_id)
        sql += " ORDER BY ts DESC LIMIT ?"
        args.append(int(limit))
        with self._open() as conn:
            return [dict(r) for r in conn.execute(sql, args)]

    def message(self, wamid):
        with self._open() as conn:
            row = conn.execute("SELECT * FROM message WHERE wamid=?", (wamid,)).fetchone()
            statuses = [dict(r) for r in conn.execute(
                """SELECT status, ts, error_code, error_title FROM status_event
                   WHERE wamid=? ORDER BY ts, id""",
                (wamid,))]
        if not row:
            return None
        out = dict(row)
        out["status_history"] = statuses
        return out

    def contact(self, wa_id):
        with self._open() as conn:
            row = conn.execute("SELECT * FROM contact WHERE wa_id=?", (wa_id,)).fetchone()
        return dict(row) if row else None

    def find_contacts(self, query, limit=10):
        """Look a contact up by name fragment or by number."""
        like = "%" + str(query).lower() + "%"
        with self._open() as conn:
            return [dict(r) for r in conn.execute(
                """SELECT * FROM contact
                   WHERE LOWER(COALESCE(name,'')) LIKE ? OR wa_id LIKE ?
                   ORDER BY COALESCE(last_inbound_ts, last_outbound_ts, 0) DESC LIMIT ?""",
                (like, like, int(limit)))]

    def audit_entries(self, limit=50):
        with self._open() as conn:
            return [dict(r) for r in conn.execute(
                "SELECT * FROM audit ORDER BY ts DESC LIMIT ?", (int(limit),))]

    def stats(self):
        with self._open() as conn:
            def one(sql, *args):
                return conn.execute(sql, args).fetchone()[0]
            return {
                "messages_in": one("SELECT COUNT(*) FROM message WHERE direction='in'"),
                "messages_out": one("SELECT COUNT(*) FROM message WHERE direction='out'"),
                "contacts": one("SELECT COUNT(*) FROM contact"),
                "opt_outs": one("SELECT COUNT(*) FROM optout"),
                "last_inbound_ts": one("SELECT COALESCE(MAX(ts),0) FROM message WHERE direction='in'"),
                "db_path": self.path,
            }


def classify_keyword(text):
    """'stop' -> 'opt_out', 'start' -> 'opt_in', anything else -> None."""
    cleaned = " ".join(str(text or "").lower().split()).strip(".!? ")
    if cleaned in OPT_OUT_WORDS:
        return "opt_out"
    if cleaned in OPT_IN_WORDS:
        return "opt_in"
    return None
