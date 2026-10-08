"""Deterministic truck-to-shipment assignment.

The language model never does this maths. It hands the request to this
module, which returns an optimal plan plus the reason every rejected pairing
was rejected, and the model only explains the result.

Here: one shipment per truck, solved exactly with the Hungarian algorithm.
For multi-drop routing with time windows swap in Google OR-Tools
(pywrapcp routing solver) behind the same `plan()` function.
"""
from .config import MAX_DRIVING_MIN, SERVICE_MIN
from .geo import drive_minutes, road_km

INFEASIBLE = 10 ** 6
UNASSIGNED = 10 ** 5


def hungarian(cost):
    """Minimum-cost assignment for an n x m matrix with n <= m.

    Returns a list `col_of_row`. Classic O(n^2 m) potentials method.
    """
    n, m = len(cost), len(cost[0])
    assert n <= m
    INF = float("inf")
    u, v = [0.0] * (n + 1), [0.0] * (m + 1)
    p, way = [0] * (m + 1), [0] * (m + 1)
    for i in range(1, n + 1):
        p[0], j0 = i, 0
        minv, used = [INF] * (m + 1), [False] * (m + 1)
        while True:
            used[j0] = True
            i0, delta, j1 = p[j0], INF, 0
            for j in range(1, m + 1):
                if not used[j]:
                    cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if cur < minv[j]:
                        minv[j], way[j] = cur, j0
                    if minv[j] < delta:
                        delta, j1 = minv[j], j
            for j in range(m + 1):
                if used[j]:
                    u[p[j]] += delta
                    v[j] -= delta
                else:
                    minv[j] -= delta
            j0 = j1
            if p[j0] == 0:
                break
        while j0:
            j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
    col_of_row = [None] * n
    for j in range(1, m + 1):
        if p[j]:
            col_of_row[p[j] - 1] = j - 1
    return col_of_row


def evaluate(truck, shipment, loc):
    """Cost of giving `shipment` to `truck`, or (None, reason) if not allowed."""
    if shipment["pallets"] > truck["capacity"]:
        return None, f"capacity {truck['capacity']} < {shipment['pallets']} pallets"
    if shipment["reefer"] and not truck["reefer"]:
        return None, "needs refrigerated truck"
    deadhead = road_km(truck["pos"], loc[shipment["origin_id"]])
    linehaul = road_km(loc[shipment["origin_id"]], loc[shipment["customer_id"]])
    minutes = drive_minutes(deadhead) * (deadhead > 1) + drive_minutes(linehaul) + SERVICE_MIN
    remaining = MAX_DRIVING_MIN - truck["driving_min_today"]
    if minutes > remaining:
        return None, f"driver has {remaining:.0f} min driving left, trip needs {minutes:.0f}"
    # deadhead km dominates; wasted capacity is a tie-breaker (right-size the truck),
    # and reefer trucks are kept free for cold-chain loads when a dry truck can do the job
    cost = deadhead + 2.0 * (truck["capacity"] - shipment["pallets"]) + 30.0 * (truck["reefer"] and not shipment["reefer"])
    return dict(cost=cost, deadhead_km=round(deadhead), linehaul_km=round(linehaul),
                drive_min=round(minutes)), None


def plan(trucks, shipments, loc):
    """trucks/shipments are lists of dicts; loc maps location id -> (lat, lon)."""
    if not shipments:
        return dict(assignments=[], unassigned=[], total_deadhead_km=0)
    detail, matrix = {}, []
    for i, s in enumerate(shipments):
        row = []
        for j, t in enumerate(trucks):
            ok, why = evaluate(t, s, loc)
            detail[i, j] = ok or why
            row.append(ok["cost"] if ok else INFEASIBLE)
        row += [UNASSIGNED] * len(shipments)     # dummy columns = "leave unassigned"
        matrix.append(row)

    cols = hungarian(matrix)
    assignments, unassigned = [], []
    for i, j in enumerate(cols):
        s = shipments[i]
        if j < len(trucks) and matrix[i][j] < INFEASIBLE:
            assignments.append(dict(shipment=s["id"], truck=trucks[j]["id"], customer=s["customer_id"],
                                    pallets=s["pallets"], capacity=trucks[j]["capacity"], **{
                                        k: v for k, v in detail[i, j].items() if k != "cost"}))
        else:
            reasons = sorted({detail[i, k] for k in range(len(trucks)) if isinstance(detail[i, k], str)})
            unassigned.append(dict(shipment=s["id"], customer=s["customer_id"], pallets=s["pallets"],
                                   reasons=reasons or ["no truck left"]))
    return dict(assignments=assignments, unassigned=unassigned,
                total_deadhead_km=sum(a["deadhead_km"] for a in assignments))
