# AI Social Media Team: example model

A working example of the "autonomous social media team for a one-person business" spec:
research → content → **approval queue** → publishing → lead triage → revenue attribution →
morning briefing, run from a Telegram bot.

- **Pure Python standard library.** The demo and the tests need no `pip install`, no keys and no internet.
- **Claude is optional.** Set `ANTHROPIC_API_KEY` (and `pip install anthropic`) to have Claude
  (`claude-opus-5-5`) write the copy and classify inbound messages. Without it, deterministic
  templates and rules do the same jobs.
- **Nothing goes live without you.** The approval gate is code (`smt/gate.py`), not a sentence in a prompt.
- **All demo data is synthetic.** The brand ("Ledger Lane", bookkeeping for freelancers), competitors,
  hooks, comments and sales are invented.

The critical review of the spec (what to change before you deploy it) is in
**[SMT_ANALYSIS.md](SMT_ANALYSIS.md)**.

## Quick start

```bash
python3 -m smt demo                          # the full week, offline, in about one second
python3 -m unittest tests.test_smt           # 35 tests: gate, bot, research, triage, retries, attribution
```

The demo walks through every module: 3 weeks of synthetic history, research, a 10-post week
in the queue, a stranger and the agent itself both failing to approve, the owner approving in
Telegram, peak-time dispatch to `outbox/`, a filming brief for a video, 8 inbound messages
(3 qualified leads, 1 spam, 4 reply drafts), a won lead attributed to its post, and the 07:00 briefing.

## How it maps onto the spec

| Spec | Where | What it does |
|---|---|---|
| Trend discovery | `research.py` | Brave Search (news, Reddit questions) and YouTube Data API (top Shorts) via **official APIs**; `SampleSource` offline |
| Hook engineering | `research.py` | Classifies each top hook's trigger (curiosity, pain point, authority, listicle, contrarian, social proof), writes a **new** hook with the same trigger, and rejects it if it shares more than 35% of the source's word 3-grams |
| Multi-format assembly | `content.py` | Video scripts (0–3 s hook, beats, pattern interrupt, CTA, shot notes), carousels, text posts, per-platform captions, hashtags, length limits, banned-phrase lint |
| Approval queue | `gate.py`, `queue_item` table | One queue for posts, replies, DMs and outreach. Owner-only approval via Telegram or the CLI. Approval is bound to a SHA-256 of the content, so any edit sends the item back to PENDING |
| Omnichannel dispatch | `publishers.py` | Dry-run (default), Buffer/Zapier/Make webhook, X API v2, LinkedIn Posts API. Approved items go out at each platform's peak slot; late approvals move to the next slot |
| Lead triage | `triage.py` | Rules (or Claude) classify comments/DMs: lead, question, praise, complaint, spam, lead-magnet keyword. Leads trigger an instant Telegram alert and are **never** auto-answered |
| Revenue attribution | `analytics.py` | Per-post tracking code + UTM link → clicks, sign-ups, sales; lead → `/won` → sale. Ranks hook triggers and topics by revenue, not views |
| Morning briefing | `analytics.py`, `scheduler.py` | 07:00 one-pager: yesterday vs the day before, what makes money, what's waiting for you, open leads, errors that need you |
| Error resilience | `resilience.py` | Exponential backoff (2, 4, 8, 16 s, honours `Retry-After`). Logs every failure and notifies you only when retries run out or the error is permanent |
| Credential vault | `.env` (`deploy/.env.example`) | Loaded at start-up, `chmod 600`, never committed |

```text
  06:00 research ──► trend / hook tables ──► content.generate_week ──► queue_item (PENDING)
                                                                            │
                                   Telegram: ✅ Approve  ❌ Reject  ✏️ Edit ◄─┘   (owner chat id only)
                                                                            │
                                                         gate.publishable() re-checks status, approver, hash
                                                                            │
                         every minute: dispatch_due ──► publisher (dry-run │ webhook │ X │ LinkedIn)
                                                                            │   video without file → filming brief
  every 10 min: inbox/*.json ──► triage ──► lead → 🔥 alert                  │
                                       └──► reply draft → queue (PENDING) ──┘
  sales export / /won ──► event table ──► attribution ──► 07:00 briefing
```

