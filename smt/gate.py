"""The approval gate - the only code path that can make content publishable.

Hard rules, enforced here in code (a prompt can't override them):

1. Every item enters the queue as PENDING. `enqueue()` has no way to set any
   other status.
2. Only the configured owner can approve, and only via a trusted channel
   (the Telegram chat whose id is SMT_OWNER_CHAT_ID, or the local CLI).
3. Approval is bound to the SHA-256 of exactly what will be published. Any
   edit - by the agent or the owner - drops the item back to PENDING.
4. `publishable()` re-checks status, approver and hash at dispatch time, so a
   row changed behind the gate's back (e.g. by direct SQL) is refused.
"""
import json
import os
from datetime import datetime

from . import db

TRUSTED_CHANNELS = {"telegram", "cli"}


class GateError(PermissionError):
    pass


def owner_id():
    return os.environ.get("SMT_OWNER_CHAT_ID", "owner")


def enqueue(conn, *, kind, platform, body, fmt=None, caption=None, hashtags=None, pillar=None, topic=None,
            hook=None, hook_trigger=None, source_hook_id=None, in_reply_to=None, tracking_code=None,
            link=None, scheduled_for=None, now=None):
    now = now or datetime.now()
    if isinstance(body, (dict, list)):
        body = json.dumps(body, ensure_ascii=False)
    if isinstance(hashtags, (list, tuple)):
        hashtags = " ".join(hashtags)
    h = db.content_hash(platform, body, caption, hashtags)
    cur = conn.execute(
        """INSERT INTO queue_item (created_ts, kind, platform, format, pillar, topic, hook, hook_trigger,
           source_hook_id, body, caption, hashtags, in_reply_to, tracking_code, link, scheduled_for,
           status, content_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'PENDING', ?)""",
        (db.ts(now), kind, platform, fmt, pillar, topic, hook, hook_trigger, source_hook_id, body, caption,
         hashtags, in_reply_to, tracking_code, link, scheduled_for, h))
    conn.commit()
    return cur.lastrowid


def _check_actor(actor, channel):
    if channel not in TRUSTED_CHANNELS:
        raise GateError(f"channel '{channel}' cannot make decisions")
    if str(actor) != str(owner_id()):
        raise GateError(f"{actor} is not the owner; only the owner can approve or reject")


def _pending(conn, item_id):
    row = conn.execute("SELECT * FROM queue_item WHERE id=?", (item_id,)).fetchone()
    if not row:
        raise KeyError(f"queue item #{item_id} not found")
    if row["status"] != "PENDING":
        raise GateError(f"#{item_id} is {row['status']}, not PENDING")
    return row


def approve(conn, item_id, actor, channel, now=None):
    _check_actor(actor, channel)
    row = _pending(conn, item_id)
    conn.execute("UPDATE queue_item SET status='APPROVED', approved_hash=?, decided_by=?, decided_ts=?, decided_via=? "
                 "WHERE id=?", (row["content_hash"], str(actor), db.ts(now or datetime.now()), channel, item_id))
    db.audit(conn, f"{actor}@{channel}", "approved", f"#{item_id} {row['kind']} {row['platform']}", now)
    return dict(conn.execute("SELECT * FROM queue_item WHERE id=?", (item_id,)).fetchone())


def reject(conn, item_id, actor, channel, reason="", now=None):
    _check_actor(actor, channel)
    row = _pending(conn, item_id)
    conn.execute("UPDATE queue_item SET status='REJECTED', decided_by=?, decided_ts=?, decided_via=?, last_error=? "
                 "WHERE id=?", (str(actor), db.ts(now or datetime.now()), channel, reason or None, item_id))
    db.audit(conn, f"{actor}@{channel}", "rejected", f"#{item_id} {reason}".strip(), now)


def edit(conn, item_id, actor, *, body=None, caption=None, hashtags=None, media_path=None, now=None):
    """Change content. Allowed while PENDING, APPROVED or AWAITING_MEDIA; always returns it to PENDING."""
    row = conn.execute("SELECT * FROM queue_item WHERE id=?", (item_id,)).fetchone()
    if not row:
        raise KeyError(f"queue item #{item_id} not found")
    if row["status"] not in ("PENDING", "APPROVED", "AWAITING_MEDIA"):
        raise GateError(f"#{item_id} is {row['status']} and can no longer be edited")
    new = {"body": body if body is not None else row["body"],
           "caption": caption if caption is not None else row["caption"],
           "hashtags": hashtags if hashtags is not None else row["hashtags"],
           "media_path": media_path if media_path is not None else row["media_path"]}
    h = db.content_hash(row["platform"], new["body"], new["caption"], new["hashtags"], new["media_path"])
    conn.execute("UPDATE queue_item SET body=?, caption=?, hashtags=?, media_path=?, content_hash=?, "
                 "status='PENDING', approved_hash=NULL, decided_by=NULL, decided_ts=NULL, decided_via=NULL WHERE id=?",
                 (new["body"], new["caption"], new["hashtags"], new["media_path"], h, item_id))
    db.audit(conn, str(actor), "edited", f"#{item_id} (back to PENDING)", now)


def publishable(conn, item_id):
    """Final check right before anything is sent to a platform."""
    row = conn.execute("SELECT * FROM queue_item WHERE id=?", (item_id,)).fetchone()
    if row is None or row["status"] != "APPROVED":
        raise GateError(f"#{item_id} is not approved")
    if row["decided_by"] != str(owner_id()) or row["decided_via"] not in TRUSTED_CHANNELS:
        raise GateError(f"#{item_id} approval is not from the owner via a trusted channel")
    current = db.content_hash(row["platform"], row["body"], row["caption"], row["hashtags"], row["media_path"])
    if row["approved_hash"] != current or row["content_hash"] != current:
        raise GateError(f"#{item_id} changed after approval; it must be approved again")
    return row


def pending(conn, kind=None):
    sql, args = "SELECT * FROM queue_item WHERE status='PENDING'", []
    if kind:
        sql, args = sql + " AND kind=?", [kind]
    return [dict(r) for r in conn.execute(sql + " ORDER BY scheduled_for IS NULL, scheduled_for, id", args)]
