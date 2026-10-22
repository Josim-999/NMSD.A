"""Module 3b - Inbound triage: comments and DMs.

* Qualified leads (pricing, "interested", booking) -> `lead` row + an
  immediate owner alert.
* Standard questions, praise, lead-magnet requests -> a drafted reply in the
  approval queue (PENDING). The owner can approve them one by one or all at
  once with `/approve_replies` in Telegram.
* Spam is ignored; complaints alert the owner and get a careful draft.

Replies are never sent automatically. The original spec asked for automatic
replies to standard queries AND "zero comments without confirmation"; those
contradict each other, and the safety rule wins. Batch approval keeps the
effort to one tap. See docs/SMT_ANALYSIS.md.
"""
import json
import os
import re
from datetime import datetime

from . import db, gate
from .config import LEAD_THRESHOLD
from .content import TOPIC_POINTS

RULES = {
    "lead": [(r"\bhow much\b", .55), (r"\b(price|pricing|cost|rates?|fees?|packages?)\b", .45),
             (r"\binterested\b", .5), (r"\b(book|schedule) (a )?(call|consult|chat)\b", .6),
             (r"\b(hire|work with|onboard with) you\b", .6), (r"\b(do|are) you (take|taking|accept\w*) (new )?clients\b", .65),
             (r"\bi need (help|someone|an? (accountant|bookkeeper))\b", .45), (r"\bavailab(le|ility)\b", .3),
             (r"\b(sign me up|where do i sign|send (me )?(the )?(details|info))\b", .5), (r"\bdm me\b", .25)],
    "spam": [(r"\b(crypto|forex|bitcoin|followers|promo(te|tion)|check (out )?my (page|profile))\b", .7),
             (r"https?://", .3), (r"\b(collab|ambassador)\b", .3)],
    "complaint": [(r"\b(scam|terrible|worst|useless|wrong advice|misleading|refund)\b", .7)],
    "praise": [(r"\b(love|loved|great|helpful|thank(s| you)|gold|saved me|needed this)\b", .5)],
    "question": [(r"\?\s*$", .4), (r"^(how|what|can|should|is|do|does|when|why)\b", .4)],
}


def rule_classify(text, brand):
    low = text.lower().strip()
    scores = {k: min(1.0, sum(w for p, w in pats if re.search(p, low))) for k, pats in RULES.items()}
    magnet = brand["cta"].split()[1].strip(".,!").lower() if brand["cta"].lower().startswith("comment ") else None
    if magnet and re.fullmatch(rf"\W*{re.escape(magnet)}\W*", low):
        return "magnet", 0.4, f"commented the CTA keyword '{magnet.upper()}'"
    if scores["spam"] >= .7:
        return "spam", 0.0, "spam pattern"
    if scores["lead"] >= LEAD_THRESHOLD or (scores["lead"] >= .45 and scores["question"]):
        return "lead", min(1.0, scores["lead"] + .1 * bool(scores["question"])), "buying signal: " + _hits(low, "lead")
    if scores["complaint"]:
        return "complaint", 0.0, "negative sentiment"
    if scores["question"]:
        return "question", scores["lead"], "general question"
    if scores["praise"]:
        return "praise", 0.0, "positive feedback"
    return "other", scores["lead"], "no clear intent"


def _hits(low, kind):
    return ", ".join(re.search(p, low).group(0) for p, _ in RULES[kind] if re.search(p, low))


FAQ_KEYWORDS = {"quarterly taxes": ["tax", "set aside", "quarter"], "late invoices": ["invoice", "late", "paid"],
                "business expenses": ["deduct", "expense", "laptop", "write off"],
                "freelance pricing": ["price", "rate", "charge"], "receipts": ["receipt"]}


def faq_answer(text):
    low = text.lower()
    scored = [(sum(k in low for k in kws), topic) for topic, kws in FAQ_KEYWORDS.items()]
    n, topic = max(scored)
    if not n:
        return None, None
    words = set(re.findall(r"[a-z]{4,}", low))
    point = max(TOPIC_POINTS[topic], key=lambda p: len(words & set(re.findall(r"[a-z]{4,}", p.lower()))))
    return topic, point


