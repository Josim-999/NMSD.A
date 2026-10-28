"""WhatsApp Business Cloud API client.

Thin, synchronous, standard library only. Its job is to turn Meta's HTTP
errors into something an AI client can act on without a human reading
developer docs: every failure carries a `hint` saying what to do next.

    client = GraphClient(config)
    client.send({"to": "966501234567", "type": "text", "text": {"body": "hi"}})
"""
import json
import mimetypes
import os
import random
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

RETRY_STATUS = {408, 429, 500, 502, 503, 504}
MAX_ATTEMPTS = 3
USER_AGENT = "wamcp/1.0 (+https://github.com/Josim-999/NMSD.A)"

# What to do about the Cloud API errors that actually come up in practice.
# Keyed by Meta's numeric error code.
HINTS = {
    0: "Authentication failed. The access token is wrong, expired or belongs to another app. Create a new one and run `python3 -m wamcp setup`.",
    3: "This app or token lacks the whatsapp_business_messaging permission. Add it to the app and regenerate the token.",
    4: "The app hit its API rate limit. Wait and retry; spread sends out rather than bursting.",
    10: "The app does not have permission for this call. Check that whatsapp_business_messaging and whatsapp_business_management are granted.",
    33: "The phone number id or WABA id does not exist, or this token cannot see it. Check WHATSAPP_PHONE_NUMBER_ID with `python3 -m wamcp doctor`.",
    100: "A parameter is invalid or missing. The `details` field says which one.",
    130429: "Throughput limit reached for this number. Slow down and retry.",
    131000: "Meta had an internal failure. Safe to retry.",
    131005: "The access token cannot act on this phone number.",
    131008: "A required parameter is missing from the request.",
    131009: "A parameter value is not allowed. Check the `details` field.",
    131016: "The Cloud API service is temporarily unavailable. Retry shortly.",
    131021: "The recipient is the same as the sender; a number cannot message itself.",
    131026: "Message undeliverable. The number may not be on WhatsApp, may have an old app version, or may not accept messages from businesses. Confirm the number is correct and registered on WhatsApp.",
    131030: "The recipient is not in this number's allowed list. A test/unverified number may only message numbers you added in the Meta app dashboard (API Setup -> To). Add the number there, or finish business verification to message anyone.",
    131031: "This WhatsApp Business Account is restricted or disabled. Check WhatsApp Manager for a policy notice.",
    131042: "Billing is not set up for the business account. Add a payment method in WhatsApp Manager.",
    131045: "The phone number is not registered for the Cloud API. Register it in WhatsApp Manager (or re-run the number's setup).",
    131047: "Outside the 24-hour customer service window: free-form messages are only allowed within 24 hours of the person's last message. Use whatsapp_send_template with an approved template instead.",
    131049: "Meta throttled this marketing-style message to protect user experience. Send utility/service content, or try later.",
    131051: "That message type is not supported for this number.",
    131052: "Media download failed: Meta could not fetch the link. The URL must be public, HTTPS and directly serve the file.",
    131053: "Media upload failed: check file size and that the MIME type is supported.",
    131056: "Too many messages to this same number in a short period. Pause before messaging them again.",
    132000: "The template's parameter count does not match the template body. Count the {{n}} placeholders and pass exactly that many.",
    132001: "That template does not exist in this language, or is not approved. List templates with whatsapp_list_templates and use the exact name and language code.",
    132005: "A template parameter is too long, or contains newlines/tabs where the template does not allow them.",
    132007: "A template parameter broke a format rule (for example a currency or date parameter).",
    132012: "A template parameter value is in the wrong format for its placeholder.",
    132015: "The template is paused because of poor quality feedback and cannot be sent.",
    132016: "The template was disabled for quality reasons. Use another template.",
    132068: "The flow is blocked.",
    133010: "The phone number is not registered on the Cloud API yet.",
    133015: "The phone number is being deleted or moved and cannot send right now.",
    135000: "Meta rejected the message as malformed. The `details` field says why.",
    190: "The access token has expired or was revoked. Generate a new permanent (system user) token and re-run `python3 -m wamcp setup`.",
    368: "The account is temporarily blocked for a policy violation. See WhatsApp Manager.",
    80007: "Rate limit reached for this business account. Retry later.",
}

# Codes where retrying the identical request is reasonable.
RETRY_CODES = {4, 131000, 131016, 130429, 80007}


