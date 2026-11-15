"""
SafeLog signal schema — the single contract between data sources and the scoring engine.

Every channel below maps 1:1 to a Pleos Connect / AAOS API (see PLEOS_SOURCE).
The simulator, the CARLA/SUMO adapters, the emulator replay and (later) the real
Kotlin SignalCollector must all emit exactly this schema, so that the same engine
code and the same thresholds run everywhere.

A Trip is a dict of equal-length numpy arrays on a common time base (default 10 Hz).
Missing / unavailable values are NaN (float channels) or -1 (int channels).
NaN is meaningful: the engine ABSTAINS when a required channel is NaN.
"""
from __future__ import annotations

import numpy as np

BASE_HZ = 10.0  # engine resamples everything to this rate

# Bump whenever a channel's type, unit, meaning or the sensor model changes. Models and training data
# carry this number; the gate refuses a model trained on a different version (see model_training/gate.py).
#   v1 (until 2026-11-06): brake as 0-100 %, steering left-positive at 0.1 deg, wiper = "actually wiping"
#   v2: brake is a pressed/not-pressed boolean (Brake.registerBrakePedalStatus), steering sign unknown and
#       calibrated at runtime, 1 deg steps, ON_CHANGE channels held, navigation distances updated ~1 Hz,
#       wiper = switch position, telltale meaning unconfirmed. Full audit: data_spec/DATA_AUDIT.md
#   v3 (2026-11-06, official API reference read page by page): steering = FRONT-WHEEL angle, left negative;
#       telltales not readable by third-party apps (PRIVATE_CAR_SAFETY); turn signal has no hazard state.
DATA_VERSION = 3

