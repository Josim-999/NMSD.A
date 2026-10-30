"""Language-model layer with a hard data boundary.

* `OfflineNarrator` (default) - no model at all; agents' deterministic text is
  returned unchanged. The whole system works without any AI server.
* `LocalLLM` - any OpenAI-compatible endpoint (vLLM, Ollama, NVIDIA NIM,
  llama.cpp server). It refuses to talk to anything that is not loopback or a
  private-network address, so company data cannot leave the network by
  accident, and personal data is pseudonymised before every call.
* `GeminiLLM` - Google's Gemini API. This one is a PUBLIC cloud endpoint, so it
  deliberately crosses the data boundary and must be switched on twice: an API
  key and an explicit acknowledgement. Personal data is pseudonymised exactly
  as for the local model, so driver names, phones and ids never leave.

Enable a local model with:
    TIS_LLM_URL=http://127.0.0.1:11434  TIS_LLM_MODEL=qwen2.5:7b
Enable Gemini with:
    TIS_LLM_ALLOW_CLOUD=1  TIS_GEMINI_API_KEY=...  [TIS_GEMINI_MODEL=gemini-2.5-flash]
A private TIS_LLM_URL always wins over Gemini, so the safe option stays the default.
"""
import ipaddress
import json
import os
import re
import socket
import urllib.error
import urllib.request
from urllib.parse import urlparse

SYSTEM_PROMPT = (
    "You are the Transportation Intelligence assistant for a logistics operation. "
    "Answer ONLY from the FACTS provided. Never invent trucks, times or numbers. "
    "Be short and operational: what is happening, why, and the recommended next action. "
    "Keep tokens like DRIVER_1 unchanged. Cite SOP ids when the facts include them."
)


GEMINI_HOST = "generativelanguage.googleapis.com"
GEMINI_MODEL = "gemini-3.8-flash"   # `models?key=` lists what a key may use
TRUTHY = {"1", "true", "yes", "on"}


class DataBoundaryError(RuntimeError):
    pass


def build_prompt(question, draft, facts, pseudo):
    """The single user turn every provider sends - already pseudonymised."""
    payload = pseudo.mask(json.dumps(facts, default=str, ensure_ascii=False))
    return (f"QUESTION: {pseudo.mask(question)}\n\nFACTS:\n{payload}\n\n"
            f"DRAFT ANSWER:\n{pseudo.mask(draft)}\n\nRewrite the draft as a clear answer.")


def describe_http_error(e):
    """Short, key-free reason for a failed API call - good enough to act on."""
    if isinstance(e, urllib.error.HTTPError):
        try:
            msg = json.load(e).get("error", {}).get("message", "")
        except Exception:
            msg = ""
        return f"HTTP {e.code}" + (f": {msg}" if msg else "")
    return e.__class__.__name__


def assert_private_endpoint(url):
    host = urlparse(url).hostname
    if not host:
        raise DataBoundaryError(f"invalid LLM url: {url}")
    try:
        addrs = {info[4][0] for info in socket.getaddrinfo(host, None)}
    except socket.gaierror as e:
        raise DataBoundaryError(f"cannot resolve {host}: {e}")
    for a in addrs:
        ip = ipaddress.ip_address(a)
        if not (ip.is_loopback or ip.is_private):
            raise DataBoundaryError(f"{host} resolves to public address {a}; refusing to send company data")


class Pseudonymizer:
    """Replace driver names (personal data) with stable tokens and back."""

    def __init__(self, names):
        self.fwd = {n: f"DRIVER_{i + 1}" for i, n in enumerate(sorted(set(names), key=len, reverse=True))}
        self.rev = {v: k for k, v in self.fwd.items()}

    def mask(self, text):
        for name, tok in self.fwd.items():
            text = text.replace(name, tok)
        text = re.sub(r"\+9665\d{8}", "[PHONE]", text)
        return re.sub(r"\b1\d{9}\b", "[NATIONAL_ID]", text)

    def unmask(self, text):
        return re.sub(r"DRIVER_\d+", lambda m: self.rev.get(m.group(0), m.group(0)), text)


class OfflineNarrator:
    name = "offline (deterministic, no model)"

    def narrate(self, question, draft, facts, pseudo):
        return draft


class LocalLLM:
    def __init__(self, base_url, model, timeout=60):
        assert_private_endpoint(base_url)
        self.url = base_url.rstrip("/") + "/v1/chat/completions"
        self.model, self.timeout = model, timeout
        self.name = f"local model {model} @ {urlparse(base_url).hostname}"

    def narrate(self, question, draft, facts, pseudo):
        body = {"model": self.model, "temperature": 0.1, "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_prompt(question, draft, facts, pseudo)}]}
        req = urllib.request.Request(self.url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                text = json.load(r)["choices"][0]["message"]["content"]
        except Exception as e:                       # model down -> deterministic answer still works
            return draft + f"\n\n(local model unavailable: {e.__class__.__name__}; showing rule-based answer)"
        return pseudo.unmask(text)


class GeminiLLM:
    """Google Gemini over the public generativelanguage API.

    This is the one provider that leaves the company network, so the caller has
    to say so explicitly (see `from_env`). The facts are pseudonymised first, so
    what travels is TRK-012 / DRIVER_3 / [PHONE], never a real person.
    """

    def __init__(self, api_key, model=GEMINI_MODEL, timeout=60, host=GEMINI_HOST):
        if not (api_key or "").strip():
            raise DataBoundaryError("Gemini API key is empty")
        self.key = api_key.strip()
        self.model, self.timeout = model, timeout
        self.url = f"https://{host}/v1beta/models/{model}:generateContent"
        self.name = f"Google Gemini {model} (public cloud, pseudonymised facts)"

    def narrate(self, question, draft, facts, pseudo):
        body = {"systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
                "contents": [{"role": "user", "parts": [{"text": build_prompt(question, draft, facts, pseudo)}]}],
                "generationConfig": {"temperature": 0.1}}
        # the key goes in a header, never in the url, so it stays out of logs
        req = urllib.request.Request(self.url, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", "x-goog-api-key": self.key})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                data = json.load(r)
            parts = data["candidates"][0]["content"]["parts"]
            # reasoning models return thought parts too; only the answer is wanted
            text = "".join(p.get("text", "") for p in parts if not p.get("thought")).strip()
            if not text:
                raise ValueError("empty response")
        except Exception as e:                       # API down or quota spent -> deterministic answer still works
            return draft + f"\n\n(Gemini unavailable: {describe_http_error(e)}; showing rule-based answer)"
        return pseudo.unmask(text)


def from_env():
    url = os.environ.get("TIS_LLM_URL")
    if url:                                          # a private endpoint always wins
        return LocalLLM(url, os.environ.get("TIS_LLM_MODEL", "llama3.1:8b"))
    key = os.environ.get("TIS_GEMINI_API_KEY", "").strip()
    if not key:
        return OfflineNarrator()
    if os.environ.get("TIS_LLM_ALLOW_CLOUD", "").strip().lower() not in TRUTHY:
        raise DataBoundaryError(
            "TIS_GEMINI_API_KEY is set but TIS_LLM_ALLOW_CLOUD is not. Gemini is a public "
            "endpoint: set TIS_LLM_ALLOW_CLOUD=1 to confirm pseudonymised operational facts "
            "may leave the network, or unset the key to stay offline.")
    return GeminiLLM(key, os.environ.get("TIS_GEMINI_MODEL", GEMINI_MODEL))
