"""Module 1 - Research & strategy: trend discovery and hook engineering.

Sources use official APIs only (Brave Search, YouTube Data API). Scraping
TikTok/Instagram with a headless browser breaks their terms of service and
gets accounts banned, so it is deliberately not implemented; see
docs/SMT_ANALYSIS.md. `SampleSource` provides synthetic data for the demo.

Hook engineering is: classify the psychological trigger of each top hook,
then write a NEW hook for the brand with the same trigger. A word-3-gram
overlap check rejects adaptations that copy the source.
"""
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime

from . import db
from .config import MAX_HOOK_OVERLAP
from .resilience import PermanentError, RateLimited, TransientError, with_retry

TRIGGERS = {
    "curiosity": [r"\bnobody (tells|talks)\b", r"\bsecret\b", r"\bwhy\b.*\?", r"\bthe real reason\b",
                  r"\bwhat happens\b", r"\bi (tried|tested)\b", r"\bthis one\b", r"\bhidden\b"],
    "pain_point": [r"\bstop\b", r"\bmistake", r"\bwrong\b", r"\blosing\b", r"\bwast", r"\bowe\b",
                   r"\bpenalt", r"\bbroke\b", r"\bstress", r"\bhate\b", r"\bafraid\b"],
    "authority": [r"\bas an? (accountant|cpa|expert|bookkeeper)\b", r"\b\d+ years\b", r"\bi've (done|filed|helped)\b",
                  r"\bcertified\b", r"\bclients?\b"],
    "listicle": [r"^\d+\s", r"\b\d+ (things|ways|mistakes|signs|tips|rules)\b"],
    "contrarian": [r"\bunpopular opinion\b", r"\bdon't\b.*\bsave\b", r"\boverrated\b", r"\bactually\b", r"\bmyth\b"],
    "social_proof": [r"\bmy client\b", r"\bsaved\b.*\$", r"\bmade \$", r"\bfrom \$?\d"],
}

# Brand-voice templates per trigger; {topic} is filled from the trend.
# They make no factual claims (no invented clients, years or numbers): those
# have to come from the owner, during editing.
HOOK_TEMPLATES = {
    "curiosity": ["The {topic} rule your accountant forgot to mention.",
                  "Check your {topic} this week. Most freelancers find the same gap."],
    "pain_point": ["Losing sleep over {topic}? This takes 30 seconds to fix.",
                   "You're overpaying because of one {topic} habit. Here's the fix."],
    "authority": ["Bookkeeper here: the {topic} setup I give every new freelance client.",
                  "What I check first when a freelancer hands me their {topic}."],
    "listicle": ["3 {topic} fixes to make before the quarter ends.",
                 "5 signs your {topic} setup is costing you money."],
    "contrarian": ["Hot take: most advice on {topic} is written for salaried people.",
                   "Stop copying other freelancers on {topic}. Here's why."],
    "social_proof": ["What happens when a freelancer stops winging {topic}.",
                     "The {topic} checklist my clients ask for most."],
}


def classify_trigger(hook):
    text = hook.lower()
    scores = Counter({t: sum(bool(re.search(p, text)) for p in pats) for t, pats in TRIGGERS.items()})
    best, n = scores.most_common(1)[0]
    return best if n else "curiosity"


def _grams(text, n=3):
    w = re.findall(r"[a-z0-9']+", text.lower())
    return {tuple(w[i:i + n]) for i in range(len(w) - n + 1)}


def overlap(adapted, source):
    src = _grams(source)
    return len(src & _grams(adapted)) / len(src) if src else 0.0


def adapt_hook(source_hook, topic, variant=0):
    """Return (new_hook, trigger). Raises ValueError if every template copies the source."""
    trigger = classify_trigger(source_hook)
    templates = HOOK_TEMPLATES[trigger]
    for i in range(len(templates)):
        hook = templates[(variant + i) % len(templates)].format(topic=topic)
        if overlap(hook, source_hook) <= MAX_HOOK_OVERLAP:
            return hook, trigger
    raise ValueError("no adaptation sufficiently different from the source hook")


# ------------------------------------------------------------------ sources
class SampleSource:
    """Synthetic trends, top hooks and audience questions for the demo and tests."""
    name = "sample"

    def __init__(self, path=None):
        path = path or os.path.join(os.path.dirname(os.path.dirname(__file__)), "samples", "research.json")
        with open(path, encoding="utf-8") as f:
            self.data = json.load(f)

    def trends(self, keywords):
        return self.data["trends"]

    def hooks(self, keywords):
        return self.data["hooks"]

    def questions(self, keywords):
        return self.data["questions"]


