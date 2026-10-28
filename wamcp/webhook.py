"""Inbound webhook receiver.

The Cloud API has no endpoint for reading a conversation: everything inbound
arrives as an HTTP callback from Meta. This receiver verifies those callbacks
and writes them into the local store, which is what the inbox tools read.

    python3 -m wamcp webhook --public-url https://chat.example.com

Meta needs two things in WhatsApp -> Configuration -> Webhooks: the callback
URL and the verify token. Then subscribe to the `messages` field.

Security: every POST is checked against X-Hub-Signature-256, an HMAC-SHA256 of
the exact request body keyed with the Meta app secret. Without WHATSAPP_APP_SECRET
the receiver still runs but refuses to accept unverified payloads unless it is
started with --insecure, because anyone who learns the URL could otherwise
write into your inbox.
"""
import hashlib
import hmac
import http.server
import json
import socketserver
import time
import urllib.parse

from . import __version__, store as store_mod

MAX_BODY = 1 << 20          # 1 MiB; real payloads are a few KB


def verify_signature(app_secret, body, header):
    """True when the X-Hub-Signature-256 header matches the body."""
    if not app_secret or not header:
        return False
    header = header.strip()
    if header.startswith("sha256="):
        header = header[7:]
    expected = hmac.new(app_secret.encode("utf-8"), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header.lower())


# --- turning a webhook payload into rows ------------------------------------
def describe(message):
    """A short readable line for a message of any type, plus its details."""
    kind = message.get("type") or "unknown"
    detail = {"media_id": None, "mime_type": None, "filename": None, "choice_id": None}

    if kind == "text":
        body = (message.get("text") or {}).get("body") or ""
    elif kind in ("image", "video", "audio", "document", "sticker"):
        obj = message.get(kind) or {}
        detail["media_id"] = obj.get("id")
        detail["mime_type"] = obj.get("mime_type")
        detail["filename"] = obj.get("filename")
        caption = obj.get("caption") or ""
        label = obj.get("filename") or detail["mime_type"] or kind
        body = f"[{kind}: {label}]" + (f" {caption}" if caption else "")
        if obj.get("voice"):
            body = f"[voice note]" + (f" {caption}" if caption else "")
    elif kind == "interactive":
        inner = message.get("interactive") or {}
        reply = inner.get("button_reply") or inner.get("list_reply") or {}
        detail["choice_id"] = reply.get("id")
        body = reply.get("title") or reply.get("description") or "[selection]"
    elif kind == "button":
        # Quick-reply button on a template message.
        button = message.get("button") or {}
        detail["choice_id"] = button.get("payload")
        body = button.get("text") or button.get("payload") or "[button]"
    elif kind == "location":
        loc = message.get("location") or {}
        where = loc.get("name") or loc.get("address") or ""
        body = f"[location: {loc.get('latitude')},{loc.get('longitude')}]" + (f" {where}" if where else "")
    elif kind == "contacts":
        names = [((c.get("name") or {}).get("formatted_name") or "?")
                 for c in message.get("contacts") or []]
        body = "[contact card: " + ", ".join(names) + "]"
    elif kind == "reaction":
        reaction = message.get("reaction") or {}
        body = f"[reacted {reaction.get('emoji', '')} to {reaction.get('message_id', '')}]"
    elif kind == "order":
        order = message.get("order") or {}
        items = order.get("product_items") or []
        body = f"[order: {len(items)} item(s)]"
    elif kind == "system":
        body = "[" + ((message.get("system") or {}).get("body") or "system message") + "]"
    elif kind in ("unsupported", "unknown"):
        errors = message.get("errors") or [{}]
        body = f"[unsupported message: {errors[0].get('title', '')}]"
    else:
        body = f"[{kind}]"
    return body, detail


