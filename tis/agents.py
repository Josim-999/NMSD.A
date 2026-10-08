"""Agent layer: intent routing, specialised agents, permissions, approval gate.

Every agent works the same way:
    tools (SQL / twin / optimizer / knowledge search)  ->  FACTS  ->  draft text
and the language model, if one is configured, may only re-phrase the draft
from those facts. Anything that changes data becomes an approval request that
a supervisor must approve; nothing executes on its own.
"""
import json
import re
from dataclasses import dataclass, field
from datetime import datetime

from . import db, exceptions, kpi, optimizer, twin as twin_mod
from .config import DEPOTS, MAX_DRIVING_MIN, NOW
from .geo import road_km
from .knowledge import KnowledgeBase
from .llm import OfflineNarrator, Pseudonymizer

PERMISSIONS = {
    "viewer": {"READ"},
    "coordinator": {"READ", "RECOMMEND", "REQUEST"},
    "supervisor": {"READ", "RECOMMEND", "REQUEST", "APPROVE"},
}

ACTIONS = {   # exception type -> (recommended action, knowledge query used to cite the SOP)
    "BREAKDOWN": ("Request road assistance now; if repair > 90 min dispatch the nearest same-type truck to transfer the load.",
                  "breakdown road assistance transfer load"),
    "CUSTOMER_WAITING": ("Call the customer's receiving contact, record reason DOCK_WAIT, escalate if waiting continues.",
                         "chronic waiting customer dock wait buffer"),
    "DOCK_CLOSED_RISK": ("Call the customer now to extend receiving hours, otherwise rebook the first slot tomorrow.",
                         "arrival predicted after dock closing time extension"),
    "DELAY": ("Confirm the cause with the driver and update the ETA; inform the customer if 45 min or more.",
              "delay thresholds inform customer new eta"),
    "IDLE": ("Call the driver to confirm why the truck is stopped (rest break, traffic, problem?).",
             "delay thresholds driver cause"),
    "GPS_STALE": ("Call the driver for position and raise a ticket with the FMS/GPS provider.",
                  "delay escalation coordinator contacts driver"),
    "ROUTE_DEVIATION": ("Confirm the route with the driver (road closure or detour?).",
                        "delay reason code route"),
    "MISSING_INFO": ("Complete the manifest number in the TMS before the next gate release.",
                     "manifest number missing before dispatch"),
}


@dataclass
class User:
    name: str
    role: str

    def can(self, perm):
        return perm in PERMISSIONS.get(self.role, set())


@dataclass
class Answer:
    agent: str
    text: str
    facts: dict = field(default_factory=dict)
    sources: list = field(default_factory=list)
    approval_id: int = None


def hhmm(dt):
    return dt.strftime("%H:%M") if dt else "--:--"


def parse_truck(q):
    m = re.search(r"\btrk[-\s]?0*(\d{1,3})\b", q, re.I) or \
        re.search(r"\b(?:truck|with|about|is|for|number|no\.?)\s+#?0*(\d{1,3})\b(?!\s*(?:km|min|pallet|hour))", q, re.I)
    return f"TRK-{int(m.group(1)):03d}" if m else None


