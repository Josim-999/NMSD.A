"""Credentials and options.

Precedence, highest first:

1. environment variables  (best for CI and for MCP client config blocks)
2. ~/.wamcp/config.json   (written by `python3 -m wamcp setup`, mode 0600)
3. the defaults below

Nothing here ever prints a secret: `redacted()` is what goes into logs, the
audit trail and the doctor report.
"""
import json
import os

CONFIG_DIR = os.path.expanduser(os.environ.get("WAMCP_HOME", "~/.wamcp"))
CONFIG_PATH = os.path.join(CONFIG_DIR, "config.json")

# Graph API version. Meta keeps each version callable for about two years and
# the Cloud API request shapes used here are stable across them, so this is a
# safe default you can move forward at any time with WHATSAPP_API_VERSION.
DEFAULT_API_VERSION = "v24.0"
DEFAULT_API_BASE = "https://graph.facebook.com"

# Environment variable for each field. The WHATSAPP_* names are the ones Meta's
# own quickstart uses, so they can be pasted straight across.
ENV = {
    "access_token": "WHATSAPP_ACCESS_TOKEN",
    "phone_number_id": "WHATSAPP_PHONE_NUMBER_ID",
    "business_account_id": "WHATSAPP_BUSINESS_ACCOUNT_ID",
    "app_secret": "WHATSAPP_APP_SECRET",
    "verify_token": "WHATSAPP_VERIFY_TOKEN",
    "api_version": "WHATSAPP_API_VERSION",
    "api_base": "WHATSAPP_API_BASE",
    "db_path": "WAMCP_DB",
    "media_dir": "WAMCP_MEDIA_DIR",
    "allowlist": "WAMCP_ALLOWLIST",
    "dry_run": "WAMCP_DRY_RUN",
    "max_sends_per_hour": "WAMCP_MAX_SENDS",
    "allow_outside_window": "WAMCP_ALLOW_OUTSIDE_WINDOW",
    "timeout": "WAMCP_TIMEOUT",
    "default_country": "WAMCP_DEFAULT_COUNTRY",
}

SECRET_FIELDS = ("access_token", "app_secret", "verify_token")

DEFAULTS = {
    "access_token": "",
    "phone_number_id": "",
    "business_account_id": "",
    "app_secret": "",
    "verify_token": "",
    "api_version": DEFAULT_API_VERSION,
    "api_base": DEFAULT_API_BASE,
    "db_path": os.path.join(CONFIG_DIR, "wamcp.db"),
    "media_dir": os.path.join(CONFIG_DIR, "media"),
    "allowlist": [],            # empty = any recipient allowed
    "dry_run": False,           # True = build and validate, never call Meta
    "max_sends_per_hour": 0,     # 0 = no cap
    "allow_outside_window": False,
    "timeout": 30.0,
    "default_country": "",      # e.g. "966" to accept local numbers
}

TRUE_WORDS = {"1", "true", "yes", "on"}


class ConfigError(RuntimeError):
    """Something is missing or malformed in the configuration."""


def _as_bool(value):
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in TRUE_WORDS


def _as_list(value):
    if isinstance(value, (list, tuple)):
        return [str(v).strip() for v in value if str(v).strip()]
    return [part.strip() for part in str(value).replace(";", ",").split(",") if part.strip()]


