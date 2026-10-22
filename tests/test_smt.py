import json
import os
import sys
import tempfile
import types
import unittest
from datetime import datetime, timedelta

os.environ["SMT_OWNER_CHAT_ID"] = "424242"
OWNER = "424242"

from smt import analytics, config, content, db, gate, publishers, research, sim, triage  # noqa: E402
from smt.llm import ClaudeWriter, OfflineWriter, mask  # noqa: E402
from smt.resilience import PermanentError, RateLimited, TransientError, with_retry  # noqa: E402
from smt.scheduler import Agent  # noqa: E402
from smt.telegram import Bot, ConsoleNotifier  # noqa: E402

NOW = config.DEMO_NOW
BRAND = config.BRAND


class Recorder:
    name = "recorder"

    def __init__(self, fail=None):
        self.sent, self.fail = [], list(fail or [])

    def publish(self, p):
        if self.fail:
            raise self.fail.pop(0)
        self.sent.append(p)
        return f"ext-{p['queue_id']}"


def fresh(week=True):
    conn = db.create(":memory:")
    research.run(conn, BRAND, [research.SampleSource()], NOW)
    ids = content.generate_week(conn, BRAND, OfflineWriter(), NOW) if week else []
    return conn, ids


def pubs(rec):
    return {p: rec for p in config.PLATFORMS}


def no_sleep(_):
    pass


def first_text_post(conn, ids):
    return next(i for i in ids if conn.execute("SELECT format FROM queue_item WHERE id=?", (i,)).fetchone()[0] != "video_script")


class TestApprovalGate(unittest.TestCase):
    def setUp(self):
        self.conn, self.ids = fresh()
        self.rec = Recorder()
        self.later = NOW + timedelta(days=8)

    def test_everything_enters_pending_and_nothing_publishes(self):
        self.assertEqual({r["status"] for r in self.conn.execute("SELECT status FROM queue_item")}, {"PENDING"})
        self.assertEqual(publishers.dispatch_due(self.conn, pubs(self.rec), self.later), [])
        self.assertEqual(self.rec.sent, [])

    def test_only_owner_on_trusted_channel_can_approve(self):
        with self.assertRaises(gate.GateError):
            gate.approve(self.conn, self.ids[0], "someone-else", "telegram")
        with self.assertRaises(gate.GateError):
            gate.approve(self.conn, self.ids[0], OWNER, "llm")
        gate.approve(self.conn, self.ids[0], OWNER, "telegram")
        with self.assertRaises(gate.GateError):              # not pending any more
            gate.approve(self.conn, self.ids[0], OWNER, "telegram")

    def test_approved_text_post_publishes_once(self):
        i = first_text_post(self.conn, self.ids)
        gate.approve(self.conn, i, OWNER, "cli")
        out = publishers.dispatch_due(self.conn, pubs(self.rec), self.later)
        self.assertEqual([o[0] for o in out], [i])
        self.assertEqual(publishers.dispatch_due(self.conn, pubs(self.rec), self.later), [])
        self.assertEqual(len(self.rec.sent), 1)

    def test_not_published_before_its_slot(self):
        i = first_text_post(self.conn, self.ids)
        gate.approve(self.conn, i, OWNER, "cli")
        slot = db.parse(self.conn.execute("SELECT scheduled_for FROM queue_item WHERE id=?", (i,)).fetchone()[0])
        self.assertEqual(publishers.dispatch_due(self.conn, pubs(self.rec), slot - timedelta(minutes=1)), [])

    def test_edit_after_approval_requires_new_approval(self):
        i = first_text_post(self.conn, self.ids)
        gate.approve(self.conn, i, OWNER, "cli")
        gate.edit(self.conn, i, "agent", caption="changed by the agent")
        self.assertEqual(self.conn.execute("SELECT status FROM queue_item WHERE id=?", (i,)).fetchone()[0], "PENDING")
        self.assertEqual(publishers.dispatch_due(self.conn, pubs(self.rec), self.later), [])

    def test_tampering_behind_the_gate_is_caught(self):
        i = first_text_post(self.conn, self.ids)
        self.conn.execute("UPDATE queue_item SET status='APPROVED', decided_by=?, decided_via='cli' WHERE id=?", (OWNER, i))
        out = publishers.dispatch_due(self.conn, pubs(self.rec), self.later)
        self.assertIn("sent back to PENDING", out[0][1])
        gate.approve(self.conn, self.ids[1], OWNER, "cli")
        self.conn.execute("UPDATE queue_item SET body='{\"text\": \"swapped\"}' WHERE id=?", (self.ids[1],))
        with self.assertRaises(gate.GateError):
            gate.publishable(self.conn, self.ids[1])
        self.assertEqual(self.rec.sent, [])

    def test_rejected_never_publishes(self):
        gate.reject(self.conn, self.ids[0], OWNER, "cli", "off-brand")
        self.assertEqual(publishers.dispatch_due(self.conn, pubs(self.rec), self.later), [])


