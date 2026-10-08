# Analysis of the Transportation Intelligence System proposal

This review covers the proposal to build a *private Transportation Intelligence System* rather than
a chatbot. The example model in this repository (`tis/`) was built to test the proposal's ideas
in working code, not just on paper.

---

## 1. Verdict

The core direction is right, and the example model supports it:

| Proposal principle | Verdict | Evidence from the example model |
|---|---|---|
| Build decision support, not a chatbot | **Correct** | 6 of the 7 agents return useful answers with **no language model at all** |
| Data foundation before AI | **Correct, and the most important point** | Delay, idle and dock-risk detection all depend on *planned* times existing in the data |
| LLM must not do optimisation maths | **Correct** | `optimizer.py` gives an exact optimal plan with a reason for every rejected truck; an LLM can't promise that |
| Human approval before any action | **Correct** | The dispatch plan becomes an approval request; a supervisor (never the requester) must approve it |
| Keep data inside the network | **Correct and enforceable** | `llm.py` refuses non-private endpoints and pseudonymises driver data before every model call |
| No fine-tuning; use RAG + tools | **Correct** | 8 SOP files plus a BM25 index answer procedure questions with citations |
| RBAC on knowledge | **Correct** | The rate card is visible to supervisors but not to coordinators |

It also needs **corrections and additions**, set out below.

---

## 2. What the proposal gets wrong or overstates

### 2.1 The language model is the *least* important layer for v1
The proposal puts a "Reasoning Model" in the middle of the diagram. In practice, the value for a
45-truck operation comes from:

1. **Digital twin**: a live status for each truck, built from plan + events + GPS
2. **Exception rules**: expected vs actual, with thresholds from the SOPs
3. **KPIs**: plain SQL
4. **Optimiser**: deterministic maths

In the example model, all four run without any AI server. The LLM only adds a natural-language
front end, document/OCR extraction and nicer summaries. **This changes the business case: v1 needs
no GPU purchase.** A GPU can come in phase 3, after the system has already proved its value.

### 2.2 The infrastructure list is too heavy for the first version
| Proposed | Recommendation for v1 | Why |
|---|---|---|
| OpenSearch **or** Qdrant | **PostgreSQL + pgvector** (or plain full-text search) | ~50 SOP documents don't justify a separate search cluster; one database is one thing to secure and back up |
| vLLM / NVIDIA NIM | **Ollama or llama.cpp on one workstation GPU** | One team of a few users doesn't need a production inference server; switch when there are many concurrent users |
| LangGraph | **A plain router + tool functions** (as in `agents.py`) | Adopt an agent framework once you have more than one multi-step workflow |
| React/Next.js | **Static HTML, or a BI tool the company already licenses** | `dashboard.py` shows that a control tower can be a single file |

Every component you add needs patching, monitoring and IT approval. Fewer components means
faster security sign-off.

### 2.3 Integration is the real project, and the proposal underplays it
The hard questions aren't about AI. They are:
- Does the **FMS/GPS vendor** offer an API or scheduled export? How often (every 1 min or every 15 min)?
- Does the **TMS** store *planned* arrival times per stop? Without them, nothing can be "late".
- Are truck, driver and customer IDs **the same across TMS, WMS, FMS and Excel**? If not, you need
  a mapping table first.
- Who **owns** the data feeds, and who fixes them when they break?

**Phase 0 must be a data inventory** (see §4). The proposal says this at the very end. It should
come first.

### 2.4 Autonomous execution should stay out of scope for a long time
"Recommend → approve → execute" is the right pattern, but *writing back* into the TMS needs IT
integration, change control and a rollback plan. The example model only changes its own
database. For the first year, keep execution limited to drafts: messages to drivers, ETA updates
and plans that a human sends.

### 2.5 The citations need checking before they go in front of management
Several sources are dated after mid-2026 (for example, the "September 2026" McKinsey survey and DHL
Trend Radar figures), and the links carry `utm_source=chatgpt.com` tags. I could not verify those
figures. **Open each link and confirm the exact numbers before quoting them in a proposal.** A
wrong statistic in front of your manager costs more credibility than leaving it out.

---

## 3. What is missing from the proposal

