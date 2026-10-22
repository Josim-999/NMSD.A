"""Language-model layer.

* `OfflineWriter` (default) - deterministic templates. The whole pipeline runs
  with no API key, which is how the tests and the demo run.
* `ClaudeWriter` - Claude via the official `anthropic` SDK (`pip install anthropic`,
  set ANTHROPIC_API_KEY). Used for copywriting and intent classification.
  Any API failure or refusal falls back to the offline result, so a model
  outage never stops the queue.

The model never decides what gets published - it only drafts into the queue.
Inbound messages are pseudonymised (handles, emails, phone numbers) before
they are sent to the API.
"""
import json
import os
import re

MODEL = os.environ.get("SMT_MODEL", "claude-opus-5-5")

WRITER_SYSTEM = """You are the copywriter for {name}, a one-person business offering {offer} to {audience}.
Voice: {tone}. Do: {do}. Never use these words or phrases: {avoid}.
Write punchy, specific, high-retention copy. One idea per piece. No emojis unless the platform is TikTok or Instagram, and then at most two.
Adapt the idea, never the wording, of any source hook you are shown: write a new sentence that uses the same psychological trigger.
Never invent statistics, client results or testimonials. If you need a number, use one from the brief or phrase it as an example."""

INTENT_SYSTEM = """Classify an inbound social media comment or DM for a small business.
intent is one of: lead (asks about price, buying, booking, availability, or says they are interested), question (a general question the public content could answer), praise, complaint, spam, other.
score is 0-1: how likely the person is to buy soon. reason is one short sentence."""

INTENT_SCHEMA = {
    "type": "object",
    "properties": {"intent": {"type": "string", "enum": ["lead", "question", "praise", "complaint", "spam", "other"]},
                   "score": {"type": "number"}, "reason": {"type": "string"}},
    "required": ["intent", "score", "reason"], "additionalProperties": False,
}


def mask(text):
    text = re.sub(r"[\w.+-]+@[\w-]+\.[\w.]+", "[EMAIL]", text)
    text = re.sub(r"\+?\d[\d\s().-]{7,}\d", "[PHONE]", text)
    return re.sub(r"@\w{2,}", "@[USER]", text)


class OfflineWriter:
    name = "offline templates (no model)"

    def polish(self, brief, draft):
        return draft

    def classify(self, text, fallback):
        return fallback


class ClaudeWriter:
    def __init__(self, brand, client=None):
        import anthropic                                  # optional dependency
        self.anthropic = anthropic
        self.client = client or anthropic.Anthropic(max_retries=4)   # SDK retries 429/5xx with backoff
        v = brand["voice"]
        self.system = WRITER_SYSTEM.format(name=brand["name"], offer=brand["offer"], audience=brand["audience"],
                                           tone=v["tone"], do="; ".join(v["do"]), avoid=", ".join(v["avoid"]))
        self.name = f"Claude ({MODEL})"
        self.last_error = None

    def _call(self, system, user, effort, schema=None):
        output_config = {"effort": effort}
        if schema:
            output_config["format"] = {"type": "json_schema", "schema": schema}
        try:
            resp = self.client.beta.messages.create(
                model=MODEL, max_tokens=16000, system=system,
                messages=[{"role": "user", "content": user}],
                output_config=output_config,
                betas=["server-side-fallback-2026-07-01"], fallbacks="default",
            )
        except self.anthropic.RateLimitError as e:
            self.last_error = f"rate limited: {e}"
            return None
        except self.anthropic.APIStatusError as e:
            self.last_error = f"API error {e.status_code}: {e}"
            return None
        except self.anthropic.APIConnectionError as e:
            self.last_error = f"connection error: {e}"
            return None
        if resp.stop_reason in ("refusal", "max_tokens"):
            self.last_error = f"stop_reason={resp.stop_reason}"
            return None
        return "".join(b.text for b in resp.content if b.type == "text").strip() or None

    def polish(self, brief, draft):
        """Rewrite a template draft in the brand voice. Returns JSON with the same keys as the draft."""
        user = (f"BRIEF:\n{json.dumps(brief, ensure_ascii=False)}\n\nDRAFT (JSON):\n{json.dumps(draft, ensure_ascii=False)}\n\n"
                "Rewrite every string value in the brand voice. Keep the same JSON keys and structure. "
                "Return only the JSON.")
        text = self._call(self.system, user, effort="medium")
        if not text:
            return draft
        try:
            out = json.loads(text[text.find("{"): text.rfind("}") + 1])
        except ValueError:
            self.last_error = "model returned non-JSON draft"
            return draft
        return out if isinstance(out, dict) and set(out) == set(draft) else draft

    def classify(self, text, fallback):
        out = self._call(INTENT_SYSTEM, mask(text), effort="low", schema=INTENT_SCHEMA)
        if not out:
            return fallback
        try:
            d = json.loads(out)
            return d["intent"], max(0.0, min(1.0, float(d["score"]))), d["reason"]
        except (ValueError, KeyError, TypeError):
            return fallback


def from_env(brand):
    if not os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("SMT_OFFLINE") == "1":
        return OfflineWriter()
    try:
        return ClaudeWriter(brand)
    except ImportError:
        print("anthropic SDK not installed (pip install anthropic); using offline templates")
        return OfflineWriter()
