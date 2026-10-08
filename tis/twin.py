"""Transportation Digital Twin.

Builds a live picture of every truck from raw operational records only
(trip plan, stop timestamps, latest GPS ping, driver reports), then projects
the rest of each trip forward to predict ETAs.
"""
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from . import db
from .config import NOW, SERVICE_MIN
from .geo import drive_minutes, haversine_km, road_km


@dataclass
class StopView:
    seq: int
    customer_id: str
    customer: str
    city: str
    planned_arrival: datetime
    actual_arrival: datetime = None
    actual_departure: datetime = None
    predicted_arrival: datetime = None
    dock_close: str = None

    @property
    def delay_min(self):
        ref = self.actual_arrival or self.predicted_arrival
        return None if ref is None else round((ref - self.planned_arrival).total_seconds() / 60)


@dataclass
class TruckState:
    truck_id: str
    plate: str
    type: str
    capacity: int
    reefer: bool
    driver_id: str
    driver_name: str
    driving_min_today: float
    status: str
    lat: float
    lon: float
    speed_kmh: float
    ping_ts: datetime
    stopped_since: datetime = None
    location_label: str = ""
    trip_id: str = None
    origin: str = None
    end: str = None
    planned_depart: datetime = None
    actual_depart: datetime = None
    stops: list = field(default_factory=list)
    next_stop: StopView = None
    prev_point: tuple = None
    next_point: tuple = None
    predicted_end: datetime = None
    planned_end: datetime = None
    events: list = field(default_factory=list)
    shipments: list = field(default_factory=list)
    exceptions: list = field(default_factory=list)

    @property
    def risk(self):
        order = {"CRITICAL": 4, "HIGH": 3, "MEDIUM": 2, "LOW": 1}
        if not self.exceptions:
            return "OK"
        return max((e.severity for e in self.exceptions), key=order.get)

    @property
    def ping_age_min(self):
        return round((NOW - self.ping_ts).total_seconds() / 60)

    @property
    def dwell_min(self):
        if self.stopped_since is None:
            return 0
        return round((NOW - self.stopped_since).total_seconds() / 60)


def _nearest_label(conn, lat, lon):
    best = min(conn.execute("SELECT id, name, city FROM location"),
               key=lambda r: haversine_km((lat, lon), _loc(conn, r["id"])))
    d = haversine_km((lat, lon), _loc(conn, best["id"]))
    if d < 1.5:
        return f"at {best['name']}"
    return f"{d:.0f} km from {best['name']}"


_LOC_CACHE = {}


def _loc(conn, lid):
    key = (id(conn), lid)
    if key not in _LOC_CACHE:
        r = conn.execute("SELECT lat, lon FROM location WHERE id=?", (lid,)).fetchone()
        _LOC_CACHE[key] = (r["lat"], r["lon"])
    return _LOC_CACHE[key]


