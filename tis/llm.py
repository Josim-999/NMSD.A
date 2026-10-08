"""Language-model layer with a hard data boundary.

* `OfflineNarrator` (default) - no model at all; agents' deterministic text is
  returned unchanged. The whole system works without any AI server.
* `LocalLLM` - any OpenAI-compatible endpoint (vLLM, Ollama, NVIDIA NIM,
  llama.cpp server). It refuses to talk to anything that is not loopback or a
  private-network address, so company data cannot leave the network by
  accident, and personal data is pseudonymised before every call.

Enable with:  TIS_LLM_URL=http://127.0.0.1:11434  TIS_LLM_MODEL=qwen2.5:7b
"""
import ipaddress
import json
import os
import re
import socket
import urllib.request
from urllib.parse import urlparse

SYSTEM_PROMPT = (
    "You are the Transportation Intelligence assistant for a logistics operation. "
    "Answer ONLY from the FACTS provided. Never invent trucks, times or numbers. "
    "Be short and operational: what is happening, why, and the recommended next action. "
    "Keep tokens like DRIVER_1 unchanged. Cite SOP ids when the facts include them."
)


class DataBoundaryError(RuntimeError):
    pass


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
        payload = pseudo.mask(json.dumps(facts, default=str, ensure_ascii=False))
        body = {"model": self.model, "temperature": 0.1, "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f"QUESTION: {pseudo.mask(question)}\n\nFACTS:\n{payload}\n\n"
                                        f"DRAFT ANSWER:\n{pseudo.mask(draft)}\n\nRewrite the draft as a clear answer."}]}
        req = urllib.request.Request(self.url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                text = json.load(r)["choices"][0]["message"]["content"]
        except Exception as e:                       # model down -> deterministic answer still works
            return draft + f"\n\n(local model unavailable: {e.__class__.__name__}; showing rule-based answer)"
        return pseudo.unmask(text)


def from_env():
    url = os.environ.get("TIS_LLM_URL")
    if not url:
        return OfflineNarrator()
    return LocalLLM(url, os.environ.get("TIS_LLM_MODEL", "llama3.1:8b"))