def _get_json(url, headers=None, timeout=20):
    req = urllib.request.Request(url, headers={"Accept": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 429:
            raise RateLimited(f"{url.split('?')[0]} rate limited", e.headers.get("Retry-After"))
        if e.code >= 500:
            raise TransientError(f"HTTP {e.code}")
        raise PermanentError(f"HTTP {e.code}: {e.read()[:200]!r}")
    except (urllib.error.URLError, TimeoutError) as e:
        raise TransientError(str(e))


class BraveSearchSource:
    """Brave Search API (https://api.search.brave.com) - news and discussions for topic trends."""
    name = "brave"

    def __init__(self, key):
        self.key = key

    def _search(self, q, endpoint="web"):
        url = f"https://api.search.brave.com/res/v1/{endpoint}/search?" + urllib.parse.urlencode({"q": q, "count": 10, "freshness": "pw"})
        return _get_json(url, {"X-Subscription-Token": self.key})

    def trends(self, keywords):
        out = []
        for kw in keywords:
            for r in self._search(kw, "news").get("results", []):
                out.append({"topic": kw, "text": r.get("title", ""), "metric": 1, "url": r.get("url")})
        return out

    def hooks(self, keywords):
        return []

    def questions(self, keywords):
        out = []
        for kw in keywords:
            res = self._search(f"{kw} site:reddit.com")
            for r in res.get("web", {}).get("results", []):
                if "?" in r.get("title", ""):
                    out.append({"topic": kw, "text": r["title"], "metric": 1, "url": r.get("url")})
        return out


class YouTubeShortsSource:
    """YouTube Data API v3 - most-viewed recent Shorts in the niche; titles are a proxy for spoken hooks."""
    name = "youtube"

    def __init__(self, key):
        self.key = key

    def trends(self, keywords):
        return []

    def questions(self, keywords):
        return []

    def hooks(self, keywords):
        out = []
        for kw in keywords:
            q = urllib.parse.urlencode({"part": "snippet", "q": f"{kw} #shorts", "type": "video", "order": "viewCount",
                                        "videoDuration": "short", "maxResults": 10, "key": self.key,
                                        "publishedAfter": "2026-01-01T00:00:00Z"})
            items = _get_json(f"https://www.googleapis.com/youtube/v3/search?{q}").get("items", [])
            ids = ",".join(i["id"]["videoId"] for i in items if "videoId" in i.get("id", {}))
            if not ids:
                continue
            stats = _get_json("https://www.googleapis.com/youtube/v3/videos?" +
                              urllib.parse.urlencode({"part": "statistics,snippet", "id": ids, "key": self.key}))
            for v in stats.get("items", []):
                out.append({"source": f"youtube:{v['snippet'].get('channelTitle', '')}", "text": v["snippet"]["title"],
                            "views": int(v["statistics"].get("viewCount", 0)), "url": f"https://youtu.be/{v['id']}"})
        return out


def sources_from_env():
    srcs = []
    if os.environ.get("BRAVE_API_KEY"):
        srcs.append(BraveSearchSource(os.environ["BRAVE_API_KEY"]))
    if os.environ.get("YOUTUBE_API_KEY"):
        srcs.append(YouTubeShortsSource(os.environ["YOUTUBE_API_KEY"]))
    return srcs or [SampleSource()]


def run(conn, brand, sources, now=None, notify=None, sleep=None):
    """Daily research: store trends, questions and classified hooks. Returns a summary dict."""
    now = now or datetime.now()
    day = now.date().isoformat()
    kws = brand["niche_keywords"]
    counts = Counter()
    kw = {"conn": conn, "notify": notify}
    if sleep:
        kw["sleep"] = sleep
    for s in sources:
        for kind, fetch in (("trend", s.trends), ("question", s.questions)):
            try:
                rows = with_retry(lambda: fetch(kws), where=f"research.{s.name}.{kind}", **kw)
            except (TransientError, PermanentError):
                continue
            for r in rows:
                conn.execute("INSERT INTO trend (day, source, kind, topic, text, metric, url) VALUES (?,?,?,?,?,?,?)",
                             (day, s.name, kind, r["topic"], r["text"], r.get("metric", 1), r.get("url")))
                counts[kind] += 1
        try:
            hooks = with_retry(lambda: s.hooks(kws), where=f"research.{s.name}.hooks", **kw)
        except (TransientError, PermanentError):
            hooks = []
        for h in hooks:
            conn.execute("INSERT INTO hook (day, source, original, trigger, views, url) VALUES (?,?,?,?,?,?)",
                         (day, h["source"], h["text"], classify_trigger(h["text"]), h.get("views", 0), h.get("url")))
            counts["hook"] += 1
    conn.commit()
    db.audit(conn, "agent", "research", json.dumps(counts), now)
    return dict(counts)


def top_topics(conn, day, n=5):
    rows = conn.execute("SELECT topic, SUM(metric) m, COUNT(*) c FROM trend WHERE day=? GROUP BY topic ORDER BY m DESC, c DESC",
                        (day,)).fetchall()
    return [r["topic"] for r in rows[:n]]


def top_hooks(conn, day, n=10):
    return [dict(r) for r in conn.execute("SELECT * FROM hook WHERE day=? ORDER BY views DESC LIMIT ?", (day, n))]
