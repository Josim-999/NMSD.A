"""Request builders for every WhatsApp message type.

Each function returns the JSON body for POST /{phone_number_id}/messages.
Limits that WhatsApp enforces server-side are checked here too, so a model
gets a precise, immediate error ("a reply button title is at most 20
characters, 'Yes please go ahead' is 19") instead of a Graph API code.
"""
from . import phones

TEXT_MAX = 4096
CAPTION_MAX = 1024
BODY_MAX = 1024
HEADER_TEXT_MAX = 60
FOOTER_MAX = 60
BUTTON_TITLE_MAX = 20
BUTTON_ID_MAX = 256
LIST_BUTTON_MAX = 20
ROW_TITLE_MAX = 24
ROW_DESC_MAX = 72
MAX_REPLY_BUTTONS = 3
MAX_LIST_SECTIONS = 10
MAX_LIST_ROWS = 10

MEDIA_KINDS = ("image", "video", "audio", "document", "sticker")
# Media types that accept a caption.
CAPTIONABLE = ("image", "video", "document")


class MessageError(ValueError):
    """The message cannot be built as described."""


def _limit(value, maximum, what):
    text = "" if value is None else str(value)
    if len(text) > maximum:
        raise MessageError(f"{what} is at most {maximum} characters, got {len(text)}")
    return text


def _required(value, what):
    if value is None or str(value).strip() == "":
        raise MessageError(f"{what} is required")
    return value


def envelope(to, country="", reply_to=None):
    """The fields every outbound message shares."""
    body = {
        "messaging_product": "whatsapp",
        "recipient_type": "individual",
        "to": phones.normalise(to, country),
    }
    if reply_to:
        # Quotes the given message, exactly like tapping reply in the app.
        body["context"] = {"message_id": str(reply_to)}
    return body


# --- plain text ------------------------------------------------------------
def text(to, body, preview_url=False, country="", reply_to=None):
    _required(body, "message text")
    out = envelope(to, country, reply_to)
    out["type"] = "text"
    out["text"] = {"body": _limit(body, TEXT_MAX, "message text"), "preview_url": bool(preview_url)}
    return out


# --- templates -------------------------------------------------------------
def _template_params(values, kind="text"):
    params = []
    for value in values or []:
        if isinstance(value, dict):
            params.append(value)          # already a full parameter object
        else:
            params.append({"type": kind, "text": str(value)})
    return params


def template(to, name, language="en_US", body_params=None, header_params=None,
             header_media=None, button_params=None, components=None,
             country="", reply_to=None):
    """A pre-approved template message.

    This is the only way to start a conversation, or to write to someone whose
    last message is more than 24 hours old.

    body_params / header_params  values for the {{1}}, {{2}}... placeholders
    header_media   {"kind": "image"|"video"|"document", "link": ...} for a media header
    button_params  values for dynamic URL buttons, in button order
    components     a complete components array, used as-is when given
    """
    _required(name, "template name")
    out = envelope(to, country, reply_to)
    spec = {"name": str(name).strip(), "language": {"code": str(language or "en_US").strip()}}

    if components is not None:
        spec["components"] = components
    else:
        built = []
        if header_media:
            kind = str(header_media.get("kind") or header_media.get("type") or "image").lower()
            if kind not in MEDIA_KINDS:
                raise MessageError(f"header media kind must be one of {', '.join(MEDIA_KINDS)}")
            target = {}
            if header_media.get("link"):
                target["link"] = header_media["link"]
            elif header_media.get("media_id") or header_media.get("id"):
                target["id"] = str(header_media.get("media_id") or header_media["id"])
            else:
                raise MessageError("header media needs a `link` or a `media_id`")
            if kind == "document" and header_media.get("filename"):
                target["filename"] = header_media["filename"]
            built.append({"type": "header", "parameters": [{"type": kind, kind: target}]})
        elif header_params:
            built.append({"type": "header", "parameters": _template_params(header_params)})
        if body_params:
            built.append({"type": "body", "parameters": _template_params(body_params)})
        for index, value in enumerate(button_params or []):
            built.append({
                "type": "button", "sub_type": "url", "index": str(index),
                "parameters": [{"type": "text", "text": str(value)}],
            })
        if built:
            spec["components"] = built

    out["type"] = "template"
    out["template"] = spec
    return out