1. **A baseline and success metrics.** Measure today first: how many minutes per day you spend
   checking trucks, how late exceptions are noticed, and how long the daily KPI report takes.
   Without a baseline you can't prove value.
2. **Prediction should start simple.** The example predicts ETAs from remaining distance plus each
   customer's *historical* dock waiting time. That already flags Hospital A and Hospital E as chronic
   delayers (>40% of visits wait over 30 min). Machine learning comes later, once there is clean history.
3. **Personal data is broader than names.** Under the Saudi PDPL, **driver GPS location history is
   personal data** too, not only names, phone numbers and IDs. You need a retention period, a
   lawful purpose and access limits for the GPS history itself. (This is not legal advice. Your
   legal/DPO team decides.)
4. **Audit and segregation of duties.** Every question and every approval should be logged, and
   nobody should approve their own request. Both are implemented in the example.
5. **Ownership and continuity.** If the system runs on your laptop and you go on leave, it stops.
   Get IT to host it and name a backup owner early. This is also what turns it from shadow IT
   into an official project.
6. **Arabic and mixed-language input**, for voice and documents. This is a later phase, but
   choose a model with good Arabic support from the start.
7. **Planning feedback loop.** Exceptions should feed back into planning. For example, the SOP
   rule "add a waiting buffer for chronic customers" is applied automatically by the KPI agent.

---

## 4. Recommended roadmap

| Phase | Duration | Deliverable | Needs |
|---|---|---|---|
| **0. Data boundary** | 2–4 weeks | Inventory of every dataset: source, owner, fields, refresh rate, classification (public / internal / confidential / personal) | Your manager + IT security |
| **1. Control tower (read-only)** | 4–6 weeks | Daily CSV/API imports into PostgreSQL → digital twin → dashboard | A small VM, read access to exports |
| **2. Exceptions + KPI automation** | 4 weeks | Rule engine, automatic morning/midday/evening reports | SOP thresholds agreed with the supervisor |
| **3. Private assistant** | 4–6 weeks | Local LLM + SOP retrieval + question answering with citations | One GPU workstation **or** an approved private-cloud endpoint |
| **4. Dispatch recommendations** | 6 weeks | Optimiser (OR-Tools for multi-drop) + approval workflow | Clean capacity, driver-hours and time-window data |
| **5. Integrations / documents / voice** | ongoing | POD/manifest OCR, driver messaging drafts, TMS write-back | IT change control |

Phases 0–2 already deliver value on their own, with no AI hardware.

---

## 5. How the proposal maps onto the example model

| Proposal layer | Example model (this repo) | Production replacement |
|---|---|---|
| TMS / WMS / FMS / Excel feeds | `tis/sim.py` (synthetic, 45 trucks, 30-day history) | CSV/API importers per source |
| PostgreSQL operational DB | `tis/db.py` (SQLite, same SQL schema) | PostgreSQL |
| Transportation digital twin | `tis/twin.py` | same logic, refreshed every N minutes |
| Exception engine | `tis/exceptions.py` (8 rule types with evidence) | same plus thresholds stored in the DB |
| KPI engine | `tis/kpi.py` | SQL views / BI tool |
| Optimisation engine | `tis/optimizer.py` (exact Hungarian assignment) | Google OR-Tools VRPTW for multi-drop |
| RAG / knowledge | `tis/knowledge.py` + `knowledge/sops/*.md` (BM25 + access control) | pgvector / OpenSearch hybrid search |
| Reasoning model | `tis/llm.py` (offline by default; any local OpenAI-compatible server) | Ollama → vLLM → NIM as usage grows |
| Specialised agents | `tis/agents.py` (Tracking, Exception, Dispatcher, KPI, Knowledge, Reporting) | same; agent framework if workflows grow |
| Human approval + audit | `approval` and `audit_log` tables, supervisor-only approval | SSO identities, ticketing integration |
| Control tower UI | `tis/dashboard.py` (single HTML file) | Web app or company BI tool |

---

## 6. How to pitch it

The suggested positioning is good. One refinement:

> "A secure, **read-only first** transportation visibility and exception-management system that
> uses our existing TMS/FMS data, **runs inside our network, and works without sending any data to
> external AI services**. AI features are added later and stay under the same controls."

Bring the working example to the meeting. Something your manager can run and see is far more
convincing than an architecture diagram.