def draft_reply(intent, text, brand):
    if intent == "question":
        topic, point = faq_answer(text)
        if point:
            return (f"Good question. Short version: {point.lower()}. It depends on your situation, so treat this as "
                    f"general info, not tax advice. My free checklist covers the rest - comment BOOKS and I'll send it.")
        return "Good question - I'll cover this in a post this week. Want me to tag you when it's up?"
    if intent == "praise":
        return "Thank you! Glad it helped. Which topic should I cover next?"
    if intent == "magnet":
        return f"Here you go: {brand['landing_url']} - shout if anything in it is unclear."
    if intent == "complaint":
        return "Sorry this didn't land for you. Can you tell me what happened? I'd like to put it right."
    return None


def ingest(conn, messages, brand, writer, now=None, notify=None):
    """Store new messages, classify them, raise leads, queue reply drafts. Returns counts by intent."""
    now = now or datetime.now()
    counts = {}
    for m in messages:
        on_item = m.get("on_item")
        if on_item is None:
            r = conn.execute("SELECT id FROM queue_item WHERE platform=? AND status='PUBLISHED' AND kind='post' "
                             "ORDER BY published_ts DESC LIMIT 1", (m["platform"],)).fetchone()
            on_item = r["id"] if r else None
        cur = conn.execute("INSERT OR IGNORE INTO inbound (received_ts, platform, channel, external_id, author, text, on_item) "
                           "VALUES (?,?,?,?,?,?,?)", (m.get("ts") or db.ts(now), m["platform"], m.get("channel", "comment"),
                                                      m["external_id"], m["author"], m["text"], on_item))
        if not cur.rowcount:
            continue                                   # already seen
        inbound_id = cur.lastrowid
        intent, score, reason = writer.classify(m["text"], rule_classify(m["text"], brand))
        conn.execute("UPDATE inbound SET intent=?, score=? WHERE id=?", (intent, score, inbound_id))
        counts[intent] = counts.get(intent, 0) + 1
        if intent == "lead":
            lead_id = conn.execute("INSERT INTO lead (created_ts, inbound_id, platform, author, reason, score, source_item) "
                                   "VALUES (?,?,?,?,?,?,?)", (db.ts(now), inbound_id, m["platform"], m["author"], reason,
                                                              score, on_item)).lastrowid
            src = conn.execute("SELECT hook, tracking_code FROM queue_item WHERE id=?", (on_item,)).fetchone() if on_item else None
            if notify:
                notify(f"🔥 Qualified lead #{lead_id} on {m['platform']} ({m.get('channel', 'comment')})\n"
                       f"{m['author']}: \"{m['text']}\"\nWhy: {reason} (score {score:.2f})\n"
                       + (f"From post: {src['hook']} [{src['tracking_code']}]\n" if src else "")
                       + "Reply personally - leads are never auto-answered.")
            if src:
                conn.execute("INSERT INTO event (ts, kind, tracking_code, lead_id) VALUES (?,?,?,?)",
                             (db.ts(now), "lead", src["tracking_code"], lead_id))
            continue
        if intent == "spam":
            conn.execute("UPDATE inbound SET handled=1 WHERE id=?", (inbound_id,))
            continue
        if intent == "complaint" and notify:
            notify(f"⚠️ Complaint on {m['platform']} from {m['author']}: \"{m['text']}\" - a draft reply is in the queue.")
        reply = draft_reply(intent, m["text"], brand)
        if reply:
            kind = "dm" if intent == "magnet" or m.get("channel") == "dm" else "reply"
            gate.enqueue(conn, kind=kind, platform=m["platform"], body={"text": reply}, in_reply_to=inbound_id,
                         topic=intent, now=now)
    conn.commit()
    return counts


class FileInbox:
    """Reads JSON messages dropped into inbox/*.json (by a Zapier/Make webhook or a platform poller).

    Each file is a list of {platform, channel, external_id, author, text}. Processed files are renamed *.done.
    """

    def __init__(self, path):
        self.path = path

    def fetch(self):
        msgs = []
        if not os.path.isdir(self.path):
            return msgs
        for name in sorted(os.listdir(self.path)):
            if name.endswith(".json"):
                full = os.path.join(self.path, name)
                with open(full, encoding="utf-8") as f:
                    msgs.extend(json.load(f))
                os.replace(full, full + ".done")
        return msgs
