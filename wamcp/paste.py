"""Pull credentials out of whatever the user pastes.

The hardest part of setting WhatsApp up is copying three values out of Meta's
dashboard without mistyping them. So instead of asking for them one at a time,
we take a paste of anything that contains them and find them:

* the `curl` command Meta shows on the WhatsApp -> API Setup page, which
  carries the access token and the phone number ID together,
* a .env file, a JSON config, `KEY=value` lines,
* labelled text copied off the dashboard ("Phone number ID  123456789"),
* or just the access token on its own.

Order matters: a labelled or structured match always beats a loose one, so a
recipient number in a sample payload is never mistaken for a phone number id.
"""
import re

FIELDS = ("access_token", "phone_number_id", "business_account_id", "app_secret", "verify_token")

# Meta ids are long decimal strings; a phone number is at most 15 digits, so
# requiring 12+ keeps sample recipients out of the way when a label is absent.
_ID = r"\d{10,25}"

# Access tokens use the URL-safe base64 alphabet. Meta's start with EAA, but
# partner and system-user tokens do not always, so the shape is what we match
# on. After an unambiguous label ("Bearer", "ACCESS_TOKEN=") a short value is
# still clearly the token; with nothing to anchor it we insist on a long one so
# that ordinary words can never be mistaken for a credential.
_TOKEN = r"[A-Za-z0-9_\-\.]{12,}"
_TOKEN_STRICT = r"[A-Za-z0-9_\-\.]{40,}"

# A key and its value, tolerating every punctuation style people paste:
# KEY=value, "key": "value", key: value, or a dashboard label with the value
# on the next line.
_SEP = r"[\"']?\s*[=:]?\s*[\"']?"
_SEP_REQUIRED = r"[\"']?\s*[=:]\s*[\"']?"

PATTERNS = {
    "access_token": [
        rf"(?:WHATSAPP_)?ACCESS_TOKEN{_SEP_REQUIRED}({_TOKEN})",
        rf"Authorization\s*:\s*Bearer\s+({_TOKEN})",
        rf"Bearer\s+({_TOKEN})",
        rf"\baccess[_\- ]?token\b{_SEP_REQUIRED}({_TOKEN})",
        rf"\btoken\b{_SEP_REQUIRED}({_TOKEN})",
        # A dashboard label with only a newline between it and the value, so
        # insist on a long token rather than trusting the separator.
        rf"\b(?:temporary\s+|permanent\s+|system\s+user\s+)?"
        rf"access[_\- ]?token\b{_SEP}({_TOKEN_STRICT})",
    ],
    "phone_number_id": [
        rf"(?:WHATSAPP_)?PHONE_NUMBER_ID{_SEP_REQUIRED}({_ID})",
        # The curl command Meta shows: .../v24.0/<phone number id>/messages
        rf"/v\d+\.\d+/({_ID})/messages",
        rf"graph\.facebook\.com/[^/\s]*/?({_ID})/messages",
        rf"\bphone[_\- ]?number[_\- ]?id\b{_SEP}({_ID})",
    ],
    "business_account_id": [
        rf"(?:WHATSAPP_)?BUSINESS_ACCOUNT_ID{_SEP_REQUIRED}({_ID})",
        rf"\b(?:whatsapp[_\- ]?)?business[_\- ]?account[_\- ]?id\b{_SEP}({_ID})",
        rf"\bwaba[_\- ]?id\b{_SEP}({_ID})",
    ],
    "app_secret": [
        rf"(?:WHATSAPP_)?APP_SECRET{_SEP_REQUIRED}([0-9a-fA-F]{{32}})",
        rf"\bapp[_\- ]?secret\b{_SEP}([0-9a-fA-F]{{32}})",
    ],
    "verify_token": [
        rf"(?:WHATSAPP_)?VERIFY_TOKEN{_SEP_REQUIRED}([^\s\"',]{{6,}})",
        rf"\bverify[_\- ]?token\b{_SEP}([^\s\"',]{{6,}})",
    ],
}

# Words that look like a value but are placeholders in Meta's own samples.
PLACEHOLDERS = {
    "your_access_token", "access_token", "token", "xxx", "none", "null",
    "<access_token>", "your-token-here", "phone_number_id", "your_phone_number_id",
    "whatsapp_business_account_id", "replace_me", "example",
}


def _clean(value):
    return str(value or "").strip().strip("\"'`,;").strip()


def _plausible(field, value):
    value = _clean(value)
    if not value or value.lower() in PLACEHOLDERS or value.startswith(("<", "{{", "${")):
        return ""
    if field in ("phone_number_id", "business_account_id") and not value.isdigit():
        return ""
    return value


def parse(text):
    """Find whatever credentials are in `text`.

    Returns {field: value} for the fields found. Never raises: an unrecognised
    paste simply comes back empty.
    """
    text = str(text or "")
    found = {}
    for field, patterns in PATTERNS.items():
        for pattern in patterns:
            for match in re.finditer(pattern, text, re.IGNORECASE):
                value = _plausible(field, match.group(1))
                if value:
                    found[field] = value
                    break
            if field in found:
                break

    # A bare token pasted on its own, with nothing to label it.
    if "access_token" not in found:
        stripped = _clean(text)
        if "\n" not in stripped and re.fullmatch(_TOKEN_STRICT, stripped):
            found["access_token"] = stripped

    # Two different long ids in one paste, only one of them labelled: the
    # phone number id is the one in the /messages url, so if that is what we
    # matched, a second distinct id is very likely the business account.
    if found.get("phone_number_id") and found.get("business_account_id") == found["phone_number_id"]:
        del found["business_account_id"]
    return found


def describe(found):
    """'access token, phone number ID' - what to tell the user we picked up."""
    names = {
        "access_token": "access token",
        "phone_number_id": "phone number ID",
        "business_account_id": "business account ID",
        "app_secret": "app secret",
        "verify_token": "verify token",
    }
    return ", ".join(names[f] for f in FIELDS if f in found) or "nothing"
