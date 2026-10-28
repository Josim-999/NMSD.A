# Transportation Intelligence System — example model

A working, **private-by-design** example of a Transportation Intelligence System for a 45-truck
operation. It is a decision-support system, not a chatbot: it builds a live digital twin of the
fleet, detects exceptions, explains their cause, calculates KPIs, proposes an optimal dispatch
plan and answers procedure questions from company SOPs. Every change goes through human approval.

- **Pure Python standard library.** No `pip install`, no internet, no GPU needed.
- **All data is synthetic.** Trucks, drivers, customers and SOPs are invented for the demo.
- **The AI model is optional.** Everything runs deterministically. A local LLM can be plugged in
  for natural-language answers, but it is only ever allowed to talk to a private-network address.

The critical review of the original proposal is in **[docs/ANALYSIS.md](docs/ANALYSIS.md)**.

---

# WhatsApp Business MCP

This repository also contains **`wamcp`**: a standard
[Model Context Protocol](https://modelcontextprotocol.io) server that connects a WhatsApp
Business number to any AI client — Claude Code, Claude Desktop, Cursor, VS Code, Windsurf,
Zed, or anything else that speaks MCP. Same philosophy as the rest of the repo: pure Python
standard library, no `pip install`, your data stays on your machine.

```bash
python3 -m wamcp setup      # paste three values from Meta's API Setup page
python3 -m wamcp doctor     # check the whole chain, with the fix for anything missing
python3 -m wamcp install    # register with Claude Code (or Desktop, Cursor, VS Code...)
```

Then ask your assistant: *"any new WhatsApp messages?"*, *"reply to Josim that the order is
ready"*, *"which chats are waiting on us?"*

It uses Meta's official **WhatsApp Business Cloud API**, so nothing here risks your number.
24 tools cover sending (text, templates, media, buttons, menus, link buttons, locations,
contact cards, reactions, read receipts), the inbox (list and read chats, search, delivery
status, media up and down), contacts and consent, templates, and the business profile.

Inbound messages arrive by webhook, which `python3 -m wamcp webhook` receives — signature
verified — into a local SQLite inbox.

Sending is gated, because an AI with a WhatsApp number can reach real people:

- `STOP` opts a person out the moment it arrives, and no tool can override it
- WhatsApp's 24-hour free-form window is enforced locally, with "send a template instead"
  in the error rather than a silent failure
- optional allow-list, hourly send cap and a dry-run mode that sends nothing
- every send, refusal and dry run is in `python3 -m wamcp audit`

Full guide, including what to copy from Meta and how to fix each error:
**[docs/WHATSAPP_MCP.md](docs/WHATSAPP_MCP.md)**.

```text
wamcp/   config, phones, graph, messages, store, policy, tools, mcp, webhook, install, CLI
```

```bash
python3 -m wamcp selftest    # 110 tests, no network: a fake Cloud API runs on localhost
```

---

## Quick start

```bash
python3 -m tis demo                                  # scripted walkthrough of every agent
python3 -m tis ask "What's happening with 27?"
python3 -m tis ask "Why is TRK-007 late?"
python3 -m tis chat                                  # interactive
python3 -m tis dashboard                             # -> reports/control_tower.html
python3 -m tis report morning                        # -> reports/morning_report.md
python3 -m tis approvals                             # pending approval requests
python3 -m tis approve 1 --user sara --role supervisor
python3 -m tis audit                                 # who asked / approved what
python3 -m unittest discover -s tests                # 18 tests
```

The simulation is frozen at **2026-10-08 13:30** so results are reproducible. `python3 -m tis init`
regenerates the database.

## Architecture

```text
 TMS / WMS / FMS / Excel  ──►  sim.py (synthetic feeds; replace with importers)
                                   │
                                   ▼
                          db.py  operational database (SQLite ⇄ PostgreSQL schema)
                                   │
          ┌────────────────────────┼─────────────────────────┐
          ▼                        ▼                         ▼
   twin.py digital twin     kpi.py KPI engine      knowledge.py SOP retrieval (RAG)
          │                                                  ▲ role-based access
          ▼                                                  │
   exceptions.py rules ──► evidence + likely cause           │
          │                                                  │
          ▼                                                  │
   agents.py  router → Tracking | Exception | Dispatcher | KPI | Knowledge | Reporting
          │                         │
          │                         ▼
          │                 optimizer.py exact assignment (the LLM never does the maths)
          │                         │
          │                         ▼
          │                 approval request ─► supervisor approves ─► executed + audit_log
          ▼
   llm.py  offline narrator (default)  or  local OpenAI-compatible model
           · refuses public endpoints   · pseudonymises driver names/phones/IDs
```

## What the demo shows

Situations seeded into the synthetic data, all detected from raw events and GPS only:

| Truck | Situation | Detected as |
|---|---|---|
| TRK-023 | Tyre burst on Al Kharj road (driver-app report) | `BREAKDOWN` CRITICAL + delay |
| TRK-007 | 78 min at Hospital A's dock (a chronic delayer, see history) | `CUSTOMER_WAITING` + knock-on `DELAY` |
| TRK-012 | Stopped 50 min on the Dammam highway | `IDLE` + `DELAY` + `DOCK_CLOSED_RISK` |
| TRK-019 | 18 km off the Buraydah corridor | `ROUTE_DEVIATION` |
| TRK-031 | GPS silent for 55 min | `GPS_STALE` |
| TRK-034 | Shipment without manifest number | `MISSING_INFO` |
| TRK-036 | Left Jeddah 95 min late; will miss Madinah dock closing | `DELAY` + `DOCK_CLOSED_RISK` |
| TRK-027 | Normal: 31 min at second customer | no exception |

Sample answer:

```text
>>> Why is TRK-007 late?
TRK-007 - risk HIGH. Likely cause: long waiting time at the customer dock.
  [HIGH] Waiting 78 min at Hospital A (planned 30 min)
  [HIGH] 58 min late for Pharmacy C (planned 13:08, predicted 14:06)
  History: Hospital A made trucks wait > 30 min on 45% of 244 visits (last 30 days), avg 36.3 min.
Recommended actions:
  - Call the customer's receiving contact, record reason DOCK_WAIT, escalate if waiting continues. [SOP-OPS-02]
  - Confirm the cause with the driver and update the ETA; inform the customer if 45 min or more. [SOP-OPS-02]
```

The dispatcher gives the 9 pending shipments an optimal assignment under capacity, reefer and
driver-hours constraints. It explains why one 22-pallet load can't fit any truck, then
**creates an approval request instead of changing anything**.

## Roles

| Role | Can |
|---|---|
| `viewer` | ask read-only questions |
| `coordinator` | + request dispatch plans (creates approval requests) |
| `supervisor` | + approve or reject requests (never their own), read restricted documents such as the rate card |

## Plugging in a local model (optional)

Any OpenAI-compatible server inside the network works: Ollama, llama.cpp, vLLM or NVIDIA NIM.

```bash
ollama serve & ollama pull qwen2.5:7b
TIS_LLM_URL=http://127.0.0.1:11434 TIS_LLM_MODEL=qwen2.5:7b python3 -m tis chat
```

The model gets only the facts computed by the tools, with driver names replaced by `DRIVER_n`.
If the URL resolves to a public IP the system refuses to start, and if the model is down it falls
back to the deterministic answer.

## Moving to real data

Replace `sim.generate()` with importers that fill the same tables from your exports:

| Table | Typical source |
|---|---|
| `truck`, `driver`, `location` | Fleet master data, customer master (with dock hours) |
| `trip`, `trip_stop`, `shipment` | TMS dispatch export (planned times are essential) |
| `gps_latest`, `driver_event` | FMS / telematics API, driver app |
| `trip_history`, `stop_history` | Past TMS + FMS data |

Then replace `knowledge/sops/*.md` with your real SOPs, and adjust the thresholds in `tis/config.py`.

## Layout

```
tis/            config, geo, db, sim, twin, exceptions, kpi, optimizer, knowledge, llm, agents, dashboard, CLI
wamcp/          WhatsApp Business MCP server (see docs/WHATSAPP_MCP.md)
knowledge/sops/ sample SOPs (markdown with access-level front matter)
tests/          unit tests (scenarios, optimiser vs brute force, RAG, RBAC, approval gate, data boundary,
                plus the WhatsApp MCP suite in test_wamcp.py)
docs/           ANALYSIS.md, WHATSAPP_MCP.md
```
