"""KPI engine: plain SQL / arithmetic, no model involved."""
from collections import Counter

from .config import HISTORY_DAYS


def fleet_today(twin):
    status = Counter(s.status for s in twin.values())
    usable = len(twin) - status["MAINTENANCE"]
    with_trip = sum(1 for s in twin.values() if s.trip_id)
    stops = [st for s in twin.values() for st in s.stops if st.actual_arrival]
    on_time = sum(1 for st in stops if st.delay_min <= 15)
    return dict(
        fleet=len(twin), status=dict(status), usable=usable,
        utilization_pct=round(100 * with_trip / usable) if usable else 0,
        trips_today=with_trip, stops_done=len(stops),
        on_time_pct=round(100 * on_time / len(stops)) if stops else None,
        trucks_with_exceptions=sum(1 for s in twin.values() if s.exceptions),
        pallets_delivered=sum(sh["pallets"] or 0 for s in twin.values() for sh in s.shipments if sh["status"] == "DELIVERED"),
    )


def drivers(conn, limit=10, order="trips"):
    order_sql = {"trips": "trips DESC, km DESC", "km": "km DESC", "on_time": "on_time_pct DESC, trips DESC",
                 "pallets": "pallets DESC"}[order]
    rows = conn.execute(f"""
        SELECT h.driver_id, d.driver_name, h.truck_id, COUNT(*) trips, ROUND(SUM(km)) km,
               SUM(pallets) pallets, SUM(stops) stops,
               ROUND(100.0 * SUM(on_time_stops) / SUM(stops)) on_time_pct,
               ROUND(AVG(duration_min) / 60.0, 1) avg_trip_h
        FROM trip_history h JOIN driver d ON d.id = h.driver_id
        GROUP BY h.driver_id ORDER BY {order_sql} LIMIT ?""", (limit,))
    return [dict(r) for r in rows]


def customers(conn):
    rows = conn.execute("""
        SELECT s.customer_id, l.name, l.city, COUNT(*) visits, ROUND(AVG(dock_wait_min)) avg_wait,
               ROUND(100.0 * SUM(dock_wait_min > 30) / COUNT(*)) long_wait_pct,
               ROUND(100.0 * SUM(delay_min <= 15) / COUNT(*)) on_time_pct
        FROM stop_history s JOIN location l ON l.id = s.customer_id
        GROUP BY s.customer_id ORDER BY avg_wait DESC""")
    return [dict(r) for r in rows]


def period_label():
    return f"last {HISTORY_DAYS} days"
