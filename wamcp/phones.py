"""Phone numbers in the shape the Cloud API wants.

WhatsApp identifies a person by `wa_id`: digits only, full country code, no
plus sign, no separators. People (and models reading a CRM export) write
numbers every other way, so everything entering this server goes through
`normalise()` once and is stored and sent in one canonical form.

    normalise("+966 50 123 4567")          -> "966501234567"
    normalise("00966501234567")            -> "966501234567"
    normalise("050 123 4567", country="966") -> "966501234567"
"""
import re

# E.164 allows at most 15 digits. Country code plus subscriber number is never
# shorter than 8 in practice; below that it is a typo or an extension.
MIN_DIGITS = 8
MAX_DIGITS = 15
# Longest national significant number in the E.164 plan; anything longer
# already includes a country code.
NATIONAL_MAX_DIGITS = 10

_KEEP = re.compile(r"[^\d+]")
# Arabic-Indic and Eastern Arabic-Indic digits, common in pasted numbers.
_EASTERN = {ord(c): str(i % 10) for i, c in enumerate("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹")}


class PhoneError(ValueError):
    """The number cannot be read as an international phone number."""


def normalise(raw, country=""):
    """Digits-only international form, or raise PhoneError.

    `country` is a calling code without the plus (e.g. "966"). It is used only
    when the number is written in local trunk form ("050...", "0 50...").
    """
    if raw is None:
        raise PhoneError("no phone number given")
    text = str(raw).strip().translate(_EASTERN)
    if not text:
        raise PhoneError("no phone number given")

    # A wa_id or jid from a webhook or an export: 966501234567@c.us
    text = text.split("@", 1)[0]
    had_plus = text.lstrip().startswith("+")
    digits = _KEEP.sub("", text).replace("+", "")
    if not digits:
        raise PhoneError(f"{raw!r} contains no digits")

    country = str(country or "").strip().lstrip("+")
    if not had_plus:
        if digits.startswith("00"):
            digits = digits[2:]                      # 00 + country code
        elif digits.startswith("0"):
            if not country:
                raise PhoneError(
                    f"{raw!r} looks like a local number (leading 0). Give it in international "
                    f"form (+<country code><number>) or set WAMCP_DEFAULT_COUNTRY."
                )
            digits = country + digits.lstrip("0")
        elif country and len(digits) <= NATIONAL_MAX_DIGITS and not digits.startswith(country):
            # A national number written without the trunk 0, e.g. "5550109999"
            # in the US or "501234567" in Saudi Arabia. National significant
            # numbers are at most 10 digits, so anything longer is already
            # carrying its country code.
            digits = country + digits

    if not digits.isdigit():
        raise PhoneError(f"{raw!r} is not a phone number")
    if len(digits) < MIN_DIGITS:
        raise PhoneError(f"{raw!r} has only {len(digits)} digits; a full international number needs at least {MIN_DIGITS}")
    if len(digits) > MAX_DIGITS:
        raise PhoneError(f"{raw!r} has {len(digits)} digits; E.164 allows at most {MAX_DIGITS}")
    if digits.startswith("0"):
        raise PhoneError(f"{raw!r} starts with 0 after the country code was applied; check the number")
    return digits


def pretty(wa_id):
    """`966501234567` -> `+966501234567`, for text shown to a person."""
    wa_id = str(wa_id or "").strip()
    return "+" + wa_id if wa_id and not wa_id.startswith("+") else wa_id


def looks_like_phone(text):
    """Cheap test used when deciding whether a string is a number or a name."""
    try:
        normalise(text)
        return True
    except PhoneError:
        return False
