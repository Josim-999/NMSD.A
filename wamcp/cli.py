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
from . import graph, install as install_mod, mcp, messages, phones, policy, store as store_mod, tools


def out(*parts):
    print(*parts)


def err(*parts):
    print(*parts, file=sys.stderr)


# --- setup ------------------------------------------------------------------
INTRO = """
WhatsApp Business MCP - setup
-----------------------------
This connects a WhatsApp Business number to any AI client that speaks MCP.

You need a Meta app with the WhatsApp product added (free):

  1. developers.facebook.com -> My Apps -> Create App -> Business
  2. Add the WhatsApp product
  3. Open WhatsApp -> API Setup and copy the three values below

The token on that page lasts 24 hours. For something permanent, create a
System User in Business Settings with whatsapp_business_messaging and
whatsapp_business_management, and generate a token with no expiry.

Nothing is sent anywhere during setup. Values are saved to
{path} with permissions 0600.
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
    value = value.strip()
    return value or default


def cmd_setup(args):
    existing = config_mod.load(args.config)
    out(INTRO.format(path=args.config or config_mod.CONFIG_PATH))
    out("")
    if not sys.stdin.isatty():
        err("setup needs an interactive terminal. Set the WHATSAPP_* environment "
            "variables instead, or run it in a terminal.")
        return 2

    token = ask("Access token", existing.access_token, secret=True)
    phone_id = ask("Phone number ID", existing.phone_number_id)
    waba = ask("WhatsApp Business Account ID (for templates, optional)", existing.business_account_id)
    out("")
    out("Optional, for receiving replies. Press enter to skip and add later:")
    app_secret = ask("App secret (Meta app -> Settings -> Basic)", existing.app_secret, secret=True)
    verify = existing.verify_token or secrets.token_urlsafe(18)
    verify = ask("Webhook verify token (any string; one is generated for you)", verify)
    country = ask("Default country calling code for local numbers, e.g. 966 (optional)",
                  existing.default_country)

    values = existing.as_dict()
    values.update({
        "access_token": token, "phone_number_id": phone_id, "business_account_id": waba,
        "app_secret": app_secret, "verify_token": verify, "default_country": country,
    })
    path = config_mod.save(config_mod.Config(**values), args.config)
    out("")
    out(f"Saved to {path} (0600).")
    out("")

    ctx = tools.Context(config_mod.load(args.config))
    _print_report(tools.diagnose(ctx))

    out("")
    out("Now add this server to your AI client. For Claude Code, run:")
    out("")
    out("  " + install_mod.claude_code_command())
    out("")
    answer = ask("Write it into a client config for you now? (claude-code/claude-desktop/"
                 "cursor/vscode/windsurf/zed/no)", "claude-code")
    if answer.lower() not in ("no", "n", ""):
        try:
            target, action = install_mod.install(answer.lower(), ctx.config)
            out(f"{action} {target}")
            out("Restart the client and ask it: \"check my WhatsApp connection\".")
        except (KeyError, ValueError, OSError) as e:
            err(f"could not write the config: {e}")
            out("Use the command printed above instead.")
    return 0


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
    ctx = tools.Context(config)
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
    ctx = tools.Context(config_mod.load(args.config))
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

    p = subs.add_parser("setup", help="interactive one-time setup")
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
        # Bare `python3 -m wamcp` is what an MCP client runs.
        args = parser.parse_args(["serve"])
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
