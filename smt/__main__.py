"""Command line for the example AI social media team.

    python -m smt demo                     scripted walkthrough of all four modules (offline)
    python -m smt init [--history]         fresh database (optionally with 3 weeks of synthetic results)
    python -m smt research                 run today's research
    python -m smt generate                 draft a week of posts into the approval queue
    python -m smt queue                    list pending items
    python -m smt show 12                  full preview of one item
    python -m smt approve 12 | reject 12 [--reason ...]
    python -m smt edit 12 --caption "..."  (any edit returns the item to PENDING)
    python -m smt attach-media 12 clip.mp4
    python -m smt dispatch                 publish approved items that are due
    python -m smt inbox [file.json]        triage comments/DMs (default: inbox/*.json)
    python -m smt leads | won 3 1200       open leads / record a sale
    python -m smt import-events sales.csv  clicks/sign-ups/sales export (ts, kind, utm_content, value)
    python -m smt attribution [--by topic] revenue by hook trigger, topic, platform or post
    python -m smt brief                    print the morning briefing
    python -m smt run                      the 24/7 agent loop (research, briefing, inbox, dispatch)
    python -m smt bot                      the Telegram approval bot (long polling)

The CLI acts as the owner (channel "cli"): it is meant to be run by the owner
on the server, over SSH.
"""
import argparse
import json
import os
import shutil
import sys
from datetime import timedelta

from . import analytics, config, content, db, gate, publishers, research, sim, triage
from .config import DEMO_NOW, ROOT
from .llm import from_env
from .scheduler import Agent
from .telegram import Bot, ConsoleNotifier, TelegramClient, card, notifier_from_env


def banner(t):
    print(f"\n{'=' * 78}\n{t}\n{'=' * 78}")


def demo(path):
    os.environ.setdefault("SMT_OWNER_CHAT_ID", "424242")
    owner = gate.owner_id()
    conn = db.create(path)
    shutil.rmtree(publishers.OUTBOX, ignore_errors=True)
    brand = config.brand()
    writer = from_env(brand)
    notify = ConsoleNotifier()
    now = DEMO_NOW
    print(f"Writer: {writer.name}. Publishing: dry-run to outbox/. Owner chat id: {owner}. Clock frozen at {now}.")

    banner("0. Three weeks of synthetic history (so attribution has something to measure)")
    sim.seed_history(conn, now - timedelta(days=24))
    for r in analytics.attribution(conn, "hook_trigger"):
        print(f"  {r['k']:<13} posts {r['posts']}  views {r['views']:>6,}  clicks {r['clicks']:>4}  "
              f"sales {r['sales']:>2}  revenue ${r['revenue']:>8,.0f}")
    print("  -> curiosity wins on views; check which trigger actually wins on revenue.")

    banner("1. Research: trends, audience questions, competitor hooks (Module 1)")
    print(" ", research.run(conn, brand, [research.SampleSource()], now))
    for h in research.top_hooks(conn, now.date().isoformat(), 6):
        adapted, trig = research.adapt_hook(h["original"], "quarterly taxes")
        print(f"  [{trig:<12}] {h['original']}\n  {'':14} -> {adapted}  (3-gram overlap "
              f"{research.overlap(adapted, h['original']):.0%})")

    banner("2. Generate the week into the approval queue (Module 2)")
    ids = content.generate_week(conn, brand, writer, now)
    for q in gate.pending(conn, "post"):
        print(f"  #{q['id']:<3} {q['scheduled_for']}  {q['platform']:<9} {q['format']:<12} [{q['hook_trigger']}] {q['hook']}")

    banner("3. The gate: nothing is published without the owner")
    print("  dispatch before approval:", publishers.dispatch_due(conn, publishers.publishers_from_env(), now + timedelta(days=7)) or "nothing published")
    bot = Bot(conn, brand, notify)
    print("  a stranger presses Approve in Telegram:", bot.handle({"callback_query": {"id": "1", "from": {"id": 999}, "data": f"a:{ids[0]}"}}) or "ignored")
    try:
        gate.approve(conn, ids[0], "agent", "llm")
    except gate.GateError as e:
        print(f"  the agent tries to approve itself: refused ({e})")
    print("  owner opens the first card:\n")
    notify(card(conn.execute("SELECT * FROM queue_item WHERE id=?", (ids[0],)).fetchone()))
    for i in ids[:7]:
        bot.handle({"callback_query": {"id": "2", "from": {"id": int(owner)}, "data": f"a:{i}"}})
    bot.handle({"message": {"chat": {"id": int(owner)}, "text": f"/edit {ids[1]} caption New caption, written by me."}})
    bot.handle({"message": {"chat": {"id": int(owner)}, "text": f"/reject {ids[7]} off-brand"}})

    banner("4. Dispatch at peak times (Module 3)")
    pubs = publishers.publishers_from_env()
    for day in range(1, 8):
        for i, outcome in publishers.dispatch_due(conn, pubs, now + timedelta(days=day), notify):
            print(f"  day +{day}: #{i} {outcome}")

    banner("5. Inbound triage: comments and DMs")
    with open(os.path.join(ROOT, "samples", "inbound.json"), encoding="utf-8") as f:
        msgs = json.load(f)
    t = now + timedelta(days=2, hours=3)
    print("  intents:", triage.ingest(conn, msgs, brand, writer, t, notify))
    print("  reply drafts in the queue:")
    for q in gate.pending(conn):
        if q["kind"] != "post":
            print(f"    #{q['id']} {q['kind']:<5} {q['platform']:<9} {json.loads(q['body'])['text'][:90]}")
    bot.handle({"message": {"chat": {"id": int(owner)}, "text": "/approve_replies"}})
    for i, outcome in publishers.dispatch_due(conn, pubs, t, notify):
        print(f"  #{i} {outcome}")

    banner("6. A lead becomes a customer -> revenue attributed to the post that started it")
    lead = conn.execute("SELECT id FROM lead ORDER BY score DESC, id LIMIT 1").fetchone()["id"]
    bot.handle({"message": {"chat": {"id": int(owner)}, "text": f"/won {lead} 1800"}})
    for r in analytics.attribution(conn, "hook_trigger")[:3]:
        print(f"  {r['k']:<13} revenue ${r['revenue']:>8,.0f}  per post ${r['revenue_per_post']:>6,.0f}  leads {r['leads']}")

    sim.simulate_engagement(conn, now + timedelta(days=3, hours=1))

    banner("7. Next morning at 07:00 - the briefing the owner receives")
    print(analytics.morning_briefing(conn, brand, now + timedelta(days=3, hours=1)))
    print(f"\nDry-run payloads are in {publishers.OUTBOX}/. Database: {path}")


