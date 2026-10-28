"""Command line: set up, check, serve, and use WhatsApp without an AI client.

    python3 -m wamcp setup          one-time wizard
    python3 -m wamcp doctor         check the whole chain
    python3 -m wamcp install        register with Claude Code / Desktop / Cursor...
    python3 -m wamcp serve          MCP server on stdio (clients run this)
    python3 -m wamcp webhook        receive inbound messages
    python3 -m wamcp send --to +... --text "hi"
    python3 -m wamcp inbox
"""
import argparse
import getpass
import json
import os
import secrets
import sys
import time

from . import PROTOCOL_VERSION, SERVER_NAME, __version__, config as config_mod
from . import graph, install as install_mod, mcp, messages, paste, phones, policy
from . import store as store_mod, tools


def out(*parts):
    print(*parts)


def err(*parts):
    print(*parts, file=sys.stderr)


# --- setup ------------------------------------------------------------------
INTRO = """
==================================================================
 WhatsApp Business MCP - setup
==================================================================

This connects your WhatsApp Business number to any AI that speaks MCP
(Claude Code, Claude Desktop, Cursor, VS Code, Windsurf, Zed).

You need one page open in your browser:

   https://developers.facebook.com/apps

   -> your app  ->  WhatsApp  ->  API Setup

   (No app yet? Create App -> Business -> add the WhatsApp product.
    It is free and takes about two minutes.)

On that page there is a grey box of example code starting with "curl".
COPY THE WHOLE BOX. It already contains your access token and your
phone number ID, so that is the only thing you need to copy.
""".strip()

PASTE_PROMPT = """
Paste it below. Anything works: the curl box, a .env file, or just your
access token on its own.

Paste, then press Enter on an empty line:
""".strip()


def ask(prompt, default="", secret=False):
    shown = f"{prompt}"
    if default:
        shown += f" [{config_mod.mask(default) if secret else default}]"
    shown += ": "
    try:
        value = getpass.getpass(shown) if secret else input(shown)
    except (EOFError, KeyboardInterrupt):
        out("")
        raise SystemExit("setup cancelled")
    return value.strip() or default


def confirm(prompt, default=True):
    suffix = "[Y/n]" if default else "[y/N]"
    answer = ask(f"{prompt} {suffix}").strip().lower()
    if not answer:
        return default
    return answer.startswith("y")


def read_paste():
    """Read a multi-line paste, ending at a blank line or end of input."""
    lines = []
    while True:
        try:
            line = input()
        except (EOFError, KeyboardInterrupt):
            break
        if not line.strip():
            if lines:
                break
            continue           # ignore leading blank lines from the paste
        lines.append(line)
    return "\n".join(lines)


def _choose_number(numbers):
    """Which WhatsApp number to send from, when the account has several."""
    if len(numbers) == 1:
        return numbers[0]
    out("")
    out("This account has several WhatsApp numbers:")
    for index, number in enumerate(numbers, 1):
        label = " ".join(x for x in (number.get("display_phone_number"),
                                     number.get("verified_name")) if x)
        out(f"  [{index}] {label or number['id']}")
    while True:
        answer = ask("Which one should the AI send from? (number)", "1")
        if answer.isdigit() and 1 <= int(answer) <= len(numbers):
            return numbers[int(answer) - 1]
        out(f"Enter a number between 1 and {len(numbers)}.")


