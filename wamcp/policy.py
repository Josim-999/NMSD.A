"""The gate every outbound message passes through.

A model holding a WhatsApp number can reach real people, so sending is
deliberately not a bare API call. Before anything leaves the machine:

* the recipient must not have opted out (STOP is honoured permanently),
* the recipient must be on WAMCP_ALLOWLIST when one is set,
* the rolling hourly send cap must not be exceeded,
* free-form text needs an open 24-hour customer service window,
* WAMCP_DRY_RUN=1 stops here and returns the exact payload instead.

Every decision, allowed or refused, is written to the audit table.
"""
import time

from . import phones

# WhatsApp's customer service window: a business may send free-form messages
# for 24 hours after the person's last message. Outside it, only approved
# templates are delivered.
WINDOW_SECONDS = 24 * 60 * 60
# Warn while there is less than this left, so a model can decide to template.
WINDOW_WARN_SECONDS = 60 * 60


class PolicyError(RuntimeError):
    """A send was refused locally. `hint` says how to proceed legitimately."""

    def __init__(self, message, kind="refused", hint=""):
        super().__init__(message)
        self.message = message
        self.kind = kind
        self.hint = hint

    def to_dict(self):
        out = {"error": self.message, "kind": self.kind, "refused_by": "wamcp policy"}
        if self.hint:
            out["what_to_do"] = self.hint
        return out


class Decision:
    """The outcome of the checks for one message."""

    def __init__(self, wa_id, payload, warnings=None, dry_run=False):
        self.wa_id = wa_id
        self.payload = payload
        self.warnings = warnings or []
        self.dry_run = dry_run


class Policy:
    def __init__(self, config, store=None, now=time.time):
        self.config = config
        self.store = store
        self._now = now

    # --- helpers -----------------------------------------------------------
    def normalise(self, to):
        return phones.normalise(to, self.config.default_country)

    def allowlist(self):
        return {phones.normalise(n, self.config.default_country) for n in self.config.allowlist}

    def window(self, wa_id):
        """How the 24-hour window stands for this contact.

        `state` is 'open', 'closed' or 'unknown' - unknown when no webhook has
        ever delivered a message from them, so we cannot tell.
        """
        last = self.store.last_inbound_ts(wa_id) if self.store else None
        if not last:
            return {"state": "unknown", "last_inbound_ts": None, "seconds_left": None}
        left = int(WINDOW_SECONDS - (self._now() - last))
        return {
            "state": "open" if left > 0 else "closed",
            "last_inbound_ts": int(last),
            "seconds_left": max(0, left),
        }

    # --- the gate ----------------------------------------------------------
    def check(self, payload, kind="message", requires_window=True):
        """Validate a built payload. Returns a Decision or raises PolicyError."""
        self.config.require_sending()
        wa_id = payload.get("to")
        if not wa_id:
            raise PolicyError("no recipient in the message", kind="invalid")
        warnings = []

        opted = self.store.is_opted_out(wa_id) if self.store else None
        if opted:
            self._record(wa_id, kind, "refused", "recipient opted out")
            raise PolicyError(
                f"{phones.pretty(wa_id)} has opted out of messages from this number"
                + (f" ({opted.get('reason')})" if opted.get("reason") else ""),
                kind="opted_out",
                hint="Do not message this person. They can opt back in by sending START, "
                     "or a human can clear it with `python3 -m wamcp consent opt_in <number>`.",
            )

        allowed = self.allowlist()
        if allowed and wa_id not in allowed:
            self._record(wa_id, kind, "refused", "not on allow-list")
            raise PolicyError(
                f"{phones.pretty(wa_id)} is not on this server's allow-list",
                kind="not_allowed",
                hint="WAMCP_ALLOWLIST restricts who can be messaged. Add the number to it "
                     "(and remember an unverified test number also has Meta's own allowed list).",
            )

        cap = self.config.max_sends_per_hour
        if cap and self.store:
            used = self.store.sends_since(self._now() - 3600)
            if used >= cap:
                self._record(wa_id, kind, "refused", f"hourly cap {cap} reached")
                raise PolicyError(
                    f"hourly send cap reached ({used}/{cap} in the last hour)",
                    kind="rate_limited",
                    hint="Wait for the hour to roll over, or raise WAMCP_MAX_SENDS. "
                         "The cap exists so an automated loop cannot message people repeatedly.",
                )
            if cap - used <= 3:
                warnings.append(f"{cap - used} of {cap} sends left in this hour")

        if requires_window and not self.config.allow_outside_window:
            state = self.window(wa_id)
            if state["state"] == "closed":
                self._record(wa_id, kind, "refused", "24h window closed")
                raise PolicyError(
                    f"the 24-hour customer service window for {phones.pretty(wa_id)} is closed "
                    f"(their last message was {_ago(self._now() - state['last_inbound_ts'])} ago)",
                    kind="window_closed",
                    hint="WhatsApp only delivers free-form messages within 24 hours of the "
                         "person's last message. Send an approved template with "
                         "whatsapp_send_template instead - list them with whatsapp_list_templates.",
                )
            if state["state"] == "open" and state["seconds_left"] is not None \
                    and state["seconds_left"] < WINDOW_WARN_SECONDS:
                warnings.append(
                    f"the 24-hour window closes in {_ago(state['seconds_left'])}; "
                    "after that only templates are delivered"
                )
            if state["state"] == "unknown":
                warnings.append(
                    "no inbound message from this contact is on record, so the 24-hour window "
                    "cannot be checked. If this is the first contact, WhatsApp requires a "
                    "template. Run `python3 -m wamcp webhook` to receive inbound messages."
                )

        if self.config.dry_run:
            self._record(wa_id, kind, "dry_run", "not sent: WAMCP_DRY_RUN is on")
            return Decision(wa_id, payload, warnings, dry_run=True)

        return Decision(wa_id, payload, warnings)

    def _record(self, wa_id, action, outcome, detail):
        if self.store:
            self.store.audit(action, wa_id=wa_id, outcome=outcome, detail=detail)


def _ago(seconds):
    seconds = int(max(0, seconds))
    if seconds < 60:
        return f"{seconds}s"
    if seconds < 3600:
        return f"{seconds // 60}m"
    if seconds < 86400:
        return f"{seconds // 3600}h {(seconds % 3600) // 60}m"
    return f"{seconds // 86400}d {(seconds % 86400) // 3600}h"
