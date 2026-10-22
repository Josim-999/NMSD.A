# Analysis of the "AI social media team" spec

This review covers the requirements spec and master prompt for an autonomous social media team
for a one-person business. The example model in `smt/` was built to test the spec in working
code, not just on paper. Platform policies change often: **check each platform's current
developer docs before relying on any detail below.**

---

## 1. Verdict

The overall shape is right: research → drafts → human approval → publish → triage → measure
revenue. The example model implements all four modules, and the parts that matter most work
**without any AI model at all**. But the spec has one internal contradiction, two plans that
would get the accounts banned, and one large gap (videos).

| Spec item | Verdict | What the example model does |
|---|---|---|
| Human approval before anything goes live | **Correct, and the most important rule** | Enforced in code (`gate.py`): owner-only, trusted channel only, bound to a content hash, re-checked at dispatch |
| "Auto-reply to standard queries" | **Contradicts the safety rule** | Replies are drafted, not sent. `/approve_replies` approves them all in one tap (§2.1) |
| Scrape TikTok/Instagram with Playwright | **Don't** | Official APIs only: YouTube Data API, Brave Search; manual swipe file for the rest (§2.2) |
| "Authorized automation wrappers" for posting | **Use official APIs or a scheduler with approved access** | Webhook to Buffer/Zapier/Make, plus X and LinkedIn APIs (§2.3) |
| "A full week of content" for TikTok/Reels/Shorts | **The agent can write it, but you have to film it** | Approved scripts become a filming brief; the video goes through approval again (§2.4) |
| Claude Code as the 24/7 engine in tmux | **Wrong tool for a daemon** | Plain Python service; Claude API only for copy and intent; PM2/systemd (§2.5) |
| Revenue attribution, not vanity metrics | **Correct** | Per-post tracking codes; the demo shows the most-viewed hook type is not the one that sells |
| Exponential backoff, notify only when needed | **Correct** | `resilience.py`, with tests |
| Telegram as the control surface | **Correct** | Owner chat id allow-list, long polling, no open ports |

---

## 2. What the spec gets wrong or underplays

### 2.1 Auto-replies vs "zero autonomous comments"
Module 3 says *"automatically reply to standard queries"*. The safety section says *"zero posts,
comments, or outreach messages go live without explicit confirmation"*. Both can't be true. A
public reply is a comment, and a mis-read sarcastic comment answered cheerfully in your name is
exactly the brand risk the gate exists for.

**Resolution in the example:** the agent drafts replies into the same queue as posts, and
`/approve_replies` approves all pending replies in one tap. Leads are never auto-answered; you
get an alert and reply yourself, because that's where the money is.

### 2.2 Scraping TikTok and Instagram will cost you the accounts
Headless-browser scraping of TikTok and Instagram is against their terms. It breaks every few
weeks, and it gets the logged-in account (yours) restricted. Use instead:

- **YouTube Data API**: top Shorts in your niche by views. Titles are a usable proxy for hooks
  (`YouTubeShortsSource`).
