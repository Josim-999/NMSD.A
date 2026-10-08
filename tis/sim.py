"""Synthetic operations generator.

Stands in for the TMS / WMS / FMS / driver-app feeds. It writes only what
those systems would really expose (plans, timestamps of events, the latest GPS
ping, driver reports) so the digital twin has to *infer* everything else.

A handful of trucks get a deterministic scenario (dock delay, idle on the
highway, breakdown...) so the demo and the tests always have something to find.
"""
import math
import random
from datetime import timedelta

from . import db
from .config import (CUSTOMERS, DEPOTS, FLEET_SIZE, HISTORY_DAYS, NOW, SEED,
                     SERVICE_MIN)
from .geo import drive_minutes, interpolate, offset_point, road_km

FIRST = ["Ahmed", "Mohammed", "Khalid", "Faisal", "Omar", "Saad", "Yousef", "Abdullah",
         "Fahad", "Nasser", "Ali", "Hassan", "Rakan", "Majed", "Turki", "Bandar", "Sultan"]
RIYADH = ["C01", "C02", "C03", "C04", "C05"]
POOL = range(37, 44)          # trucks with no trip today
MAINTENANCE = (44, 45)


def M(m):
    return timedelta(minutes=m)


def pt(lid):
    d = DEPOTS.get(lid) or CUSTOMERS[lid]
    return d["lat"], d["lon"]


def leg_min(a, b):
    return drive_minutes(road_km(pt(a), pt(b)))


def truck_spec(n):
    if n >= 40:
        return "VAN", 8, 1
    if n % 3 == 0:
        return "DRY", 24, 0
    return "REEFER", 18, 1


# --------------------------------------------------------------------------
# Scenario trucks. Times are anchored on NOW so the situation is "live".
# --------------------------------------------------------------------------
def scenario(n):
    if n == 7:    # stuck at a chronic dock (Hospital A)
        return dict(origin="MLP", stops=["C01", "C03"], end="MLP", depart=NOW - M(110), waits={0: 120})
    if n == 12:   # unplanned stop on the Riyadh-Dammam highway
        return dict(origin="MLP", stops=["C10", "C11"], end="DMM-HUB", depart=NOW - M(240),
                    idle={0: (NOW - M(50), 90)})
    if n == 19:   # off the planned corridor to Buraydah
        return dict(origin="MLP", stops=["C06"], end="MLP", depart=NOW - M(150), deviation={0: 18})
    if n == 23:   # breakdown, reported through the driver app
        return dict(origin="MLP", stops=["C12"], end="MLP", depart=NOW - M(140),
                    breakdown={0: NOW - M(110)},
                    event=(NOW - M(105), "BREAKDOWN", "Tyre burst on Al Kharj road, waiting for road assistance"))
    if n == 27:   # normal: waiting 31 min at its second customer
        d = leg_min("MLP", "C02") + SERVICE_MIN + 5 + leg_min("C02", "C05")
        return dict(origin="MLP", stops=["C02", "C05"], end="MLP", depart=NOW - M(d + 31), waits={0: 5, 1: 90})
    if n == 31:   # GPS unit silent for 55 minutes
        return dict(origin="MLP", stops=["C03", "C04"], end="MLP", depart=NOW - M(90), stale=55)
    if n == 34:   # manifest number missing on the first shipment
        return dict(origin="MLP", stops=["C02", "C03"], end="MLP", depart=NOW - M(60), missing_manifest=0)
    if n == 36:   # late loading -> will reach Madinah after the dock closes
        return dict(origin="JED-HUB", stops=["C14"], end="JED-HUB", depart=NOW.replace(hour=8, minute=30),
                    depart_delay=95, waits={0: 0})
    return None


