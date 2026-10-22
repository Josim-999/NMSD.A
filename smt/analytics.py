"""Module 4 - Analytics & ROI attribution, and the morning briefing.

Attribution chain:  post (tracking_code) -> UTM click -> sign-up -> sale,
and                 post -> comment/DM -> lead -> sale (`close_lead`).

The unit that matters is revenue per hook trigger and per topic, not likes.
Views are kept only to compute conversion rates.
"""
import csv
from datetime import datetime, timedelta

from . import db, gate

EVENT_KINDS = {"view", "click", "lead", "signup", "sale"}


def record_event(conn, kind, tracking_code, value=0.0, lead_id=None, when=None):
    if kind not in EVENT_KINDS:
        raise ValueError(f"unknown event kind {kind}")
    conn.execute("INSERT INTO event (ts, kind, tracking_code, lead_id, value) VALUES (?,?,?,?,?)",
                 (db.ts(when or datetime.now()), kind, tracking_code, lead_id, value))
    conn.commit()


def import_csv(conn, path):
    """Import events from an analytics / Stripe / Gumroad export.

    Needs columns: ts, kind, utm_content (the tracking code) and optionally value.
    """
    n = 0
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            record_event(conn, r["kind"], r["utm_content"], float(r.get("value") or 0), when=db.parse(r["ts"]))
            n += 1
    return n


def close_lead(conn, lead_id, value, when=None):
    row = conn.execute("SELECT l.*, q.tracking_code FROM lead l LEFT JOIN queue_item q ON q.id = l.source_item "
                       "WHERE l.id=?", (lead_id,)).fetchone()
    if not row:
        raise KeyError(f"lead #{lead_id} not found")
    conn.execute("UPDATE lead SET status='WON', value=? WHERE id=?", (value, lead_id))
    record_event(conn, "sale", row["tracking_code"], value, lead_id, when)


_AGG = """
SELECT {key} AS k, COUNT(DISTINCT q.id) AS posts,
       COALESCE(SUM(e.kind='view'), 0) AS views, COALESCE(SUM(e.kind='click'), 0) AS clicks,
       COALESCE(SUM(e.kind='lead'), 0) AS leads, COALESCE(SUM(e.kind='signup'), 0) AS signups,
       COALESCE(SUM(e.kind='sale'), 0) AS sales, COALESCE(SUM(CASE WHEN e.kind='sale' THEN e.value END), 0) AS revenue
FROM queue_item q LEFT JOIN event e ON e.tracking_code = q.tracking_code {since}
WHERE q.kind='post' AND q.status='PUBLISHED'
GROUP BY k ORDER BY revenue DESC, leads DESC, clicks DESC
"""


def attribution(conn, by="hook_trigger", since=None):
    key = {"hook_trigger": "q.hook_trigger", "topic": "q.topic", "platform": "q.platform",
           "post": "q.id || ' ' || q.platform || ' | ' || q.hook"}[by]
    sql = _AGG.format(key=key, since="AND e.ts >= ?" if since else "")
    rows = [dict(r) for r in conn.execute(sql, (db.ts(since),) if since else ())]
    for r in rows:
        r["click_rate"] = r["clicks"] / r["views"] if r["views"] else 0.0
        r["revenue_per_post"] = r["revenue"] / r["posts"] if r["posts"] else 0.0
    return rows


def totals(conn, start, end):
    r = conn.execute("SELECT COALESCE(SUM(kind='view'),0) v, COALESCE(SUM(kind='click'),0) c, "
                     "COALESCE(SUM(kind='lead'),0) l, COALESCE(SUM(kind='signup'),0) s, "
                     "COALESCE(SUM(kind='sale'),0) n, COALESCE(SUM(CASE WHEN kind='sale' THEN value END),0) rev "
                     "FROM event WHERE ts >= ? AND ts < ?", (db.ts(start), db.ts(end))).fetchone()
    pub = conn.execute("SELECT COUNT(*) FROM queue_item WHERE kind='post' AND status='PUBLISHED' AND published_ts >= ? AND published_ts < ?",
                       (db.ts(start), db.ts(end))).fetchone()[0]
    return {"published": pub, "views": r["v"], "clicks": r["c"], "leads": r["l"], "signups": r["s"],
            "sales": r["n"], "revenue": r["rev"]}