- **Brave Search API** (or Google's): news and Reddit/forum questions for trends (`BraveSearchSource`).
- **Your own accounts' insights** through the official Instagram/TikTok APIs: what worked *for you*
  beats what worked for a competitor.
- **A manual swipe file.** Ten minutes a week saving competitor hooks you like into a JSON file
  (`SampleSource` reads that format). That's legal, and it's often better data than a scraper.

"Adapt without plagiarism" also needs a mechanical check, not just good intentions. The example
rejects any adapted hook that reuses more than 35% of the source's word 3-grams.

### 2.3 Publishing: official access is the bottleneck, not code
- **TikTok Content Posting API**: until your app passes TikTok's audit, posts from it are
  restricted to private visibility.
- **Instagram Graph API**: needs a Business/Creator account linked to a Facebook Page, app review
  for the publishing permissions, and a daily cap on API-published posts.
- **YouTube**: an upload uses a large share of the default daily API quota.
- **X**: write access depends on your API tier.
- **LinkedIn**: posting as yourself needs the "Share on LinkedIn" product on your app.

For one person, the realistic route is a scheduler that already holds this access (Buffer,
Later, Metricool) or Zapier/Make in front of them. That's why the example has a
`WebhookPublisher`. Direct X and LinkedIn publishers are included for text posts.
"Unofficial automation wrappers" (private-API libraries) are the fastest way to a ban.

### 2.4 The biggest gap: someone has to film the videos
Three of the five channels are short-form video. The agent can research, script, caption and
schedule, but it can't appear on camera. The spec never says who turns scripts into videos.
In the example:

1. An approved `video_script` with no file is set to `AWAITING_MEDIA`, and the owner gets a
   filming brief (hook, beats, CTA, shot notes) on Telegram.
2. `attach-media` adds the file. That is an edit, so **the finished video goes through
   approval again**. You approve what will actually be posted, not just the script.

Plan a weekly 60–90 minute batch-filming session. Without one, the "week of content" is a week of scripts.

### 2.5 Claude Code isn't a 24/7 service
Claude Code is an interactive coding agent. Running it as a daemon in tmux, with terminal access
and social media credentials, has three problems:
- The model has more power than the job needs.
- Every routine tick costs model calls.
- A prompt ("never publish without approval") is the only thing standing between the model and
  your accounts.

The example splits the work:
- **Deterministic code** does scheduling, the gate, dispatch, retries, attribution and the briefing.
- **The Claude API** (`smt/llm.py`, `claude-opus-5-5`) does only what needs language: polishing
  copy in your voice and classifying the intent of messages. If the API is down or declines a
  request, the template or rule result is used.
- **The model never holds a publish capability.** It writes into the queue; the code decides.

Claude Code is still the right tool for **building and changing** this system, just not for being it.

### 2.6 Attribution needs a per-post signal
UTM links only work where a link is clickable. On Instagram and TikTok the click usually goes
through "link in bio", which loses which post it came from. The example gives each post its own
tracking code and records leads against the post they commented on. For the bio link, use one of:
- a **different CTA keyword per post** ("comment BOOKS" vs "comment TAX")
- a per-post landing-page path or discount code
- asking "where did you hear about me?" on the booking form

Import sales with `utm_content` from Stripe/Gumroad (`import-events`), and close comment leads
with `/won`.

The synthetic history in the demo shows why this matters. Curiosity hooks get more than twice
the views of pain-point hooks but make no sales. Pain-point and authority hooks bring in about
80% of the revenue. A views dashboard would tell you to post more of the wrong thing.

### 2.7 Inbound messages are personal data, and DMs have their own rules
- Comments and DMs contain names, handles and sometimes emails and phone numbers. The example
  pseudonymises them before they go to the Claude API, and the database stays on your server.
  Set a retention period (this is not legal advice; check GDPR/your local law).
- Instagram/Messenger only let a business message someone **within 24 hours of their last
  message** (with narrow exceptions). Cold "outreach" DMs to people who never wrote to you
  are spam under most platforms' rules. Keep outreach as owner-approved drafts, low volume,
  personal.

### 2.8 Brand safety needs more than "don't sound robotic"
- **No invented proof.** A model told to write "high-retention hooks" will happily write
  "I helped 200 clients…". The example's templates make no factual claims, the writer prompt
  forbids invented numbers and testimonials, and approval is where you add real ones.
- **Banned-phrase lint.** The brand's "avoid" list is checked in code. A model draft that uses
  one is thrown away in favour of the template.
- **Regulated advice.** If your niche is money, health or law, replies need a "general info, not
  advice" line, as the example's FAQ replies have.

### 2.9 Smaller points
- **Peak times** in the config are generic defaults. Replace them with your own analytics after
  about 4 weeks of posting.
- **Oracle Cloud Always Free** reclaims instances that stay idle for a week (low CPU, network and
  memory use), and this agent is mostly idle. Upgrade the tenancy to Pay-As-You-Go (Always Free
  resources stay free) or check Oracle's current policy. Back up `smt.db` nightly
  (`setup_vps.sh` prints a cron line).
- **The Telegram bot token is a master key.** Keep it in `.env` with `chmod 600`, turn on Telegram
  2FA, and rotate the token through @BotFather if it leaks. The bot ignores every chat except yours.

---

## 3. Recommended rollout

| Week | Do | Success looks like |
|---|---|---|
| 0 | Fill in `brand.json` and `TOPIC_POINTS`, record 2 weeks of baseline (posts, leads, sales) | You know today's numbers |
| 1 | VPS + Telegram, `SMT_PUBLISH_MODE=dryrun`, approve in Telegram, read `outbox/` | You'd have published ≥ 70% of drafts with light edits |
| 2–3 | Go live via Buffer/Zapier webhook; inbox feed from Zapier; batch-film once a week | All approved posts go out on time; leads reach you within 10 min |
| 4+ | Add the Claude key and compare its drafts with the templates; tune peak times; drop the weakest hook types by revenue | Revenue per post rises; approval time falls under 10 min/day |

---

## 4. Answer to the spec's closing question

> *Deploy using Claude Code locally on a workstation, or spin it up on an Oracle Cloud VPS with a Telegram bot interface?*

**The Oracle Cloud VPS with Telegram**, for three reasons:
1. The 07:00 briefing, peak-time posts and 10-minute lead alerts need a machine that is always on.
2. Telegram long polling needs no open ports.
3. The VPS has nothing else on it to protect.

Use your workstation for the first dry-run days and for changing the code (with Claude Code).
Run the service itself under systemd or PM2 on the VPS; `deploy/` has both.