# --- media -----------------------------------------------------------------
def media(to, kind, link=None, media_id=None, caption=None, filename=None,
          country="", reply_to=None):
    kind = str(kind or "").lower().strip()
    if kind not in MEDIA_KINDS:
        raise MessageError(f"media kind must be one of {', '.join(MEDIA_KINDS)}, got {kind!r}")
    if not link and not media_id:
        raise MessageError(
            "give either `link` (a public HTTPS url Meta can download) or `media_id` "
            "(from whatsapp_upload_media)"
        )
    if link and media_id:
        raise MessageError("give `link` or `media_id`, not both")
    target = {"link": link} if link else {"id": str(media_id)}
    if caption:
        if kind not in CAPTIONABLE:
            raise MessageError(f"{kind} messages cannot carry a caption")
        target["caption"] = _limit(caption, CAPTION_MAX, "caption")
    if filename:
        if kind != "document":
            raise MessageError("`filename` only applies to documents")
        target["filename"] = str(filename)
    out = envelope(to, country, reply_to)
    out["type"] = kind
    out[kind] = target
    return out


# --- location --------------------------------------------------------------
def location(to, latitude, longitude, name=None, address=None, country="", reply_to=None):
    try:
        lat, lon = float(latitude), float(longitude)
    except (TypeError, ValueError):
        raise MessageError("latitude and longitude must be numbers")
    if not -90 <= lat <= 90 or not -180 <= lon <= 180:
        raise MessageError("latitude must be between -90 and 90 and longitude between -180 and 180")
    out = envelope(to, country, reply_to)
    out["type"] = "location"
    out["location"] = {"latitude": lat, "longitude": lon}
    if name:
        out["location"]["name"] = str(name)
    if address:
        out["location"]["address"] = str(address)
    return out


# --- reaction --------------------------------------------------------------
def reaction(to, message_id, emoji, country=""):
    _required(message_id, "message_id of the message to react to")
    out = envelope(to, country)
    out["type"] = "reaction"
    # An empty emoji removes a previous reaction - that is the documented way.
    out["reaction"] = {"message_id": str(message_id), "emoji": "" if emoji is None else str(emoji)}
    return out


# --- contact card ----------------------------------------------------------
def contact_card(to, name, phone, country="", organization=None, email=None, reply_to=None):
    _required(name, "contact name")
    _required(phone, "contact phone")
    number = phones.pretty(phones.normalise(phone, country))
    card = {
        "name": {"formatted_name": str(name), "first_name": str(name).split()[0]},
        "phones": [{"phone": number, "wa_id": number.lstrip("+"), "type": "WORK"}],
    }
    if organization:
        card["org"] = {"company": str(organization)}
    if email:
        card["emails"] = [{"email": str(email), "type": "WORK"}]
    out = envelope(to, country, reply_to)
    out["type"] = "contacts"
    out["contacts"] = [card]
    return out


# --- interactive -----------------------------------------------------------
def _interactive_shell(body, header=None, footer=None):
    shell = {"body": {"text": _limit(_required(body, "body text"), BODY_MAX, "body text")}}
    if header:
        if isinstance(header, dict):
            shell["header"] = header
        else:
            shell["header"] = {"type": "text", "text": _limit(header, HEADER_TEXT_MAX, "header text")}
    if footer:
        shell["footer"] = {"text": _limit(footer, FOOTER_MAX, "footer text")}
    return shell


