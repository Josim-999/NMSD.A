"""Module 2 - Content generation: a week of scripts, carousels and captions.

Every piece is written into the approval queue as PENDING via `gate.enqueue`.
This module has no access to any publisher.

Each item gets a tracking code and a UTM-tagged link so clicks, sign-ups and
sales can be traced back to the exact hook and topic (see analytics.py).
"""
import json
import re
from datetime import datetime, timedelta
from urllib.parse import urlencode

from . import db, gate, research
from .config import PLATFORMS, POSTS_PER_WEEK

# Talking points per topic. SYNTHETIC general guidance for the demo brand -
# a real brand replaces these with its own expertise (or lets the writer draft them).
TOPIC_POINTS = {
    "quarterly taxes": ["Move a fixed % of every payment into a separate tax account the day it lands",
                        "Put the four estimated-payment dates in your calendar now, with a 1-week reminder",
                        "Pay from the tax account only - never from your spending account",
                        "Re-check the % each quarter; income swings change the number"],
    "late invoices": ["Put payment terms and a late-fee clause in the contract, not just the invoice",
                      "Send invoices the day work is delivered, not at month end",
                      "Automate a polite reminder at day 3 and day 10 overdue",
                      "Offer card payment - the fee is cheaper than 60 days of waiting"],
    "business expenses": ["Keep one card for business only - it is your audit trail",
                          "Mixed-use items: deduct the business share and write down how you worked it out",
                          "Snap receipts the same day; shoebox receipts are lost receipts",
                          "Review your categories monthly so year-end takes an hour, not a week"],
    "freelance pricing": ["Work out your floor rate: target income + costs + tax, divided by billable days",
                          "Billable days are fewer than you think - plan for 60-70% of working days",
                          "Quote projects from your day rate, then add a buffer for revisions",
                          "Raise prices for new clients first; existing clients get 60 days' notice"],
    "receipts": ["Photograph receipts at the till - it takes 5 seconds",
                 "Name files date_vendor_amount so search does the work",
                 "Forward e-receipts to one inbox folder",
                 "Do a 10-minute receipt sweep every Friday"],
}
GENERIC_POINTS = ["Write it down once, in one place", "Set a 15-minute weekly money check-in",
                  "Separate business and personal money", "Ask for help before tax season, not during it"]

HASHTAGS = {
    "base": ["#freelancer", "#bookkeeping", "#freelancetips", "#selfemployed", "#smallbusiness",
             "#taxtips", "#moneytips", "#freelancelife"],
    "topic": {"quarterly taxes": ["#quarterlytaxes", "#taxseason"], "late invoices": ["#invoicing", "#getpaid"],
              "business expenses": ["#taxdeductions", "#expenses"], "freelance pricing": ["#pricing", "#freelancerates"],
              "receipts": ["#receipts", "#organized"]},
    "count": {"tiktok": 4, "instagram": 8, "youtube": 3, "linkedin": 3, "x": 1},
}
CAPTION_LIMIT = {"x": 280, "tiktok": 2200, "instagram": 2200, "youtube": 100, "linkedin": 3000}


def voice_issues(text, brand):
    low = text.lower()
    issues = [f"banned phrase '{w}'" for w in brand["voice"]["avoid"] if re.search(rf"\b{re.escape(w)}\b", low)]
    if re.search(r"\b(guaranteed|100% legal|never pay tax)\b", low):
        issues.append("unsafe claim")
    return issues


def week_slots(start, n=POSTS_PER_WEEK):
    """Pick n (datetime, platform) peak slots over the 7 days from `start`, spread across platforms and days."""
    cands = []
    for d in range(7):
        day = (start + timedelta(days=d)).date()
        for p, cfg in PLATFORMS.items():
            t = cfg["peak"].get(day.weekday())
            if t:
                hh, mm = map(int, t.split(":"))
                when = datetime(day.year, day.month, day.day, hh, mm)
                if when > start:
                    cands.append((when, p))
    chosen, per_platform, per_day = [], {p: 0 for p in PLATFORMS}, {}
    while len(chosen) < n and cands:
        cands.sort(key=lambda c: (per_platform[c[1]], per_day.get(c[0].date(), 0), c[0]))
        when, p = cands.pop(0)
        chosen.append((when, p))
        per_platform[p] += 1
        per_day[when.date()] = per_day.get(when.date(), 0) + 1
    return sorted(chosen)


