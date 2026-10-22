"""Module 3a - Omnichannel dispatch of APPROVED items.

`dispatch_due()` is the only caller of a publisher, and it calls
`gate.publishable()` on every item first.

Publishers:
* `DryRunPublisher` (default) - writes the exact payload to outbox/ and goes
  no further. Use it until you trust the pipeline.
* `WebhookPublisher` - POSTs the payload to a Buffer / Zapier / Make webhook.
  For a one-person business this is usually the practical route: those tools
  already hold approved app access to TikTok, Instagram and YouTube.
* `XPublisher`, `LinkedInPublisher` - official APIs for text posts.

Video platforms need an actual video. When an approved script has no media
yet, dispatch sends the owner a filming brief and parks the item as
AWAITING_MEDIA. Attaching the video (`attach-media`) is an edit, so the final
video goes back through approval.
"""
import json
import os
import urllib.error
import urllib.request
from datetime import datetime, timedelta

from . import db, gate
from .config import PLATFORMS, ROOT, VIDEO_PLATFORMS
from .resilience import PermanentError, RateLimited, TransientError, with_retry

OUTBOX = os.path.join(ROOT, "outbox")


def payload(row):
    body = json.loads(row["body"]) if row["body"].startswith(("{", "[")) else row["body"]
    return {"queue_id": row["id"], "kind": row["kind"], "platform": row["platform"], "format": row["format"],
            "body": body, "caption": row["caption"], "hashtags": (row["hashtags"] or "").split(),
            "link": row["link"], "media_path": row["media_path"], "tracking_code": row["tracking_code"],
            "in_reply_to": row["in_reply_to"]}