def cmd_setup(args):
    existing = config_mod.load(args.config)
    if not sys.stdin.isatty():
        err("Setup needs an interactive terminal. Either run it in a terminal, or set the "
            "WHATSAPP_ACCESS_TOKEN and WHATSAPP_PHONE_NUMBER_ID environment variables.")
        return 2

    out(INTRO)
    out("")
    out(PASTE_PROMPT)
    found = paste.parse(read_paste())
    if found:
        out(f"Got it - found your {paste.describe(found)}.")
    elif existing.access_token:
        out("Nothing recognised in that paste; keeping what is already saved.")
    else:
        out("I could not find an access token in that. Let's do it by hand.")

    token = found.get("access_token") or existing.access_token
    if not token:
        token = ask("Access token (paste it)", secret=True)
    if not token:
        err("No access token, so there is nothing to connect. Run setup again when you have one.")
        return 2

    values = existing.as_dict()
    values.update({k: v for k, v in found.items() if v})
    values["access_token"] = token
    config = config_mod.Config(**values)

    # Ask Meta what this token can reach, rather than asking the user.
    out("")
    out("Checking the token with Meta and looking up your account...")
    client = graph.GraphClient(config)
    try:
        assets = client.discover()
    except Exception as e:                                      # noqa: BLE001
        err(f"could not reach the WhatsApp API: {e}")
        assets = {"phone_numbers": [], "business_account_id": "", "notes": []}

    if assets["phone_numbers"]:
        chosen = _choose_number(assets["phone_numbers"])
        values["phone_number_id"] = chosen["id"]
        label = " ".join(x for x in (chosen.get("verified_name"),
                                     chosen.get("display_phone_number")) if x)
        out(f"  number:  {label or chosen['id']}")
    elif not values.get("phone_number_id"):
        out("  I could not find a phone number with this token.")
        out("  It is the 15-digit 'Phone number ID' on the API Setup page.")
        values["phone_number_id"] = ask("Phone number ID")

    if assets["business_account_id"] and not values.get("business_account_id"):
        values["business_account_id"] = assets["business_account_id"]
        out(f"  account: {assets['business_account_name'] or assets['business_account_id']}")
    if not values.get("business_account_id"):
        out("  No business account ID found; template tools will be off until it is set.")
        out("  It is the 'WhatsApp Business Account ID' on the same page - optional for now.")
        values["business_account_id"] = ask("WhatsApp Business Account ID (or Enter to skip)")

    # A verify token is only used to receive messages, and any string will do,
    # so generate one rather than asking.
    values["verify_token"] = values.get("verify_token") or secrets.token_urlsafe(18)
    config = config_mod.Config(**values)
    path = config_mod.save(config, args.config)
    out("")
    out(f"Saved to {path} (only you can read it).")

    ctx = tools.Context(config_mod.load(args.config))
    report = tools.diagnose(ctx)
    out("")
    _print_report(report)

    _offer_install(ctx.config)
    if report["ready"]:
        _offer_test_message(args, ctx)

    out("")
    out("=" * 66)
    out("Done. Two things worth knowing:")
    out("")
    out("  * To RECEIVE messages, run this as well (it explains itself):")
    out("        python3 -m wamcp webhook --public-url https://your-host")
    out("")
    out("  * The token from the API Setup page expires in 24 hours. For one that")
    out("    does not: Business Settings -> System Users -> Add, give it your app")
    out("    with whatsapp_business_messaging and whatsapp_business_management,")
    out("    then Generate token with expiry Never. Re-run setup and paste it.")
    out("")
    out("Anything wrong later:  python3 -m wamcp doctor")
    out("=" * 66)
    return 0


def _offer_install(config):
    """Put the server into whichever AI clients are on this machine."""
    detected = install_mod.detect()
    out("")
    if not detected:
        out("No AI client detected on this machine. Paste this into whichever you use:")
        out("")
        out(json.dumps(install_mod.config_document(config), indent=2))
        return
    labels = [install_mod.CLIENTS[c]["label"] for c in detected]
    out("Found on this machine: " + ", ".join(labels))
    if not confirm("Add WhatsApp to " + ("it" if len(detected) == 1 else "them") + " now?"):
        out("Skipped. You can do it later with `python3 -m wamcp install`.")
        return
    for client in detected:
        try:
            target, action = install_mod.install(client, config)
            out(f"  {action:9} {target}")
        except (KeyError, ValueError, OSError) as e:
            err(f"  failed for {client}: {e}")
    out("")
    out("Restart the client, then say to it:  \"check my WhatsApp connection\"")