class TestTelegramBot(unittest.TestCase):
    def setUp(self):
        self.conn, self.ids = fresh()
        self.notify = ConsoleNotifier(echo=False)
        self.bot = Bot(self.conn, BRAND, self.notify)

    def status(self, i):
        return self.conn.execute("SELECT status FROM queue_item WHERE id=?", (i,)).fetchone()[0]

    def test_stranger_is_ignored(self):
        self.assertIsNone(self.bot.handle({"callback_query": {"id": "x", "from": {"id": 1}, "data": f"a:{self.ids[0]}"}}))
        self.assertEqual(self.status(self.ids[0]), "PENDING")
        self.assertEqual(self.notify.sent, [])

    def test_owner_buttons_and_edit(self):
        self.bot.handle({"callback_query": {"id": "x", "from": {"id": int(OWNER)}, "data": f"a:{self.ids[0]}"}})
        self.assertEqual(self.status(self.ids[0]), "APPROVED")
        self.bot.handle({"message": {"chat": {"id": int(OWNER)}, "text": f"/edit {self.ids[0]} caption Mine now"}})
        self.assertEqual(self.status(self.ids[0]), "PENDING")
        self.bot.handle({"callback_query": {"id": "x", "from": {"id": int(OWNER)}, "data": f"r:{self.ids[1]}"}})
        self.assertEqual(self.status(self.ids[1]), "REJECTED")

    def test_approve_replies_does_not_touch_posts(self):
        triage.ingest(self.conn, [{"platform": "x", "external_id": "1", "author": "@a", "text": "Love this, thanks!"}],
                      BRAND, OfflineWriter(), NOW)
        self.bot.handle({"message": {"chat": {"id": int(OWNER)}, "text": "/approve_replies"}})
        statuses = dict(self.conn.execute("SELECT kind, status FROM queue_item WHERE kind='reply'").fetchall())
        self.assertEqual(statuses, {"reply": "APPROVED"})
        self.assertEqual(len(gate.pending(self.conn, "post")), len(self.ids))


class TestResearch(unittest.TestCase):
    def test_trigger_classification(self):
        self.assertEqual(research.classify_trigger("Nobody tells freelancers this"), "curiosity")
        self.assertEqual(research.classify_trigger("Stop making this invoice mistake"), "pain_point")
        self.assertEqual(research.classify_trigger("5 things I wish I knew"), "listicle")
        self.assertEqual(research.classify_trigger("As an accountant, I would never"), "authority")
        self.assertEqual(research.classify_trigger("Unpopular opinion: you don't need an LLC"), "contrarian")

    def test_adaptations_do_not_copy(self):
        for h in research.SampleSource().hooks([]):
            adapted, _ = research.adapt_hook(h["text"], "late invoices")
            self.assertLessEqual(research.overlap(adapted, h["text"]), config.MAX_HOOK_OVERLAP)

    def test_copy_guard_rejects_verbatim(self):
        source = research.HOOK_TEMPLATES["curiosity"][0].format(topic="late invoices")
        hook, _ = research.adapt_hook(source, "late invoices")     # skips the template that matches the source
        self.assertNotEqual(hook, source)
        saved = research.HOOK_TEMPLATES["curiosity"]
        research.HOOK_TEMPLATES["curiosity"] = saved[:1]
        try:
            with self.assertRaises(ValueError):
                research.adapt_hook(source, "late invoices")
        finally:
            research.HOOK_TEMPLATES["curiosity"] = saved

    def test_failing_source_is_retried_then_skipped(self):
        class Flaky(research.SampleSource):
            name = "flaky"

            def trends(self, kws):
                raise TransientError("timeout")
        conn = db.create(":memory:")
        notes = []
        counts = research.run(conn, BRAND, [Flaky()], NOW, notify=notes.append, sleep=no_sleep)
        self.assertNotIn("trend", counts)
        self.assertEqual(counts["hook"], 6)
        self.assertEqual(len(notes), 1)                 # one escalation, not one per attempt