def _post(url, data, headers, timeout=30):
    req = urllib.request.Request(url, data=json.dumps(data).encode(), method="POST",
                                 headers={"Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}, r.headers
    except urllib.error.HTTPError as e:
        if e.code == 429:
            raise RateLimited("rate limited", e.headers.get("Retry-After"))
        if e.code >= 500:
            raise TransientError(f"HTTP {e.code}")
        raise PermanentError(f"HTTP {e.code}: {e.read()[:300]!r}")
    except (urllib.error.URLError, TimeoutError) as e:
        raise TransientError(str(e))


class DryRunPublisher:
    name = "dry-run (outbox/)"

    def __init__(self, outbox=OUTBOX):
        self.outbox = outbox

    def publish(self, p):
        os.makedirs(self.outbox, exist_ok=True)
        path = os.path.join(self.outbox, f"{p['queue_id']:04d}_{p['platform']}_{p['kind']}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(p, f, indent=2, ensure_ascii=False)
        return f"dryrun:{os.path.basename(path)}"


class WebhookPublisher:
    def __init__(self, url, secret=None):
        self.url, self.secret = url, secret
        self.name = "webhook"

    def publish(self, p):
        res, _ = _post(self.url, p, {"X-SMT-Secret": self.secret} if self.secret else {})
        return str(res.get("id", "webhook-accepted"))


class XPublisher:
    """X API v2 POST /2/tweets with an OAuth 2.0 user-context token (tweet.write scope)."""
    name = "x"

    def __init__(self, token):
        self.token = token

    def publish(self, p):
        text = p["body"]["text"] if isinstance(p["body"], dict) else str(p["body"])
        data = {"text": text}
        if p["kind"] == "reply" and p.get("reply_to_external_id"):
            data["reply"] = {"in_reply_to_tweet_id": p["reply_to_external_id"]}
        res, _ = _post("https://api.x.com/2/tweets", data, {"Authorization": f"Bearer {self.token}"})
        return res.get("data", {}).get("id", "")


class LinkedInPublisher:
    """LinkedIn Posts API (text posts) for a member or organisation URN."""
    name = "linkedin"

    def __init__(self, token, author_urn, version="202609"):
        self.token, self.author, self.version = token, author_urn, version

    def publish(self, p):
        body = p["body"]
        text = body.get("text") if isinstance(body, dict) and "text" in body else \
            "\n\n".join(body.get("slides", [])) if isinstance(body, dict) else str(body)
        if p.get("caption") and "slides" in (body if isinstance(body, dict) else {}):
            text = p["caption"] + "\n\n" + text
        data = {"author": self.author, "commentary": text + "\n\n" + " ".join(p["hashtags"]),
                "visibility": "PUBLIC", "lifecycleState": "PUBLISHED", "isReshareDisabledByAuthor": False,
                "distribution": {"feedDistribution": "MAIN_FEED", "targetEntities": [], "thirdPartyDistributionChannels": []}}
        _, headers = _post("https://api.linkedin.com/rest/posts", data,
                           {"Authorization": f"Bearer {self.token}", "LinkedIn-Version": self.version,
                            "X-Restli-Protocol-Version": "2.0.0"})
        return headers.get("x-restli-id", "")


def publishers_from_env():
    """Map each platform to a publisher. Anything not configured stays dry-run."""
    dry = DryRunPublisher()
    pubs = {p: dry for p in PLATFORMS}
    if os.environ.get("SMT_PUBLISH_MODE", "dryrun") != "live":
        return pubs
    hook = os.environ.get("SMT_WEBHOOK_URL")
    if hook:
        wh = WebhookPublisher(hook, os.environ.get("SMT_WEBHOOK_SECRET"))
        pubs = {p: wh for p in PLATFORMS}
    if os.environ.get("X_USER_TOKEN"):
        pubs["x"] = XPublisher(os.environ["X_USER_TOKEN"])
    if os.environ.get("LINKEDIN_TOKEN") and os.environ.get("LINKEDIN_AUTHOR_URN"):
        pubs["linkedin"] = LinkedInPublisher(os.environ["LINKEDIN_TOKEN"], os.environ["LINKEDIN_AUTHOR_URN"])
    return pubs


def next_peak(platform, after):
    peaks = PLATFORMS[platform]["peak"]
    for d in range(8):
        day = (after + timedelta(days=d)).date()
        t = peaks.get(day.weekday())
        if t:
            hh, mm = map(int, t.split(":"))
            when = datetime(day.year, day.month, day.day, hh, mm)
            if when > after:
                return when
    return after


def filming_brief(row):
    b = json.loads(row["body"])
    beats = "\n".join(f"  {x}" for x in b.get("body", []))
    return (f"🎬 Filming brief for #{row['id']} ({row['platform']}, planned {row['scheduled_for']})\n"
            f"Hook (0-3s): {b.get('hook_0_3s')}\n{beats}\nCTA: {b.get('cta')}\nNotes: {b.get('shot_notes')}\n"
            f"Attach the video with: python -m smt attach-media {row['id']} <file.mp4>  (then approve it again)")


def dispatch_due(conn, publishers, now=None, notify=None, sleep=None):
    """Publish every APPROVED item whose slot has come. Returns list of (id, outcome)."""
    now = now or datetime.now()
    out = []
    rows = conn.execute("SELECT * FROM queue_item WHERE status='APPROVED' ORDER BY scheduled_for, id").fetchall()
    for row in rows:
        if row["scheduled_for"] and db.parse(row["scheduled_for"]) > now:
            continue
        try:
            row = gate.publishable(conn, row["id"])
        except gate.GateError as e:
            conn.execute("UPDATE queue_item SET status='PENDING', last_error=? WHERE id=?", (str(e), row["id"]))
            conn.commit()
            out.append((row["id"], f"sent back to PENDING: {e}"))
            continue
        if row["kind"] == "post" and row["format"] == "video_script" and row["platform"] in VIDEO_PLATFORMS \
                and not row["media_path"]:
            conn.execute("UPDATE queue_item SET status='AWAITING_MEDIA' WHERE id=?", (row["id"],))
            conn.commit()
            if notify:
                notify(filming_brief(row))
            out.append((row["id"], "awaiting media (filming brief sent)"))
            continue
        p = payload(row)
        if row["in_reply_to"]:
            ext = conn.execute("SELECT external_id FROM inbound WHERE id=?", (row["in_reply_to"],)).fetchone()
            p["reply_to_external_id"] = ext["external_id"] if ext else None
        pub = publishers[row["platform"]]
        kw = {"conn": conn, "notify": notify}
        if sleep:
            kw["sleep"] = sleep
        try:
            ext_id = with_retry(lambda: pub.publish(p), where=f"publish #{row['id']} {row['platform']}", **kw)
        except (TransientError, PermanentError) as e:
            conn.execute("UPDATE queue_item SET status='FAILED', attempts=attempts+1, last_error=? WHERE id=?",
                         (str(e), row["id"]))
            conn.commit()
            out.append((row["id"], f"FAILED: {e}"))
            continue
        conn.execute("UPDATE queue_item SET status='PUBLISHED', published_ts=?, external_id=?, attempts=attempts+1 "
                     "WHERE id=?", (db.ts(now), ext_id, row["id"]))
        if row["in_reply_to"]:
            conn.execute("UPDATE inbound SET handled=1 WHERE id=?", (row["in_reply_to"],))
        db.audit(conn, "agent", "published", f"#{row['id']} {row['platform']} via {pub.name}: {ext_id}", now)
        out.append((row["id"], f"published via {pub.name}"))
    return out


def reschedule_missed(conn, now=None):
    """Approved after its slot passed? Move it to that platform's next peak slot instead of posting at a dead hour."""
    now = now or datetime.now()
    n = 0
    for row in conn.execute("SELECT id, platform, scheduled_for, decided_ts FROM queue_item WHERE status='APPROVED' "
                            "AND kind='post' AND scheduled_for < decided_ts").fetchall():
        conn.execute("UPDATE queue_item SET scheduled_for=? WHERE id=?",
                     (db.ts(next_peak(row["platform"], max(now, db.parse(row["decided_ts"])))), row["id"]))
        n += 1
    conn.commit()
    return n
