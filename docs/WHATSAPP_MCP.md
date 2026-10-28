# WhatsApp Business MCP

A standard [Model Context Protocol](https://modelcontextprotocol.io) server that connects a
WhatsApp Business number to any AI client — Claude Code, Claude Desktop, Cursor, VS Code,
Windsurf, Zed, or anything else that speaks MCP.

* **Pure Python standard library.** No `pip install`, no Node, no build step, no daemon.
* **Official API.** Meta's WhatsApp Business Cloud API — not a reverse-engineered web client,
  so nothing gets your number banned.
* **Safe by default.** Opt-outs honoured, the 24-hour window enforced, an optional
  allow-list and hourly cap, a dry-run mode and an audit trail of every send.
* **Your data stays yours.** Message history lives in a SQLite file on your machine
  (`~/.wamcp/wamcp.db`, mode 0600). Nothing is sent anywhere except to Meta.

---

## Quick start

Two ways. Both end in the same place.

### Just ask your AI

If the server is already registered with your client, say:

> **connect my WhatsApp**

It calls `whatsapp_setup`, tells you which page to open, and you paste back the
grey `curl` box that Meta shows you. That box already contains your access token
and your phone number ID, so it is the only thing you ever copy — the server
asks Meta for everything else itself.

### Or one command in a terminal

```bash
python3 -m wamcp
```

With nothing configured yet, that *is* the setup wizard. It:

1. tells you exactly which page to open and what to copy,
2. takes one paste — the `curl` box, a `.env` file, or just the token,
3. asks Meta which number and business account that token can reach, so you
   do not have to look them up,
4. detects the AI clients installed on this machine and offers to add itself
   to them,
5. checks the whole chain and offers to send a test message to your own phone.

```text
==================================================================
 WhatsApp Business MCP - setup
==================================================================
...
On that page there is a grey box of sample code starting with "curl".
COPY THE WHOLE BOX. It already contains your access token and your
phone number ID, so that is the only thing you need to copy.

Paste, then press Enter on an empty line:
<paste>

Got it - found your access token, phone number ID.
Checking the token with Meta and looking up your account...
  number:  My Shop +1 555 000 0001
  account: My Shop WABA

Saved to ~/.wamcp/config.json (only you can read it).

WhatsApp Business MCP 1.0.0 - ready to send, with warnings
[ ok ] credentials: token EAAP...9xQZ (211 chars), phone_number_id 713504158376402
[ ok ] cloud api: My Shop on +1 555 000 0001 (quality GREEN)

Found on this machine: Claude Code (CLI, VS Code and JetBrains extensions)
Add WhatsApp to it now? [Y/n]:
  created   ~/.claude.json

Restart the client, then say to it:  "check my WhatsApp connection"
```

### What you need from Meta (about 10 minutes, free)

1. [developers.facebook.com/apps](https://developers.facebook.com/apps) →
   **Create App** → *Business* → add the **WhatsApp** product.
2. Open **WhatsApp → API Setup** and copy the grey `curl` box. That is it.

Under **To** on the same page, add your own phone number — a test number may
only message numbers on that list.

If you would rather hand over the values one by one, they are on that page too:

| Value | Used for |
|---|---|
| **Access token** | every call. The one on this page lasts 24 hours |
| **Phone number ID** | which number you send from |
| **WhatsApp Business Account ID** | templates (discovered automatically) |

For a token that does not expire: **Business Settings → System Users → Add**,
give it the WhatsApp app with `whatsapp_business_messaging` and
`whatsapp_business_management`, then **Generate token** with expiry *Never*.
Re-run setup and paste the new one.

### What a paste can be

Setup accepts anything that contains the values, and ignores the rest:

* the `curl` sample from Meta's API Setup page (token + phone number ID),
* a `.env` file or `KEY=value` lines,
* a JSON config,
* text copied off the dashboard, labels and all,
* or just the access token on its own.

### Your first message

Your own number has not written to the business yet, so the 24-hour window is
closed and WhatsApp will only deliver a template. Every new account has
`hello_world` — setup offers to send it for you, or:

```bash
python3 -m wamcp send --to +<your number> --template hello_world
```

Reply from your phone, and the window opens for 24 hours. Now plain text works:

```bash
python3 -m wamcp send --to +<your number> --text "Sent by an AI, through the official API."
```

## Connecting an AI client

Setup does this for you, for every client it finds on the machine. To do it by
hand, `python3 -m wamcp install <client>` merges the server into that client's
config file and leaves any other servers you have alone (it also writes a
`.wamcp-backup` copy first).

| Client | Command |
|---|---|
| Claude Code | `python3 -m wamcp install claude-code` |
| Claude Code, shared with a repo | `python3 -m wamcp install claude-code-project` → `.mcp.json` |
| Claude Desktop | `python3 -m wamcp install claude-desktop` |
| Cursor | `python3 -m wamcp install cursor` |
| VS Code (Copilot agent mode) | `python3 -m wamcp install vscode` |
| Windsurf | `python3 -m wamcp install windsurf` |
| Zed | `python3 -m wamcp install zed` |
| Anything else | `python3 -m wamcp install print` |

Claude Code also takes a one-liner, which `install` prints for you:

```bash
claude mcp add whatsapp-business --scope user --env PYTHONPATH=/path/to/NMSD.A -- python3 -m wamcp serve
```

The generated block is the same everywhere:

```json
{
  "mcpServers": {
    "whatsapp-business": {
      "command": "python3",
      "args": ["-m", "wamcp", "serve"],
      "env": { "PYTHONPATH": "/path/to/NMSD.A" }
    }
  }
}
```

Credentials are deliberately **not** put in that file — the server reads
`~/.wamcp/config.json` itself, so a synced or committed client config never carries your
token. Pass `--with-secrets` if the client runs somewhere that cannot see your home
directory (a container, a remote host), and keep that file private.

### Over HTTP, for hosted agents

```bash
python3 -m wamcp serve --http --port 8765 --token "$(openssl rand -hex 16)"
```

Streamable HTTP, stateless: each `POST /` carries one JSON-RPC message, `GET /health`
reports the version. Bind it to localhost or require the bearer token — preferably both.

---

## Receiving messages

The Cloud API has no endpoint for reading a conversation: **inbound messages only arrive as
webhooks.** Until the receiver runs, the inbox tools see only what this server sent.

```bash
python3 -m wamcp webhook --public-url https://chat.example.com
```

It prints the callback URL and verify token to paste into **WhatsApp → Configuration →
Webhooks**; then subscribe to the **messages** field. The URL must be reachable from the
internet over HTTPS — for a laptop, put a tunnel in front:

```bash
cloudflared tunnel --url http://localhost:8080
python3 -m wamcp webhook --public-url https://<the-tunnel-host>
```

Every POST is verified against `X-Hub-Signature-256` (HMAC-SHA256 of the exact body, keyed
with your app secret), so nobody who learns the URL can write into your inbox. Without
`WHATSAPP_APP_SECRET` the receiver refuses POSTs unless you pass `--insecure`.

Run it beside the MCP server — both use the same SQLite file in WAL mode, so they can
read and write at the same time. On a server, a systemd unit or `tmux` is enough.

---

## The tools your AI gets

**Sending** — `whatsapp_send_message`, `whatsapp_send_template`, `whatsapp_send_media`,
`whatsapp_send_buttons`, `whatsapp_send_list`, `whatsapp_send_link_button`,
`whatsapp_send_location`, `whatsapp_send_contact_card`, `whatsapp_react`,
`whatsapp_mark_read`

**Inbox** — `whatsapp_list_chats`, `whatsapp_read_chat`, `whatsapp_search_messages`,
`whatsapp_message_status`, `whatsapp_download_media`, `whatsapp_upload_media`

**Contacts and consent** — `whatsapp_find_contact`, `whatsapp_set_contact_note`,
`whatsapp_consent`

**Templates** — `whatsapp_list_templates`, `whatsapp_create_template`

**Account and setup** — `whatsapp_account_info`, `whatsapp_update_profile`,
`whatsapp_status`, `whatsapp_setup`

`whatsapp_setup` is what makes "connect my WhatsApp" work as a sentence: called
with no arguments it returns the instructions for the user, and called with
their paste it saves the credentials, discovers the number and business account
from the token, and takes effect immediately without a restart. Anything pasted
into a chat is visible in that conversation, so if you would rather your token
never appear there, use `python3 -m wamcp setup` in a terminal — it does the
same work.

Also exposed: resources `whatsapp://status`, `whatsapp://chats`, `whatsapp://setup`, and
prompts `whatsapp_connect`, `whatsapp_triage`, `whatsapp_reply`.

Things you can now say to your assistant:

> *Any new WhatsApp messages?*
> *Reply to Josim that the order is ready, and ask whether to deliver or hold for pickup.*
> *Which chats are still waiting on us? Draft a reply for each one.*
> *Download the photo Ahmed sent and tell me what the meter reading says.*
> *Send the delivery template to everyone who ordered today.*

---

## The two rules that shape everything

**1. The 24-hour customer service window.** Free-form messages — text, media, buttons,
lists — are only delivered within 24 hours of that person's last message to you. Outside
it, only an **approved template** is delivered, and a template is also the only way to
start a conversation with somebody new.

This server tracks the window per contact and refuses before calling Meta, with the
remedy in the error, so an assistant can correct itself instead of silently failing:

```
the 24-hour customer service window for +966501234567 is closed (their last message
was 2d 4h ago)
what to do: WhatsApp only delivers free-form messages within 24 hours of the person's
last message. Send an approved template with whatsapp_send_template instead - list them
with whatsapp_list_templates.
```

**2. Consent.** Someone who writes `STOP` (or `UNSUBSCRIBE`, `CANCEL`, `REMOVE ME`…) is
opted out the moment the webhook arrives, and every later send to them is refused —
templates included. `START` opts back in. Nothing in the tool surface can override it;
clearing an opt-out takes a human running
`python3 -m wamcp consent opt_in <number>`.

---

## Guardrails

An AI with a WhatsApp number can reach real people, so sending is not a bare API call.
Each of these is one environment variable:

| Setting | Effect |
|---|---|
| `WAMCP_DRY_RUN=1` | Build and validate every message, send nothing, return the exact payload. The way to try a workflow safely. |
| `WAMCP_ALLOWLIST=+966…,+966…` | Only these numbers can be messaged at all. |
| `WAMCP_MAX_SENDS=30` | Hourly cap across all recipients, so a loop cannot spam anyone. |
| `WAMCP_ALLOW_OUTSIDE_WINDOW=1` | Stop enforcing the 24-hour window locally and let Meta decide. Off by default. |
| `WAMCP_DEFAULT_COUNTRY=966` | Accept local numbers like `050 123 4567`. |

Every send, refusal and dry run lands in the audit table: `python3 -m wamcp audit`.

Beyond that, the ordinary advice applies: your assistant drafts, you approve, it sends
once. These messages land on real phones and cannot be unsent.

---

## All the settings

| Variable | Default | Meaning |
|---|---|---|
| `WHATSAPP_ACCESS_TOKEN` | — | **Required.** Cloud API token |
| `WHATSAPP_PHONE_NUMBER_ID` | — | **Required.** Which number to send from |
| `WHATSAPP_BUSINESS_ACCOUNT_ID` | — | WABA id; needed for templates |
| `WHATSAPP_APP_SECRET` | — | Verifies inbound webhooks |
| `WHATSAPP_VERIFY_TOKEN` | — | The string Meta echoes when subscribing |
| `WHATSAPP_API_VERSION` | `v24.0` | Graph API version |
| `WHATSAPP_API_BASE` | `https://graph.facebook.com` | Override for a BSP or a test double |
| `WAMCP_DB` | `~/.wamcp/wamcp.db` | Message store |
| `WAMCP_MEDIA_DIR` | `~/.wamcp/media` | Where downloads land |
| `WAMCP_TIMEOUT` | `30` | Seconds per API call |
| `WAMCP_HOME` | `~/.wamcp` | Moves the whole config directory |

Any secret can come from a file instead of the environment — `WHATSAPP_ACCESS_TOKEN_FILE`,
`WHATSAPP_APP_SECRET_FILE`, `WHATSAPP_VERIFY_TOKEN_FILE` — which keeps it out of client
configs and process listings.

---

## Commands

```bash
python3 -m wamcp                            # with nothing configured: the setup wizard
python3 -m wamcp setup                      # the same thing, explicitly (alias: connect)
python3 -m wamcp doctor [--json]            # check credentials, API access, inbox
python3 -m wamcp install [client] [--list] [--with-secrets] [--dry-run]
python3 -m wamcp serve [--http --port N --token T]
python3 -m wamcp webhook --public-url https://host [--port 8080] [--insecure]
python3 -m wamcp send --to +966... --text "hi"
python3 -m wamcp send --to +966... --template order_update --param A12 --param today
python3 -m wamcp inbox [contact] [--unanswered] [--json]
python3 -m wamcp templates
python3 -m wamcp consent {check,list,opt_out,opt_in} [number]
python3 -m wamcp audit
python3 -m wamcp selftest                   # 138 tests, no network
```

---

## When something goes wrong

Run `python3 -m wamcp doctor` first: it names the problem and the fix. Otherwise, the
errors that actually come up:

| What you see | What it means |
|---|---|
| *token has expired or was revoked* (190) | The API Setup token lasts 24 hours. Make a System User token. |
| *recipient is not in this number's allowed list* (131030) | Add the number under **To** in API Setup, or finish business verification. |
| *outside the 24-hour window* (131047) | Send a template instead. |
| *template does not exist in this language* (132001) | Run `whatsapp_list_templates`; the language code must match exactly (`en_US` ≠ `en`). |
| *parameter count does not match* (132000) | Count the `{{n}}` placeholders and pass exactly that many. |
| *Message undeliverable* (131026) | The number is not on WhatsApp, or will not accept business messages. |
| Inbox always empty | The webhook receiver is not running, or Meta is not subscribed to the `messages` field. |
| Client shows no tools | Check the client's MCP log. The server writes diagnostics to stderr and protocol only to stdout. |
| Setup found no phone number | The token is probably app-scoped rather than business-scoped. Paste the `curl` box instead of just the token, or give the Phone number ID when asked. |
| Running `python3 -m wamcp` starts a wizard when you wanted the server | It only does that with nothing configured *and* a terminal attached. Use `python3 -m wamcp serve` to be explicit. |

Every Cloud API error comes back with Meta's code, their `details` and a `what_to_do`
line, so an assistant can usually fix its own next call.

---

## How it fits together

```text
  AI client (Claude Code, Desktop, Cursor, ...)
        │  MCP over stdio (or Streamable HTTP)
        ▼
  mcp.py ── tools.py ── policy.py ────────────┐  opt-out · allow-list · 24h window
        │       │                             │  hourly cap · dry run · audit
        │       ├── messages.py  build + validate every message type
        │       ├── phones.py    E.164 normalisation
        │       └── graph.py ──► graph.facebook.com  (WhatsApp Cloud API)
        │
        └── store.py  SQLite: messages, contacts, opt-outs, audit
                 ▲
                 │
  webhook.py ◄───┴── Meta's webhook POSTs (HMAC-SHA256 verified)
```

## Design notes

* **Errors are data.** A failed tool call returns `isError` with a structured payload —
  Meta's code, their details, and what to do — rather than throwing. Models recover from
  that; they do not recover from a stack trace.
* **The gate is not in the tools.** Every send, whatever its type, goes through
  `policy.check()`. There is no tool that can skip consent or the window.
* **Secrets never widen.** They are masked in `doctor`, in the audit log and in
  `whatsapp://status`; the store is `0600`; the client config gets no token unless asked.
* **No polling.** Inbound messages are pushed by Meta, so neither the server nor the model
  burns calls asking "anything new?".
* **Setup is a tool, not just a script.** The same flow runs from a terminal and
  from inside a conversation, because the hard part — finding three values in a
  dashboard — is the same either way. Parsing a paste and asking Meta what the
  token can reach removes almost all of the typing.
* **Protocol version negotiation is forgiving.** The server echoes the client's MCP
  revision when it recognises it and otherwise answers with its own, so new clients and
  old ones both work on a transport whose framing has not changed.