def tracking_link(brand, platform, code):
    q = urlencode({"utm_source": platform, "utm_medium": "social", "utm_campaign": "organic", "utm_content": code})
    return f"{brand['landing_url']}?{q}"


def draft(fmt, platform, hook, topic, brand, link):
    points = TOPIC_POINTS.get(topic, GENERIC_POINTS)
    cta = brand["cta"]
    if fmt == "video_script":
        return {
            "hook_0_3s": hook,
            "on_screen_text": hook.split(".")[0][:60],
            "body": [f"Beat {i + 1}: {p}." for i, p in enumerate(points[:3])],
            "pattern_interrupt": "Cut to the screen recording / whiteboard at beat 2 to reset attention.",
            "cta": cta + ".",
            "length_s": 35,
            "shot_notes": "Face to camera, first frame already mid-sentence, captions burned in.",
        }
    if fmt == "carousel":
        slides = [hook] + [p + "." for p in points] + [f"Save this for tax season. {cta}."]
        return {"slides": slides}
    text = f"{hook}\n\n" + "\n".join(f"- {p}" for p in points[:3]) + f"\n\n{cta}: {link}"
    if platform == "x":
        text = f"{hook} {points[0]}. {link}"
    return {"text": text}


def caption_for(fmt, platform, hook, topic, brand, link):
    if platform == "x":
        return None                              # the post text is the caption
    base = f"{hook} {brand['cta']}."
    if platform in ("instagram", "tiktok"):
        base += " Link in bio."
    elif platform == "linkedin":
        base += f" {link}"
    return base[:CAPTION_LIMIT[platform]]


def hashtags_for(platform, topic):
    tags = HASHTAGS["topic"].get(topic, []) + HASHTAGS["base"]
    tags = tags[:HASHTAGS["count"][platform]]
    return tags + (["#shorts"] if platform == "youtube" else [])


def generate_week(conn, brand, writer, start=None, n=POSTS_PER_WEEK):
    """Create n PENDING posts for the coming week from today's research. Returns their ids."""
    start = start or datetime.now()
    day = start.date().isoformat()
    topics = research.top_topics(conn, day) or [p for p in TOPIC_POINTS][:3]
    hooks = research.top_hooks(conn, day) or [{"id": None, "original": "Nobody tells you this", "source": "none"}]
    ids, fmt_turn = [], {p: 0 for p in PLATFORMS}
    for i, (when, platform) in enumerate(week_slots(start, n)):
        topic = topics[i % len(topics)]
        for k in range(len(hooks)):                  # skip any source hook we can't adapt without copying
            src = hooks[(i + k) % len(hooks)]
            try:
                hook, trigger = research.adapt_hook(src["original"], topic, variant=i // len(hooks))
                break
            except ValueError:
                continue
        else:
            continue
        formats = PLATFORMS[platform]["formats"]
        fmt = formats[fmt_turn[platform] % len(formats)]
        fmt_turn[platform] += 1
        next_id = conn.execute("SELECT COALESCE(MAX(id), 0) + 1 FROM queue_item").fetchone()[0]
        code = f"{platform[:2].upper()}{when:%m%d}-{next_id}"
        link = tracking_link(brand, platform, code)
        body = draft(fmt, platform, hook, topic, brand, link)
        caption = caption_for(fmt, platform, hook, topic, brand, link)
        brief = {"platform": platform, "format": fmt, "topic": topic, "trigger": trigger,
                 "source_hook_for_inspiration_only": src["original"], "cta": brand["cta"]}
        polished = writer.polish(brief, {"body": body, "caption": caption})
        if not voice_issues(json.dumps(polished, ensure_ascii=False), brand):
            body, caption = polished["body"], polished["caption"]
        if platform == "x" and len(body["text"]) > CAPTION_LIMIT["x"]:
            body["text"] = body["text"][:CAPTION_LIMIT["x"] - 1] + "…"
        ids.append(gate.enqueue(conn, kind="post", platform=platform, fmt=fmt, body=body, caption=caption,
                                hashtags=hashtags_for(platform, topic), pillar=_pillar(topic, brand), topic=topic,
                                hook=hook, hook_trigger=trigger, source_hook_id=src.get("id"), tracking_code=code,
                                link=link, scheduled_for=db.ts(when), now=start))
    db.audit(conn, "agent", "generated_week", f"{len(ids)} posts queued as PENDING", start)
    return ids


def _pillar(topic, brand):
    if "pric" in topic or "invoice" in topic:
        return "pricing & cash flow"
    if "tax" in topic:
        return "tax mistakes"
    return "bookkeeping habits"
