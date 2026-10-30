# Using Google Gemini as the language layer

The system answers every question deterministically without any model. A model
is only used to rewrite the final draft in natural language. By default that
model must live on a private address (`LocalLLM`), because the facts handed to
it describe real operations. Gemini is a **public** endpoint, so turning it on
is a deliberate, two-part decision.

## 1. Get the right kind of key

A Gemini **API key** comes from Google AI Studio: <https://aistudio.google.com/apikey>.
It looks like `AIza...`.

It is not the same thing as a Gmail account, a Google One / Gemini Advanced
subscription, or an app password. Those pay for the Gemini *app* and give you no
API key. The API has its own free tier and its own paid billing, set up in AI
Studio or Google Cloud.

## 2. Put the key in the environment, not in the code

```bash
cp .env.example .env          # then edit .env
set -a && . ./.env && set +a  # load it into this shell
```

Or directly:

```bash
export TIS_LLM_ALLOW_CLOUD=1
export TIS_GEMINI_API_KEY='AIza...'
export TIS_GEMINI_MODEL=gemini-2.5-flash   # optional
```

`TIS_LLM_ALLOW_CLOUD` is the acknowledgement that pseudonymised operational
facts may leave the network. Without it the key alone is refused:

```
! TIS_GEMINI_API_KEY is set but TIS_LLM_ALLOW_CLOUD is not. ...
```

## 3. Check it

```bash
python3 -m tis llm-check     # dummy facts only, no company data
```

Expected:

```
Language layer: Google Gemini gemini-2.5-flash (public cloud, pseudonymised facts)
...
OK - the model answered.
```

A wrong key prints `HTTP 400: API key not valid`; an exhausted free tier prints
`HTTP 429`. In both cases the system keeps working and shows the rule-based
answer. Then use it as normal:

```bash
python3 -m tis ask "Why is TRK-007 late?"
python3 -m tis chat
```

## What actually gets sent

Per question, one HTTPS POST to `generativelanguage.googleapis.com` containing:

* the question,
* the facts the deterministic agents already assembled (truck ids, times,
  delays, customer codes, SOP ids),
* the draft answer to rewrite.

Before anything is sent, `Pseudonymizer` replaces driver names with
`DRIVER_1`-style tokens, phone numbers with `[PHONE]` and national ids with
`[NATIONAL_ID]` (the fields listed in `config.PERSONAL_FIELDS`). The model
answers with the tokens; they are re-identified locally. So personal data stays
inside, but **commercial data does leave** — customer codes, volumes, delays and
SOP references. Decide whether that is acceptable under your own policy before
setting `TIS_LLM_ALLOW_CLOUD=1`; for a PDPL-sensitive deployment, run a local
model instead and leave the default boundary in place.

The API key is sent in the `x-goog-api-key` header, never in a URL or a log
line, and is never written into reports or the audit log.

## Precedence

| Environment | Language layer |
|---|---|
| nothing set | `OfflineNarrator` — deterministic text |
| `TIS_LLM_URL` set | `LocalLLM` — private endpoint wins, even if a Gemini key is also set |
| Gemini key + `TIS_LLM_ALLOW_CLOUD=1` | `GeminiLLM` |
| Gemini key alone | refused with `DataBoundaryError` |