class TestContent(unittest.TestCase):
    def setUp(self):
        self.conn, self.ids = fresh()
        self.rows = [dict(r) for r in self.conn.execute("SELECT * FROM queue_item")]

    def test_week_shape(self):
        self.assertEqual(len(self.ids), config.POSTS_PER_WEEK)
        self.assertEqual(len({r["tracking_code"] for r in self.rows}), len(self.rows))
        self.assertEqual({r["platform"] for r in self.rows}, set(config.PLATFORMS))
        for r in self.rows:
            self.assertIn(r["format"], config.PLATFORMS[r["platform"]]["formats"])
            self.assertIn(f"utm_content={r['tracking_code']}", r["link"])
            day, hhmm = r["scheduled_for"].split("T")
            self.assertEqual(config.PLATFORMS[r["platform"]]["peak"][datetime.fromisoformat(day).weekday()], hhmm)

    def test_brand_voice_and_limits(self):
        for r in self.rows:
            text = r["body"] + (r["caption"] or "")
            self.assertEqual(content.voice_issues(text, BRAND), [], r["id"])
            if r["platform"] == "x":
                self.assertLessEqual(len(json.loads(r["body"])["text"]), 280)

    def test_video_scripts_have_structure(self):
        for r in self.rows:
            if r["format"] == "video_script":
                b = json.loads(r["body"])
                self.assertTrue(b["hook_0_3s"] and b["body"] and b["cta"])


class TestTriage(unittest.TestCase):
    def classify(self, text):
        return triage.rule_classify(text, BRAND)[0]

    def test_intents(self):
        self.assertEqual(self.classify("How much do you charge?"), "lead")
        self.assertEqual(self.classify("Are you taking new clients? Interested in a call."), "lead")
        self.assertEqual(self.classify("what are your packages and pricing?"), "lead")
        self.assertEqual(self.classify("Can I deduct my laptop?"), "question")
        self.assertEqual(self.classify("BOOKS"), "magnet")
        self.assertEqual(self.classify("Check my profile for crypto promo"), "spam")
        self.assertEqual(self.classify("This is misleading"), "complaint")
        self.assertEqual(self.classify("so helpful, thanks"), "praise")

    def test_ingest_alerts_leads_and_queues_replies(self):
        conn, _ = fresh(week=False)
        notes = ConsoleNotifier(echo=False)
        with open(os.path.join(config.ROOT, "samples", "inbound.json"), encoding="utf-8") as f:
            msgs = json.load(f)
        counts = triage.ingest(conn, msgs, BRAND, OfflineWriter(), NOW, notes)
        self.assertEqual(counts["lead"], 3)
        self.assertEqual(sum("Qualified lead" in t for t, _ in notes.sent), 3)
        self.assertEqual(conn.execute("SELECT COUNT(*) FROM lead").fetchone()[0], 3)
        replies = gate.pending(conn)
        self.assertEqual(len(replies), 4)                 # magnet, praise, question, complaint; none for leads/spam
        self.assertTrue(all(r["status"] == "PENDING" for r in replies))
        self.assertEqual(triage.ingest(conn, msgs, BRAND, OfflineWriter(), NOW), {})   # duplicates ignored


class TestResilience(unittest.TestCase):
    def test_retry_then_success_does_not_notify(self):
        calls, notes, sleeps = [], [], []

        def fn():
            calls.append(1)
            if len(calls) < 3:
                raise RateLimited("429", retry_after=5)
            return "ok"
        self.assertEqual(with_retry(fn, where="t", notify=notes.append, sleep=sleeps.append, jitter=False), "ok")
        self.assertEqual(notes, [])
        self.assertEqual(sleeps, [5.0, 5.0])              # retry-after wins over the 2s/4s backoff

    def test_exponential_backoff_and_escalation(self):
        notes, sleeps = [], []
        with self.assertRaises(TransientError):
            with_retry(lambda: (_ for _ in ()).throw(TransientError("down")), where="t", notify=notes.append,
                       sleep=sleeps.append, attempts=5, jitter=False)
        self.assertEqual(sleeps, [2.0, 4.0, 8.0, 16.0])
        self.assertEqual(len(notes), 1)

    def test_permanent_error_is_not_retried(self):
        calls, notes = [], []

        def fn():
            calls.append(1)
            raise PermanentError("401")
        with self.assertRaises(PermanentError):
            with_retry(fn, where="t", notify=notes.append, sleep=no_sleep)
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(notes), 1)


