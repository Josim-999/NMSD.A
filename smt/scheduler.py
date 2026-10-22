"""The agent loop: one `tick(now)` runs whatever is due.

    06:00  research (daily); on Mondays, or when fewer than 3 posts are lined up, generate a week
    07:00  morning briefing to the owner
    every 10 min  inbox triage
    every tick    reschedule approved-but-missed slots, dispatch approved items that are due

Run it under PM2 or systemd (see deploy/). The Telegram bot runs as a second
process (`python -m smt bot`) so a slow platform API never blocks approvals.
"""
import time
from datetime import datetime, timedelta

from . import analytics, content, db, publishers, research, triage
from .config import BRIEFING_AT, INBOX_POLL_MIN, RESEARCH_AT


def _at(now, hhmm):
    hh, mm = map(int, hhmm.split(":"))
    return now.replace(hour=hh, minute=mm, second=0, microsecond=0)


class Agent:
    def __init__(self, conn, brand, writer, sources, pubs, inbox, notify, sleep=time.sleep):
        self.conn, self.brand, self.writer = conn, brand, writer
        self.sources, self.pubs, self.inbox, self.notify, self.sleep = sources, pubs, inbox, notify, sleep

    def _once(self, key, now):
        day = now.date().isoformat()
        if db.get_kv(self.conn, key) == day:
            return False
        db.set_kv(self.conn, key, day)
        return True

    def tick(self, now=None):
        now = now or datetime.now()
        done = []
        if now >= _at(now, RESEARCH_AT) and self._once("last_research", now):
            done.append(f"research {research.run(self.conn, self.brand, self.sources, now, self.notify, self.sleep)}")
            lined_up = self.conn.execute("SELECT COUNT(*) FROM queue_item WHERE kind='post' AND status IN "
                                         "('PENDING','APPROVED','AWAITING_MEDIA') AND scheduled_for > ?",
                                         (db.ts(now),)).fetchone()[0]
            if now.weekday() == 0 or lined_up < 3:
                ids = content.generate_week(self.conn, self.brand, self.writer, now)
                done.append(f"generated {len(ids)} posts")
                self.notify(f"📝 {len(ids)} new posts for the week are waiting for approval. Send /queue to review.")
        if now >= _at(now, BRIEFING_AT) and self._once("last_briefing", now):
            self.notify(analytics.morning_briefing(self.conn, self.brand, now))
            done.append("briefing sent")
        last = db.parse(db.get_kv(self.conn, "last_inbox"))
        if last is None or now - last >= timedelta(minutes=INBOX_POLL_MIN):
            db.set_kv(self.conn, "last_inbox", db.ts(now))
            msgs = self.inbox.fetch()
            if msgs:
                done.append(f"inbox {triage.ingest(self.conn, msgs, self.brand, self.writer, now, self.notify)}")
        publishers.reschedule_missed(self.conn, now)
        sent = publishers.dispatch_due(self.conn, self.pubs, now, self.notify, self.sleep)
        if sent:
            done.append(f"dispatch {sent}")
        return done

    def run_forever(self, every_sec=60):
        while True:
            try:
                for line in self.tick():
                    print(f"{datetime.now():%Y-%m-%d %H:%M} {line}", flush=True)
            except Exception as e:                      # keep the loop alive; tell the owner once per new error
                db.audit(self.conn, "agent", "tick_error", repr(e))
                if db.get_kv(self.conn, "last_tick_error") != repr(e):
                    db.set_kv(self.conn, "last_tick_error", repr(e))
                    self.notify(f"⚠️ Agent loop error: {e!r}. It keeps running; check the logs.")
            self.sleep(every_sec)