# name: (dtype, unit, description, Pleos / AAOS source, status)
CHANNELS: dict[str, tuple[str, str, str, str, str]] = {
    "t":              ("f8", "s",     "time since trip start", "System clock", "official"),
    # --- motion ---------------------------------------------------------------
    "speed_kph":      ("f4", "km/h",  "vehicle speed (cluster value)", "AAOS CarPropertyManager PERF_VEHICLE_SPEED (CONTINUOUS)", "official (AAOS CDD: every AAOS car MUST report it). Float m/s, signed (reverse < 0), CONTINUOUS, dangerous permission CAR_SPEED -> collector stores |v| x 3.6"),
    "gear":           ("i1", "enum",  "0=P 1=R 2=N 3=D, -1 unknown", "AAOS GEAR_SELECTION / CURRENT_GEAR", "official (AAOS CDD MUST). VehicleGear enum (P=4, R=2, N=1, D=8) -> mapped to 0..3"),
    "brake_on":       ("i1", "bool",  "brake pedal pressed (pedal switch), -1 unknown. NOT a position", "Vehicle SDK Brake.registerBrakePedalStatus / getCurrentBrakePedalStatus (Boolean)", "official API; boolean confirmed on emulator by several teams"),
    "steer_deg":      ("f4", "deg",   "FRONT-WHEEL steering angle as delivered, leftward NEGATIVE (not the steering-wheel rotation). Engine auto-detects a steering-wheel unit (emulator slider) and checks the sign", "Vehicle SDK SteeringWheel.getCurrentSteeringWheelAngle / registerSteeringWheelAngle (Float?)", "OFFICIAL API reference; resolution + rate to-confirm"),
    "steer_bound_deg":("f4", "deg",   "max |angle| the steering signal can report (unit hint: ~40 = front wheel, 540 = steering wheel / emulator slider), NaN unknown", "Vehicle SDK SteeringWheel.checkSteeringWheelAngleCapabilities -> AreaBounds<Float>", "OFFICIAL API reference"),
    "heading_deg":    ("f4", "deg",   "heading 0-360, compass bearing assumed (clockwise; docs only say 'direction of travel')", "NaviHelper onCurrentLocationInfo(CurrentLocationInfo.heading: Double) or FusedLocation LocationData.heading: Float (isSupported() per car, dangerous permission)", "OFFICIAL API reference; convention to-confirm"),
    "odo_km":         ("f4", "km",    "odometer", "Vehicle SDK Odometer.getCurrentDistanceDriven (Float?, km)", "OFFICIAL API reference; resolution to-confirm (Virtual Console 1 km steps)"),
    "cruise_active":  ("i1", "bool",  "SCC controls the speed: ACTIVATED/USER_OVERRIDE = 1, ENABLED/SUSPENDED = 0, OTHER/FORCED_DEACTIVATION_WARNING/unsupported = -1", "Vehicle SDK DrivingMode.getCruiseControlState (CruiseControl enum)", "OFFICIAL API reference; mapping is ours"),
    "drive_mode":     ("i1", "enum",  "0 eco 1 normal 2 sport", "Vehicle SDK DrivingMode.getCurrentDrivingModeState (DrivingModeType NORMAL/ECO/SPORT)", "OFFICIAL API reference"),
    # --- driver inputs / body ---------------------------------------------------
    "turn_signal":    ("i1", "enum",  "0 off 1 left 2 right (3 hazard: simulator only, never delivered by the API)", "Vehicle SDK TurnSignal.getCurrentTurnSignalState (TurnSignalValue OFF/RIGHT/LEFT)", "OFFICIAL API reference"),
    "wiper_active":   ("i1", "bool",  "front wipers actually operating: WiperState ON = 1, OFF = 0, OTHER / SERVICE / unsupported = -1 (also works with the switch on AUTO)", "Vehicle SDK Wiper.getCurrentWiperState(front WindowArea) -> WiperState (mirrors AAOS WINDSHIELD_WIPERS_STATE)", "OFFICIAL API reference; mapping is ours"),
    "low_beam":       ("i1", "bool",  "low beam on (LightState ON = 1, OFF / DAYTIME_RUNNING = 0)", "Vehicle SDK Light.getCurrentLowBeamLightState", "OFFICIAL API reference"),
    "night":          ("i1", "bool",  "night mode", "AAOS NIGHT_MODE", "official (AAOS CDD MUST)"),
    "outside_temp_c": ("f4", "degC",  "outside air temperature", "AAOS ENV_OUTSIDE_TEMPERATURE", "to-confirm"),
    "seats_occupied": ("i1", "count", "occupied seats", "Vehicle SDK Seat.getCurrentSeatOccupancy(SeatArea) -> Boolean? per seat", "OFFICIAL API reference"),
    "belts_buckled":  ("i1", "count", "buckled belts among occupied seats", "Vehicle SDK Seat.getCurrentSeatBeltBuckledState(SeatArea) -> Boolean? per seat", "OFFICIAL API reference"),
    # --- telltales (warnings raised by the car itself) ------------------------
    "tt_fca":         ("i1", "bool",  "forward collision-avoidance warning", "Safety telltale fca", "NOT AVAILABLE to third-party apps: Safety.getTelltalesInfo needs PRIVATE_CAR_SAFETY (signature|privileged). Always -1"),
    "tt_lka":         ("i1", "bool",  "lane keeping / departure warning", "Safety telltale lka/lfa", "NOT AVAILABLE to third-party apps: Safety.getTelltalesInfo needs PRIVATE_CAR_SAFETY (signature|privileged). Always -1"),
    "tt_lca":         ("i1", "bool",  "blind-spot / lane change warning L or R", "Safety.registerTelltales LCA_LEFT/RIGHT", "NOT AVAILABLE to third-party apps: Safety.getTelltalesInfo needs PRIVATE_CAR_SAFETY (signature|privileged). Always -1"),
    "tt_daw":         ("i1", "bool",  "driver attention warning", "Safety.registerTelltales DRIVER_ATTENTION", "NOT AVAILABLE to third-party apps: Safety.getTelltalesInfo needs PRIVATE_CAR_SAFETY (signature|privileged). Always -1"),
    "tt_icy":         ("i1", "bool",  "icy road warning", "Safety.registerTelltales ICY_ROAD", "NOT AVAILABLE to third-party apps: Safety.getTelltalesInfo needs PRIVATE_CAR_SAFETY (signature|privileged). Always -1"),
    "tt_abs_esc":     ("i1", "bool",  "ABS/ESC intervention lamp", "Safety.registerTelltales ABS / ESC", "NOT AVAILABLE to third-party apps: Safety.getTelltalesInfo needs PRIVATE_CAR_SAFETY (signature|privileged). Always -1"),
    "tt_tpms":        ("i1", "bool",  "low tyre pressure (any wheel)", "Vehicle SDK Tire.getLowTirePressure (CAR_SAFETY, normal)", "OFFICIAL API reference"),
    # --- navigation context ---------------------------------------------------
    "speed_limit_kph":("f4", "km/h",  "current road speed limit", "NaviHelper onCurrentRoadSpeedLimit(speedLimit: Int), every second", "OFFICIAL API reference"),
    "road_class":     ("i1", "enum",  "0 urban 1 rural 2 expressway", "derived from NaviHelper (limit / route info)", "derived"),
    "tbt_dist_m":     ("f4", "m",     "distance to next turn manoeuvre", "NaviHelper onTBTInfo", "official"),
    "tbt_type":       ("i1", "enum",  "0 none 1 left (TURN_LEFT, TURN_SHARP_L) 2 right (TURN_RIGHT, TURN_SHARP_R) 3 u-turn (TURN_UTURN) 4 straight/keep/IC/under-overpass (no turn)", "NaviHelper onTBTInfo(List<TBTInfo(type: TurnType, distance: Double m)>)", "OFFICIAL API reference"),
    "caution_type":   ("i1", "enum",  "0 none 1 SCHOOL_ZONE 2 SPEED_BUMP 3 RAILROAD_CROSSING 4 (sharp curve: simulator only, NOT in the API) 5 ACCIDENT_PRONE_AREA 6 FOG_AREA 7 (icy: simulator only, NOT in the API) 8 WILDLIFE_CROSSING 9 DOWNHILL_CAUTION_AREA 10 FALLING_ROCK_AREA 11 NARROW_ROAD 12 SLIPPERY_ROAD_ALERT", "NaviHelper onCautionPointInfo(List<CautionPoint(type, remainDistance: Int m)>)", "OFFICIAL API reference (24 CautionPointType values)"),
    "caution_dist_m": ("f4", "m",     "distance to caution point (negative = inside / passed)", "NaviHelper CautionPoint.remainDistance (Int, m)", "OFFICIAL API reference"),
    "school_zone":    ("i1", "bool",  "inside child protection zone", "NaviHelper CautionPoint SCHOOL_ZONE + CameraAlert school", "official"),
    "camera_overspeed":("i1","bool",  "navigation reports overspeed at camera", "NaviHelper onCameraAlert(List<CameraAlert(type, speedLimit km/h, remainDistance m, isOverSpeed)>)", "OFFICIAL API reference"),
    "congestion":     ("i1", "enum",  "0 free (FAST/MODERATE) 1 slow (SLOW) 2 jam (HEAVY), UNKNOWN -> 0", "NaviHelper RouteStateInfo.trafficStatus / TrafficCongestionInfo", "OFFICIAL API reference"),
    "nav_active":     ("i1", "bool",  "route guidance active (NavigationStatus ROUTE_DRIVE)", "NaviHelper onNavigationStatus(NavigationStatus)", "OFFICIAL API reference; whether a car HAS navigation is not queryable (official FAQ)"),
}

