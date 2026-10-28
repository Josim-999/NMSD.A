"""The MCP tool surface.

Tool names and descriptions are the API an AI actually programs against, so
each description says what the tool does, when WhatsApp allows it, and what to
reach for instead when it does not. Every result carries both a readable
summary (`content`) and the raw object (`structuredContent`).
"""
import json
import os
import secrets
import textwrap
import time

from . import __version__, config as config_mod, graph, messages, paste, phones, policy, store

TOOLS = []
_HANDLERS = {}


def tool(name, title, description, schema, annotations=None):
    """Register one MCP tool."""
    def wrap(fn):
        spec = {
            "name": name,
            "title": title,
            "description": textwrap.dedent(description).strip(),
            "inputSchema": schema,
        }
        if annotations:
            spec["annotations"] = dict(annotations, title=title)
        TOOLS.append(spec)
        _HANDLERS[name] = fn
        return fn
    return wrap


READ_ONLY = {"readOnlyHint": True, "destructiveHint": False, "openWorldHint": True}
SENDS = {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": True}

# --- shared schema pieces ---------------------------------------------------
TO = {
    "type": "string",
    "description": "Who to message: a phone number in international form such as "
                   "+966501234567, or the name of a contact already in the local "
                   "contact book (from a previous inbound message).",
}
REPLY_TO = {
    "type": "string",
    "description": "Optional WhatsApp message id (wamid...) to quote, which shows this "
                   "message as a reply to that one in the chat.",
}


def _schema(properties, required=()):
    return {"type": "object", "properties": properties, "required": list(required)}


class ToolError(RuntimeError):
    """A tool could not run. Carries a structured payload for the client."""

    def __init__(self, message, kind="invalid_request", hint="", extra=None):
        super().__init__(message)
        self.payload = {"error": message, "kind": kind}
        if hint:
            self.payload["what_to_do"] = hint
        if extra:
            self.payload.update(extra)


class Context:
    """Everything the tools need, built on first use."""

    def __init__(self, config=None, client=None, store_=None, now=time.time, config_path=None):
        # Where credentials are read from and written back to, so a server
        # started with --config keeps using that file.
        self.config_path = config_path
        self.config = config or config_mod.load(config_path)
        self._client = client
        self._store = store_
        self._policy = None
        self.now = now
        # A client or store handed in by a test or an embedder is kept across
        # a reload; lazily built ones are rebuilt against the new config.
        self._fixed_client = client is not None
        self._fixed_store = store_ is not None

    @property
    def store(self):
        if self._store is None:
            self._store = store.Store(self.config.db_path)
        return self._store

    @property
    def client(self):
        if self._client is None:
            self._client = graph.GraphClient(self.config)
        return self._client

    @property
    def policy(self):
        if self._policy is None:
            self._policy = policy.Policy(self.config, self.store, now=self.now)
        return self._policy

    def reload(self, config=None):
        """Pick up credentials saved during this session, without a restart."""
        self.config = config or config_mod.load(self.config_path)
        self._policy = None
        if not self._fixed_client:
            self._client = None
        if not self._fixed_store:
            self._store = None
        return self.config


# --- results ---------------------------------------------------------------
def ok(summary, data=None):
    result = {"content": [{"type": "text", "text": summary}], "isError": False}
    if data is not None:
        result["structuredContent"] = data
    return result


def _lines(*parts):
    return "\n".join(str(p) for p in parts if p)


# --- recipient resolution ---------------------------------------------------
def resolve_recipient(ctx, to):
    """Turn a number or a saved contact name into a wa_id."""
    if to is None or str(to).strip() == "":
        raise ToolError("`to` is required: a phone number or a known contact name")
    text = str(to).strip()
    if phones.looks_like_phone(text):
        return phones.normalise(text, ctx.config.default_country)
    matches = ctx.store.find_contacts(text, limit=6)
    named = [m for m in matches if (m.get("name") or "").strip()]
    if len(named) == 1:
        return named[0]["wa_id"]
    if len(named) > 1:
        raise ToolError(
            f"{text!r} matches {len(named)} contacts",
            kind="ambiguous_recipient",
            hint="Call again with the exact phone number.",
            extra={"candidates": [{"name": m["name"], "number": phones.pretty(m["wa_id"])}
                                  for m in named]},
        )
    try:
        return phones.normalise(text, ctx.config.default_country)
    except phones.PhoneError as e:
        raise ToolError(
            f"cannot work out who {text!r} is: {e}",
            kind="unknown_recipient",
            hint="Give the number in international form, e.g. +966501234567. "
                 "Names only work for contacts who have messaged this number before.",
        )


def _deliver(ctx, payload, action, requires_window=True, summary_body=None):
    """Policy gate, send, record, summarise. Every send goes through here."""
    decision = ctx.policy.check(payload, action, requires_window=requires_window)
    wa_id = decision.wa_id
    who = phones.pretty(wa_id)
    contact = ctx.store.contact(wa_id) or {}
    name = (contact.get("name") or "").strip()
    label = f"{name} ({who})" if name else who

    if decision.dry_run:
        return ok(
            _lines(f"DRY RUN - nothing was sent to {label}.",
                   "WAMCP_DRY_RUN is on. This is the exact request that would go to WhatsApp:",
                   json.dumps(payload, indent=2, ensure_ascii=False),
                   *[f"note: {w}" for w in decision.warnings]),
            {"dry_run": True, "to": wa_id, "payload": payload, "warnings": decision.warnings},
        )

    response = ctx.client.send(payload)
    sent = (response.get("messages") or [{}])[0]
    wamid = sent.get("id", "")
    ctx.store.record_message(
        wamid or f"local-{int(ctx.now())}", wa_id, "out",
        ts=ctx.now(), type=payload.get("type"),
        body=summary_body, status=sent.get("message_status") or "accepted", raw=payload,
    )
    ctx.store.audit(action, wa_id=wa_id, outcome="sent", detail=wamid)
    data = {"sent": True, "to": wa_id, "message_id": wamid,
            "type": payload.get("type"), "warnings": decision.warnings}
    return ok(
        _lines(f"Sent to {label}." + (f" ({summary_body[:80]})" if summary_body else ""),
               f"message_id: {wamid}",
               *[f"note: {w}" for w in decision.warnings]),
        data,
    )


# =========================================================================== #
#  Sending
# =========================================================================== #
@tool(
    "whatsapp_send_message", "Send a WhatsApp text message",
    """
    Send a plain text WhatsApp message.

    WhatsApp only delivers free-form text inside the 24-hour customer service
    window - the 24 hours after that person's last message to this number.
    Outside it this tool refuses and tells you so; use whatsapp_send_template
    instead, which is also the only way to start a new conversation.
    """,
    _schema({
        "to": TO,
        "text": {"type": "string", "description": "The message body, up to 4096 characters. "
                                                  "WhatsApp formatting works: *bold*, _italic_, ```code```."},
        "preview_url": {"type": "boolean", "description": "Show a link preview for the first URL in the text.",
                        "default": False},
        "reply_to": REPLY_TO,
    }, ["to", "text"]),
    SENDS,
)
def send_message(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("to"))
    body = args.get("text")
    payload = messages.text(wa_id, body, preview_url=args.get("preview_url", False),
                            reply_to=args.get("reply_to"))
    return _deliver(ctx, payload, "send_message", summary_body=body)


@tool(
    "whatsapp_send_template", "Send an approved WhatsApp template",
    """
    Send a pre-approved message template. This is the only way to open a new
    conversation or to write to someone whose last message is over 24 hours
    old, and it works whatever the window state.

    Use whatsapp_list_templates first to get exact names, language codes and
    how many {{1}}, {{2}}... placeholders the body takes; pass exactly that
    many values in body_params, in order.
    """,
    _schema({
        "to": TO,
        "template": {"type": "string", "description": "Exact template name, e.g. order_update."},
        "language": {"type": "string", "description": "Template language code, e.g. en_US, en, ar. "
                                                      "Must match the approved template.",
                     "default": "en_US"},
        "body_params": {"type": "array", "items": {"type": "string"},
                        "description": "Values for the body placeholders {{1}}, {{2}}... in order."},
        "header_params": {"type": "array", "items": {"type": "string"},
                          "description": "Values for placeholders in a text header."},
        "header_media": {
            "type": "object",
            "description": "For a template whose header is an image, video or document.",
            "properties": {
                "kind": {"type": "string", "enum": ["image", "video", "document"]},
                "link": {"type": "string", "description": "Public HTTPS url of the file."},
                "media_id": {"type": "string", "description": "Id from whatsapp_upload_media."},
                "filename": {"type": "string", "description": "Shown for documents."},
            },
        },
        "button_params": {"type": "array", "items": {"type": "string"},
                          "description": "Values for dynamic URL buttons, in button order."},
        "components": {"type": "array", "items": {"type": "object"},
                       "description": "Escape hatch: a complete Cloud API components array, "
                                      "used verbatim instead of the fields above."},
        "reply_to": REPLY_TO,
    }, ["to", "template"]),
    SENDS,
)
def send_template(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("to"))
    payload = messages.template(
        wa_id, args.get("template"), args.get("language") or "en_US",
        body_params=args.get("body_params"), header_params=args.get("header_params"),
        header_media=args.get("header_media"), button_params=args.get("button_params"),
        components=args.get("components"), reply_to=args.get("reply_to"),
    )
    body = f"template {args.get('template')}"
    # Templates are exempt from the 24-hour window - that is their purpose.
    return _deliver(ctx, payload, "send_template", requires_window=False, summary_body=body)


@tool(
    "whatsapp_send_media", "Send an image, video, audio file or document",
    """
    Send a file. Give either `link` (a public HTTPS url Meta can download) or
    `media_id` from whatsapp_upload_media - use upload for local files, which
    is also faster and avoids download failures.

    Limits: images and audio 5 MB, video and documents 16 MB, stickers 100 KB
    (512x512 WebP). Only image, video and document may carry a caption.
    """,
    _schema({
        "to": TO,
        "kind": {"type": "string", "enum": list(messages.MEDIA_KINDS),
                 "description": "What sort of file this is."},
        "link": {"type": "string", "description": "Public HTTPS url of the file."},
        "media_id": {"type": "string", "description": "Media id from whatsapp_upload_media."},
        "caption": {"type": "string", "description": "Caption, up to 1024 characters "
                                                     "(image, video and document only)."},
        "filename": {"type": "string", "description": "Name the recipient sees for a document."},
        "reply_to": REPLY_TO,
    }, ["to", "kind"]),
    SENDS,
)
def send_media(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("to"))
    payload = messages.media(
        wa_id, args.get("kind"), link=args.get("link"), media_id=args.get("media_id"),
        caption=args.get("caption"), filename=args.get("filename"), reply_to=args.get("reply_to"),
    )
    label = f"{args.get('kind')}: {args.get('caption') or args.get('filename') or args.get('link') or args.get('media_id')}"
    return _deliver(ctx, payload, "send_media", summary_body=label)


@tool(
    "whatsapp_send_buttons", "Send a message with up to 3 tappable reply buttons",
    """
    Send text with up to three reply buttons. When the person taps one, the
    reply arrives as an inbound message whose `choice_id` is that button's id,
    so it is a reliable way to collect a yes/no/later answer.

    Needs an open 24-hour window. Button titles are limited to 20 characters.
    For more than three options use whatsapp_send_list.
    """,
    _schema({
        "to": TO,
        "body": {"type": "string", "description": "The question or message, up to 1024 characters."},
        "buttons": {
            "type": "array", "maxItems": 3,
            "description": "1 to 3 buttons. Strings, or objects with `id` and `title`.",
            "items": {"anyOf": [
                {"type": "string"},
                _schema({"id": {"type": "string"}, "title": {"type": "string"}}, ["title"]),
            ]},
        },
        "header": {"type": "string", "description": "Optional bold heading, up to 60 characters."},
        "footer": {"type": "string", "description": "Optional small footer, up to 60 characters."},
        "reply_to": REPLY_TO,
    }, ["to", "body", "buttons"]),
    SENDS,
)
def send_buttons(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("to"))
    payload = messages.buttons(
        wa_id, args.get("body"), args.get("buttons"), header=args.get("header"),
        footer=args.get("footer"), reply_to=args.get("reply_to"),
    )
    return _deliver(ctx, payload, "send_buttons", summary_body=args.get("body"))


@tool(
    "whatsapp_send_list", "Send a tap-to-choose menu of up to 10 options",
    """
    Send a menu the person opens and picks from: up to 10 rows in total,
    optionally grouped into sections. The tapped row's id comes back as
    `choice_id` on the inbound message.

    Needs an open 24-hour window. Row titles are limited to 24 characters and
    descriptions to 72.
    """,
    _schema({
        "to": TO,
        "body": {"type": "string", "description": "Message shown above the menu button."},
        "button": {"type": "string", "description": "Text on the button that opens the menu, "
                                                    "up to 20 characters, e.g. 'View options'."},
        "sections": {
            "type": "array",
            "description": "Either sections with rows, or a flat list of rows "
                           "(which becomes one section).",
            "items": {"type": "object", "properties": {
                "title": {"type": "string"},
                "rows": {"type": "array", "items": {"type": "object", "properties": {
                    "id": {"type": "string"}, "title": {"type": "string"},
                    "description": {"type": "string"},
                }}},
            }},
        },
        "header": {"type": "string"},
        "footer": {"type": "string"},
        "reply_to": REPLY_TO,
    }, ["to", "body", "button", "sections"]),
    SENDS,
)
def send_list(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("to"))
    payload = messages.option_list(
        wa_id, args.get("body"), args.get("button"), args.get("sections"),
        header=args.get("header"), footer=args.get("footer"), reply_to=args.get("reply_to"),
    )
    return _deliver(ctx, payload, "send_list", summary_body=args.get("body"))


@tool(
    "whatsapp_send_link_button", "Send a message with a button that opens a link",
    """
    Send text with a single button that opens a URL - for payment pages,
    tracking links, booking forms. Needs an open 24-hour window.
    """,
    _schema({
        "to": TO,
        "body": {"type": "string"},
        "button_text": {"type": "string", "description": "Up to 20 characters, e.g. 'Track order'."},
        "url": {"type": "string", "description": "The link to open."},
        "header": {"type": "string"},
        "footer": {"type": "string"},
    }, ["to", "body", "button_text", "url"]),
    SENDS,
)
def send_link_button(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("to"))
    payload = messages.cta_url(
        wa_id, args.get("body"), args.get("button_text"), args.get("url"),
        header=args.get("header"), footer=args.get("footer"),
    )
    return _deliver(ctx, payload, "send_link_button", summary_body=args.get("body"))


@tool(
    "whatsapp_send_location", "Send a map pin",
    """
    Send a location the recipient can open in their maps app. Needs an open
    24-hour window.
    """,
    _schema({
        "to": TO,
        "latitude": {"type": "number"},
        "longitude": {"type": "number"},
        "name": {"type": "string", "description": "Place name shown on the pin."},
        "address": {"type": "string"},
        "reply_to": REPLY_TO,
    }, ["to", "latitude", "longitude"]),
    SENDS,
)
def send_location(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("to"))
    payload = messages.location(
        wa_id, args.get("latitude"), args.get("longitude"),
        name=args.get("name"), address=args.get("address"), reply_to=args.get("reply_to"),
    )
    return _deliver(ctx, payload, "send_location",
                    summary_body=args.get("name") or f"{args.get('latitude')},{args.get('longitude')}")


@tool(
    "whatsapp_send_contact_card", "Send someone's contact details as a card",
    """
    Send a contact card the recipient can save or tap to call. Needs an open
    24-hour window.
    """,
    _schema({
        "to": TO,
        "name": {"type": "string", "description": "Name on the card."},
        "phone": {"type": "string", "description": "That person's number, international form."},
        "organization": {"type": "string"},
        "email": {"type": "string"},
    }, ["to", "name", "phone"]),
    SENDS,
)
def send_contact_card(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("to"))
    payload = messages.contact_card(
        wa_id, args.get("name"), args.get("phone"),
        country=ctx.config.default_country,
        organization=args.get("organization"), email=args.get("email"),
    )
    return _deliver(ctx, payload, "send_contact_card", summary_body=f"contact {args.get('name')}")


@tool(
    "whatsapp_react", "React to a message with an emoji",
    """
    Put an emoji reaction on a message, or remove one by passing an empty
    emoji. Cheap way to acknowledge something without sending a message.
    """,
    _schema({
        "to": TO,
        "message_id": {"type": "string", "description": "wamid of the message to react to."},
        "emoji": {"type": "string", "description": "A single emoji, or \"\" to remove the reaction."},
    }, ["to", "message_id", "emoji"]),
    SENDS,
)
def react(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("to"))
    payload = messages.reaction(wa_id, args.get("message_id"), args.get("emoji"))
    return _deliver(ctx, payload, "react", requires_window=False,
                    summary_body=f"reaction {args.get('emoji')}")


@tool(
    "whatsapp_mark_read", "Mark a message read, optionally showing typing",
    """
    Show the blue ticks on an inbound message, and optionally the typing
    indicator - worth doing before composing a long reply so the person knows
    they were heard. The typing indicator lasts up to 25 seconds or until the
    next message.
    """,
    _schema({
        "message_id": {"type": "string", "description": "wamid of the inbound message."},
        "typing": {"type": "boolean", "description": "Also show 'typing...'.", "default": False},
    }, ["message_id"]),
    SENDS,
)
def mark_read(ctx, args):
    ctx.config.require_sending()
    if ctx.config.dry_run:
        return ok("DRY RUN - would mark the message read.", {"dry_run": True})
    ctx.client.mark_read(args.get("message_id"), typing=bool(args.get("typing")))
    return ok("Marked as read." + (" Typing indicator shown." if args.get("typing") else ""),
              {"message_id": args.get("message_id"), "read": True})


# =========================================================================== #
#  Inbox
# =========================================================================== #
@tool(
    "whatsapp_list_chats", "List recent WhatsApp conversations",
    """
    Recent conversations, most recently active first, with the last message,
    who spoke last, whether the 24-hour window is open and whether the contact
    has opted out.

    This reads the local inbox, which is filled by the webhook receiver
    (`python3 -m wamcp webhook`). With no webhook running, only messages this
    server sent are listed - WhatsApp has no endpoint to fetch history.
    """,
    _schema({
        "limit": {"type": "integer", "description": "How many conversations.", "default": 20},
        "unanswered_only": {"type": "boolean",
                            "description": "Only chats where they spoke last.", "default": False},
    }),
    READ_ONLY,
)
def list_chats(ctx, args):
    rows = ctx.store.conversations(limit=int(args.get("limit") or 20),
                                   unread_only=bool(args.get("unanswered_only")))
    out = []
    for row in rows:
        window = ctx.policy.window(row["wa_id"])
        out.append({
            "number": phones.pretty(row["wa_id"]),
            "name": row.get("name") or "",
            "last_message": row.get("last_body") or "",
            "last_from": "them" if row.get("last_direction") == "in" else "us",
            "last_message_at": _stamp(row.get("last_ts")),
            "messages": row.get("messages") or 0,
            "window": window["state"],
            "window_closes_in": _human(window["seconds_left"]),
            "opted_out": bool(row.get("opted_out")),
            "note": row.get("note") or "",
        })
    if not out:
        return ok(
            _lines("No conversations recorded yet.",
                   "Inbound messages arrive only by webhook. Start the receiver with",
                   "  python3 -m wamcp webhook --public-url https://your-host",
                   "and point the WhatsApp app's Webhooks field at it (field: messages)."),
            {"chats": [], "hint": "no webhook data yet"},
        )
    lines = [f"{len(out)} conversation(s):"]
    for chat in out:
        who = f"{chat['name']} {chat['number']}" if chat["name"] else chat["number"]
        flags = []
        if chat["opted_out"]:
            flags.append("OPTED OUT")
        if chat["window"] == "open":
            flags.append(f"window open, {chat['window_closes_in']} left")
        elif chat["window"] == "closed":
            flags.append("window closed - template only")
        lines.append(f"- {who} | {chat['last_message_at']} | {chat['last_from']}: "
                     f"{(chat['last_message'] or '')[:70]}" + (f" | {', '.join(flags)}" if flags else ""))
    return ok("\n".join(lines), {"chats": out})


@tool(
    "whatsapp_read_chat", "Read the messages of one conversation",
    """
    The message history this server holds for one contact, oldest first, with
    delivery status for outbound messages and the tapped button id for replies
    to interactive messages.
    """,
    _schema({
        "contact": TO,
        "limit": {"type": "integer", "description": "How many messages, newest kept.", "default": 30},
        "since_hours": {"type": "integer", "description": "Only messages from the last N hours."},
    }, ["contact"]),
    READ_ONLY,
)
def read_chat(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("contact"))
    since = None
    if args.get("since_hours"):
        since = ctx.now() - float(args["since_hours"]) * 3600
    rows = ctx.store.messages(wa_id, limit=int(args.get("limit") or 30), since_ts=since)
    contact = ctx.store.contact(wa_id) or {}
    window = ctx.policy.window(wa_id)
    out = [{
        "at": _stamp(r["ts"]),
        "from": "them" if r["direction"] == "in" else "us",
        "type": r["type"],
        "text": r["body"] or "",
        "message_id": r["wamid"],
        "status": r["status"] or "",
        "choice_id": r["choice_id"] or "",
        "media_id": r["media_id"] or "",
    } for r in rows]
    name = contact.get("name") or ""
    header = f"{name + ' ' if name else ''}{phones.pretty(wa_id)} - {len(out)} message(s)."
    if window["state"] == "open":
        header += f" 24-hour window open, {_human(window['seconds_left'])} left."
    elif window["state"] == "closed":
        header += " 24-hour window CLOSED - only templates will be delivered."
    else:
        header += " No inbound message on record, so the window state is unknown."
    if ctx.store.is_opted_out(wa_id):
        header += " This contact has OPTED OUT - do not message them."
    body = [f"[{m['at']}] {m['from']}: {m['text'] or '(' + (m['type'] or 'message') + ')'}"
            + (f" <{m['status']}>" if m["status"] else "")
            + (f" [tapped: {m['choice_id']}]" if m["choice_id"] else "")
            for m in out]
    return ok(_lines(header, *body) if body else header,
              {"contact": {"number": phones.pretty(wa_id), "name": name,
                           "note": contact.get("note") or ""},
               "window": window, "messages": out})


@tool(
    "whatsapp_search_messages", "Search the stored WhatsApp messages",
    """
    Plain substring search over message text across all conversations, newest
    first. Useful for finding an order number, an address or what was promised
    to someone.
    """,
    _schema({
        "query": {"type": "string", "description": "Text to look for."},
        "contact": {"type": "string", "description": "Optional: restrict to one contact."},
        "limit": {"type": "integer", "default": 30},
    }, ["query"]),
    READ_ONLY,
)
def search_messages(ctx, args):
    wa_id = resolve_recipient(ctx, args["contact"]) if args.get("contact") else None
    rows = ctx.store.search(args.get("query"), limit=int(args.get("limit") or 30), wa_id=wa_id)
    out = [{"at": _stamp(r["ts"]), "number": phones.pretty(r["wa_id"]),
            "from": "them" if r["direction"] == "in" else "us",
            "text": r["body"] or "", "message_id": r["wamid"]} for r in rows]
    summary = f"{len(out)} match(es) for {args.get('query')!r}."
    lines = [f"- [{m['at']}] {m['number']} {m['from']}: {m['text'][:100]}" for m in out]
    return ok(_lines(summary, *lines), {"matches": out})


@tool(
    "whatsapp_message_status", "Check whether a message was delivered or read",
    """
    The delivery history of one outbound message: accepted, sent, delivered,
    read, or failed with the reason. Statuses arrive by webhook, so this needs
    the receiver running.
    """,
    _schema({"message_id": {"type": "string", "description": "wamid of the message."}},
            ["message_id"]),
    READ_ONLY,
)
def message_status(ctx, args):
    row = ctx.store.message(args.get("message_id"))
    if not row:
        raise ToolError(
            f"no record of message {args.get('message_id')}",
            kind="not_found",
            hint="Only messages this server sent or received are stored. Delivery statuses "
                 "need the webhook receiver running.",
        )
    history = [f"{h['status']} at {_stamp(h['ts'])}"
               + (f" ({h['error_title']})" if h.get("error_title") else "")
               for h in row["status_history"]]
    return ok(
        _lines(f"{row['wamid']} to {phones.pretty(row['wa_id'])}: {row['status'] or 'unknown'}",
               *history),
        {"message_id": row["wamid"], "to": phones.pretty(row["wa_id"]),
         "direction": row["direction"], "status": row["status"],
         "error": row["error"], "history": row["status_history"]},
    )


@tool(
    "whatsapp_download_media", "Download a photo, voice note or document someone sent",
    """
    Fetch a media file by the `media_id` on an inbound message and save it
    locally, returning the path so it can be read, transcribed or attached.
    Media ids expire after 14 days.
    """,
    _schema({
        "media_id": {"type": "string"},
        "directory": {"type": "string", "description": "Where to save it. Defaults to WAMCP_MEDIA_DIR."},
        "filename": {"type": "string", "description": "Name to save as; extension is guessed otherwise."},
    }, ["media_id"]),
    {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": True},
)
def download_media(ctx, args):
    info = ctx.client.download_media(args.get("media_id"), args.get("directory"), args.get("filename"))
    ctx.store.audit("download_media", detail=info["path"])
    return ok(f"Saved {info['mime_type']} ({info['bytes']} bytes) to {info['path']}", info)


@tool(
    "whatsapp_upload_media", "Upload a local file to WhatsApp and get a media id",
    """
    Upload a file from this machine to Meta and get back a `media_id` to pass
    to whatsapp_send_media. Preferred over `link` for local files. The id is
    valid for 30 days and can be reused for many sends.
    """,
    _schema({
        "path": {"type": "string", "description": "Path to the file on this machine."},
        "mime_type": {"type": "string", "description": "Override the guessed MIME type."},
    }, ["path"]),
    {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": True},
)
def upload_media(ctx, args):
    path = os.path.expanduser(str(args.get("path") or ""))
    result = ctx.client.upload_media(path, args.get("mime_type"))
    media_id = result.get("id", "")
    ctx.store.audit("upload_media", detail=f"{path} -> {media_id}")
    return ok(f"Uploaded {path}. media_id: {media_id}", {"media_id": media_id, "path": path})


# =========================================================================== #
#  Contacts and consent
# =========================================================================== #
@tool(
    "whatsapp_find_contact", "Look up a contact by name or number",
    """
    Find someone in the local contact book - built from inbound messages -
    and see their number, when they last wrote, the 24-hour window state and
    their opt-out status. Use it before sending when you only know a name.
    """,
    _schema({
        "query": {"type": "string", "description": "Part of a name, or a phone number."},
        "limit": {"type": "integer", "default": 10},
    }, ["query"]),
    READ_ONLY,
)
def find_contact(ctx, args):
    rows = ctx.store.find_contacts(args.get("query"), limit=int(args.get("limit") or 10))
    out = []
    for row in rows:
        window = ctx.policy.window(row["wa_id"])
        out.append({
            "name": row.get("name") or "",
            "number": phones.pretty(row["wa_id"]),
            "last_heard_from": _stamp(row.get("last_inbound_ts")),
            "window": window["state"],
            "opted_out": bool(ctx.store.is_opted_out(row["wa_id"])),
            "note": row.get("note") or "",
        })
    if not out:
        return ok(f"No contact matches {args.get('query')!r}.", {"contacts": []})
    lines = [f"- {c['name'] or '(no name)'} {c['number']} | last heard {c['last_heard_from']} | "
             f"window {c['window']}" + (" | OPTED OUT" if c["opted_out"] else "") for c in out]
    return ok(_lines(f"{len(out)} contact(s):", *lines), {"contacts": out})


@tool(
    "whatsapp_set_contact_note", "Save a note against a contact",
    """
    Attach a short note to a contact (their order number, language, what they
    are waiting for) so it is in front of you next time that chat comes up.
    Stored locally, never sent to WhatsApp.
    """,
    _schema({"contact": TO, "note": {"type": "string"}}, ["contact", "note"]),
    {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
)
def set_contact_note(ctx, args):
    wa_id = resolve_recipient(ctx, args.get("contact"))
    ctx.store.set_note(wa_id, args.get("note"))
    return ok(f"Note saved for {phones.pretty(wa_id)}.",
              {"number": phones.pretty(wa_id), "note": args.get("note")})


@tool(
    "whatsapp_consent", "Check or change a contact's opt-out status",
    """
    Manage who may be messaged. `check` reports one contact, `list` shows
    everyone opted out, `opt_out` blocks further messages to a number and
    `opt_in` clears that block.

    The webhook receiver opts people out automatically when they send STOP or
    UNSUBSCRIBE, and every send is refused for an opted-out number. Only clear
    an opt-out when the person has actually asked to hear from you again.
    """,
    _schema({
        "action": {"type": "string", "enum": ["check", "list", "opt_out", "opt_in"],
                   "description": "What to do."},
        "contact": {"type": "string", "description": "Required for check, opt_out and opt_in."},
        "reason": {"type": "string", "description": "Why, recorded in the audit trail."},
    }, ["action"]),
    {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
)
def consent(ctx, args):
    action = (args.get("action") or "").strip()
    if action == "list":
        rows = ctx.store.opt_outs()
        lines = [f"- {phones.pretty(r['wa_id'])} since {_stamp(r['ts'])}"
                 + (f" ({r['reason']})" if r.get("reason") else "") for r in rows]
        return ok(_lines(f"{len(rows)} number(s) opted out:", *lines) if rows
                  else "Nobody has opted out.",
                  {"opt_outs": [{"number": phones.pretty(r["wa_id"]), "since": _stamp(r["ts"]),
                                 "reason": r.get("reason") or ""} for r in rows]})
    if action not in ("check", "opt_out", "opt_in"):
        raise ToolError("action must be one of check, list, opt_out, opt_in")
    wa_id = resolve_recipient(ctx, args.get("contact"))
    if action == "check":
        state = ctx.store.is_opted_out(wa_id)
        return ok(f"{phones.pretty(wa_id)} " + ("has OPTED OUT." if state else "may be messaged."),
                  {"number": phones.pretty(wa_id), "opted_out": bool(state),
                   "since": _stamp(state["ts"]) if state else None,
                   "reason": (state or {}).get("reason") or ""})
    if action == "opt_out":
        ctx.store.opt_out(wa_id, args.get("reason") or "set by assistant")
        ctx.store.audit("opt_out", wa_id=wa_id, detail=args.get("reason") or "")
        return ok(f"{phones.pretty(wa_id)} will no longer be messaged.",
                  {"number": phones.pretty(wa_id), "opted_out": True})
    ctx.store.opt_in(wa_id)
    ctx.store.audit("opt_in", wa_id=wa_id, detail=args.get("reason") or "")
    return ok(f"Opt-out cleared for {phones.pretty(wa_id)}.",
              {"number": phones.pretty(wa_id), "opted_out": False})


# =========================================================================== #
#  Templates
# =========================================================================== #
@tool(
    "whatsapp_list_templates", "List the approved message templates",
    """
    The templates on this WhatsApp Business Account with their status, language
    and placeholder count. Only APPROVED templates can be sent. Read this
    before whatsapp_send_template so the name, language and parameter count are
    right first time.
    """,
    _schema({
        "name": {"type": "string", "description": "Filter by exact template name."},
        "limit": {"type": "integer", "default": 50},
        "approved_only": {"type": "boolean", "default": False},
    }),
    READ_ONLY,
)
def list_templates(ctx, args):
    response = ctx.client.list_templates(limit=int(args.get("limit") or 50), name=args.get("name"))
    items = response.get("data") or []
    out = []
    for item in items:
        if args.get("approved_only") and item.get("status") != "APPROVED":
            continue
        body = ""
        placeholders = 0
        for component in item.get("components") or []:
            if (component.get("type") or "").upper() == "BODY":
                body = component.get("text") or ""
                placeholders = len({p for p in _placeholders(body)})
        out.append({
            "name": item.get("name"),
            "language": item.get("language"),
            "status": item.get("status"),
            "category": item.get("category"),
            "body": body,
            "body_params": placeholders,
            "rejected_reason": item.get("rejected_reason") or "",
        })
    if not out:
        return ok(
            _lines("No templates found.",
                   "Create one with whatsapp_create_template, or in WhatsApp Manager -> "
                   "Message templates. Approval usually takes minutes."),
            {"templates": []},
        )
    lines = [f"- {t['name']} [{t['language']}] {t['status']} ({t['category']}) "
             f"{t['body_params']} parameter(s): {(t['body'] or '')[:80]}" for t in out]
    return ok(_lines(f"{len(out)} template(s):", *lines), {"templates": out})


@tool(
    "whatsapp_create_template", "Submit a new message template for approval",
    """
    Create a template and submit it to Meta for review. Use UTILITY for
    transactional messages (order updates, appointments, receipts),
    AUTHENTICATION for one-time codes and MARKETING for promotions.

    Write placeholders as {{1}}, {{2}} in the body and give one example value
    for each - Meta rejects templates without examples. Names must be
    lowercase with underscores. Approval is usually quick but not instant, so
    the template cannot be sent until its status is APPROVED.
    """,
    _schema({
        "name": {"type": "string", "description": "lowercase_with_underscores, e.g. order_ready."},
        "category": {"type": "string", "enum": ["UTILITY", "MARKETING", "AUTHENTICATION"]},
        "language": {"type": "string", "default": "en_US"},
        "body": {"type": "string", "description": "Body text, with {{1}}, {{2}}... placeholders."},
        "body_examples": {"type": "array", "items": {"type": "string"},
                          "description": "One example value per placeholder, in order. Required "
                                         "whenever the body has placeholders."},
        "header": {"type": "string", "description": "Optional header text (no placeholders here)."},
        "footer": {"type": "string", "description": "Optional footer, up to 60 characters."},
        "buttons": {
            "type": "array",
            "description": "Optional buttons: {'type':'QUICK_REPLY','text':...} or "
                           "{'type':'URL','text':...,'url':...}.",
            "items": {"type": "object"},
        },
    }, ["name", "category", "body"]),
    {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": True},
)
def create_template(ctx, args):
    name = str(args.get("name") or "").strip()
    if not name.replace("_", "").isalnum() or name != name.lower():
        raise ToolError(
            f"template name {name!r} is not valid",
            hint="Use lowercase letters, digits and underscores only, e.g. order_ready.",
        )
    body = str(args.get("body") or "").strip()
    if not body:
        raise ToolError("a template needs body text")
    wanted = _placeholders(body)
    examples = [str(v) for v in (args.get("body_examples") or [])]
    if len(wanted) != len(examples):
        raise ToolError(
            f"the body has {len(wanted)} placeholder(s) but {len(examples)} example(s) were given",
            hint="Give exactly one example value per {{n}} placeholder; Meta rejects "
                 "templates whose examples do not match.",
        )
    components = []
    if args.get("header"):
        components.append({"type": "HEADER", "format": "TEXT", "text": str(args["header"])})
    body_component = {"type": "BODY", "text": body}
    if examples:
        body_component["example"] = {"body_text": [examples]}
    components.append(body_component)
    if args.get("footer"):
        components.append({"type": "FOOTER", "text": str(args["footer"])})
    if args.get("buttons"):
        components.append({"type": "BUTTONS", "buttons": args["buttons"]})

    payload = {
        "name": name,
        "category": str(args.get("category") or "UTILITY").upper(),
        "language": str(args.get("language") or "en_US"),
        "components": components,
    }
    response = ctx.client.create_template(payload)
    ctx.store.audit("create_template", detail=name)
    return ok(
        _lines(f"Submitted template {name!r} ({payload['category']}, {payload['language']}).",
               f"status: {response.get('status', 'PENDING')} - id {response.get('id', '')}",
               "It can be sent once Meta marks it APPROVED; check with whatsapp_list_templates."),
        {"name": name, "id": response.get("id"), "status": response.get("status"),
         "submitted": payload},
    )


# =========================================================================== #
#  Account and setup
# =========================================================================== #
SETUP_STEPS = """
To connect your WhatsApp Business number, open this page in your browser:

    https://developers.facebook.com/apps

Then:  your app  ->  WhatsApp  ->  API Setup

(No app yet? Create App -> Business -> add the WhatsApp product. Free, ~2 min.)

On that page there is a grey box of sample code beginning with `curl`.
Copy the whole box and paste it to me - it already contains both values I
need. Pasting just the access token works too.
""".strip()


@tool(
    "whatsapp_setup", "Connect a WhatsApp Business number",
    """
    Set up or repair this server's WhatsApp credentials, so a user can connect
    their number by talking to you instead of using a terminal.

    Call it with no arguments to get the instructions to show them: which page
    to open and what to copy. When they paste, call it again with that text as
    `paste` - it accepts the `curl` sample from Meta's API Setup page (which
    carries both the access token and the phone number ID), a .env file, or a
    bare access token, and then asks Meta which number and business account
    that token can reach, so nothing else usually has to be typed.

    Credentials are written to ~/.wamcp/config.json, readable only by this
    user, and take effect immediately - no restart.

    Note for the user: anything they paste into the chat is visible in the
    conversation. If they would rather their token never appear there, tell
    them to run `python3 -m wamcp setup` in a terminal instead; it does
    exactly the same thing.
    """,
    _schema({
        "paste": {"type": "string",
                  "description": "What the user pasted: the curl box from Meta's API Setup "
                                 "page, a .env file, a JSON config, or just the access token."},
        "phone_number_id": {"type": "string",
                            "description": "Which number to send from, when a previous call "
                                           "reported that the account has several."},
        "business_account_id": {"type": "string",
                               "description": "WhatsApp Business Account id, if it could not "
                                              "be discovered and the user has it."},
    }),
    {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True, "openWorldHint": True},
)
def setup(ctx, args):
    blob = str(args.get("paste") or "")
    found = paste.parse(blob) if blob.strip() else {}
    for field in ("phone_number_id", "business_account_id"):
        if args.get(field):
            found[field] = str(args[field]).strip()

    if not found and not blob.strip():
        return ok(SETUP_STEPS, {"next_step": "ask the user to paste it",
                                "configured": not ctx.config.missing_for_sending()})
    if blob.strip() and not found:
        raise ToolError(
            "I could not find any WhatsApp credentials in that",
            kind="nothing_found",
            hint="Ask for the whole grey `curl` box from the WhatsApp -> API Setup page, "
                 "or the access token on its own.",
        )

    values = ctx.config.as_dict()
    values.update({k: v for k, v in found.items() if v})
    if not values.get("access_token"):
        raise ToolError(
            "found " + paste.describe(found) + ", but no access token",
            kind="need_token",
            hint="Ask the user for the access token from the same API Setup page "
                 "(a long string beginning EAA...).",
        )

    config = config_mod.Config(**values)
    # Ask Meta what this token reaches rather than asking the user for more.
    assets = graph.GraphClient(config).discover()
    numbers = assets["phone_numbers"]
    chosen = ""
    if values.get("phone_number_id"):
        chosen = values["phone_number_id"]
    elif len(numbers) == 1:
        chosen = numbers[0]["id"]
    elif len(numbers) > 1:
        return ok(
            _lines("This account has several WhatsApp numbers. Ask the user which one to send"
                   " from, then call whatsapp_setup again with its phone_number_id:",
                   *[f"  {n['id']}  {n.get('display_phone_number', '')} "
                     f"{n.get('verified_name', '')}".rstrip() for n in numbers]),
            {"next_step": "choose a phone_number_id", "phone_numbers": numbers},
        )
    values["phone_number_id"] = chosen
    if assets["business_account_id"] and not values.get("business_account_id"):
        values["business_account_id"] = assets["business_account_id"]
    # Any string works as a webhook verify token, so generate rather than ask.
    values["verify_token"] = values.get("verify_token") or secrets.token_urlsafe(18)

    config = config_mod.Config(**values)
    path = config_mod.save(config, ctx.config_path)
    ctx.reload()
    report = diagnose(ctx)

    lines = [f"Saved your WhatsApp credentials to {path} (only you can read it).",
             f"Found: {paste.describe(found)}."]
    if numbers:
        label = next((f"{n.get('verified_name', '')} {n.get('display_phone_number', '')}".strip()
                      for n in numbers if n["id"] == chosen), "")
        if label:
            lines.append(f"Sending from: {label}")
    if assets["business_account_id"]:
        lines.append(f"Business account: {assets['business_account_name'] or assets['business_account_id']}")
    lines.append("")
    lines.append(f"Status: {report['summary']}")
    for check in report["checks"]:
        if check["state"] != "ok":
            lines.append(f"  [{check['state']}] {check['name']}: {check['detail']}")
            if check.get("fix"):
                lines.append(f"          -> {check['fix']}")
    if report["ready"]:
        lines += [
            "",
            "It works. One WhatsApp rule to tell the user: you cannot send them plain",
            "text until they have written to the business, so the first message has to",
            "be a template. Every new account has `hello_world`:",
            "  whatsapp_send_template(to=\"+<their number>\", template=\"hello_world\")",
            "They must also add their own number under `To` on the API Setup page first",
            "if the number is still unverified. Once they reply, plain text works for 24h.",
        ]
    return ok(_lines(*lines), {"saved": True, "config_path": path, "ready": report["ready"],
                               "report": report, "discovery_notes": assets.get("notes", [])})


@tool(
    "whatsapp_account_info", "Show this WhatsApp Business number and profile",
    """
    The connected number: display number, verified business name, quality
    rating and messaging limit, plus the public business profile customers see.
    """,
    _schema({}),
    READ_ONLY,
)
def account_info(ctx, args):
    number = ctx.client.phone_number()
    profile = {}
    try:
        data = ctx.client.business_profile()
        profile = (data.get("data") or [{}])[0]
    except graph.WhatsAppError:
        profile = {}
    info = {
        "display_phone_number": number.get("display_phone_number"),
        "verified_name": number.get("verified_name"),
        "quality_rating": number.get("quality_rating"),
        "platform": number.get("platform_type"),
        "code_verification": number.get("code_verification_status"),
        "throughput": (number.get("throughput") or {}).get("level"),
        "phone_number_id": ctx.config.phone_number_id,
        "business_account_id": ctx.config.business_account_id,
        "profile": {k: profile.get(k) for k in
                    ("about", "description", "email", "address", "vertical", "websites")},
    }
    return ok(
        _lines(f"{info['verified_name'] or 'WhatsApp Business'} on {info['display_phone_number']}",
               f"quality: {info['quality_rating']} | throughput: {info['throughput']} | "
               f"verification: {info['code_verification']}",
               f"about: {info['profile'].get('about') or '(not set)'}"),
        info,
    )


@tool(
    "whatsapp_update_profile", "Update the public business profile",
    """
    Change what customers see on the business profile: the about line,
    description, email, address, website and category.
    """,
    _schema({
        "about": {"type": "string", "description": "Short status line, up to 139 characters."},
        "description": {"type": "string", "description": "Up to 512 characters."},
        "email": {"type": "string"},
        "address": {"type": "string"},
        "vertical": {"type": "string", "description": "Business category, e.g. RETAIL, HEALTH, "
                                                      "EDU, FINANCE, PROF_SERVICES, OTHER."},
        "websites": {"type": "array", "items": {"type": "string"}, "description": "Up to 2 urls."},
    }),
    {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True, "openWorldHint": True},
)
def update_profile(ctx, args):
    fields = {k: v for k, v in args.items()
              if k in ("about", "description", "email", "address", "vertical", "websites")
              and v not in (None, "")}
    if not fields:
        raise ToolError("give at least one profile field to change")
    ctx.client.update_business_profile(fields)
    ctx.store.audit("update_profile", detail=",".join(sorted(fields)))
    return ok("Business profile updated: " + ", ".join(sorted(fields)), {"updated": fields})


@tool(
    "whatsapp_status", "Check that the WhatsApp connection is working",
    """
    Self-check for this server: which settings are present, whether the token
    and phone number id actually work, whether inbound messages are arriving,
    and what is still missing - with the exact next step for each problem.

    Run this first when anything WhatsApp-related fails, and when a user asks
    how to connect their WhatsApp. It is safe: it never sends a message.
    """,
    _schema({}),
    READ_ONLY,
)
def status(ctx, args):
    report = diagnose(ctx)
    lines = [f"WhatsApp Business MCP {__version__} - {report['summary']}"]
    for check in report["checks"]:
        mark = {"ok": "ok  ", "warn": "warn", "fail": "FAIL"}[check["state"]]
        lines.append(f"[{mark}] {check['name']}: {check['detail']}")
        if check.get("fix"):
            lines.append(f"       -> {check['fix']}")
    return ok("\n".join(lines), report)


def diagnose(ctx):
    """The checks behind whatsapp_status and `python3 -m wamcp doctor`."""
    cfg = ctx.config
    checks = []

    def add(name, state, detail, fix=""):
        checks.append({"name": name, "state": state, "detail": detail, "fix": fix})

    missing = cfg.missing_for_sending()
    if missing:
        add("credentials", "fail", "missing " + ", ".join(missing),
            "Run `python3 -m wamcp setup`, or set those variables in the MCP client config. "
            "Get them from developers.facebook.com -> your app -> WhatsApp -> API Setup.")
    else:
        add("credentials", "ok", f"token {config_mod.mask(cfg.access_token)}, "
                                 f"phone_number_id {cfg.phone_number_id}")

    if cfg.business_account_id:
        add("business account id", "ok", cfg.business_account_id)
    else:
        add("business account id", "warn", "not set - template tools are unavailable",
            f"Set {config_mod.ENV['business_account_id']} to the WABA id from WhatsApp Manager.")

    if not missing:
        try:
            number = ctx.client.phone_number()
            add("cloud api", "ok",
                f"{number.get('verified_name') or 'number'} on {number.get('display_phone_number')} "
                f"(quality {number.get('quality_rating') or 'n/a'})")
        except graph.WhatsAppError as e:
            add("cloud api", "fail", str(e.message), e.hint or "Check the token and phone number id.")
        except Exception as e:                                  # noqa: BLE001 - report, never crash
            add("cloud api", "fail", f"{type(e).__name__}: {e}", "")

    try:
        stats = ctx.store.stats()
        detail = (f"{stats['messages_in']} inbound, {stats['messages_out']} outbound, "
                  f"{stats['contacts']} contact(s) at {stats['db_path']}")
        if stats["messages_in"] == 0:
            add("inbox", "warn", detail + " - no inbound message has arrived yet",
                "Inbound messages only come by webhook. Run "
                "`python3 -m wamcp webhook --public-url https://your-host` and set that url "
                "(field: messages) in the Meta app's WhatsApp -> Configuration page.")
        else:
            add("inbox", "ok", detail + f", last inbound {_stamp(stats['last_inbound_ts'])}")
    except Exception as e:                                      # noqa: BLE001
        add("inbox", "fail", f"cannot open {cfg.db_path}: {e}", "Check WAMCP_DB is writable.")

    if cfg.app_secret:
        add("webhook signature", "ok", "app secret present; payloads will be verified")
    else:
        add("webhook signature", "warn", "no app secret - webhook payloads cannot be verified",
            f"Set {config_mod.ENV['app_secret']} (Meta app -> Settings -> Basic -> App secret) "
            "so forged webhook calls are rejected.")

    guards = []
    if cfg.dry_run:
        guards.append("DRY RUN: nothing is actually sent")
    if cfg.allowlist:
        guards.append(f"allow-list of {len(cfg.allowlist)} number(s)")
    if cfg.max_sends_per_hour:
        guards.append(f"max {cfg.max_sends_per_hour} sends/hour")
    if not cfg.allow_outside_window:
        guards.append("24-hour window enforced")
    add("guardrails", "ok", "; ".join(guards) or "none configured")

    states = [c["state"] for c in checks]
    if "fail" in states:
        summary = "not ready: see the FAIL lines below"
    elif "warn" in states:
        summary = "ready to send, with warnings"
    else:
        summary = "ready"
    return {"summary": summary, "ready": "fail" not in states, "checks": checks,
            "version": __version__, "api_version": cfg.api_version,
            "settings": cfg.redacted()}


# --- small helpers ----------------------------------------------------------
def _placeholders(text):
    """{{1}}, {{2}} ... found in a template body."""
    out, rest = [], str(text or "")
    while "{{" in rest:
        start = rest.index("{{")
        end = rest.find("}}", start)
        if end == -1:
            break
        token = rest[start + 2:end].strip()
        if token.isdigit():
            out.append(token)
        rest = rest[end + 2:]
    return out


def _stamp(ts):
    if not ts:
        return ""
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(int(ts)))


def _human(seconds):
    if seconds is None:
        return ""
    return policy._ago(seconds)


# --- dispatch ---------------------------------------------------------------
def call(ctx, name, arguments):
    """Run a tool by name. Errors come back as tool results, not exceptions,
    so the model can read what went wrong and fix its next call."""
    handler = _HANDLERS.get(name)
    if handler is None:
        raise KeyError(name)
    args = arguments if isinstance(arguments, dict) else {}
    try:
        return handler(ctx, args)
    except ToolError as e:
        return _error(e.payload)
    except (messages.MessageError, phones.PhoneError) as e:
        return _error({"error": str(e), "kind": "invalid_request"})
    except policy.PolicyError as e:
        return _error(e.to_dict())
    except config_mod.ConfigError as e:
        return _error({"error": str(e), "kind": "not_configured",
                       "what_to_do": "Run `python3 -m wamcp setup` or `python3 -m wamcp doctor`."})
    except graph.WhatsAppError as e:
        return _error(e.to_dict())
    except Exception as e:                                      # noqa: BLE001
        return _error({"error": f"{type(e).__name__}: {e}", "kind": "server_error"})


def _error(payload):
    text = payload.get("error", "failed")
    if payload.get("details"):
        text += f"\ndetails: {payload['details']}"
    if payload.get("candidates"):
        text += "\ncandidates: " + ", ".join(
            f"{c.get('name')} {c.get('number')}" for c in payload["candidates"])
    if payload.get("what_to_do"):
        text += f"\nwhat to do: {payload['what_to_do']}"
    return {"content": [{"type": "text", "text": text}], "isError": True,
            "structuredContent": payload}
