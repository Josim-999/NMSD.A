"""MCP server core: JSON-RPC 2.0 over stdio and over Streamable HTTP.

stdio is what a desktop AI client launches (Claude Code, Claude Desktop,
Cursor, Zed, Continue...): one JSON-RPC message per line on stdin, one per
line on stdout. Nothing but protocol ever goes to stdout - logs go to stderr,
because a stray print breaks the stream.

The HTTP transport is for clients that connect over the network, and for
hosted agents. It is stateless: each POST carries a complete JSON-RPC message.
"""
import http.server
import json
import socketserver
import sys
import threading
import time

from . import KNOWN_PROTOCOL_VERSION_SET, PROTOCOL_VERSION, SERVER_NAME, __version__, tools

PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603

INSTRUCTIONS = """
This server connects a WhatsApp Business number (Meta Cloud API) to you.

Two WhatsApp rules shape everything:

1. The 24-hour customer service window. Free-form messages (text, media,
   buttons, lists) are only delivered within 24 hours of that person's last
   message. Outside it, only an approved template is delivered - and a
   template is also the only way to start a conversation with someone new.
   whatsapp_list_chats shows each window's state, and the send tools refuse
   rather than silently fail.
2. Consent. A person who sends STOP is opted out automatically and every
   later send to them is refused. Do not work around that.

Practical notes:

* Inbound messages arrive by webhook, not by polling, and are stored locally.
  If whatsapp_list_chats is empty, the webhook receiver is not running: tell
  the user to run `python3 -m wamcp webhook --public-url https://their-host`.
* Call whatsapp_status when anything fails or when the user asks how to set
  WhatsApp up; it reports exactly what is missing and the next step.
* These messages go to real people's phones. Draft, confirm the recipient and
  the wording with the user, and send once - do not retry a send that returned
  a message id.
""".strip()

RESOURCES = [
    {
        "uri": "whatsapp://status",
        "name": "WhatsApp connection status",
        "title": "Connection status",
        "description": "Whether the WhatsApp connection works, and what is missing if not.",
        "mimeType": "application/json",
    },
    {
        "uri": "whatsapp://chats",
        "name": "Recent WhatsApp conversations",
        "title": "Recent conversations",
        "description": "The most recently active conversations with their 24-hour window state.",
        "mimeType": "application/json",
    },
    {
        "uri": "whatsapp://setup",
        "name": "WhatsApp setup guide",
        "title": "Setup guide",
        "description": "Step by step: how to connect a WhatsApp Business number to this server.",
        "mimeType": "text/markdown",
    },
]

PROMPTS = [
    {
        "name": "whatsapp_connect",
        "title": "Connect my WhatsApp",
        "description": "Walk the user through connecting their WhatsApp Business number.",
        "arguments": [],
    },
    {
        "name": "whatsapp_triage",
        "title": "Triage the WhatsApp inbox",
        "description": "Go through unanswered WhatsApp conversations and propose a reply for each.",
        "arguments": [{"name": "limit", "description": "How many conversations to review.",
                       "required": False}],
    },
    {
        "name": "whatsapp_reply",
        "title": "Draft a WhatsApp reply",
        "description": "Read one conversation and draft the next message for approval.",
        "arguments": [{"name": "contact", "description": "Name or number of the contact.",
                       "required": True}],
    },
]

SETUP_GUIDE = """
# Connect a WhatsApp Business number

You need a Meta app with WhatsApp added. Fifteen minutes, no cost to start.

1. **Create the app** - developers.facebook.com -> My Apps -> Create App ->
   choose *Business* -> add the **WhatsApp** product.
2. **Copy three values** from WhatsApp -> *API Setup*:
   - the temporary **access token** (24 hours; swap it for a permanent System
     User token later: Business Settings -> System Users -> Generate token with
     `whatsapp_business_messaging` and `whatsapp_business_management`),
   - the **Phone number ID** of the test or production number,
   - the **WhatsApp Business Account ID** (needed for templates).
3. **Tell this server** - run `python3 -m wamcp setup` and paste them, or set
   `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID` and
   `WHATSAPP_BUSINESS_ACCOUNT_ID`.
4. **Check it** - `python3 -m wamcp doctor`, or call the `whatsapp_status`
   tool. It names anything still missing.
5. **Add your own phone as a test recipient** - on the same API Setup page,
   under *To*, add the number you will test with. An unverified number may
   only message numbers on that list.
6. **Receive replies** - inbound messages arrive by webhook:
   `python3 -m wamcp webhook --public-url https://your-host` prints the URL and
   verify token to paste into WhatsApp -> Configuration -> Webhooks, then
   subscribe to the **messages** field.

Then say: *send a WhatsApp to +<your number> saying hello*. Because your own
number has not messaged the business yet, the 24-hour window is closed, so the
first message must be a template - `hello_world` exists on every new account:

    whatsapp_send_template(to="+<your number>", template="hello_world", language="en_US")

Reply to it from your phone and the window opens for 24 hours; after that
plain `whatsapp_send_message` works.
""".strip()