def _offer_test_message(args, ctx):
    """Send the user a message on their own phone, to prove it works."""
    out("")
    out("Want to prove it works? I can send a message to your own phone.")
    out("(It has to be a template, because you have not written to the business yet.")
    out(" Every new account has one called hello_world.)")
    number = ""
    for _ in range(2):
        answer = ask("Your WhatsApp number in international form, e.g. +966501234567 "
                     "(Enter to skip)")
        if not answer:
            return
        try:
            number = phones.pretty(phones.normalise(answer, ctx.config.default_country))
            break
        except phones.PhoneError as e:
            out(f"  {e}")
    if not number:
        out("Skipping the test message. Try later with:")
        out("  python3 -m wamcp send --to +<your number> --template hello_world")
        return

    result = tools.call(ctx, "whatsapp_send_template",
                        {"to": number, "template": "hello_world", "language": "en_US"})
    out("")
    for item in result.get("content", []):
        out(item.get("text", ""))
    if not result.get("isError"):
        out("")
        out("Check your phone. Reply to that message and the 24-hour window opens,")
        out("so your AI can then send ordinary text to you.")
        return

    out("")
    detail = result.get("structuredContent") or {}
    if detail.get("code") == 131030:
        out("That number has to be allowed first: on the API Setup page, under 'To',")
        out("add your own number. Then run:")
    elif detail.get("code") in (132001, 132000):
        out("This account has no hello_world template. See what it does have:")
        out("  python3 -m wamcp templates")
        out("then send one of those:")
    else:
        out("Not fatal - the connection itself is fine. To try again:")
    out(f"  python3 -m wamcp send --to {number} --template hello_world")


# --- doctor -----------------------------------------------------------------
def _print_report(report):
    out(f"WhatsApp Business MCP {report['version']} - {report['summary']}")
    for check in report["checks"]:
        mark = {"ok": " ok ", "warn": "warn", "fail": "FAIL"}[check["state"]]
        out(f"[{mark}] {check['name']}: {check['detail']}")
        if check.get("fix"):
            out(f"        -> {check['fix']}")


def cmd_doctor(args):
    ctx = tools.Context(config_mod.load(args.config))
    report = tools.diagnose(ctx)
    if args.json:
        out(json.dumps(report, indent=2))
    else:
        _print_report(report)
        out("")
        out(f"Graph API version: {report['api_version']}   MCP protocol: {PROTOCOL_VERSION}")
    return 0 if report["ready"] else 1


# --- install ----------------------------------------------------------------
def cmd_install(args):
    config = config_mod.load(args.config)
    if args.list:
        for name, spec in install_mod.CLIENTS.items():
            out(f"{name:22} {spec['label']}")
            path = install_mod.client_path(name)
            if path:
                out(f"{'':22} {path}")
        return 0
    client = args.client or "print"
    if client == "print" or args.dry_run:
        key = install_mod.CLIENTS.get(client, {}).get("key", "mcpServers")
        out(json.dumps(install_mod.config_document(config, args.with_secrets, key=key), indent=2))
        out("")
        out("Claude Code one-liner:")
        out("  " + install_mod.claude_code_command(config, args.with_secrets))
        return 0
    try:
        target, action = install_mod.install(client, config, args.with_secrets, path=args.path)
    except (KeyError, ValueError, OSError) as e:
        err(str(e))
        return 2
    out(f"{action} {target}")
    out(f"Server name: {SERVER_NAME}. Restart {install_mod.CLIENTS[client]['label']} to pick it up.")
    if not args.with_secrets:
        out("Credentials were left in ~/.wamcp/config.json rather than this file.")
    return 0


# --- serve ------------------------------------------------------------------
def cmd_serve(args):
    config = config_mod.load(args.config)
    ctx = tools.Context(config, config_path=args.config)
    if args.http:
        mcp.serve_http_forever(ctx, host=args.host, port=args.port,
                               auth_token=args.token or os.environ.get("WAMCP_HTTP_TOKEN", ""),
                               verbose=args.verbose)
        return 0
    mcp.serve_stdio(ctx, quiet=args.quiet)
    return 0


