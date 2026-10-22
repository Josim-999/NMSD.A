"""Synthetic history for the demo: three weeks of published posts and their results.

The numbers are invented but shaped like a common real pattern: curiosity
hooks get the most views, while pain-point and authority hooks bring the
buyers. That is the point of revenue attribution - views alone would pick the
wrong winner.
"""
import random
from datetime import timedelta

from . import db, gate
from .content import week_slots

# trigger -> (avg views, click rate, signup rate per click, sale rate per signup, avg sale $)
PROFILE = {
    "curiosity":    (9000, .006, .20, .05, 900),
    "pain_point":   (4200, .018, .30, .14, 1200),
    "authority":    (3100, .015, .32, .18, 1500),
    "listicle":     (6000, .009, .22, .06, 900),
    "contrarian":   (7000, .007, .18, .04, 900),
    "social_proof": (2500, .016, .30, .12, 1200),
}
HISTORY_HOOKS = [
    ("curiosity", "quarterly taxes", "The quarterly taxes rule nobody puts in the onboarding email."),
    ("pain_point", "late invoices", "Still chasing invoices at day 60? Fix the contract, not the client."),
    ("authority", "business expenses", "Bookkeeper here: the expense setup I give every freelance client."),
    ("listicle", "freelance pricing", "4 numbers to know before you quote your next project."),
    ("contrarian", "receipts", "Hot take: you don't need a receipt app. You need a Friday habit."),
    ("social_proof", "quarterly taxes", "How one illustrator stopped dreading tax season."),
]


def seed_history(conn, start, weeks=3, seed=7):
    rnd = random.Random(seed)
    owner = gate.owner_id()
    i = 0
    for w in range(weeks):
        for when, platform in week_slots(start + timedelta(weeks=w), 6):
            trig, topic, hook = HISTORY_HOOKS[i % len(HISTORY_HOOKS)]
            i += 1
            code = f"H{i:03d}"
            item = gate.enqueue(conn, kind="post", platform=platform, fmt="text_post", body={"text": hook},
                                caption=hook, topic=topic, hook=hook, hook_trigger=trig, tracking_code=code,
                                link=f"https://example.com/checklist?utm_content={code}", scheduled_for=db.ts(when),
                                now=when - timedelta(days=2))
            gate.approve(conn, item, owner, "cli", now=when - timedelta(days=1))
            conn.execute("UPDATE queue_item SET status='PUBLISHED', published_ts=?, external_id=? WHERE id=?",
                         (db.ts(when), f"hist-{i}", item))
            _engage(conn, rnd, code, trig, when, hours=72)
    conn.commit()


def _engage(conn, rnd, code, trig, when, hours, scale=1.0):
    views, ctr, sr, cr, aov = PROFILE.get(trig, PROFILE["curiosity"])
    v = int(views * scale * rnd.uniform(.6, 1.4))
    clicks = sum(rnd.random() < ctr for _ in range(v))
    signups = sum(rnd.random() < sr for _ in range(clicks))
    sales = sum(rnd.random() < cr for _ in range(signups))
    rows = [("view", 0)] * v + [("click", 0)] * clicks + [("signup", 0)] * signups + \
           [("sale", aov * rnd.uniform(.8, 1.2)) for _ in range(sales)]
    conn.executemany("INSERT INTO event (ts, kind, tracking_code, value) VALUES (?,?,?,?)",
                     [(db.ts(when + timedelta(hours=rnd.uniform(1, hours))), k, code, round(val, 2)) for k, val in rows])


def simulate_engagement(conn, until, seed=11):
    """Give the demo's newly published posts first-day results, up to `until`."""
    rnd = random.Random(seed)
    for r in conn.execute("SELECT tracking_code, hook_trigger, published_ts FROM queue_item WHERE kind='post' "
                          "AND status='PUBLISHED' AND external_id NOT LIKE 'hist-%'").fetchall():
        start = db.parse(r["published_ts"])
        hours = (until - start).total_seconds() / 3600
        if hours > 1:
            _engage(conn, rnd, r["tracking_code"], r["hook_trigger"], start, hours, scale=min(1.0, hours / 72))
    conn.commit()