def build(conn):
    """Return {truck_id: TruckState} reflecting the situation at NOW."""
    _LOC_CACHE.clear()
    avg_wait = {r["id"]: r["avg_wait_min"] or 0 for r in conn.execute("SELECT id, avg_wait_min FROM location")}
    twin = {}
    rows = conn.execute("""
        SELECT t.*, d.driver_name, d.driving_min_today, g.ts AS ping_ts, g.lat, g.lon,
               g.speed_kmh, g.stopped_since
        FROM truck t JOIN driver d ON d.id = t.driver_id JOIN gps_latest g ON g.truck_id = t.id
        ORDER BY t.id""")
    for r in rows:
        st = TruckState(
            truck_id=r["id"], plate=r["plate"], type=r["type"], capacity=r["capacity_pallets"],
            reefer=bool(r["reefer"]), driver_id=r["driver_id"], driver_name=r["driver_name"],
            driving_min_today=r["driving_min_today"], status="AVAILABLE",
            lat=r["lat"], lon=r["lon"], speed_kmh=r["speed_kmh"], ping_ts=db.parse(r["ping_ts"]),
            stopped_since=db.parse(r["stopped_since"]))
        st.location_label = _nearest_label(conn, st.lat, st.lon)
        st.events = [dict(e) for e in conn.execute(
            "SELECT ts, event, note FROM driver_event WHERE truck_id=? ORDER BY ts", (st.truck_id,))]

        if r["in_maintenance"]:
            st.status = "MAINTENANCE"
            twin[st.truck_id] = st
            continue

        trip = conn.execute("SELECT * FROM trip WHERE truck_id=?", (st.truck_id,)).fetchone()
        if trip is None:
            twin[st.truck_id] = st
            continue

        st.trip_id, st.origin, st.end = trip["id"], trip["origin_id"], trip["end_id"]
        st.planned_depart, st.actual_depart = db.parse(trip["planned_depart"]), db.parse(trip["actual_depart"])
        st.planned_end = db.parse(trip["planned_end"])
        st.shipments = [dict(s) for s in conn.execute("SELECT * FROM shipment WHERE trip_id=? ORDER BY stop_seq", (st.trip_id,))]
        for s in conn.execute("""SELECT s.*, l.name, l.city, l.dock_close FROM trip_stop s
                                 JOIN location l ON l.id = s.location_id WHERE trip_id=? ORDER BY seq""", (st.trip_id,)):
            st.stops.append(StopView(s["seq"], s["location_id"], s["name"], s["city"], db.parse(s["planned_arrival"]),
                                     db.parse(s["actual_arrival"]), db.parse(s["actual_departure"]),
                                     dock_close=s["dock_close"]))

        if trip["actual_end"]:
            st.status = "AVAILABLE"            # trip finished, truck is free again
            st.trip_id = trip["id"]
            twin[st.truck_id] = st
            continue

        # ---- derive status from events --------------------------------
        current = next((s for s in st.stops if s.actual_arrival and not s.actual_departure), None)
        upcoming = [s for s in st.stops if not s.actual_arrival]
        if any(e["event"] == "BREAKDOWN" for e in st.events):
            st.status = "BREAKDOWN"
        elif st.actual_depart is None:
            st.status = "LOADING"
        elif current:
            st.status = "AT_CUSTOMER"
        elif upcoming:
            st.status = "IN_TRANSIT"
        else:
            st.status = "RETURNING"

        # ---- corridor the truck should currently be on ---------------
        done = [s for s in st.stops if s.actual_departure]
        prev_id = done[-1].customer_id if done else st.origin
        nxt_id = upcoming[0].customer_id if upcoming else st.end
        st.prev_point, st.next_point = _loc(conn, prev_id), _loc(conn, nxt_id)
        st.next_stop = current or (upcoming[0] if upcoming else None)

        # ---- forward projection --------------------------------------
        t = NOW
        pos = (st.lat, st.lon)
        if st.status == "LOADING":
            t = max(NOW, st.planned_depart) + timedelta(minutes=15)
        if current:
            expected = current.actual_arrival + timedelta(minutes=SERVICE_MIN + avg_wait[current.customer_id])
            t = max(NOW + timedelta(minutes=10), expected)
            pos = _loc(conn, current.customer_id)
        for s in upcoming:
            t += timedelta(minutes=drive_minutes(road_km(pos, _loc(conn, s.customer_id))))
            s.predicted_arrival = t
            t += timedelta(minutes=SERVICE_MIN + avg_wait[s.customer_id])
            pos = _loc(conn, s.customer_id)
        st.predicted_end = t + timedelta(minutes=drive_minutes(road_km(pos, _loc(conn, st.end))))
        twin[st.truck_id] = st
    return twin


def customer_history(conn):
    """Per-customer dock behaviour over the history window."""
    out = {}
    for r in conn.execute("""SELECT customer_id, COUNT(*) n, AVG(dock_wait_min) avg_wait,
                                    SUM(dock_wait_min > 30) long_waits
                             FROM stop_history GROUP BY customer_id"""):
        out[r["customer_id"]] = dict(visits=r["n"], avg_wait=round(r["avg_wait"], 1),
                                     long_wait_pct=round(100 * r["long_waits"] / r["n"]))
    return out