# --- webhook ----------------------------------------------------------------
def cmd_webhook(args):
    config = config_mod.load(args.config)
    if not config.verify_token:
        err("no webhook verify token set. Run `python3 -m wamcp setup`, or set "
            f"{config_mod.ENV['verify_token']} to any string of your choosing.")
        return 2

    def say(message):
        if not args.quiet:
            out(f"[{time.strftime('%H:%M:%S')}] {message}")

    def on_event(kind, data):
        if kind == "message":
            who = data.get("name") or phones.pretty(data.get("from", ""))
            say(f"message from {who}: {str(data.get('text'))[:100]}")
        elif kind == "failed":
            say(f"delivery FAILED to {phones.pretty(data.get('to', ''))}: {data.get('error')}")

    from . import webhook as webhook_mod
    server = webhook_mod.make_server(
        config, host=args.host, port=args.port, path=args.path,
        insecure=args.insecure, verbose=args.verbose, say=say, on_event=on_event,
    )
    public = (args.public_url or f"http://{args.host}:{args.port}").rstrip("/")
    callback = public + args.path
    out("Webhook receiver running.")
    out("")
    out("Paste these into developers.facebook.com -> your app -> WhatsApp -> Configuration:")
    out(f"  Callback URL : {callback}")
    out(f"  Verify token : {config.verify_token}")
    out("  then subscribe to the 'messages' field.")
    out("")
    if not args.public_url:
        out("That URL must be reachable from the internet over HTTPS. For a local test use a")
        out("tunnel, e.g. `cloudflared tunnel --url http://localhost:%d`, and pass its https" % args.port)
        out("address as --public-url.")
        out("")
    if not config.app_secret and not args.insecure:
        out("No app secret is set, so POSTs cannot be verified and will be refused.")
        out(f"Set {config_mod.ENV['app_secret']} (Meta app -> Settings -> Basic), or restart")
        out("with --insecure to accept unverified payloads.")
        out("")
    out(f"Listening on {args.host}:{args.port}{args.path} - Ctrl-C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        out("\nstopped")
    finally:
        server.shutdown()
        server.server_close()
    return 0


# --- send / inbox -----------------------------------------------------------
def _run_tool(args, name, arguments):
    ctx = tools.Context(config_mod.load(args.config), config_path=args.config)
    result = tools.call(ctx, name, arguments)
    if getattr(args, "json", False):
        out(json.dumps(result.get("structuredContent", {}), indent=2, ensure_ascii=False))
    else:
        for item in result.get("content", []):
            out(item.get("text", ""))
    return 1 if result.get("isError") else 0


def cmd_send(args):
    if args.template:
        return _run_tool(args, "whatsapp_send_template", {
            "to": args.to, "template": args.template, "language": args.language,
            "body_params": args.param or [],
        })
    if args.media:
        return _run_tool(args, "whatsapp_send_media", {
            "to": args.to, "kind": args.kind, "link": args.media, "caption": args.text,
        })
    if not args.text:
        err("give --text, or --template NAME, or --media URL")
        return 2
    return _run_tool(args, "whatsapp_send_message", {"to": args.to, "text": args.text})


def cmd_inbox(args):
    if args.contact:
        return _run_tool(args, "whatsapp_read_chat", {"contact": args.contact, "limit": args.limit})
    return _run_tool(args, "whatsapp_list_chats",
                     {"limit": args.limit, "unanswered_only": args.unanswered})


def cmd_templates(args):
    return _run_tool(args, "whatsapp_list_templates", {"limit": args.limit})


def cmd_consent(args):
    return _run_tool(args, "whatsapp_consent",
                     {"action": args.action, "contact": args.contact, "reason": args.reason})


def cmd_audit(args):
    config = config_mod.load(args.config)
    entries = store_mod.Store(config.db_path).audit_entries(limit=args.limit)
    if args.json:
        out(json.dumps(entries, indent=2))
        return 0
    if not entries:
        out("nothing in the audit log yet")
        return 0
    for entry in entries:
        when = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(entry["ts"]))
        who = phones.pretty(entry["wa_id"]) if entry["wa_id"] else "-"
        out(f"{when}  {entry['action']:18} {who:16} {entry['outcome']:8} {entry['detail'] or ''}")
    return 0


def cmd_version(args):
    out(f"wamcp {__version__}")
    out(f"MCP protocol {PROTOCOL_VERSION} (also speaks "
        f"{', '.join(v for v in __import__('wamcp').KNOWN_PROTOCOL_VERSIONS)})")
    out(f"Graph API {config_mod.load(args.config).api_version}")
    out(f"Python {sys.version.split()[0]} at {sys.executable}")
    return 0


def cmd_selftest(args):
    import unittest
    root = install_mod.package_root()
    suite = unittest.defaultTestLoader.discover(os.path.join(root, "tests"),
                                                pattern="test_wamcp*.py")
    runner = unittest.TextTestRunner(verbosity=2 if args.verbose else 1)
    return 0 if runner.run(suite).wasSuccessful() else 1