class TestDispatch(unittest.TestCase):
    def setUp(self):
        self.conn, self.ids = fresh()
        self.later = NOW + timedelta(days=8)

    def test_video_without_media_waits_for_owner(self):
        vid = next(i for i in self.ids if self.conn.execute("SELECT format FROM queue_item WHERE id=?", (i,)).fetchone()[0] == "video_script")
        gate.approve(self.conn, vid, OWNER, "cli")
        notes = ConsoleNotifier(echo=False)
        rec = Recorder()
        publishers.dispatch_due(self.conn, pubs(rec), self.later, notes)
        self.assertEqual(self.conn.execute("SELECT status FROM queue_item WHERE id=?", (vid,)).fetchone()[0], "AWAITING_MEDIA")
        self.assertIn("Filming brief", notes.sent[0][0])
        with tempfile.NamedTemporaryFile(suffix=".mp4") as f:
            gate.edit(self.conn, vid, OWNER, media_path=f.name)
            self.assertEqual(publishers.dispatch_due(self.conn, pubs(rec), self.later), [])   # needs re-approval
            gate.approve(self.conn, vid, OWNER, "cli")
            publishers.dispatch_due(self.conn, pubs(rec), self.later)
        self.assertEqual(rec.sent[0]["media_path"], f.name)

    def test_platform_failure_marks_failed_and_escalates(self):
        i = first_text_post(self.conn, self.ids)
        gate.approve(self.conn, i, OWNER, "cli")
        notes = []
        rec = Recorder(fail=[PermanentError("401 bad token")])
        out = publishers.dispatch_due(self.conn, pubs(rec), self.later, notes.append)
        self.assertIn("FAILED", out[0][1])
        self.assertEqual(len(notes), 1)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM error_log WHERE needs_human=1").fetchone()[0], 1)

    def test_transient_failure_recovers_silently(self):
        i = first_text_post(self.conn, self.ids)
        gate.approve(self.conn, i, OWNER, "cli")
        notes = []
        rec = Recorder(fail=[TransientError("timeout")])
        out = publishers.dispatch_due(self.conn, pubs(rec), self.later, notes.append, sleep=no_sleep)
        self.assertIn("published", out[0][1])
        self.assertEqual(notes, [])

    def test_late_approval_moves_to_next_peak(self):
        i = self.ids[0]
        late = NOW + timedelta(days=2)
        gate.approve(self.conn, i, OWNER, "cli", now=late)
        publishers.reschedule_missed(self.conn, late)
        row = self.conn.execute("SELECT platform, scheduled_for FROM queue_item WHERE id=?", (i,)).fetchone()
        when = db.parse(row["scheduled_for"])
        self.assertGreater(when, late)
        self.assertEqual(config.PLATFORMS[row["platform"]]["peak"][when.weekday()], f"{when:%H:%M}")

    def test_dry_run_writes_outbox(self):
        with tempfile.TemporaryDirectory() as d:
            i = first_text_post(self.conn, self.ids)
            gate.approve(self.conn, i, OWNER, "cli")
            dry = publishers.DryRunPublisher(d)
            publishers.dispatch_due(self.conn, pubs(dry), self.later)
            files = os.listdir(d)
            self.assertEqual(len(files), 1)
            with open(os.path.join(d, files[0]), encoding="utf-8") as f:
                self.assertEqual(json.load(f)["queue_id"], i)