class Session:
    """One MCP conversation. Holds the negotiated protocol version."""

    def __init__(self, ctx):
        self.ctx = ctx
        self.protocol_version = PROTOCOL_VERSION
        self.client = {}
        self.initialised = False

    # --- request handling --------------------------------------------------
    def handle(self, message):
        """Returns a JSON-RPC response dict, or None for notifications."""
        if not isinstance(message, dict):
            return error_response(None, INVALID_REQUEST, "a JSON-RPC message must be an object")
        method = message.get("method")
        ident = message.get("id")
        params = message.get("params")
        if params is None:
            params = {}
        elif not isinstance(params, dict):
            return error_response(ident, INVALID_PARAMS, "params must be an object")
        if not method:
            return error_response(ident, INVALID_REQUEST, "no method")

        if method.startswith("notifications/"):
            if method == "notifications/initialized":
                self.initialised = True
            return None                        # notifications are never answered

        try:
            handler = getattr(self, "_" + method.replace("/", "_"), None)
            if handler is None:
                return error_response(ident, METHOD_NOT_FOUND, f"unknown method {method!r}")
            return response(ident, handler(params))
        except tools.ToolError as e:
            return error_response(ident, INVALID_PARAMS, e.payload.get("error", "bad request"),
                                  e.payload)
        except KeyError as e:
            return error_response(ident, INVALID_PARAMS, f"unknown name: {e}")
        except Exception as e:                                  # noqa: BLE001
            return error_response(ident, INTERNAL_ERROR, f"{type(e).__name__}: {e}")

    # --- lifecycle ---------------------------------------------------------
    def _initialize(self, params):
        wanted = str(params.get("protocolVersion") or "").strip()
        # Echo the client's revision when we know it; the framing used here is
        # the same across revisions, so an unknown one gets our preferred.
        self.protocol_version = wanted if wanted in KNOWN_PROTOCOL_VERSION_SET else PROTOCOL_VERSION
        self.client = params.get("clientInfo") or {}
        return {
            "protocolVersion": self.protocol_version,
            "capabilities": {
                "tools": {"listChanged": False},
                "resources": {"listChanged": False, "subscribe": False},
                "prompts": {"listChanged": False},
                "logging": {},
            },
            "serverInfo": {
                "name": SERVER_NAME,
                "title": "WhatsApp Business",
                "version": __version__,
            },
            "instructions": INSTRUCTIONS,
        }

    def _ping(self, params):
        return {}

    def _logging_setLevel(self, params):
        return {}

    def _completion_complete(self, params):
        return {"completion": {"values": [], "total": 0, "hasMore": False}}

    # --- tools -------------------------------------------------------------
    def _tools_list(self, params):
        return {"tools": tools.TOOLS}

    def _tools_call(self, params):
        name = params.get("name")
        if not name:
            raise tools.ToolError("tools/call needs a tool name")
        if name not in {t["name"] for t in tools.TOOLS}:
            raise KeyError(name)
        return tools.call(self.ctx, name, params.get("arguments") or {})

    # --- resources ---------------------------------------------------------
    def _resources_list(self, params):
        return {"resources": RESOURCES}

    def _resources_templates_list(self, params):
        return {"resourceTemplates": []}

    def _resources_read(self, params):
        uri = params.get("uri")
        if uri == "whatsapp://setup":
            return {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": SETUP_GUIDE}]}
        if uri == "whatsapp://status":
            body = tools.diagnose(self.ctx)
        elif uri == "whatsapp://chats":
            result = tools.call(self.ctx, "whatsapp_list_chats", {"limit": 20})
            body = result.get("structuredContent", {})
        else:
            raise tools.ToolError(f"unknown resource {uri!r}", kind="not_found")
        return {"contents": [{"uri": uri, "mimeType": "application/json",
                              "text": json.dumps(body, indent=2, ensure_ascii=False)}]}

    # --- prompts -----------------------------------------------------------
    def _prompts_list(self, params):
        return {"prompts": PROMPTS}

    def _prompts_get(self, params):
        name = params.get("name")
        args = params.get("arguments") or {}
        if name == "whatsapp_connect":
            text = ("Help me connect my WhatsApp Business number to you. Call whatsapp_status "
                    "first, then walk me through whatever is missing, one step at a time, using "
                    "the whatsapp://setup guide. Finish by sending a test message to my own "
                    "number once I give it to you.")
        elif name == "whatsapp_triage":
            limit = args.get("limit") or 10
            text = (f"Call whatsapp_list_chats with unanswered_only=true and limit={limit}. For "
                    "each conversation where they spoke last, read it with whatsapp_read_chat, "
                    "then propose a reply. Note which chats are outside the 24-hour window and "
                    "would need a template. Show me every draft for approval before sending "
                    "anything.")
        elif name == "whatsapp_reply":
            contact = args.get("contact") or ""
            text = (f"Read my WhatsApp conversation with {contact} using whatsapp_read_chat, then "
                    "draft the next message. Keep it short and in the same language and tone the "
                    "customer used. Show me the draft before sending.")
        else:
            raise tools.ToolError(f"unknown prompt {name!r}", kind="not_found")
        return {"messages": [{"role": "user", "content": {"type": "text", "text": text}}]}


# --- JSON-RPC helpers -------------------------------------------------------
def response(ident, result):
    return {"jsonrpc": "2.0", "id": ident, "result": result}


def error_response(ident, code, message, data=None):
    err = {"code": code, "message": message}
    if data:
        err["data"] = data
    return {"jsonrpc": "2.0", "id": ident, "error": err}


def log(message):
    """Diagnostics go to stderr: stdout carries protocol only."""
    sys.stderr.write(f"[wamcp] {message}\n")
    sys.stderr.flush()


# --- stdio transport --------------------------------------------------------
def serve_stdio(ctx, stdin=None, stdout=None, quiet=False):
    """Run the server on stdin/stdout until the client closes the stream."""
    stdin = stdin or sys.stdin
    stdout = stdout or sys.stdout
    session = Session(ctx)
    if not quiet:
        log(f"WhatsApp Business MCP {__version__} on stdio (protocol {PROTOCOL_VERSION})")
    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError as e:
            _write(stdout, error_response(None, PARSE_ERROR, f"invalid JSON: {e}"))
            continue
        if isinstance(message, list):          # JSON-RPC batch (older clients)
            out = [r for r in (session.handle(m) for m in message) if r is not None]
            if out:
                _write(stdout, out)
            continue
        reply = session.handle(message)
        if reply is not None:
            _write(stdout, reply)
    return session


def _write(stream, payload):
    stream.write(json.dumps(payload, ensure_ascii=False) + "\n")
    stream.flush()


# --- Streamable HTTP transport ---------------------------------------------
class _Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = f"wamcp/{__version__}"

    @property
    def session(self):
        return self.server.session

    def log_message(self, fmt, *args):
        if self.server.verbose:
            log(f"http {self.address_string()} {fmt % args}")

    def _authorised(self):
        token = self.server.auth_token
        if not token:
            return True
        header = self.headers.get("Authorization", "")
        if header.startswith("Bearer ") and header[7:].strip() == token:
            return True
        self._send(401, {"error": "unauthorised"},
                   extra={"WWW-Authenticate": 'Bearer realm="wamcp"'})
        return False

    def _send(self, status, payload, extra=None):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("MCP-Protocol-Version", self.session.protocol_version)
        self.send_header("Access-Control-Allow-Origin", self.server.allow_origin)
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", self.server.allow_origin)
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers",
                         "Content-Type, Authorization, MCP-Protocol-Version, Mcp-Session-Id")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if not self._authorised():
            return
        if self.path.rstrip("/") in ("", "/health", "/healthz"):
            self._send(200, {"server": SERVER_NAME, "version": __version__,
                             "protocolVersion": PROTOCOL_VERSION, "transport": "streamable-http"})
            return
        # No server-initiated messages, so there is no stream to open.
        self._send(405, {"error": "this server does not open an event stream; POST JSON-RPC instead"})

    def do_POST(self):
        if not self._authorised():
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        raw = self.rfile.read(length) if length else b""
        try:
            message = json.loads(raw.decode("utf-8")) if raw else None
        except (ValueError, UnicodeDecodeError) as e:
            self._send(400, error_response(None, PARSE_ERROR, f"invalid JSON: {e}"))
            return
        if message is None:
            self._send(400, error_response(None, INVALID_REQUEST, "empty body"))
            return
        with self.server.lock:
            if isinstance(message, list):
                replies = [r for r in (self.session.handle(m) for m in message) if r is not None]
                self._send(200 if replies else 202, replies or None)
                return
            reply = self.session.handle(message)
        if reply is None:
            self._send(202, None)              # a notification: accepted, nothing to say
        else:
            self._send(200, reply)


class _HTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def serve_http(ctx, host="127.0.0.1", port=8765, auth_token="", verbose=False,
               allow_origin="http://localhost"):
    """Run the server over Streamable HTTP. Returns the server (call shutdown)."""
    httpd = _HTTPServer((host, port), _Handler)
    httpd.session = Session(ctx)
    httpd.auth_token = auth_token
    httpd.verbose = verbose
    httpd.allow_origin = allow_origin
    httpd.lock = threading.Lock()
    log(f"WhatsApp Business MCP {__version__} on http://{host}:{port} "
        f"({'bearer token required' if auth_token else 'no auth - keep it on localhost'})")
    return httpd


def serve_http_forever(ctx, **kwargs):
    httpd = serve_http(ctx, **kwargs)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log("stopping")
    finally:
        httpd.shutdown()
        httpd.server_close()