# --- argument parsing -------------------------------------------------------
def build_parser():
    parser = argparse.ArgumentParser(
        prog="python3 -m wamcp",
        description="WhatsApp Business MCP - connect a WhatsApp Business number to any AI.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Start with `setup`, check with `doctor`, then `install`.",
    )
    parser.add_argument("--config", help="path to the config file "
                                        f"(default {config_mod.CONFIG_PATH})")
    parser.add_argument("--version", action="version", version=f"wamcp {__version__}")
    subs = parser.add_subparsers(dest="command")

    for name in ("setup", "connect"):
        p = subs.add_parser(name, help="interactive one-time setup (paste and go)")
        p.set_defaults(func=cmd_setup)

    p = subs.add_parser("doctor", help="check credentials, API access and the inbox")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_doctor)

    p = subs.add_parser("install", help="register this server with an AI client")
    p.add_argument("client", nargs="?", help="claude-code, claude-desktop, cursor, vscode, "
                                             "windsurf, zed, claude-code-project, print")
    p.add_argument("--list", action="store_true", help="show supported clients and their files")
    p.add_argument("--with-secrets", action="store_true",
                   help="inline the credentials in the client config instead of reading "
                        "~/.wamcp/config.json")
    p.add_argument("--path", help="write to this file instead of the client's default")
    p.add_argument("--dry-run", action="store_true", help="print the JSON, change nothing")
    p.set_defaults(func=cmd_install)

    p = subs.add_parser("serve", help="run the MCP server (stdio by default)")
    p.add_argument("--http", action="store_true", help="serve Streamable HTTP instead of stdio")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--token", default="", help="require this bearer token on HTTP requests")
    p.add_argument("--quiet", action="store_true", help="no banner on stderr")
    p.add_argument("--verbose", action="store_true")
    p.set_defaults(func=cmd_serve)

    p = subs.add_parser("webhook", help="receive inbound messages from Meta")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--path", default="/webhook")
    p.add_argument("--public-url", help="the https address Meta will call, e.g. https://x.example.com")
    p.add_argument("--insecure", action="store_true",
                   help="accept payloads without verifying the signature (testing only)")
    p.add_argument("--quiet", action="store_true")
    p.add_argument("--verbose", action="store_true")
    p.set_defaults(func=cmd_webhook)

    p = subs.add_parser("send", help="send a message from the command line")
    p.add_argument("--to", required=True)
    p.add_argument("--text", help="message text, or a caption for --media")
    p.add_argument("--template", help="send this approved template instead of text")
    p.add_argument("--language", default="en_US")
    p.add_argument("--param", action="append", help="a template body parameter; repeatable")
    p.add_argument("--media", help="public https url of a file to send")
    p.add_argument("--kind", default="image", choices=list(messages.MEDIA_KINDS))
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_send)

    p = subs.add_parser("inbox", help="list conversations, or read one")
    p.add_argument("contact", nargs="?", help="name or number to read")
    p.add_argument("--limit", type=int, default=20)
    p.add_argument("--unanswered", action="store_true", help="only chats where they spoke last")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_inbox)

    p = subs.add_parser("templates", help="list message templates")
    p.add_argument("--limit", type=int, default=50)
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_templates)

    p = subs.add_parser("consent", help="check or change who may be messaged")
    p.add_argument("action", choices=["check", "list", "opt_out", "opt_in"])
    p.add_argument("contact", nargs="?")
    p.add_argument("--reason", default="")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_consent)

    p = subs.add_parser("audit", help="show what this server has done")
    p.add_argument("--limit", type=int, default=50)
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_audit)

    p = subs.add_parser("version", help="versions of everything involved")
    p.set_defaults(func=cmd_version)

    p = subs.add_parser("selftest", help="run the test suite")
    p.add_argument("--verbose", action="store_true")
    p.set_defaults(func=cmd_selftest)
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "command", None):
        # A client launching the server has a pipe on stdin; a person running
        # it by hand has a terminal and almost certainly wants to set it up.
        unconfigured = bool(config_mod.load(getattr(args, "config", None)).missing_for_sending())
        interactive = sys.stdin.isatty() and sys.stdout.isatty()
        args = parser.parse_args(["setup" if (unconfigured and interactive) else "serve"])
    try:
        return args.func(args)
    except config_mod.ConfigError as e:
        err(str(e))
        return 2
    except (graph.WhatsAppError, policy.PolicyError) as e:
        err(str(e))
        return 1
    except BrokenPipeError:
        return 0
    except KeyboardInterrupt:
        return 130