def buttons(to, body, choices, header=None, footer=None, country="", reply_to=None):
    """Up to three tappable reply buttons.

    `choices` is a list of strings, or of {"id": ..., "title": ...}.
    """
    if not choices:
        raise MessageError("give between 1 and 3 buttons")
    if len(choices) > MAX_REPLY_BUTTONS:
        raise MessageError(
            f"WhatsApp allows at most {MAX_REPLY_BUTTONS} reply buttons, got {len(choices)}. "
            "Use whatsapp_send_list for more options."
        )
    built, seen = [], set()
    for index, choice in enumerate(choices):
        if isinstance(choice, dict):
            title = _required(choice.get("title"), f"title of button {index + 1}")
            ident = str(choice.get("id") or f"btn_{index + 1}")
        else:
            title, ident = choice, f"btn_{index + 1}"
        title = _limit(title, BUTTON_TITLE_MAX, f"button title {title!r}")
        ident = _limit(ident, BUTTON_ID_MAX, "button id")
        if ident in seen:
            raise MessageError(f"button ids must be unique, {ident!r} is repeated")
        seen.add(ident)
        built.append({"type": "reply", "reply": {"id": ident, "title": title}})

    shell = _interactive_shell(body, header, footer)
    shell["type"] = "button"
    shell["action"] = {"buttons": built}
    out = envelope(to, country, reply_to)
    out["type"] = "interactive"
    out["interactive"] = shell
    return out


def option_list(to, body, button, sections, header=None, footer=None, country="", reply_to=None):
    """A tap-to-open menu: up to 10 rows, grouped in sections.

    `sections` is [{"title": ..., "rows": [{"id","title","description"}, ...]}]
    or a plain list of row dicts/strings, which becomes one unnamed section.
    """
    button = _limit(_required(button, "list button text"), LIST_BUTTON_MAX, "list button text")
    if not sections:
        raise MessageError("give at least one row")
    if isinstance(sections, dict):
        sections = [sections]
    if sections and not isinstance(sections[0], dict) or (sections and "rows" not in sections[0]):
        sections = [{"title": "Options", "rows": list(sections)}]
    if len(sections) > MAX_LIST_SECTIONS:
        raise MessageError(f"at most {MAX_LIST_SECTIONS} sections, got {len(sections)}")

    built, total, seen = [], 0, set()
    for s_index, section in enumerate(sections):
        rows = []
        for r_index, row in enumerate(section.get("rows") or []):
            if isinstance(row, dict):
                title = _required(row.get("title"), f"title of row {r_index + 1}")
                ident = str(row.get("id") or f"row_{s_index + 1}_{r_index + 1}")
                description = row.get("description")
            else:
                title, ident, description = row, f"row_{s_index + 1}_{r_index + 1}", None
            if ident in seen:
                raise MessageError(f"row ids must be unique, {ident!r} is repeated")
            seen.add(ident)
            entry = {"id": _limit(ident, BUTTON_ID_MAX, "row id"),
                     "title": _limit(title, ROW_TITLE_MAX, f"row title {title!r}")}
            if description:
                entry["description"] = _limit(description, ROW_DESC_MAX, "row description")
            rows.append(entry)
            total += 1
        if not rows:
            raise MessageError(f"section {s_index + 1} has no rows")
        built.append({"title": _limit(section.get("title") or f"Section {s_index + 1}", ROW_TITLE_MAX,
                                      "section title"), "rows": rows})
    if total > MAX_LIST_ROWS:
        raise MessageError(f"a list holds at most {MAX_LIST_ROWS} rows in total, got {total}")

    shell = _interactive_shell(body, header, footer)
    shell["type"] = "list"
    shell["action"] = {"button": button, "sections": built}
    out = envelope(to, country, reply_to)
    out["type"] = "interactive"
    out["interactive"] = shell
    return out


def cta_url(to, body, display_text, url, header=None, footer=None, country="", reply_to=None):
    """A message with one button that opens a link."""
    link = str(_required(url, "url"))
    if not link.lower().startswith(("https://", "http://")):
        raise MessageError("url must start with https:// or http://")
    shell = _interactive_shell(body, header, footer)
    shell["type"] = "cta_url"
    shell["action"] = {
        "name": "cta_url",
        "parameters": {
            "display_text": _limit(_required(display_text, "button text"), BUTTON_TITLE_MAX, "button text"),
            "url": link,
        },
    }
    out = envelope(to, country, reply_to)
    out["type"] = "interactive"
    out["interactive"] = shell
    return out