def random_trip(n, rng):
    kind = n % 4
    if kind in (0, 1):
        stops = rng.sample(RIYADH, rng.choice([2, 3]))
        return dict(origin="MLP", stops=stops, end="MLP",
                    depart=NOW.replace(hour=rng.randint(5, 10), minute=rng.choice([0, 15, 30, 45])))
    if kind == 2:
        return dict(origin="MLP", stops=[rng.choice(["C06", "C12"])], end="MLP",
                    depart=NOW.replace(hour=rng.randint(5, 7), minute=rng.choice([0, 30])))
    if rng.random() < 0.5:
        return dict(origin="MLP", stops=["C10", "C11"], end="DMM-HUB",
                    depart=NOW.replace(hour=rng.randint(4, 6), minute=30))
    return dict(origin="JED-HUB", stops=rng.sample(["C07", "C08", "C09"], 2), end="JED-HUB",
                depart=NOW.replace(hour=rng.randint(6, 9), minute=0))


# --------------------------------------------------------------------------
# Timeline simulation
# --------------------------------------------------------------------------
def simulate(spec, rng, scripted):
    """Return (planned_stops, segments) for one trip."""
    pts = [spec["origin"]] + spec["stops"] + [spec["end"]]
    depart = spec["depart"]

    planned, t = [], depart
    for k in range(len(spec["stops"])):
        t += M(leg_min(pts[k], pts[k + 1]))
        planned.append((t, t + M(SERVICE_MIN)))
        t += M(SERVICE_MIN)
    planned_end = t + M(leg_min(pts[-2], pts[-1]))

    delay = spec.get("depart_delay", 0 if scripted else rng.choice([0, 0, 5, 10, 15]))
    t = depart + M(delay)
    segs = [dict(kind="LOADING", t0=depart - M(45), t1=t, a=pt(pts[0]))]
    for k in range(len(pts) - 1):
        a, b = pt(pts[k]), pt(pts[k + 1])
        dur = leg_min(pts[k], pts[k + 1]) * (1.0 if scripted else rng.uniform(0.95, 1.08))
        dev = spec.get("deviation", {}).get(k, 0)
        if k in spec.get("breakdown", {}):
            stop_at = spec["breakdown"][k]
            f = (stop_at - t) / M(dur)
            segs.append(dict(kind="DRIVE", t0=t, t1=stop_at, a=a, b=b, f0=0, f1=f, dev=dev))
            segs.append(dict(kind="BREAKDOWN", t0=stop_at, t1=None, a=interpolate(a, b, f)))
            return planned, planned_end, segs
        if k in spec.get("idle", {}):
            stop_at, idle_min = spec["idle"][k]
            f = (stop_at - t) / M(dur)
            segs.append(dict(kind="DRIVE", t0=t, t1=stop_at, a=a, b=b, f0=0, f1=f, dev=dev))
            segs.append(dict(kind="STOP", t0=stop_at, t1=stop_at + M(idle_min), a=interpolate(a, b, f)))
            t = stop_at + M(idle_min)
            segs.append(dict(kind="DRIVE", t0=t, t1=t + M(dur * (1 - f)), a=a, b=b, f0=f, f1=1, dev=dev))
            t += M(dur * (1 - f))
        else:
            segs.append(dict(kind="DRIVE", t0=t, t1=t + M(dur), a=a, b=b, f0=0, f1=1, dev=dev))
            t += M(dur)
        if k < len(spec["stops"]):
            cust = CUSTOMERS[spec["stops"][k]]
            wait = spec.get("waits", {}).get(k)
            if wait is None:
                wait = 0 if scripted else min(120, rng.expovariate(1 / cust["avg_wait"]))
            segs.append(dict(kind="SERVICE", t0=t, t1=t + M(SERVICE_MIN + wait), a=b, seq=k))
            t += M(SERVICE_MIN + wait)
    return planned, planned_end, segs


def state_at(segs, when):
    """Position, speed and stopped-since of the truck at a given time."""
    for s in segs:
        if s["t0"] <= when and (s["t1"] is None or when < s["t1"]):
            if s["kind"] == "DRIVE":
                f = s["f0"] + (s["f1"] - s["f0"]) * ((when - s["t0"]) / (s["t1"] - s["t0"]))
                pos = offset_point(s["a"], s["b"], f, s["dev"] * math.sin(math.pi * f)) if s["dev"] else interpolate(s["a"], s["b"], f)
                speed = 80 if road_km(s["a"], s["b"]) > 60 else 32
                return pos, speed, None
            return s["a"], 0.0, s["t0"]
    if when < segs[0]["t0"]:
        return segs[0]["a"], 0.0, segs[0]["t0"]
    last = segs[-1]
    return last["b"], 0.0, last["t1"]