def main(argv=None):
    config.load_env()
    p = argparse.ArgumentParser(prog="python -m smt", description="AI social media team - example model")
    p.add_argument("--db", default=db.DEFAULT_PATH)
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("demo")
    sub.add_parser("init").add_argument("--history", action="store_true")
    sub.add_parser("research")
    sub.add_parser("generate")
    sub.add_parser("queue")
    sub.add_parser("show").add_argument("id", type=int)
    sub.add_parser("approve").add_argument("id", type=int)
    rj = sub.add_parser("reject")
    rj.add_argument("id", type=int)
    rj.add_argument("--reason", default="")
    ed = sub.add_parser("edit")
    ed.add_argument("id", type=int)
    ed.add_argument("--caption")
    ed.add_argument("--hashtags")
    ed.add_argument("--body", help="full JSON body")
    am = sub.add_parser("attach-media")
    am.add_argument("id", type=int)
    am.add_argument("path")
    sub.add_parser("dispatch")
    sub.add_parser("inbox").add_argument("file", nargs="?")
    sub.add_parser("leads")
    w = sub.add_parser("won")
    w.add_argument("lead", type=int)
    w.add_argument("value", type=float)
    sub.add_parser("import-events").add_argument("file")
    sub.add_parser("attribution").add_argument("--by", default="hook_trigger", choices=["hook_trigger", "topic", "platform", "post"])
    sub.add_parser("brief")
    sub.add_parser("run")
    sub.add_parser("bot")
    args = p.parse_args(argv)

    if args.cmd in (None, "demo"):
        return demo(os.path.join(ROOT, "smt_demo.db") if args.db == db.DEFAULT_PATH else args.db)
    if args.cmd == "init":
        conn = db.create(args.db)
        if args.history:
            from datetime import datetime
            sim.seed_history(conn, datetime.now() - timedelta(days=24))
        print(f"Database created: {args.db}")
        return
    conn = db.connect(args.db)
    brand = config.brand()
    owner = gate.owner_id()
    try:
        if args.cmd == "research":
            print(research.run(conn, brand, research.sources_from_env(), notify=notifier_from_env(conn)))
        elif args.cmd == "generate":
            print(f"{len(content.generate_week(conn, brand, from_env(brand)))} posts queued as PENDING")
        elif args.cmd == "queue":
            for q in gate.pending(conn):
                print(f"  #{q['id']:<4} {q['kind']:<5} {q['platform']:<9} {q['scheduled_for'] or '':<16} "
                      f"{(q['hook'] or json.loads(q['body']).get('text', ''))[:70]}")
        elif args.cmd == "show":
            print(card(conn.execute("SELECT * FROM queue_item WHERE id=?", (args.id,)).fetchone()))
        elif args.cmd == "approve":
            print(f"#{gate.approve(conn, args.id, owner, 'cli')['id']} approved")
        elif args.cmd == "reject":
            gate.reject(conn, args.id, owner, "cli", args.reason)
            print(f"#{args.id} rejected")
        elif args.cmd == "edit":
            gate.edit(conn, args.id, owner, caption=args.caption, hashtags=args.hashtags, body=args.body)
            print(f"#{args.id} edited; it is PENDING again")
        elif args.cmd == "attach-media":
            if not os.path.isfile(args.path):
                raise ValueError(f"{args.path} is not a file")
            gate.edit(conn, args.id, owner, media_path=os.path.abspath(args.path))
            print(f"#{args.id} has its video; approve it again to publish")
        elif args.cmd == "dispatch":
            for i, outcome in publishers.dispatch_due(conn, publishers.publishers_from_env(), notify=notifier_from_env(conn)):
                print(f"  #{i} {outcome}")
        elif args.cmd == "inbox":
            if args.file:
                with open(args.file, encoding="utf-8") as f:
                    msgs = json.load(f)
            else:
                msgs = triage.FileInbox(os.path.join(ROOT, "inbox")).fetch()
            print(triage.ingest(conn, msgs, brand, from_env(brand), notify=notifier_from_env(conn)))
        elif args.cmd == "leads":
            for r in conn.execute("SELECT * FROM lead ORDER BY status, score DESC"):
                print(f"  #{r['id']} {r['status']:<4} {r['platform']:<9} {r['author']:<18} {r['reason']}")
        elif args.cmd == "won":
            analytics.close_lead(conn, args.lead, args.value)
            print(f"lead #{args.lead} won, ${args.value:,.0f} attributed")
        elif args.cmd == "import-events":
            print(f"{analytics.import_csv(conn, args.file)} events imported")
        elif args.cmd == "attribution":
            for r in analytics.attribution(conn, args.by):
                print(f"  {str(r['k'])[:48]:<48} posts {r['posts']:>3} clicks {r['clicks']:>4} leads {r['leads']:>3} "
                      f"sales {r['sales']:>3} revenue ${r['revenue']:>9,.0f}")
        elif args.cmd == "brief":
            print(analytics.morning_briefing(conn, brand))
        elif args.cmd == "run":
            sources = research.sources_from_env()
            if any(isinstance(s_, research.SampleSource) for s_ in sources):
                print("WARNING: no BRAVE_API_KEY / YOUTUBE_API_KEY - research uses the synthetic sample data", flush=True)
            agent = Agent(conn, brand, from_env(brand), sources, publishers.publishers_from_env(),
                          triage.FileInbox(os.path.join(ROOT, "inbox")), notifier_from_env(conn))
            print(f"Agent running. Owner: {owner}. Ctrl-C to stop.", flush=True)
            agent.run_forever()
        elif args.cmd == "bot":
            token = os.environ.get("TELEGRAM_BOT_TOKEN")
            if not token or not os.environ.get("SMT_OWNER_CHAT_ID"):
                raise ValueError("set TELEGRAM_BOT_TOKEN and SMT_OWNER_CHAT_ID in .env")
            print("Telegram bot polling. Ctrl-C to stop.", flush=True)
            Bot(conn, brand, notifier_from_env(conn)).poll_forever(TelegramClient(token))
    except (KeyError, ValueError, gate.GateError) as e:
        print(f"! {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