def ingest(store, payload, on_event=None):
    """Write one webhook payload into the store. Returns what it did."""
    result = {"messages": 0, "statuses": 0, "opt_outs": [], "errors": []}
    if not isinstance(payload, dict):
        return result

    for entry in payload.get("entry") or []:
        for change in entry.get("changes") or []:
            value = change.get("value") or {}
            names = {c.get("wa_id"): ((c.get("profile") or {}).get("name") or "")
                     for c in value.get("contacts") or []}
            for wa_id, name in names.items():
                if wa_id and name:
                    store.set_contact_name(wa_id, name)

            for message in value.get("messages") or []:
                wa_id = message.get("from")
                wamid = message.get("id")
                if not wa_id or not wamid:
                    continue
                body, detail = describe(message)
                store.record_message(
                    wamid, wa_id, "in",
                    ts=int(message.get("timestamp") or time.time()),
                    type=message.get("type"), body=body,
                    media_id=detail["media_id"], mime_type=detail["mime_type"],
                    filename=detail["filename"], choice_id=detail["choice_id"],
                    reply_to=(message.get("context") or {}).get("id"),
                    raw=message,
                )
                result["messages"] += 1

                # Honour STOP/UNSUBSCRIBE the moment it arrives.
                keyword = store_mod.classify_keyword(body if message.get("type") == "text" else "")
                if keyword == "opt_out":
                    store.opt_out(wa_id, f"sent {body.strip()[:40]!r}")
                    store.audit("opt_out", wa_id=wa_id, outcome="auto", detail="keyword")
                    result["opt_outs"].append(wa_id)
                elif keyword == "opt_in":
                    store.opt_in(wa_id)
                    store.audit("opt_in", wa_id=wa_id, outcome="auto", detail="keyword")
                if on_event:
                    on_event("message", {"from": wa_id, "name": names.get(wa_id, ""),
                                         "text": body, "id": wamid})

            for status in value.get("statuses") or []:
                errors = status.get("errors") or []
                store.record_status(
                    status.get("id") or "", status.get("status") or "",
                    ts=int(status.get("timestamp") or time.time()),
                    wa_id=status.get("recipient_id"),
                    error_code=(errors[0].get("code") if errors else None),
                    error_title=(errors[0].get("title") if errors else None),
                    raw=status,
                )
                result["statuses"] += 1
                if errors and on_event:
                    on_event("failed", {"to": status.get("recipient_id"),
                                        "error": errors[0].get("title", "")})

            for problem in value.get("errors") or []:
                result["errors"].append(problem.get("title") or str(problem))
    return result


# --- the HTTP receiver ------------------------------------------------------
class _Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = f"wamcp-webhook/{__version__}"

    def log_message(self, fmt, *args):
        if self.server.verbose:
            self.server.say(f"{self.address_string()} {fmt % args}")

    def _text(self, status, body="", content_type="text/plain; charset=utf-8"):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def _path_ok(self):
        if not self.server.path_prefix:
            return True
        return urllib.parse.urlparse(self.path).path.rstrip("/") in (
            self.server.path_prefix.rstrip("/"), "")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if parsed.path.rstrip("/") in ("/health", "/healthz"):
            self._text(200, json.dumps({"ok": True, "version": __version__}), "application/json")
            return
        # Meta's one-time verification handshake.
        mode = (query.get("hub.mode") or [""])[0]
        token = (query.get("hub.verify_token") or [""])[0]
        challenge = (query.get("hub.challenge") or [""])[0]
        if mode == "subscribe":
            if self.server.verify_token and token == self.server.verify_token:
                self.server.say("webhook verified by Meta")
                self._text(200, challenge)
            else:
                self.server.say("webhook verification REJECTED: verify token did not match")
                self._text(403, "verify token mismatch")
            return
        self._text(200, f"wamcp webhook {__version__} - POST here from Meta")

    def do_POST(self):
        if not self._path_ok():
            self._text(404, "not found")
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length > MAX_BODY:
            self._text(413, "payload too large")
            return
        raw = self.rfile.read(length) if length else b""

        signature = self.headers.get("X-Hub-Signature-256", "")
        if self.server.app_secret:
            if not verify_signature(self.server.app_secret, raw, signature):
                self.server.say("rejected a POST: bad X-Hub-Signature-256")
                self._text(401, "invalid signature")
                return
        elif not self.server.insecure:
            self.server.say("rejected a POST: no WHATSAPP_APP_SECRET set to verify it with "
                            "(start with --insecure to accept anyway)")
            self._text(401, "signature verification is not configured")
            return

        try:
            payload = json.loads(raw.decode("utf-8")) if raw else {}
        except (ValueError, UnicodeDecodeError) as e:
            self.server.say(f"rejected a POST: invalid JSON ({e})")
            self._text(400, "invalid JSON")
            return

        try:
            result = ingest(self.server.store, payload, on_event=self.server.on_event)
        except Exception as e:                                  # noqa: BLE001
            # Non-200 makes Meta retry, which is what we want if we could not store it.
            self.server.say(f"failed to store a payload: {type(e).__name__}: {e}")
            self._text(500, "could not store")
            return
        if result["messages"] or result["statuses"]:
            self.server.say(f"stored {result['messages']} message(s), "
                            f"{result['statuses']} status update(s)")
        self._text(200, "ok")


class _WebhookServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def make_server(config, host="0.0.0.0", port=8080, path="/webhook", insecure=False,
                verbose=False, say=print, on_event=None, store=None):
    httpd = _WebhookServer((host, port), _Handler)
    httpd.store = store or store_mod.Store(config.db_path)
    httpd.app_secret = config.app_secret
    httpd.verify_token = config.verify_token
    httpd.path_prefix = path
    httpd.insecure = insecure
    httpd.verbose = verbose
    httpd.say = say
    httpd.on_event = on_event
    return httpd
