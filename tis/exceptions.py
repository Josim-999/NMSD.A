"""Exception engine: compares expected vs actual and classifies the gap.

Rules are deterministic and explainable on purpose. Every exception carries
its evidence so the assistant can say *why*, not just *what*.
"""
from dataclasses import dataclass, field

from .config import (DELAY_HIGH_MIN, DELAY_MEDIUM_MIN, DEVIATION_KM,
                     GPS_STALE_MIN, IDLE_MIN, NOW, SERVICE_MIN, WAITING_EXTRA_MIN)
from .geo import haversine_km, point_segment_km
from .twin import customer_history

SEVERITY_ORDER = {"CRITICAL": 4, "HIGH": 3, "MEDIUM": 2, "LOW": 1}


@dataclass
class TransportException:
    truck_id: str
    type: str
    severity: str
    message: str
    evidence: dict = field(default_factory=dict)


def _hhmm(dt):
    return dt.strftime("%H:%M") if dt else "-"


def detect(conn, twin):
    """Attach exceptions to every TruckState and return them ranked."""
    hist = customer_history(conn)
    found = []
    for st in twin.values():
        st.exceptions = []
        add = lambda *a, **kw: st.exceptions.append(TransportException(st.truck_id, *a, **kw))

        for s in st.shipments:
            if s["status"] != "DELIVERED" and (not s["manifest_no"] or not s["pallets"]):
                add("MISSING_INFO", "MEDIUM", f"Shipment {s['id']} has no manifest number",
                    evidence={"shipment": s["id"], "customer_id": s["customer_id"]})

        if st.status in ("AVAILABLE", "MAINTENANCE") or not st.trip_id:
            found += st.exceptions
            continue

        breakdown = next((e for e in st.events if e["event"] == "BREAKDOWN"), None)
        if breakdown:
            add("BREAKDOWN", "CRITICAL", f"Driver reported breakdown at {breakdown['ts'][11:]}: {breakdown['note']}",
                evidence={"reported": breakdown["ts"], "location": st.location_label})

        if st.ping_age_min > GPS_STALE_MIN:
            add("GPS_STALE", "HIGH", f"No GPS ping for {st.ping_age_min} min (last {_hhmm(st.ping_ts)})",
                evidence={"last_ping": _hhmm(st.ping_ts), "last_position": st.location_label})

        nxt = st.next_stop
        if st.status == "AT_CUSTOMER" and nxt:
            dwell = round((NOW - nxt.actual_arrival).total_seconds() / 60)
            if dwell > SERVICE_MIN + WAITING_EXTRA_MIN:
                h = hist.get(nxt.customer_id, {})
                add("CUSTOMER_WAITING", "HIGH" if dwell > SERVICE_MIN + 45 else "MEDIUM",
                    f"Waiting {dwell} min at {nxt.customer} (planned {SERVICE_MIN} min)",
                    evidence={"customer_id": nxt.customer_id, "arrived": _hhmm(nxt.actual_arrival),
                              "dwell_min": dwell, **{f"hist_{k}": v for k, v in h.items()}})

        if st.status == "IN_TRANSIT" and st.speed_kmh == 0 and st.dwell_min >= IDLE_MIN and not breakdown:
            near_site = haversine_km((st.lat, st.lon), st.next_point) < 1.5 or haversine_km((st.lat, st.lon), st.prev_point) < 1.5
            if not near_site:
                add("IDLE", "HIGH" if st.dwell_min >= 45 else "MEDIUM",
                    f"Stopped {st.dwell_min} min away from any site ({st.location_label})",
                    evidence={"stopped_since": _hhmm(st.stopped_since), "location": st.location_label})

        if st.status == "IN_TRANSIT" and st.prev_point and st.next_point:
            off = point_segment_km((st.lat, st.lon), st.prev_point, st.next_point)
            leg = haversine_km(st.prev_point, st.next_point)
            if off > max(DEVIATION_KM, 0.03 * leg):
                add("ROUTE_DEVIATION", "MEDIUM", f"{off:.0f} km off the planned corridor",
                    evidence={"offset_km": round(off, 1), "leg_km": round(leg)})

        for s in st.stops:
            if s.actual_arrival or not s.predicted_arrival:
                continue
            d = s.delay_min
            if d >= DELAY_MEDIUM_MIN:
                h = hist.get(s.customer_id, {})
                add("DELAY", "HIGH" if d >= DELAY_HIGH_MIN else "MEDIUM",
                    f"{d} min late for {s.customer} (planned {_hhmm(s.planned_arrival)}, predicted {_hhmm(s.predicted_arrival)})",
                    evidence={"customer_id": s.customer_id, "seq": s.seq, "delay_min": d,
                              "planned": _hhmm(s.planned_arrival), "predicted": _hhmm(s.predicted_arrival)})
            if s.dock_close and _hhmm(s.predicted_arrival) > s.dock_close:
                add("DOCK_CLOSED_RISK", "HIGH",
                    f"Predicted arrival {_hhmm(s.predicted_arrival)} at {s.customer} is after dock closing {s.dock_close}",
                    evidence={"customer_id": s.customer_id, "dock_close": s.dock_close,
                              "predicted": _hhmm(s.predicted_arrival)})
            break  # only judge the next upcoming stop; later ones depend on it

        found += st.exceptions
    found.sort(key=lambda e: (-SEVERITY_ORDER[e.severity], e.truck_id))
    return found


def likely_cause(st):
    """Turn a truck's exceptions into a single human-readable root cause."""
    types = {e.type: e for e in st.exceptions}
    for t, text in (("BREAKDOWN", "vehicle breakdown"), ("IDLE", "unplanned stop en route"),
                    ("CUSTOMER_WAITING", "long waiting time at the customer dock"),
                    ("GPS_STALE", "no recent GPS data (cannot confirm progress)"),
                    ("ROUTE_DEVIATION", "truck is off the planned route")):
        if t in types:
            return text
    if "DELAY" in types:
        if st.actual_depart and st.planned_depart and (st.actual_depart - st.planned_depart).total_seconds() > 1800:
            late = round((st.actual_depart - st.planned_depart).total_seconds() / 60)
            return f"late departure from origin ({late} min after plan)"
        return "accumulated waiting at earlier stops"
    return None