def _delta(a, b):
    return "" if not b else f" ({(a - b) / b:+.0%})"


def morning_briefing(conn, brand, now=None):
    now = now or datetime.now()
    today = datetime(now.year, now.month, now.day)
    y = totals(conn, today - timedelta(days=1), today)
    prev = totals(conn, today - timedelta(days=2), today - timedelta(days=1))
    wk = totals(conn, today - timedelta(days=7), today)
    best = [r for r in attribution(conn, "hook_trigger", today - timedelta(days=30)) if r["revenue"] or r["leads"]][:3]
    topics = [r for r in attribution(conn, "topic", today - timedelta(days=30)) if r["revenue"] or r["leads"]][:3]
    queue = gate.pending(conn)
    posts = [q for q in queue if q["kind"] == "post"]
    replies = [q for q in queue if q["kind"] != "post"]
    media = conn.execute("SELECT id, platform, scheduled_for FROM queue_item WHERE status='AWAITING_MEDIA' ORDER BY id").fetchall()
    leads = conn.execute("SELECT * FROM lead WHERE status='NEW' ORDER BY score DESC, id").fetchall()
    errs = conn.execute("SELECT * FROM error_log WHERE needs_human=1 AND ts >= ? ORDER BY id DESC LIMIT 3",
                        ((today - timedelta(days=1)).isoformat(),)).fetchall()

    L = [f"☀️ {brand['name']} - morning briefing, {now:%a %d %b %Y}", "",
         "YESTERDAY",
         f"  Published {y['published']} · views {y['views']:,}{_delta(y['views'], prev['views'])} · "
         f"clicks {y['clicks']}{_delta(y['clicks'], prev['clicks'])}",
         f"  Leads {y['leads']} · sign-ups {y['signups']} · sales {y['sales']} · revenue ${y['revenue']:,.0f}",
         f"  Last 7 days: {wk['leads']} leads, {wk['sales']} sales, ${wk['revenue']:,.0f} revenue", ""]
    if best:
        L.append("WHAT MAKES MONEY (30 days, by hook trigger)")
        L += [f"  {r['k']:<13} {r['posts']} posts → {r['leads']} leads, {r['sales']} sales, ${r['revenue']:,.0f}"
              for r in best]
        L += [f"  topic: {r['k']} → ${r['revenue']:,.0f}" for r in topics]
        L.append("")
    L.append(f"WAITING FOR YOU ({len(posts)} posts, {len(replies)} replies/DMs, {len(media)} video(s) to film)")
    for q in posts[:5]:
        L.append(f"  #{q['id']} {q['platform']:<9} {q['format']:<12} {q['scheduled_for'] or '':<16} {q['hook'][:60]}")
    if len(posts) > 5:
        L.append(f"  … and {len(posts) - 5} more  (/queue)")
    if replies:
        L.append(f"  {len(replies)} reply drafts - approve all with /approve_replies")
    for m in media:
        L.append(f"  🎬 #{m['id']} {m['platform']} needs a video (slot {m['scheduled_for']})")
    L.append("")
    L.append(f"QUALIFIED LEADS ({len(leads)} open)")
    for ld in leads[:5]:
        L.append(f"  #{ld['id']} {ld['platform']} {ld['author']} - {ld['reason']} (score {ld['score']:.2f})")
    if not leads:
        L.append("  none - nothing to chase today")
    if errs:
        L.append("")
        L.append("NEEDS ATTENTION")
        L += [f"  {e['ts'][11:16]} {e['where_']}: {e['error'][:90]}" for e in errs]
    return "\n".join(L)
