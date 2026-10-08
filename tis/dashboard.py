"""Static HTML control tower (single self-contained file, no internet needed)."""
import html

from . import kpi
from .config import CUSTOMERS, DEPOTS, NOW
from .exceptions import SEVERITY_ORDER

BBOX = (38.6, 51.0, 20.8, 28.2)   # lon_min, lon_max, lat_min, lat_max
W, H = 620, 400


def _xy(lat, lon):
    x = (lon - BBOX[0]) / (BBOX[1] - BBOX[0]) * W
    y = (BBOX[3] - lat) / (BBOX[3] - BBOX[2]) * H
    return round(x, 1), round(y, 1)


RISK_CLASS = {"CRITICAL": "crit", "HIGH": "high", "MEDIUM": "med", "LOW": "low", "OK": "ok"}


def render(ai):
    k = kpi.fleet_today(ai.twin)
    e = html.escape
    trucks = sorted(ai.twin.values(), key=lambda s: (-SEVERITY_ORDER.get(s.risk, 0), s.truck_id))

    pts = []
    for lid, d in {**DEPOTS, **CUSTOMERS}.items():
        x, y = _xy(d["lat"], d["lon"])
        cls = "depot" if lid in DEPOTS else "cust"
        pts.append(f'<rect class="{cls}" x="{x - 4}" y="{y - 4}" width="8" height="8"><title>{e(d["name"])}</title></rect>')
    for lid in ("MLP", "JED-HUB", "DMM-HUB"):
        x, y = _xy(DEPOTS[lid]["lat"], DEPOTS[lid]["lon"])
        anchor = "end" if x > W * 0.8 else "start"
        pts.append(f'<text x="{x - 7 if anchor == "end" else x + 7}" y="{y - 6}" text-anchor="{anchor}">{lid}</text>')
    for st in trucks:
        x, y = _xy(st.lat, st.lon)
        pts.append(f'<circle class="t {RISK_CLASS[st.risk]}" cx="{x}" cy="{y}" r="{6 if st.exceptions else 4}">'
                   f'<title>{st.truck_id} {st.status} {e(st.location_label)}</title></circle>')

    rows = []
    for st in trucks:
        nxt = st.next_stop
        eta = nxt.predicted_arrival.strftime("%H:%M") if nxt and nxt.predicted_arrival else ""
        delay = f"{nxt.delay_min:+d}" if nxt and nxt.delay_min is not None and st.status != "AVAILABLE" else ""
        issues = "<br>".join(e(x.message) for x in st.exceptions)
        rows.append(f"<tr><td><b>{st.truck_id}</b></td><td>{st.type}</td><td>{st.status}</td>"
                    f"<td>{e(st.location_label)}</td><td>{e(nxt.customer) if nxt and st.status != 'AVAILABLE' else ''}</td>"
                    f"<td>{eta}</td><td>{delay}</td><td><span class='pill {RISK_CLASS[st.risk]}'>{st.risk}</span></td>"
                    f"<td class='issues'>{issues}</td></tr>")

    tiles = [("Utilisation", f"{k['utilization_pct']}%"), ("On-time arrivals", f"{k['on_time_pct']}%"),
             ("Stops done", k["stops_done"]), ("Pallets delivered", k["pallets_delivered"]),
             ("Trucks with exceptions", k["trucks_with_exceptions"]), ("Available now", k["status"].get("AVAILABLE", 0))]
    tile_html = "".join(f"<div class='tile'><div class='v'>{v}</div><div class='l'>{l}</div></div>" for l, v in tiles)

    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Transport Control Tower</title>
<style>
:root{{--bg:#f6f7f9;--card:#fff;--fg:#1c2430;--muted:#5f6b7a;--line:#e3e7ec;
--crit:#b42318;--high:#d9480f;--med:#b7791f;--low:#3b6fb6;--ok:#2f855a;--depot:#1c2430;--cust:#8a94a3}}
@media (prefers-color-scheme:dark){{:root{{--bg:#11151b;--card:#1a2029;--fg:#e6eaf0;--muted:#9aa5b4;--line:#2a323d;
--crit:#f97066;--high:#fb8c4a;--med:#f2c94c;--low:#7fa7e6;--ok:#5fd38d;--depot:#e6eaf0;--cust:#6b7584}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,-apple-system,Segoe UI,sans-serif}}
main{{max-width:1200px;margin:0 auto;padding:20px 16px}}h1{{font-size:20px;margin:0 0 2px}}.sub{{color:var(--muted);margin-bottom:16px}}
.tiles{{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin-bottom:16px}}
.tile,.card{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px}}
.v{{font-size:24px;font-weight:650}}.l{{color:var(--muted);font-size:12px}}
svg{{width:100%;height:auto;display:block}}svg text{{fill:var(--muted);font-size:11px}}
.depot{{fill:var(--depot)}}.cust{{fill:var(--cust)}}.t{{stroke:var(--card);stroke-width:1.5}}
.t.crit{{fill:var(--crit)}}.t.high{{fill:var(--high)}}.t.med{{fill:var(--med)}}.t.low{{fill:var(--low)}}.t.ok{{fill:var(--ok)}}
.wrap{{overflow-x:auto}}table{{border-collapse:collapse;width:100%;min-width:860px}}
td:first-child{{white-space:nowrap}}th,td{{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}}th{{color:var(--muted);font-weight:600;font-size:12px}}
.pill{{font-size:11px;font-weight:700;padding:2px 7px;border-radius:99px;color:var(--card)}}
.pill.crit{{background:var(--crit)}}.pill.high{{background:var(--high)}}.pill.med{{background:var(--med)}}.pill.low{{background:var(--low)}}.pill.ok{{background:var(--ok)}}
.issues{{color:var(--muted);font-size:12px}}.grid{{display:grid;grid-template-columns:minmax(0,3fr) minmax(0,2fr);gap:12px;margin-bottom:16px}}
pre{{white-space:pre-wrap;font:12px/1.5 ui-monospace,Menlo,Consolas,monospace;margin:0}}
@media (max-width:800px){{.grid{{grid-template-columns:1fr}}}}
</style></head><body><main>
<h1>Transport Control Tower</h1>
<div class="sub">Synthetic example data, situation at {NOW:%Y-%m-%d %H:%M}. {k['fleet']} trucks.</div>
<div class="tiles">{tile_html}</div>
<div class="grid">
 <div class="card"><svg viewBox="0 0 {W} {H}" role="img" aria-label="Truck positions">{''.join(pts)}</svg></div>
 <div class="card"><pre>{e(ai.attention().text)}</pre></div>
</div>
<div class="card wrap"><table><thead><tr><th>Truck</th><th>Type</th><th>Status</th><th>Location</th><th>Next stop</th>
<th>ETA</th><th>Delay</th><th>Risk</th><th>Exceptions</th></tr></thead><tbody>{''.join(rows)}</tbody></table></div>
</main></body></html>
"""
