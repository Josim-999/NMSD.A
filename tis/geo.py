"""Small geometry helpers (no external GIS dependency)."""
import math

from .config import CITY_SPEED_KMH, HIGHWAY_SPEED_KMH, ROAD_FACTOR

EARTH_KM = 6371.0


def haversine_km(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_KM * math.asin(math.sqrt(h))


def road_km(a, b):
    return haversine_km(a, b) * ROAD_FACTOR


def drive_minutes(km):
    speed = HIGHWAY_SPEED_KMH if km > 60 else CITY_SPEED_KMH
    return km / speed * 60


def _to_xy(p, ref_lat):
    """Equirectangular projection in km; fine for distances of a few hundred km."""
    k = math.pi / 180 * EARTH_KM
    return p[1] * k * math.cos(math.radians(ref_lat)), p[0] * k


def interpolate(a, b, f):
    return a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f


def offset_point(a, b, f, km):
    """Point at fraction f along a->b, pushed `km` sideways (simulates a wrong road)."""
    lat, lon = interpolate(a, b, f)
    ax, ay = _to_xy(a, lat)
    bx, by = _to_xy(b, lat)
    dx, dy = bx - ax, by - ay
    norm = math.hypot(dx, dy) or 1.0
    nx, ny = -dy / norm, dx / norm
    k = math.pi / 180 * EARTH_KM
    return lat + ny * km / k, lon + nx * km / (k * math.cos(math.radians(lat)))


def point_segment_km(p, a, b):
    """Distance from p to the straight corridor a->b."""
    ref = p[0]
    px, py = _to_xy(p, ref)
    ax, ay = _to_xy(a, ref)
    bx, by = _to_xy(b, ref)
    dx, dy = bx - ax, by - ay
    seg2 = dx * dx + dy * dy
    t = 0.0 if seg2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / seg2))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))