class TestAttribution(unittest.TestCase):
    def test_revenue_beats_vanity(self):
        conn = db.create(":memory:")
        sim.seed_history(conn, NOW - timedelta(days=24))
        rows = analytics.attribution(conn, "hook_trigger")
        by_views = max(rows, key=lambda r: r["views"])["k"]
        by_revenue = rows[0]["k"]
        self.assertNotEqual(by_views, by_revenue)
        self.assertIn(by_revenue, {"pain_point", "authority"})

    def test_won_lead_is_attributed_to_its_post(self):
        conn, ids = fresh()
        i = first_text_post(conn, ids)
        gate.approve(conn, i, OWNER, "cli")
        publishers.dispatch_due(conn, pubs(Recorder()), NOW + timedelta(days=8))
        platform, code = conn.execute("SELECT platform, tracking_code FROM queue_item WHERE id=?", (i,)).fetchone()
        triage.ingest(conn, [{"platform": platform, "external_id": "z", "author": "@b", "text": "How much is it?"}],
                      BRAND, OfflineWriter(), NOW + timedelta(days=8))
        lead = conn.execute("SELECT id, source_item FROM lead").fetchone()
        self.assertEqual(lead["source_item"], i)
        analytics.close_lead(conn, lead["id"], 1500)
        post = [r for r in analytics.attribution(conn, "post") if r["k"].startswith(f"{i} ")][0]
        self.assertEqual((post["leads"], post["sales"], post["revenue"]), (1, 1, 1500))
        self.assertIn(code, conn.execute("SELECT GROUP_CONCAT(tracking_code) FROM event WHERE kind='sale'").fetchone()[0])

    def test_briefing_contents(self):
        conn, ids = fresh()
        triage.ingest(conn, [{"platform": "x", "external_id": "q", "author": "@c", "text": "Interested! Pricing?"}],
                      BRAND, OfflineWriter(), NOW)
        text = analytics.morning_briefing(conn, BRAND, NOW + timedelta(hours=1))
        self.assertIn(f"WAITING FOR YOU ({len(ids)} posts", text)
        self.assertIn("QUALIFIED LEADS (1 open)", text)


class TestScheduler(unittest.TestCase):
    def test_daily_routine_runs_once(self):
        conn = db.create(":memory:")
        notes = ConsoleNotifier(echo=False)
        agent = Agent(conn, BRAND, OfflineWriter(), [research.SampleSource()], pubs(Recorder()),
                      triage.FileInbox("/nonexistent"), notes, sleep=no_sleep)
        early = agent.tick(NOW - timedelta(hours=1))
        self.assertFalse(any("research" in d for d in early))
        done = agent.tick(NOW)                            # Monday 06:00
        self.assertTrue(any(d.startswith("research") for d in done))
        self.assertIn(f"generated {config.POSTS_PER_WEEK} posts", done)
        self.assertEqual(agent.tick(NOW + timedelta(minutes=5)), [])
        self.assertIn("briefing sent", agent.tick(NOW + timedelta(hours=1)))
        self.assertNotIn("briefing sent", agent.tick(NOW + timedelta(hours=2)))
        self.assertTrue(all(r["status"] == "PENDING" for r in conn.execute("SELECT status FROM queue_item")))


class FakeAnthropic(types.ModuleType):
    class RateLimitError(Exception):
        pass

    class APIStatusError(Exception):
        status_code = 500

    class APIConnectionError(Exception):
        pass


def fake_client(text, stop="end_turn"):
    block = types.SimpleNamespace(type="text", text=text)
    resp = types.SimpleNamespace(stop_reason=stop, content=[block])
    calls = []

    def create(**kw):
        calls.append(kw)
        return resp
    return types.SimpleNamespace(beta=types.SimpleNamespace(messages=types.SimpleNamespace(create=create))), calls


class TestClaudeWriter(unittest.TestCase):
    def setUp(self):
        sys.modules["anthropic"] = FakeAnthropic("anthropic")

    def tearDown(self):
        sys.modules.pop("anthropic", None)

    def test_classify_uses_model_and_masks_personal_data(self):
        client, calls = fake_client('{"intent": "lead", "score": 0.9, "reason": "asks for a quote"}')
        w = ClaudeWriter(BRAND, client)
        self.assertEqual(w.classify("@maya here, email maya@x.com, quote?", None), ("lead", 0.9, "asks for a quote"))
        sent = calls[0]["messages"][0]["content"]
        self.assertNotIn("maya@x.com", sent)
        self.assertNotIn("@maya", sent)
        self.assertEqual(calls[0]["model"], "claude-opus-5-5")

    def test_refusal_falls_back(self):
        client, _ = fake_client("", stop="refusal")
        w = ClaudeWriter(BRAND, client)
        self.assertEqual(w.classify("hi", ("other", 0, "x")), ("other", 0, "x"))
        draft = {"body": {"text": "a"}, "caption": "b"}
        self.assertEqual(w.polish({}, draft), draft)

    def test_polish_must_keep_shape(self):
        client, _ = fake_client('{"body": {"text": "new"}}')     # caption key missing
        draft = {"body": {"text": "a"}, "caption": "b"}
        self.assertEqual(ClaudeWriter(BRAND, client).polish({}, draft), draft)

    def test_mask(self):
        self.assertEqual(mask("call +1 555 123 4567 or a@b.co, @sam"), "call [PHONE] or [EMAIL], @[USER]")


if __name__ == "__main__":
    unittest.main()
