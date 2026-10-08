"""Operational database.

SQLite is used so the example runs anywhere with zero setup. The schema is
plain SQL and maps 1:1 onto PostgreSQL for a production deployment.
"""
import os
import sqlite3
from datetime import datetime

DEFAULT_PATH = os.environ.get("TIS_DB", os.path.join(os.path.dirname(os.path.dirname(__file__)), "tis.db"))

SCHEMA = """
CREATE TABLE location (
    id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, city TEXT,
    lat REAL NOT NULL, lon REAL NOT NULL,
    avg_wait_min REAL, dock_open TEXT, dock_close TEXT, appointment INTEGER
);
CREATE TABLE driver (
    id TEXT PRIMARY KEY, driver_name TEXT NOT NULL, phone TEXT, national_id TEXT,
    driving_min_today REAL NOT NULL DEFAULT 0
);
CREATE TABLE truck (
    id TEXT PRIMARY KEY, plate TEXT NOT NULL, type TEXT NOT NULL,
    capacity_pallets INTEGER NOT NULL, reefer INTEGER NOT NULL,
    driver_id TEXT REFERENCES driver(id), home_depot TEXT REFERENCES location(id),
    in_maintenance INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE trip (
    id TEXT PRIMARY KEY, truck_id TEXT REFERENCES truck(id), driver_id TEXT REFERENCES driver(id),
    origin_id TEXT REFERENCES location(id), end_id TEXT REFERENCES location(id),
    planned_depart TEXT, actual_depart TEXT, planned_end TEXT, actual_end TEXT
);
CREATE TABLE trip_stop (
    trip_id TEXT REFERENCES trip(id), seq INTEGER, location_id TEXT REFERENCES location(id),
    planned_arrival TEXT, planned_departure TEXT, actual_arrival TEXT, actual_departure TEXT,
    PRIMARY KEY (trip_id, seq)
);
CREATE TABLE shipment (
    id TEXT PRIMARY KEY, trip_id TEXT REFERENCES trip(id), stop_seq INTEGER,
    origin_id TEXT REFERENCES location(id), customer_id TEXT REFERENCES location(id),
    pallets INTEGER, reefer INTEGER NOT NULL, manifest_no TEXT,
    status TEXT NOT NULL, required_by TEXT, assigned_truck TEXT
);
CREATE TABLE gps_latest (
    truck_id TEXT PRIMARY KEY REFERENCES truck(id), ts TEXT NOT NULL,
    lat REAL NOT NULL, lon REAL NOT NULL, speed_kmh REAL NOT NULL, stopped_since TEXT
);
CREATE TABLE driver_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT, truck_id TEXT, ts TEXT, event TEXT, note TEXT
);
CREATE TABLE trip_history (
    day TEXT, truck_id TEXT, driver_id TEXT, km REAL, pallets INTEGER,
    stops INTEGER, on_time_stops INTEGER, duration_min REAL
);
CREATE TABLE stop_history (
    day TEXT, truck_id TEXT, driver_id TEXT, customer_id TEXT,
    delay_min REAL, dock_wait_min REAL
);
CREATE TABLE approval (
    id INTEGER PRIMARY KEY AUTOINCREMENT, created_ts TEXT, requested_by TEXT,
    action TEXT, summary TEXT, payload TEXT, status TEXT,
    decided_by TEXT, decided_ts TEXT
);
CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, user TEXT, role TEXT,
    action TEXT, detail TEXT
);
"""


def connect(path=DEFAULT_PATH):
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def create(path=DEFAULT_PATH):
    if path != ":memory:" and os.path.exists(path):
        os.remove(path)
    conn = connect(path)
    conn.executescript(SCHEMA)
    return conn


def ts(dt):
    return dt.isoformat(timespec="minutes") if dt else None


def parse(s):
    return datetime.fromisoformat(s) if s else None


def audit(conn, user, role, action, detail):
    conn.execute(
        "INSERT INTO audit_log (ts, user, role, action, detail) VALUES (?,?,?,?,?)",
        (datetime.now().isoformat(timespec="seconds"), user, role, action, detail),
    )
    conn.commit()