class TransportAI:
    def __init__(self, conn, llm=None, kb=None):
        self.conn = conn
        self.llm = llm or OfflineNarrator()
        self.kb = kb or KnowledgeBase()
        self.refresh()

    def refresh(self):
        self.twin = twin_mod.build(self.conn)
        self.exceptions = exceptions.detect(self.conn, self.twin)
        self.loc = {r["id"]: (r["lat"], r["lon"]) for r in self.conn.execute("SELECT id, lat, lon FROM location")}
        self.names = {r["id"]: r["name"] for r in self.conn.execute("SELECT id, name FROM location")}
        self.pseudo = Pseudonymizer([s.driver_name for s in self.twin.values()])

    # ------------------------------------------------------------------ routing
    def route(self, q):
        ql = q.lower()
        truck = parse_truck(q)
        procedural = re.search(r"what (should|do|must) (i|we)|how (do|should|can) (i|we)|what happens if|procedure|policy|"
                               r"\bsop\b|\brule|penalt|allowed|permitted|temperature|maximum|minimum|limit", ql)
        if procedural and not truck:
            return "Knowledge", self.knowledge, dict(question=q)
        if re.search(r"\b(report|briefing|summary)\b", ql):
            kind = next((k for k in ("morning", "midday", "evening") if k in ql), "midday")
            return "Reporting", self.report, dict(kind=kind)
        if re.search(r"(plan|propose|optimi[sz]e|assign|allocate)", ql) and re.search(r"(dispatch|shipment|truck|load)", ql):
            return "Dispatcher", self.dispatch_plan, {}
        if re.search(r"(not|n't|un)\s*(yet\s*)?(been\s*)?(dispatch|assign)|pending|waiting for a truck", ql):
            return "Dispatcher", self.pending_shipments, {}
        if re.search(r"driver", ql) and re.search(r"(most|top|best|rank|kpi|perform|on.?time|km|kilomet|pallet)", ql):
            order = "on_time" if re.search(r"on.?time|punctual", ql) else "km" if re.search(r"\bkm\b|kilomet|distance", ql) \
                else "pallets" if "pallet" in ql else "trips"
            return "KPI", self.kpi_drivers, dict(order=order)
        if re.search(r"customer", ql) and re.search(r"(wait|delay|worst|kpi|dock)", ql):
            return "KPI", self.kpi_customers, {}
        if re.search(r"\bkpi|utili[sz]ation|on.?time|performance|how (are|is) (we|the fleet)", ql):
            return "KPI", self.kpi_fleet, {}
        if truck:
            if re.search(r"\bwhy\b|late|delay|problem|issue|what (should|do) (i|we)|do about|action", ql):
                return "Exception", self.explain_truck, dict(truck_id=truck)
            return "Tracking", self.truck_status, dict(truck_id=truck)
        if re.search(r"\b(free|available|unassigned|idle trucks|spare)\b", ql):
            m = re.search(r"within\s+(\d+)\s*km\s+(?:of|from)\s+([a-z\- ]+)", ql)
            near = None
            if m:
                key = m.group(2).strip().upper()
                near = next((d for d in DEPOTS if d.startswith(key) or key in DEPOTS[d]["name"].upper()), None)
            return "Tracking", self.available, dict(near=near, radius=int(m.group(1)) if m else None)
        if re.search(r"\b(delay|delayed|late|behind)\b", ql) and not re.search(r"what should|how (do|should)|procedure", ql):
            return "Exception", self.delayed, {}
        if re.search(r"attention|problem|exception|issue|risk|urgent|right now|alert", ql) and \
                not re.search(r"what should|procedure|policy", ql):
            return "Exception", self.attention, {}
        if re.search(r"\b(where|status|fleet|overview|how many trucks)\b", ql):
            return "Tracking", self.fleet_overview, {}
        return "Knowledge", self.knowledge, dict(question=q)

    def ask(self, question, user):
        if not user.can("READ"):
            raise PermissionError(f"role {user.role} cannot read operational data")
        agent, fn, kwargs = self.route(question)
        if fn in (self.dispatch_plan, self.knowledge):
            kwargs["user"] = user
        ans = fn(**kwargs)
        ans.agent = agent
        ans.text = self.llm.narrate(question, ans.text, ans.facts, self.pseudo)
        db.audit(self.conn, user.name, user.role, f"ask:{agent}", question)
        return ans

    # ------------------------------------------------------------------ helpers
    def cite(self, etype):
        hits = self.kb.search(ACTIONS[etype][1], k=1, min_score=0.5)
        return hits[0][1].doc_id if hits else None

    def _truck(self, truck_id):
        st = self.twin.get(truck_id)
        if st is None:
            raise KeyError(f"{truck_id} is not in the fleet (TRK-001 .. TRK-{len(self.twin):03d})")
        return st

    def _actions(self, st):
        out, seen = [], set()
        for e in st.exceptions:
            if e.type in seen:
                continue
            seen.add(e.type)
            out.append((ACTIONS[e.type][0], self.cite(e.type)))
        return out

    # ------------------------------------------------------------------ tracking agent
    def truck_status(self, truck_id):
        st = self._truck(truck_id)
        lines = [f"{st.truck_id} ({st.type}, {st.capacity} pallets, plate {st.plate}) - driver {st.driver_name}",
                 f"Status: {st.status}, {st.location_label}, speed {st.speed_kmh:.0f} km/h, last GPS {hhmm(st.ping_ts)}"
                 + (f" ({st.ping_age_min} min ago)" if st.ping_age_min > 5 else "")]
        if st.stopped_since and st.status not in ("AVAILABLE", "MAINTENANCE"):
            lines.append(f"Stopped since {hhmm(st.stopped_since)} ({st.dwell_min} min)")
        if st.trip_id:
            lines.append(f"Trip {st.trip_id}: {self.names[st.origin]} -> {self.names[st.end]}, "
                         f"planned departure {hhmm(st.planned_depart)}, actual {hhmm(st.actual_depart)}")
            for s in st.stops:
                if s.actual_departure:
                    state = f"delivered (arrived {hhmm(s.actual_arrival)}, left {hhmm(s.actual_departure)})"
                elif s.actual_arrival:
                    state = f"ON SITE since {hhmm(s.actual_arrival)} ({round((NOW - s.actual_arrival).total_seconds() / 60)} min)"
                else:
                    state = f"ETA {hhmm(s.predicted_arrival)} (planned {hhmm(s.planned_arrival)}, {s.delay_min:+d} min)"
                lines.append(f"  {s.seq + 1}. {s.customer}, {s.city}: {state}")
            if st.status not in ("AVAILABLE",):
                lines.append(f"Predicted trip end at {self.names[st.end]}: {hhmm(st.predicted_end)} (planned {hhmm(st.planned_end)})")
        else:
            lines.append(f"No trip today. Driving time used {st.driving_min_today:.0f} of {MAX_DRIVING_MIN} min.")
        lines.append(f"Risk: {st.risk}")
        for e in st.exceptions:
            lines.append(f"  [{e.severity}] {e.type}: {e.message}")
        facts = dict(truck=st.truck_id, status=st.status, location=st.location_label, risk=st.risk,
                     driver=st.driver_name, exceptions=[e.__dict__ for e in st.exceptions],
                     stops=[dict(customer=s.customer, planned=hhmm(s.planned_arrival), arrived=hhmm(s.actual_arrival),
                                 eta=hhmm(s.predicted_arrival), delay_min=s.delay_min) for s in st.stops])
        return Answer("", "\n".join(lines), facts)

    def available(self, near=None, radius=None):
        rows = []
        for st in self.twin.values():
            if st.status != "AVAILABLE":
                continue
            dist = road_km((st.lat, st.lon), self.loc[near]) if near else None
            if radius and dist > radius:
                continue
            rows.append(dict(truck=st.truck_id, type=st.type, capacity=st.capacity, location=st.location_label,
                             driver=st.driver_name, driving_left_min=round(MAX_DRIVING_MIN - st.driving_min_today),
                             distance_km=None if dist is None else round(dist)))
        rows.sort(key=lambda r: (r["distance_km"] or 0, -r["driving_left_min"]))
        where = f" within {radius} km of {near}" if near else ""
        lines = [f"{len(rows)} trucks available{where}:"]
        for r in rows:
            lines.append(f"  {r['truck']}  {r['type']:<6} {r['capacity']:>2} plt  {r['location']:<40} "
                         f"driving left {r['driving_left_min'] / 60:.1f} h")
        return Answer("", "\n".join(lines), dict(available=rows))

    def fleet_overview(self):
        k = kpi.fleet_today(self.twin)
        lines = [f"Fleet {k['fleet']} trucks at {hhmm(NOW)}: " + ", ".join(f"{v} {s}" for s, v in sorted(k["status"].items(), key=lambda x: -x[1])),
                 f"{k['trucks_with_exceptions']} trucks have open exceptions; ask 'what requires attention right now?'"]
        return Answer("", "\n".join(lines), k)

    # ------------------------------------------------------------------ exception agent
    def explain_truck(self, truck_id):
        st = self._truck(truck_id)
        if not st.exceptions:
            ans = self.truck_status(truck_id)
            ans.text = f"{truck_id} has no open exception.\n" + ans.text
            return ans
        cause = exceptions.likely_cause(st)
        hist = twin_mod.customer_history(self.conn)
        lines = [f"{truck_id} - risk {st.risk}. Likely cause: {cause}."]
        for e in st.exceptions:
            lines.append(f"  [{e.severity}] {e.message}")
        for e in st.exceptions:
            cid = e.evidence.get("customer_id")
            h = hist.get(cid)
            if h and h["long_wait_pct"] >= 25:
                lines.append(f"  History: {self.names[cid]} made trucks wait > 30 min on {h['long_wait_pct']}% of "
                             f"{h['visits']} visits ({kpi.period_label()}), avg {h['avg_wait']} min. This is a known pattern.")
                break
        lines.append("Recommended actions:")
        sources = []
        for text, sop in self._actions(st):
            lines.append(f"  - {text}" + (f" [{sop}]" if sop else ""))
            sources += [sop] if sop else []
        return Answer("", "\n".join(lines), dict(truck=truck_id, cause=cause, exceptions=[e.__dict__ for e in st.exceptions]),
                      sources)

    def attention(self, limit=10):
        trucks = sorted({e.truck_id for e in self.exceptions},
                        key=lambda t: (-exceptions.SEVERITY_ORDER[self.twin[t].risk], t))
        lines = [f"{len(trucks)} trucks need attention at {hhmm(NOW)} (most urgent first):"]
        facts = []
        for t in trucks[:limit]:
            st = self.twin[t]
            first_action, sop = self._actions(st)[0]
            lines.append(f"  {t} [{st.risk}] {exceptions.likely_cause(st) or st.exceptions[0].type.lower()} - "
                         f"{st.exceptions[0].message}")
            lines.append(f"      -> {first_action}" + (f" [{sop}]" if sop else ""))
            facts.append(dict(truck=t, risk=st.risk, exceptions=[e.message for e in st.exceptions], action=first_action))
        return Answer("", "\n".join(lines), dict(attention=facts))

    def delayed(self):
        rows = [e for e in self.exceptions if e.type == "DELAY"]
        lines = [f"{len(rows)} trucks are predicted late for their next stop:"]
        for e in sorted(rows, key=lambda e: -e.evidence["delay_min"]):
            st = self.twin[e.truck_id]
            lines.append(f"  {e.truck_id} +{e.evidence['delay_min']} min -> {self.names[e.evidence['customer_id']]} "
                         f"(planned {e.evidence['planned']}, ETA {e.evidence['predicted']}); cause: {exceptions.likely_cause(st)}")
        return Answer("", "\n".join(lines), dict(delays=[e.__dict__ for e in rows]))

    # ------------------------------------------------------------------ dispatcher agent
    def _pending(self):
        return [dict(r) for r in self.conn.execute("SELECT * FROM shipment WHERE status='PENDING' ORDER BY required_by, id")]

    def pending_shipments(self):
        rows = self._pending()
        lines = [f"{len(rows)} shipments are not dispatched yet:"]
        for s in rows:
            lines.append(f"  {s['id']}  {self.names[s['origin_id']]} -> {self.names[s['customer_id']]}  "
                         f"{s['pallets']} plt {'reefer' if s['reefer'] else 'dry'}  due {s['required_by'].replace('T', ' ')}")
        return Answer("", "\n".join(lines), dict(pending=rows))

    def dispatch_plan(self, user):
        if not user.can("RECOMMEND"):
            raise PermissionError(f"role {user.role} cannot request dispatch plans")
        busy = {r[0] for r in self.conn.execute("SELECT assigned_truck FROM shipment WHERE status='PLANNED'")}
        trucks = [dict(id=s.truck_id, capacity=s.capacity, reefer=s.reefer, pos=(s.lat, s.lon),
                       driving_min_today=s.driving_min_today)
                  for s in self.twin.values() if s.status == "AVAILABLE" and s.truck_id not in busy]
        ships = self._pending()
        result = optimizer.plan(trucks, ships, self.loc)
        lines = [f"Proposed dispatch plan (optimal assignment, {len(trucks)} available trucks, {len(ships)} shipments):"]
        for a in result["assignments"]:
            lines.append(f"  {a['shipment']} -> {a['truck']}  {self.names[a['customer']]:<12} {a['pallets']:>2}/{a['capacity']} plt  "
                         f"empty run {a['deadhead_km']} km, linehaul {a['linehaul_km']} km, {a['drive_min'] / 60:.1f} h")
        for u in result["unassigned"]:
            lines.append(f"  {u['shipment']} NOT ASSIGNED ({self.names[u['customer']]}, {u['pallets']} plt): "
                         + "; ".join(u["reasons"][:3]))
            if any(r.startswith("capacity") for r in u["reasons"]):
                lines.append(f"      -> no single truck can carry {u['pallets']} pallets: split into two loads or use a subcontractor.")
        lines.append(f"Total empty running: {result['total_deadhead_km']} km.")
        approval_id = None
        if result["assignments"] and user.can("REQUEST"):
            approval_id = self.request_approval(user, "assign_shipments",
                                                f"Assign {len(result['assignments'])} shipments", result["assignments"])
            lines.append(f"Nothing has been changed. Approval request #{approval_id} created - a supervisor must approve it.")
        return Answer("", "\n".join(lines), result, approval_id=approval_id)

    # ------------------------------------------------------------------ KPI agent
    def kpi_drivers(self, order="trips"):
        rows = kpi.drivers(self.conn, limit=10, order=order)
        lines = [f"Top drivers by {order.replace('_', '-')} ({kpi.period_label()}):"]
        for i, r in enumerate(rows, 1):
            lines.append(f"  {i:>2}. {r['driver_name']:<12} {r['truck_id']}  {r['trips']} trips  {r['km']:.0f} km  "
                         f"{r['pallets']} plt  on-time {r['on_time_pct']:.0f}%")
        return Answer("", "\n".join(lines), dict(drivers=rows))

    def kpi_fleet(self):
        k = kpi.fleet_today(self.twin)
        lines = [f"Fleet KPIs today ({hhmm(NOW)}):",
                 f"  Utilisation: {k['utilization_pct']}% ({k['trips_today']} of {k['usable']} usable trucks dispatched)",
                 f"  Stops completed: {k['stops_done']}, on-time arrival: {k['on_time_pct']}%",
                 f"  Pallets delivered: {k['pallets_delivered']}",
                 f"  Trucks with open exceptions: {k['trucks_with_exceptions']}",
                 "  Status: " + ", ".join(f"{s} {v}" for s, v in sorted(k["status"].items()))]
        return Answer("", "\n".join(lines), k)

    def kpi_customers(self):
        rows = kpi.customers(self.conn)
        lines = [f"Customer dock performance ({kpi.period_label()}), worst first:"]
        for r in rows[:8]:
            flag = "  <- add planning buffer [SOP-OPS-02]" if r["long_wait_pct"] > 25 else ""
            lines.append(f"  {r['name']:<12} {r['city']:<9} avg wait {r['avg_wait']:.0f} min, "
                         f"waits > 30 min on {r['long_wait_pct']:.0f}% of {r['visits']} visits{flag}")
        return Answer("", "\n".join(lines), dict(customers=rows), ["SOP-OPS-02"])

    # ------------------------------------------------------------------ knowledge agent
    def knowledge(self, question, user):
        hits = self.kb.search(question, role=user.role, k=2)
        if not hits:
            return Answer("", "I could not find this in the company procedures I have access to. "
                              "Try asking about delays, docks, breakdowns, cold chain, driver hours or documents.")
        lines = []
        for score, c in hits:
            lines.append(f"From {c.doc_id} - {c.title} / {c.section}:")
            body = c.text.rsplit("\n", 1)[0]          # drop the indexed title/tags line
            lines += [f"  {l}" for l in body.splitlines() if l.strip()]
        return Answer("", "\n".join(lines), dict(passages=[c.__dict__ for _, c in hits]), [c.doc_id for _, c in hits])

    # ------------------------------------------------------------------ reporting agent
    def report(self, kind="midday"):
        k = kpi.fleet_today(self.twin)
        parts = [f"# {kind.capitalize()} transport report - {NOW:%Y-%m-%d %H:%M}", "",
                 "## Fleet", self.kpi_fleet().text, "",
                 "## Requires attention", self.attention().text, "",
                 "## Delays", self.delayed().text, "",
                 "## Not yet dispatched", self.pending_shipments().text, "",
                 "## Customers causing waiting", self.kpi_customers().text]
        return Answer("", "\n".join(parts), k, ["SOP-OPS-02"])

    # ------------------------------------------------------------------ approval gate
    def request_approval(self, user, action, summary, payload):
        cur = self.conn.execute(
            "INSERT INTO approval (created_ts, requested_by, action, summary, payload, status) VALUES (?,?,?,?,?, 'PENDING')",
            (datetime.now().isoformat(timespec="seconds"), user.name, action, summary, json.dumps(payload)))
        self.conn.commit()
        db.audit(self.conn, user.name, user.role, "approval_requested", f"#{cur.lastrowid} {action}: {summary}")
        return cur.lastrowid

    def approvals(self, status="PENDING"):
        return [dict(r) for r in self.conn.execute("SELECT * FROM approval WHERE status=? ORDER BY id", (status,))]

    def decide(self, approval_id, user, approve=True):
        if not user.can("APPROVE"):
            raise PermissionError(f"role {user.role} cannot approve actions")
        row = self.conn.execute("SELECT * FROM approval WHERE id=?", (approval_id,)).fetchone()
        if row is None or row["status"] != "PENDING":
            raise ValueError(f"approval #{approval_id} is not pending")
        if row["requested_by"] == user.name:
            raise PermissionError("segregation of duties: you cannot approve your own request")
        status = "APPROVED" if approve else "REJECTED"
        if approve:
            self._execute(row["action"], json.loads(row["payload"]))
        self.conn.execute("UPDATE approval SET status=?, decided_by=?, decided_ts=? WHERE id=?",
                          (status, user.name, datetime.now().isoformat(timespec="seconds"), approval_id))
        self.conn.commit()
        db.audit(self.conn, user.name, user.role, f"approval_{status.lower()}", f"#{approval_id} {row['action']}")
        self.refresh()
        return status

    def _execute(self, action, payload):
        if action == "assign_shipments":
            for a in payload:
                self.conn.execute("UPDATE shipment SET status='PLANNED', assigned_truck=? WHERE id=? AND status='PENDING'",
                                  (a["truck"], a["shipment"]))
        else:
            raise ValueError(f"unknown action {action}")