## Telegram commands

| Command | Does |
|---|---|
| `/queue` | One card per pending item with ✅ / ❌ / ✏️ buttons |
| `/approve 12`, `/reject 12 too salesy` | Decide one item |
| `/edit 12 caption <text>` (or `text`, `hashtags`) | Change it; it then needs approval again |
| `/approve_replies` | Approve all pending reply/DM drafts at once (never posts) |
| `/leads`, `/won 3 1200` | Open leads; record a sale against the post that produced the lead |
| `/brief` | The morning briefing, now |

Messages from any other chat are ignored and written to the audit log.

## Deploying (Oracle Cloud VPS + Telegram, recommended)

1. **Create a VM.** An Oracle Cloud Always Free Ampere A1 VM (Ubuntu 24.04) is plenty: the agent
   uses almost no CPU. See the note on idle reclamation in the analysis.
2. **Create the bot.** Message @BotFather → `/newbot` → copy the token. Send your bot any message,
   then open `https://api.telegram.org/bot<TOKEN>/getUpdates` and copy `chat.id`.
3. **Install.**
   ```bash
   git clone <this repo> && cd NMSD.A
   bash deploy/setup_vps.sh                     # user `smt`, /opt/smt, venv, systemd units
   sudo -u smt nano /opt/smt/.env               # TELEGRAM_BOT_TOKEN, SMT_OWNER_CHAT_ID, then the rest
   sudo systemctl enable --now smt-agent smt-bot
   journalctl -u smt-agent -f
   ```
   Prefer PM2? `pm2 start deploy/ecosystem.config.js && pm2 save && pm2 startup`.
   tmux works for testing but doesn't restart after a crash or reboot.
4. **Edit the brand.** Copy `BRAND` from `smt/config.py` into `/opt/smt/brand.json` (your offer,
   audience, voice, banned phrases, CTA, landing URL) and set `SMT_BRAND_FILE`. Replace the
   `TOPIC_POINTS` in `smt/content.py` with your own expertise.
5. **Run dry for a week.** Keep `SMT_PUBLISH_MODE=dryrun`, approve posts in Telegram, and read
   what lands in `outbox/`. Then set `live` and configure `SMT_WEBHOOK_URL` (Buffer/Zapier/Make)
   and/or the X and LinkedIn tokens.
6. **Feed it inbound and sales.** Have Zapier/Make drop new comments/DMs as JSON into
   `/opt/smt/inbox/` (`[{platform, channel, external_id, author, text}]`), and import clicks, sign-ups and sales
   with `python -m smt import-events sales.csv` (columns `ts, kind, utm_content, value`), or use `/won`.

No inbound port is needed (the bot uses long polling). Keep the firewall closed except SSH.

**Running locally instead:** the same commands work on a laptop (`python3 -m smt run` and
`python3 -m smt bot` in two terminals). That's fine for the dry-run week, but the 07:00 briefing
and peak-time posts stop whenever the laptop sleeps.

## CLI

```bash
python3 -m smt init [--history]      python3 -m smt research        python3 -m smt generate
python3 -m smt queue                 python3 -m smt show 12         python3 -m smt approve 12
python3 -m smt edit 12 --caption ".."   python3 -m smt attach-media 12 clip.mp4
python3 -m smt dispatch              python3 -m smt inbox [file]    python3 -m smt leads
python3 -m smt won 3 1200            python3 -m smt import-events sales.csv
python3 -m smt attribution --by topic   python3 -m smt brief
python3 -m smt run                   python3 -m smt bot
```

The CLI acts as the owner: it's for you, on the server, over SSH.

## Layout

```
smt/        config, db, gate, research, content, llm, publishers, triage, analytics, telegram, scheduler, sim, CLI
samples/    synthetic research data and inbound messages
deploy/     .env.example, setup_vps.sh, systemd units, PM2 ecosystem file
tests/      test_smt.py
```
