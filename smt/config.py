"""Configuration: brand profile, platforms, schedules and thresholds.

Secrets never live here. They are read from the environment (or a `.env` file,
see `deploy/.env.example`) by `load_env()`. The brand below is SYNTHETIC; edit
`BRAND` or point SMT_BRAND_FILE at a JSON file with the same keys.
"""
import json
import os
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The demo is frozen at a fixed "now" so every run is reproducible.
# `python -m smt run` uses the real clock instead.
DEMO_NOW = datetime(2026, 10, 19, 6, 0)          # a Monday morning

BRAND = {
    "name": "Ledger Lane",
    "owner": "Sam",
    "offer": "bookkeeping and tax-ready books for freelancers",
    "audience": "freelance designers, developers and consultants",
    "niche_keywords": ["freelancer taxes", "freelance bookkeeping", "self employed invoices",
                       "quarterly tax", "freelance pricing"],
    "competitors": ["@taxtok_tina", "@numbersnerd", "@freelancefinance"],
    "voice": {
        "tone": "direct, warm, a little cheeky; talks like a friend who happens to be an accountant",
        "do": ["short sentences", "concrete numbers", "one idea per post", "say 'you' not 'one'"],
        "avoid": ["unlock", "game-changer", "in today's fast-paced world", "dive in",
                  "leverage", "elevate", "synergy", "revolutionize"],
    },
    "cta": "Comment BOOKS and I'll send you my free freelancer tax checklist",
    "landing_url": "https://example.com/checklist",
    "content_pillars": ["tax mistakes", "pricing & cash flow", "bookkeeping habits", "client stories"],
}

# Platforms, the formats each takes, and default peak posting slots (local time).
# Replace the slots with your own analytics once you have 4+ weeks of data.
PLATFORMS = {
    "tiktok":    {"formats": ["video_script"], "peak": {0: "19:00", 1: "19:00", 2: "19:00", 3: "19:00", 4: "17:00", 5: "11:00", 6: "11:00"}},
    "instagram": {"formats": ["video_script", "carousel"], "peak": {0: "18:00", 1: "18:00", 2: "12:00", 3: "18:00", 4: "12:00", 5: "10:00"}},
    "youtube":   {"formats": ["video_script"], "peak": {0: "17:00", 2: "17:00", 4: "17:00", 5: "10:00"}},
    "linkedin":  {"formats": ["carousel", "text_post"], "peak": {1: "08:30", 2: "08:30", 3: "08:30"}},
    "x":         {"formats": ["text_post"], "peak": {0: "12:00", 1: "12:00", 2: "12:00", 3: "12:00", 4: "12:00"}},
}
VIDEO_PLATFORMS = {"tiktok", "instagram", "youtube"}

# Daily routine (local time)
RESEARCH_AT = "06:00"
BRIEFING_AT = "07:00"
INBOX_POLL_MIN = 10
POSTS_PER_WEEK = 10

# Resilience
RETRY_ATTEMPTS = 5
RETRY_BASE_SEC = 2.0
RETRY_MAX_SEC = 60.0

# Plagiarism guard: max share of a source hook's word 3-grams that an adapted hook may reuse
MAX_HOOK_OVERLAP = 0.35

# Lead scoring: >= LEAD_THRESHOLD is a Qualified Lead
LEAD_THRESHOLD = 0.6


def load_env(path=None):
    """Load KEY=VALUE lines from .env into os.environ (existing variables win)."""
    path = path or os.environ.get("SMT_ENV_FILE", os.path.join(ROOT, ".env"))
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def brand():
    path = os.environ.get("SMT_BRAND_FILE")
    if path:
        with open(path, encoding="utf-8") as f:
            return {**BRAND, **json.load(f)}
    return BRAND
