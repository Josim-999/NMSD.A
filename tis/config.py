"""Static configuration and master data for the example model.

Everything here is SYNTHETIC. In a real deployment these values come from the
TMS/WMS/FMS master data and from company policy documents.
"""
from datetime import datetime

# The simulation is frozen at a fixed "now" so every run is reproducible.
NOW = datetime(2026, 10, 8, 13, 30)
SEED = 42
FLEET_SIZE = 45
HISTORY_DAYS = 30

# --- Locations (lat, lon) -------------------------------------------------
DEPOTS = {
    "MLP": {"name": "Main Logistics Park (Riyadh)", "lat": 24.600, "lon": 46.800},
    "JED-HUB": {"name": "Jeddah Hub", "lat": 21.450, "lon": 39.250},
    "DMM-HUB": {"name": "Dammam Hub", "lat": 26.400, "lon": 50.050},
}

# avg_wait = typical extra dock waiting (minutes) seen historically.
# dock = receiving hours. appointment = booking needed before arrival.
CUSTOMERS = {
    "C01": {"name": "Hospital A", "city": "Riyadh", "lat": 24.690, "lon": 46.680, "avg_wait": 40, "dock": ("07:00", "15:00"), "appointment": True},
    "C02": {"name": "Hospital B", "city": "Riyadh", "lat": 24.750, "lon": 46.620, "avg_wait": 8, "dock": ("07:00", "16:00"), "appointment": False},
    "C03": {"name": "Pharmacy C", "city": "Riyadh", "lat": 24.800, "lon": 46.720, "avg_wait": 5, "dock": ("08:00", "22:00"), "appointment": False},
    "C04": {"name": "Clinic D", "city": "Riyadh", "lat": 24.660, "lon": 46.750, "avg_wait": 6, "dock": ("08:00", "14:00"), "appointment": False},
    "C05": {"name": "Hospital E", "city": "Riyadh", "lat": 24.710, "lon": 46.570, "avg_wait": 35, "dock": ("06:00", "15:00"), "appointment": True},
    "C06": {"name": "Hospital F", "city": "Buraydah", "lat": 26.330, "lon": 43.970, "avg_wait": 10, "dock": ("07:00", "17:00"), "appointment": False},
    "C07": {"name": "Hospital G", "city": "Jeddah", "lat": 21.540, "lon": 39.170, "avg_wait": 12, "dock": ("07:00", "16:00"), "appointment": True},
    "C08": {"name": "Clinic H", "city": "Jeddah", "lat": 21.600, "lon": 39.150, "avg_wait": 6, "dock": ("08:00", "18:00"), "appointment": False},
    "C09": {"name": "Hospital I", "city": "Makkah", "lat": 21.420, "lon": 39.830, "avg_wait": 15, "dock": ("07:00", "15:00"), "appointment": True},
    "C10": {"name": "Hospital J", "city": "Dammam", "lat": 26.430, "lon": 50.100, "avg_wait": 9, "dock": ("07:00", "16:00"), "appointment": False},
    "C11": {"name": "Pharmacy K", "city": "Khobar", "lat": 26.280, "lon": 50.210, "avg_wait": 5, "dock": ("08:00", "22:00"), "appointment": False},
    "C12": {"name": "Hospital L", "city": "Al Kharj", "lat": 24.150, "lon": 47.300, "avg_wait": 7, "dock": ("07:00", "15:00"), "appointment": False},
    "C13": {"name": "Hospital M", "city": "Hail", "lat": 27.520, "lon": 41.690, "avg_wait": 9, "dock": ("07:00", "16:00"), "appointment": False},
    "C14": {"name": "Hospital N", "city": "Madinah", "lat": 24.470, "lon": 39.610, "avg_wait": 10, "dock": ("07:00", "14:00"), "appointment": True},
}

# --- Operating policy (would come from the SOP knowledge base) -------------
SERVICE_MIN = 30              # planned unloading time per stop
MAX_DRIVING_MIN = 600         # 10 h driving per day (sample company policy)
CITY_SPEED_KMH = 35
HIGHWAY_SPEED_KMH = 80
ROAD_FACTOR = 1.2             # road distance vs straight line

# --- Exception thresholds ---------------------------------------------------
DELAY_MEDIUM_MIN = 20
DELAY_HIGH_MIN = 45
IDLE_MIN = 20
WAITING_EXTRA_MIN = 20        # dwell beyond planned service time
GPS_STALE_MIN = 30
DEVIATION_KM = 8

# --- Privacy / data boundary --------------------------------------------------
# Fields classified as personal data (Saudi PDPL). They are pseudonymised
# before any text is sent to a language model, even a local one.
PERSONAL_FIELDS = {"driver_name", "phone", "national_id"}