# How each channel arrives. ON_CHANGE channels fire only when the value changes: the collector must HOLD
# the last value (no staleness timeout), otherwise a straight road looks like "steering missing".
UPDATE_MODE = {
    "speed_kph": "CONTINUOUS (rate set by subscriber; ~10 Hz seen on emulator)",
    "steer_deg": "ON_CHANGE", "brake_on": "ON_CHANGE", "turn_signal": "ON_CHANGE", "gear": "ON_CHANGE",
    "wiper_active": "ON_CHANGE", "low_beam": "ON_CHANGE", "cruise_active": "ON_CHANGE", "night": "ON_CHANGE",
    "tbt_dist_m": "EVENT (~1 Hz) -> engine dead-reckons between updates", "caution_dist_m": "EVENT (~1 Hz)",
    "speed_limit_kph": "EVENT on change", "heading_deg": "EVENT (~1 Hz, NaviHelper CurrentLocationInfo)",
    "odo_km": "POLL (resolution 0.1-1 km unknown -> not used for distance)",
}

INT_CHANNELS = [k for k, v in CHANNELS.items() if v[0].startswith("i")]
FLOAT_CHANNELS = [k for k, v in CHANNELS.items() if v[0].startswith("f")]

CAUTION = {"none": 0, "school": 1, "speed_bump": 2, "railroad": 3, "sharp_curve": 4,
           "accident_prone": 5, "fog": 6, "icy": 7, "wildlife": 8, "downhill": 9, "falling_rock": 10,
           "narrow_road": 11, "slippery": 12}           # 4 and 7 exist only in the simulator (not in CautionPointType)
TBT = {"none": 0, "left": 1, "right": 2, "uturn": 3, "straight": 4}
SIGNAL = {"off": 0, "left": 1, "right": 2, "hazard": 3}
GEAR = {"P": 0, "R": 1, "N": 2, "D": 3}

G = 9.80665  # m/s^2


def empty_trip(n: int, hz: float = BASE_HZ) -> dict[str, np.ndarray]:
    trip: dict[str, np.ndarray] = {}
    for name, (dt, *_rest) in CHANNELS.items():
        if dt.startswith("f"):
            trip[name] = np.full(n, np.nan, dtype=np.float32 if dt == "f4" else np.float64)
        else:
            trip[name] = np.full(n, -1, dtype=np.int8)
    trip["t"] = np.arange(n, dtype=np.float64) / hz
    return trip


def validate(trip: dict[str, np.ndarray]) -> None:
    n = len(trip["t"])
    for name in CHANNELS:
        if name not in trip:
            raise KeyError(f"missing channel {name}")
        if len(trip[name]) != n:
            raise ValueError(f"channel {name} has length {len(trip[name])} != {n}")


def schema_markdown() -> str:
    lines = ["| channel | unit | meaning | source API | status |", "|---|---|---|---|---|"]
    for k, (_dt, unit, desc, src, status) in CHANNELS.items():
        lines.append(f"| `{k}` | {unit} | {desc} | {src} | {status} |")
    return "\n".join(lines)


if __name__ == "__main__":
    print(schema_markdown())