class WhatsAppError(RuntimeError):
    """A failed Cloud API call, with the information needed to fix it."""

    def __init__(self, message, status=None, code=None, subcode=None,
                 details="", fbtrace_id="", hint="", kind="api_error"):
        super().__init__(message)
        self.message = message
        self.status = status
        self.code = code
        self.subcode = subcode
        self.details = details
        self.fbtrace_id = fbtrace_id
        self.hint = hint or HINTS.get(code, "")
        self.kind = kind

    @property
    def retryable(self):
        return self.code in RETRY_CODES or (self.status in RETRY_STATUS if self.status else False)

    def to_dict(self):
        out = {"error": self.message, "kind": self.kind}
        for key in ("status", "code", "subcode", "details", "fbtrace_id"):
            value = getattr(self, key)
            if value not in (None, "", 0):
                out[key] = value
        if self.hint:
            out["what_to_do"] = self.hint
        return out

    def __str__(self):
        parts = [self.message]
        if self.details and self.details not in self.message:
            parts.append(self.details)
        if self.hint:
            parts.append(self.hint)
        return " — ".join(parts)


class GraphClient:
    """Calls one WhatsApp Business phone number's Cloud API endpoints."""

    def __init__(self, config, opener=None, sleep=time.sleep):
        self.config = config
        self._opener = opener or urllib.request.build_opener(
            urllib.request.ProxyHandler(),  # honours HTTPS_PROXY
            urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        )
        self._sleep = sleep

    # --- plumbing ----------------------------------------------------------
    def url(self, path, params=None):
        path = str(path).lstrip("/")
        url = f"{self.config.api_base}/{self.config.api_version}/{path}"
        if params:
            clean = {k: v for k, v in params.items() if v not in (None, "")}
            if clean:
                url += "?" + urllib.parse.urlencode(clean)
        return url

    def request(self, method, path, body=None, params=None, url=None, binary=False,
                content_type="application/json", headers=None):
        """One Cloud API call, with retries on transient failures."""
        target = url or self.url(path, params)
        data = None
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")

        last_error = None
        for attempt in range(1, MAX_ATTEMPTS + 1):
            req = urllib.request.Request(target, data=data, method=method)
            req.add_header("Authorization", f"Bearer {self.config.access_token}")
            req.add_header("User-Agent", USER_AGENT)
            if data is not None:
                req.add_header("Content-Type", content_type)
            for key, value in (headers or {}).items():
                req.add_header(key, value)

            try:
                with self._opener.open(req, timeout=self.config.timeout) as resp:
                    payload = resp.read()
                    if binary:
                        return {
                            "bytes": payload,
                            "content_type": resp.headers.get("Content-Type", ""),
                            "status": resp.status,
                        }
                    return self._decode(payload, resp.status)
            except urllib.error.HTTPError as e:
                raw = e.read()
                error = self._from_http_error(e.code, raw)
                if attempt < MAX_ATTEMPTS and (e.code in RETRY_STATUS or error.retryable):
                    self._backoff(attempt, e.headers.get("Retry-After"))
                    last_error = error
                    continue
                raise error
            except urllib.error.URLError as e:
                last_error = WhatsAppError(
                    f"cannot reach {self.config.api_base}: {e.reason}",
                    kind="network_error",
                    hint="Check the machine's internet access and any proxy settings. "
                         "graph.facebook.com must be reachable over HTTPS.",
                )
                if attempt < MAX_ATTEMPTS:
                    self._backoff(attempt, None)
                    continue
                raise last_error
            except TimeoutError:
                last_error = WhatsAppError(
                    f"timed out after {self.config.timeout:g}s calling the WhatsApp Cloud API",
                    kind="timeout",
                    hint="Raise WAMCP_TIMEOUT, or retry: the message may still have been accepted.",
                )
                if attempt < MAX_ATTEMPTS:
                    self._backoff(attempt, None)
                    continue
                raise last_error
        raise last_error  # pragma: no cover - loop always returns or raises

    def _backoff(self, attempt, retry_after):
        delay = 0.0
        if retry_after:
            try:
                delay = float(retry_after)
            except (TypeError, ValueError):
                delay = 0.0
        if delay <= 0:
            delay = min(8.0, 0.5 * (2 ** (attempt - 1))) + random.random() * 0.25
        self._sleep(delay)

    @staticmethod
    def _decode(payload, status):
        if not payload:
            return {}
        try:
            return json.loads(payload.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise WhatsAppError(
                "the Cloud API returned a response that is not JSON",
                status=status, kind="bad_response",
                details=payload[:200].decode("utf-8", "replace"),
            )

    @staticmethod
    def _from_http_error(status, raw):
        body = {}
        try:
            body = json.loads(raw.decode("utf-8")) or {}
        except (ValueError, UnicodeDecodeError, AttributeError):
            pass
        err = body.get("error") if isinstance(body, dict) else None
        if not isinstance(err, dict):
            text = (raw or b"")[:300].decode("utf-8", "replace").strip()
            return WhatsAppError(
                f"WhatsApp Cloud API returned HTTP {status}",
                status=status, details=text,
                hint="Retry; if it persists check Meta's platform status." if status in RETRY_STATUS else "",
            )
        data = err.get("error_data") or {}
        return WhatsAppError(
            err.get("message") or f"HTTP {status}",
            status=status,
            code=err.get("code"),
            subcode=err.get("error_subcode"),
            details=(data.get("details") if isinstance(data, dict) else "") or "",
            fbtrace_id=err.get("fbtrace_id") or "",
        )

    # --- discovery ---------------------------------------------------------
    def discover(self):
        """Work out what WhatsApp assets this token can reach.

        Setup asks for an access token and then calls this, so a user who has
        pasted only a token (or only a curl command) does not have to go back
        to the dashboard for the phone number id and the WABA id.

        Every step is best effort: a token with narrow permissions simply
        yields less. Nothing here raises - the caller shows what was found and
        asks for the rest.
        """
        found = {"phone_numbers": [], "business_account_id": "",
                 "business_account_name": "", "notes": []}

        def attempt(label, fn):
            try:
                return fn()
            except WhatsAppError as e:
                found["notes"].append(f"{label}: {e.message}")
            except Exception as e:                              # noqa: BLE001
                found["notes"].append(f"{label}: {type(e).__name__}: {e}")
            return None

        # 1. A phone number id we already hold tells us the most, in one call.
        if self.config.phone_number_id:
            number = attempt("phone number", lambda: self.request(
                "GET", self.config.phone_number_id,
                params={"fields": "id,display_phone_number,verified_name,quality_rating"}))
            if number:
                found["phone_numbers"].append({
                    "id": number.get("id") or self.config.phone_number_id,
                    "display_phone_number": number.get("display_phone_number", ""),
                    "verified_name": number.get("verified_name", ""),
                })

        # 2. The business account, which is what template calls need.
        if not self.config.business_account_id and self.config.phone_number_id:
            # Not every API version exposes the parent account on a phone
            # number, so a failure here is expected rather than a problem.
            linked = attempt("linked account", lambda: self.request(
                "GET", self.config.phone_number_id,
                params={"fields": "whatsapp_business_account{id,name}"}))
            account = (linked or {}).get("whatsapp_business_account") or {}
            if account.get("id"):
                found["business_account_id"] = account["id"]
                found["business_account_name"] = account.get("name", "")

        if not found["business_account_id"] and not self.config.business_account_id:
            # Walk the businesses this token can see.
            businesses = attempt("businesses", lambda: self.request(
                "GET", "me/businesses", params={"fields": "id,name", "limit": 25}))
            for business in (businesses or {}).get("data") or []:
                for edge in ("owned_whatsapp_business_accounts",
                             "client_whatsapp_business_accounts"):
                    accounts = attempt(edge, lambda e=edge, b=business: self.request(
                        "GET", f"{b['id']}/{e}", params={"fields": "id,name", "limit": 25}))
                    data = (accounts or {}).get("data") or []
                    if data:
                        found["business_account_id"] = data[0]["id"]
                        found["business_account_name"] = data[0].get("name", "")
                        break
                if found["business_account_id"]:
                    break

        # 3. The numbers on that account, if we still have none.
        waba = found["business_account_id"] or self.config.business_account_id
        if waba and not found["phone_numbers"]:
            numbers = attempt("phone numbers", lambda: self.request(
                "GET", f"{waba}/phone_numbers",
                params={"fields": "id,display_phone_number,verified_name", "limit": 25}))
            for number in (numbers or {}).get("data") or []:
                found["phone_numbers"].append({
                    "id": number.get("id", ""),
                    "display_phone_number": number.get("display_phone_number", ""),
                    "verified_name": number.get("verified_name", ""),
                })
        return found

    # --- messages ----------------------------------------------------------
    def send(self, payload):
        """POST a built message payload to /{phone_number_id}/messages."""
        self.config.require_sending()
        body = dict(payload)
        body.setdefault("messaging_product", "whatsapp")
        return self.request("POST", f"{self.config.phone_number_id}/messages", body=body)

    def mark_read(self, message_id, typing=False):
        self.config.require_sending()
        body = {"messaging_product": "whatsapp", "status": "read", "message_id": message_id}
        if typing:
            body["typing_indicator"] = {"type": "text"}
        return self.request("POST", f"{self.config.phone_number_id}/messages", body=body)

    # --- media -------------------------------------------------------------
    def media_info(self, media_id):
        return self.request("GET", str(media_id), params={"phone_number_id": self.config.phone_number_id})

    def download_media(self, media_id, directory=None, filename=None):
        """Fetch a media file Meta is holding and save it locally."""
        info = self.media_info(media_id)
        link = info.get("url")
        if not link:
            raise WhatsAppError(
                f"no download url for media {media_id}",
                kind="not_found",
                hint="Media ids expire after 14 days (5 for some types). Ask the sender to resend.",
            )
        # The media URL is pre-signed but still needs the bearer token.
        result = self.request("GET", None, url=link, binary=True)
        directory = directory or self.config.media_dir
        os.makedirs(directory, mode=0o700, exist_ok=True)
        mime = info.get("mime_type") or result.get("content_type") or "application/octet-stream"
        if not filename:
            ext = mimetypes.guess_extension(mime.split(";")[0].strip()) or ".bin"
            filename = f"{media_id}{ext}"
        path = os.path.join(directory, os.path.basename(filename))
        with open(path, "wb") as fh:
            fh.write(result["bytes"])
        return {
            "media_id": str(media_id),
            "path": os.path.abspath(path),
            "mime_type": mime,
            "bytes": len(result["bytes"]),
            "sha256": info.get("sha256", ""),
        }

    def upload_media(self, path, mime_type=None):
        """Upload a local file and get the media id to send it with."""
        self.config.require_sending()
        path = os.path.expanduser(path)
        if not os.path.isfile(path):
            raise WhatsAppError(f"no such file: {path}", kind="not_found")
        mime_type = mime_type or mimetypes.guess_type(path)[0] or "application/octet-stream"
        with open(path, "rb") as fh:
            content = fh.read()
        boundary = f"----wamcp{uuid.uuid4().hex}"
        body = _multipart(boundary, [
            ("messaging_product", "whatsapp"),
            ("type", mime_type),
        ], ("file", os.path.basename(path), mime_type, content))
        return self.request(
            "POST", f"{self.config.phone_number_id}/media", body=body,
            content_type=f"multipart/form-data; boundary={boundary}",
        )

    def delete_media(self, media_id):
        return self.request("DELETE", str(media_id))

    # --- templates ---------------------------------------------------------
    def list_templates(self, limit=50, after=None, name=None):
        self.config.require_business_account("Listing templates")
        params = {"limit": limit, "after": after,
                  "fields": "name,status,category,language,components,quality_score,rejected_reason"}
        if name:
            params["name"] = name
        return self.request("GET", f"{self.config.business_account_id}/message_templates", params=params)

    def create_template(self, body):
        self.config.require_business_account("Creating a template")
        return self.request("POST", f"{self.config.business_account_id}/message_templates", body=body)

    def delete_template(self, name):
        self.config.require_business_account("Deleting a template")
        return self.request("DELETE", f"{self.config.business_account_id}/message_templates",
                            params={"name": name})

    # --- account -----------------------------------------------------------
    def phone_number(self, fields=None):
        self.config.require_sending()
        fields = fields or ("verified_name,display_phone_number,quality_rating,"
                            "code_verification_status,platform_type,throughput")
        return self.request("GET", self.config.phone_number_id, params={"fields": fields})

    def business_profile(self):
        self.config.require_sending()
        return self.request(
            "GET", f"{self.config.phone_number_id}/whatsapp_business_profile",
            params={"fields": "about,address,description,email,profile_picture_url,websites,vertical"},
        )

    def update_business_profile(self, fields):
        self.config.require_sending()
        body = {"messaging_product": "whatsapp"}
        body.update(fields)
        return self.request("POST", f"{self.config.phone_number_id}/whatsapp_business_profile", body=body)

    def phone_numbers(self):
        self.config.require_business_account("Listing the account's phone numbers")
        return self.request(
            "GET", f"{self.config.business_account_id}/phone_numbers",
            params={"fields": "id,display_phone_number,verified_name,quality_rating,code_verification_status"},
        )


def _multipart(boundary, fields, file_field):
    """Build a multipart/form-data body (only needed for media upload)."""
    crlf = b"\r\n"
    out = []
    for name, value in fields:
        out += [
            f"--{boundary}".encode(),
            f'Content-Disposition: form-data; name="{name}"'.encode(),
            b"", str(value).encode("utf-8"),
        ]
    name, filename, mime, content = file_field
    out += [
        f"--{boundary}".encode(),
        f'Content-Disposition: form-data; name="{name}"; filename="{filename}"'.encode(),
        f"Content-Type: {mime}".encode(),
        b"", content,
        f"--{boundary}--".encode(), b"",
    ]
    return crlf.join(out)