class Config:
    """Resolved settings for one process."""

    def __init__(self, **values):
        data = dict(DEFAULTS)
        data.update({k: v for k, v in values.items() if v is not None})
        self.access_token = str(data["access_token"]).strip()
        self.phone_number_id = str(data["phone_number_id"]).strip()
        self.business_account_id = str(data["business_account_id"]).strip()
        self.app_secret = str(data["app_secret"]).strip()
        self.verify_token = str(data["verify_token"]).strip()
        self.api_version = str(data["api_version"]).strip() or DEFAULT_API_VERSION
        self.api_base = str(data["api_base"]).strip().rstrip("/") or DEFAULT_API_BASE
        self.db_path = os.path.expanduser(str(data["db_path"]))
        self.media_dir = os.path.expanduser(str(data["media_dir"]))
        self.allowlist = _as_list(data["allowlist"])
        self.dry_run = _as_bool(data["dry_run"])
        self.allow_outside_window = _as_bool(data["allow_outside_window"])
        self.default_country = str(data["default_country"]).strip().lstrip("+")
        try:
            self.max_sends_per_hour = int(data["max_sends_per_hour"] or 0)
        except (TypeError, ValueError):
            raise ConfigError(f"{ENV['max_sends_per_hour']} must be a whole number")
        try:
            self.timeout = float(data["timeout"] or 30.0)
        except (TypeError, ValueError):
            raise ConfigError(f"{ENV['timeout']} must be a number of seconds")

    # --- requirements ------------------------------------------------------
    def missing_for_sending(self):
        """Fields needed before any message can go out."""
        missing = []
        if not self.access_token:
            missing.append(ENV["access_token"])
        if not self.phone_number_id:
            missing.append(ENV["phone_number_id"])
        return missing

    def require_sending(self):
        missing = self.missing_for_sending()
        if missing:
            raise ConfigError(
                "WhatsApp is not configured yet: missing " + ", ".join(missing)
                + ". Run `python3 -m wamcp setup`, or set those variables in your MCP client config."
            )

    def require_business_account(self, what="templates"):
        if not self.business_account_id:
            raise ConfigError(
                f"{what} need the WhatsApp Business Account id. Set {ENV['business_account_id']} "
                "(Meta Business Suite -> WhatsApp Manager -> Account tools -> the WABA ID)."
            )

    # --- presentation ------------------------------------------------------
    def redacted(self):
        """Config as a dict safe to print, log or hand to a model."""
        out = {}
        for field in DEFAULTS:
            value = getattr(self, field)
            if field in SECRET_FIELDS:
                out[field] = mask(value)
            else:
                out[field] = value
        return out

    def as_dict(self, include_secrets=True):
        out = {field: getattr(self, field) for field in DEFAULTS}
        if not include_secrets:
            for field in SECRET_FIELDS:
                out.pop(field)
        return out


def mask(secret):
    """`EAAb...9xQZ` - enough to tell two tokens apart, useless if leaked."""
    if not secret:
        return ""
    if len(secret) <= 10:
        return "*" * len(secret)
    return f"{secret[:4]}...{secret[-4:]} ({len(secret)} chars)"


def _from_env():
    values = {}
    for field, var in ENV.items():
        if var in os.environ and os.environ[var] != "":
            values[field] = os.environ[var]
    # Let secrets come from a file so they never sit in a client config or
    # in the process environment of a shared machine.
    for field in SECRET_FIELDS:
        path = os.environ.get(ENV[field] + "_FILE")
        if path:
            try:
                with open(os.path.expanduser(path), encoding="utf-8") as fh:
                    values[field] = fh.read().strip()
            except OSError as e:
                raise ConfigError(f"cannot read {ENV[field]}_FILE ({path}): {e}")
    return values


def read_file(path=None):
    """Stored config, or {} when there is none."""
    path = path or CONFIG_PATH
    if not os.path.exists(path):
        return {}
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as e:
        raise ConfigError(f"cannot read {path}: {e}")
    if not isinstance(data, dict):
        raise ConfigError(f"{path} must contain a JSON object")
    return {k: v for k, v in data.items() if k in DEFAULTS}


def load(path=None, **overrides):
    """The Config this process should use."""
    values = read_file(path)
    values.update(_from_env())
    values.update({k: v for k, v in overrides.items() if v is not None})
    return Config(**values)


def save(config, path=None):
    """Write config to disk, readable only by this user."""
    path = path or CONFIG_PATH
    directory = os.path.dirname(path) or "."
    os.makedirs(directory, mode=0o700, exist_ok=True)
    payload = config.as_dict() if isinstance(config, Config) else dict(config)
    tmp = path + ".tmp"
    # Create with 0600 from the start: never a moment where the token is
    # world-readable.
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, sort_keys=True)
        fh.write("\n")
    os.replace(tmp, path)
    os.chmod(path, 0o600)
    return path
