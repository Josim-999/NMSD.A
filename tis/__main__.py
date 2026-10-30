"""Command line for the example Transportation Intelligence System.

    python -m tis demo                       scripted walkthrough
    python -m tis ask "which trucks are delayed?"
    python -m tis chat                       interactive assistant
    python -m tis report morning             writes reports/<kind>_report.md
    python -m tis dashboard                  writes reports/control_tower.html
    python -m tis approvals                  list pending approval requests
    python -m tis approve 1 --user sara --role supervisor
    python -m tis init                       regenerate the synthetic database
    python -m tis llm-check                  verify the configured language layer
"""
import argparse
import os
import sys

from . import db, dashboard, sim
from .agents import TransportAI, User
from .llm import DataBoundaryError, Pseudonymizer, from_env

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORTS = os.path.join(ROOT, "reports")

DEMO = [
    "What requires attention right now?",
    "Which trucks are delayed?",
    "Why is TRK-007 late?",
    "What's happening with 27?",
    "Show me all trucks available within 100 km of MLP",
    "Which drivers completed the most trips?",
    "Which customers make us wait the most?",
    "What shipments haven't been dispatched?",
    "Prepare a dispatch plan for the pending shipments",
    "What should I do if a driver reaches a customer but the receiving dock is closed?",
    "What is the penalty for late delivery to hospitals?",
]


def open_system(path):
    if not os.path.exists(path):
        init(path)
    return TransportAI(db.connect(path), llm=from_env())


def llm_check():
    """Round-trip the configured language layer with dummy facts (no real data)."""
    llm = from_env()
    print(f"Language layer: {llm.name}")
    facts = {"truck": "TRK-001", "status": "delayed", "delay_min": 25, "driver_name": "Test Driver"}
    out = llm.narrate("Why is TRK-001 late?", "TRK-001 is 25 minutes late.", facts,
                      Pseudonymizer(["Test Driver"]))
    print(f"\nModel said:\n{out}")
    ok = "unavailable:" not in out
    print("\nOK - the model answered." if ok else "\nFAILED - see the note above; the rule-based answer was used.")
    return 0 if ok else 1


def init(path):
    conn = db.create(path)
    sim.generate(conn)
    conn.close()
    print(f"Synthetic operations database created: {path}")


def show(ans):
    print(f"[{ans.agent} agent]")
    print(ans.text)
    if ans.sources:
        print(f"Sources: {', '.join(dict.fromkeys(ans.sources))}")
    print()


def main(argv=None):
    # identity flags are accepted before or after the sub-command
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--db", default=argparse.SUPPRESS)
    common.add_argument("--user", default=argparse.SUPPRESS)
    common.add_argument("--role", default=argparse.SUPPRESS, choices=["viewer", "coordinator", "supervisor"])
    p = argparse.ArgumentParser(prog="python -m tis", parents=[common],
                                description="Transportation Intelligence System - example model")
    sub = p.add_subparsers(dest="cmd")
    _add = sub.add_parser
    sub.add_parser = lambda name, **kw: _add(name, parents=[common], **kw)
    sub.add_parser("init")
    sub.add_parser("llm-check")
    sub.add_parser("demo")
    sub.add_parser("chat")
    a = sub.add_parser("ask")
    a.add_argument("question", nargs="+")
    r = sub.add_parser("report")
    r.add_argument("kind", nargs="?", default="midday", choices=["morning", "midday", "evening"])
    sub.add_parser("dashboard")
    sub.add_parser("approvals")
    ap = sub.add_parser("approve")
    ap.add_argument("id", type=int)
    rj = sub.add_parser("reject")
    rj.add_argument("id", type=int)
    sub.add_parser("audit")
    args = p.parse_args(argv)
    for k, v in (("db", db.DEFAULT_PATH), ("user", "coordinator1"), ("role", "coordinator")):
        if not hasattr(args, k):
            setattr(args, k, v)
    user = User(args.user, args.role)

    if args.cmd == "init":
        return init(args.db)
    try:
        if args.cmd == "llm-check":
            return llm_check()
        ai = open_system(args.db)
    except DataBoundaryError as e:                   # misconfigured model -> explain, do not traceback
        print(f"! {e}", file=sys.stderr)
        return 1
    print(f"Language layer: {ai.llm.name}\n")

    try:
        if args.cmd in (None, "demo"):
            for q in DEMO:
                print(f">>> {q}")
                show(ai.ask(q, user))
            print(">>> (as supervisor) same rate-card question")
            show(ai.ask(DEMO[-1], User("sara", "supervisor")))
            print("Next: `python -m tis approvals` then `python -m tis approve <id> --user sara --role supervisor`")
        elif args.cmd == "ask":
            show(ai.ask(" ".join(args.question), user))
        elif args.cmd == "chat":
            print("Ask about trucks, delays, drivers, shipments, procedures. Empty line to quit.")
            while True:
                try:
                    q = input("you> ").strip()
                except EOFError:
                    break
                if not q:
                    break
                try:
                    show(ai.ask(q, user))
                except (KeyError, PermissionError) as e:
                    print(f"! {e}\n")
        elif args.cmd == "report":
            os.makedirs(REPORTS, exist_ok=True)
            ans = ai.ask(f"{args.kind} report", user)
            out = os.path.join(REPORTS, f"{args.kind}_report.md")
            with open(out, "w", encoding="utf-8") as f:
                f.write(ans.text + "\n")
            print(ans.text)
            print(f"\nSaved {out}")
        elif args.cmd == "dashboard":
            os.makedirs(REPORTS, exist_ok=True)
            out = os.path.join(REPORTS, "control_tower.html")
            with open(out, "w", encoding="utf-8") as f:
                f.write(dashboard.render(ai))
            print(f"Saved {out}")
        elif args.cmd == "approvals":
            rows = ai.approvals()
            print(f"{len(rows)} pending approval requests")
            for r in rows:
                print(f"  #{r['id']} {r['action']}: {r['summary']} (requested by {r['requested_by']} at {r['created_ts']})")
        elif args.cmd in ("approve", "reject"):
            print(f"#{args.id}: {ai.decide(args.id, user, approve=args.cmd == 'approve')}")
        elif args.cmd == "audit":
            for r in ai.conn.execute("SELECT * FROM audit_log ORDER BY id DESC LIMIT 25"):
                print(f"  {r['ts']}  {r['user']:<14} {r['role']:<12} {r['action']:<22} {r['detail']}")
    except (KeyError, PermissionError, ValueError) as e:
        print(f"! {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