def driving_minutes(segs, until):
    total = 0.0
    for s in segs:
        if s["kind"] == "DRIVE" and s["t0"] < until:
            total += ((min(s["t1"], until)) - s["t0"]).total_seconds() / 60
    return total


# --------------------------------------------------------------------------
def generate(conn, seed=SEED):
    rng = random.Random(seed)
    cur = conn.cursor()

    for lid, d in DEPOTS.items():
        cur.execute("INSERT INTO location VALUES (?,?,?,?,?,?,?,?,?,?)",
                    (lid, "DEPOT", d["name"], None, d["lat"], d["lon"], None, None, None, 0))
    for lid, c in CUSTOMERS.items():
        cur.execute("INSERT INTO location VALUES (?,?,?,?,?,?,?,?,?,?)",
                    (lid, "CUSTOMER", c["name"], c["city"], c["lat"], c["lon"], c["avg_wait"],
                     c["dock"][0], c["dock"][1], int(c["appointment"])))

    skill = {}
    for n in range(1, FLEET_SIZE + 1):
        did, tid = f"D{n:03d}", f"TRK-{n:03d}"
        name = f"{FIRST[(n * 7) % len(FIRST)]} {chr(65 + (n * 5) % 26)}."
        cur.execute("INSERT INTO driver VALUES (?,?,?,?,?)",
                    (did, name, f"+9665{rng.randint(10000000, 99999999)}", f"1{rng.randint(100000000, 999999999)}", 0))
        typ, cap, reefer = truck_spec(n)
        home = "JED-HUB" if n == 43 else "MLP"
        cur.execute("INSERT INTO truck VALUES (?,?,?,?,?,?,?,?)",
                    (tid, f"{rng.randint(1000, 9999)} {''.join(rng.choice('ABDEGHJKLNRSTUVXZ') for _ in range(3))}",
                     typ, cap, reefer, did, home, int(n in MAINTENANCE)))
        skill[n] = rng.uniform(0.9, 1.15)   # >1 = slower driver (history only)

    # ---- today's trips --------------------------------------------------
    for n in range(1, FLEET_SIZE + 1):
        tid, did = f"TRK-{n:03d}", f"D{n:03d}"
        if n in MAINTENANCE:
            cur.execute("INSERT INTO gps_latest VALUES (?,?,?,?,?,?)",
                        (tid, db.ts(NOW - M(3)), *pt("MLP"), 0.0, db.ts(NOW.replace(hour=0, minute=0))))
            continue
        if n in POOL:
            home = "JED-HUB" if n == 43 else "MLP"
            used = 560 if n == 41 else rng.choice([0, 0, 60, 120, 240])
            cur.execute("UPDATE driver SET driving_min_today=? WHERE id=?", (used, did))
            cur.execute("INSERT INTO gps_latest VALUES (?,?,?,?,?,?)",
                        (tid, db.ts(NOW - M(2)), *pt(home), 0.0, db.ts(NOW - M(rng.randint(60, 300)))))
            continue

        spec = scenario(n)
        scripted = spec is not None
        spec = spec or random_trip(n, rng)
        planned, planned_end, segs = simulate(spec, rng, scripted)
        trip_id = f"T{NOW:%y%m%d}-{n:03d}"

        actual_depart = segs[0]["t1"] if segs[0]["t1"] <= NOW else None
        final = segs[-1]
        actual_end = final["t1"] if final["kind"] == "DRIVE" and final["t1"] <= NOW else None
        cur.execute("INSERT INTO trip VALUES (?,?,?,?,?,?,?,?,?)",
                    (trip_id, tid, did, spec["origin"], spec["end"], db.ts(spec["depart"]),
                     db.ts(actual_depart), db.ts(planned_end), db.ts(actual_end)))

        service = {s["seq"]: s for s in segs if s["kind"] == "SERVICE"}
        for k, cid in enumerate(spec["stops"]):
            s = service.get(k)
            arr = s["t0"] if s and s["t0"] <= NOW else None
            dep = s["t1"] if s and s["t1"] <= NOW else None
            cur.execute("INSERT INTO trip_stop VALUES (?,?,?,?,?,?,?)",
                        (trip_id, k, cid, db.ts(planned[k][0]), db.ts(planned[k][1]), db.ts(arr), db.ts(dep)))
            typ, cap, reefer = truck_spec(n)
            pallets = rng.randint(2, max(3, cap // len(spec["stops"])))
            manifest = None if spec.get("missing_manifest") == k else f"MNF-{rng.randint(100000, 999999)}"
            cur.execute("INSERT INTO shipment VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        (f"S{n:03d}{k}", trip_id, k, spec["origin"], cid, pallets, reefer, manifest,
                         "DELIVERED" if dep else "DISPATCHED", db.ts(NOW.replace(hour=18, minute=0)), tid))

        ping_time = NOW - M(spec.get("stale", rng.randint(0, 4)))
        pos, speed, stopped = state_at(segs, ping_time)
        cur.execute("INSERT INTO gps_latest VALUES (?,?,?,?,?,?)",
                    (tid, db.ts(ping_time), pos[0], pos[1], speed, db.ts(stopped)))
        cur.execute("UPDATE driver SET driving_min_today=? WHERE id=?", (round(driving_minutes(segs, NOW)), did))
        if "event" in spec:
            when, event, note = spec["event"]
            cur.execute("INSERT INTO driver_event (truck_id, ts, event, note) VALUES (?,?,?,?)",
                        (tid, db.ts(when), event, note))

    # ---- shipments waiting for a truck ----------------------------------
    tomorrow = (NOW + timedelta(days=1)).replace(hour=12, minute=0)
    today = NOW.replace(hour=18, minute=0)
    pending = [("P01", "MLP", "C02", 6, 1, today), ("P02", "MLP", "C06", 16, 1, tomorrow),
               ("P03", "MLP", "C12", 20, 0, tomorrow), ("P04", "MLP", "C03", 4, 1, today),
               ("P05", "MLP", "C10", 14, 1, tomorrow), ("P06", "MLP", "C04", 3, 1, today),
               ("P07", "MLP", "C13", 22, 1, tomorrow), ("P08", "JED-HUB", "C08", 5, 1, today),
               ("P09", "MLP", "C11", 10, 0, tomorrow)]
    for sid, org, cid, pallets, reefer, due in pending:
        cur.execute("INSERT INTO shipment VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (sid, None, None, org, cid, pallets, reefer, f"MNF-{rng.randint(100000, 999999)}",
                     "PENDING", db.ts(due), None))

    # ---- 30 days of history ---------------------------------------------
    for day in range(HISTORY_DAYS, 0, -1):
        d = (NOW - timedelta(days=day)).date().isoformat()
        for n in range(1, 44):
            if rng.random() > 0.85:
                continue
            spec = random_trip(n, rng)
            pts = [spec["origin"]] + spec["stops"] + [spec["end"]]
            km = sum(road_km(pt(pts[k]), pt(pts[k + 1])) for k in range(len(pts) - 1))
            late, on_time, total = 0.0, 0, 0.0
            for k, cid in enumerate(spec["stops"]):
                drive = leg_min(pts[k], pts[k + 1])
                late += drive * (skill[n] - 1) + rng.uniform(-5, 10)
                wait = min(150, rng.expovariate(1 / CUSTOMERS[cid]["avg_wait"]))
                cur.execute("INSERT INTO stop_history VALUES (?,?,?,?,?,?)",
                            (d, f"TRK-{n:03d}", f"D{n:03d}", cid, round(max(0, late), 1), round(wait, 1)))
                on_time += late <= 15
                late += wait
                total += drive * skill[n] + SERVICE_MIN + wait
            total += leg_min(pts[-2], pts[-1]) * skill[n]
            typ, cap, _ = truck_spec(n)
            cur.execute("INSERT INTO trip_history VALUES (?,?,?,?,?,?,?,?)",
                        (d, f"TRK-{n:03d}", f"D{n:03d}", round(km, 1), rng.randint(cap // 2, cap),
                         len(spec["stops"]), on_time, round(total)))
    conn.commit()
