"""
Derived signals.

Pleos does not expose an accelerometer or yaw-rate sensor to third-party apps
(gyro is listed as restricted), so kinematics are reconstructed:

  a_long  = d(speed)/dt as a 1-second centred difference          (works at 1 Hz and up)
  jerk    = d(a_long)/dt, Savitzky-Golay                           (only if speed >= 5 Hz)
  yaw     = v * tan(delta) / (L * (1 + K v^2))  delta = front-wheel angle (official API)   (bicycle model)
  yaw_nav = -d(heading)/dt  (heading is a compass bearing)         (NaviHelper / FusedLocation, cross-check)
  steering sign + ratio are calibrated against heading or navigation turns (unknown per car model)
  a_lat   = v * yaw

The 1-second difference matches the industry definition of a harsh event
("speed change larger than X in one second", Tesla / Progressive), so the thresholds
in thresholds.yaml mean the same thing whatever the sampling rate.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import yaml
from scipy.signal import savgol_filter

from data_spec.signals import BASE_HZ, G

CONFIG_PATH = Path(__file__).parent / "config" / "thresholds.yaml"


def load_config(path: Path | None = None) -> dict:
    with open(path or CONFIG_PATH, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


@dataclass
class VehicleParams:
    wheelbase_m: float = 2.895        # Hyundai Grandeur (GN7) published wheelbase
    steering_ratio: float = 13.5      # only used if a car / the emulator sends the steering-WHEEL angle
    understeer_k: float = 0.0025      # s^2/m^2, assumption for a mid-size sedan


def _native_rate(x: np.ndarray, hz: float) -> float:
    valid = np.isfinite(x)
    if valid.sum() < 2:
        return 0.0
    idx = np.flatnonzero(valid)
    # count distinct-sample positions (a held value repeated every frame still counts as a sample)
    span = (idx[-1] - idx[0]) / hz
    return (len(idx) - 1) / span if span > 0 else 0.0


def fill_gaps(x: np.ndarray, hz: float, max_age_s: float) -> np.ndarray:
    """Linear interpolation across gaps no longer than max_age_s; longer gaps stay NaN."""
    x = x.astype(np.float64).copy()
    valid = np.isfinite(x)
    if valid.sum() < 2:
        return np.full_like(x, np.nan)
    idx = np.arange(len(x))
    vi = idx[valid]
    out = np.interp(idx, vi, x[valid])
    # mask long gaps
    nxt = np.searchsorted(vi, idx, side="left")
    prv = np.clip(nxt - 1, 0, len(vi) - 1)
    nxt = np.clip(nxt, 0, len(vi) - 1)
    gap = (vi[nxt] - vi[prv]) / hz
    long_gap = (gap > max_age_s) & ~valid
    out[long_gap] = np.nan
    out[idx < vi[0]] = np.nan
    out[idx > vi[-1]] = np.nan
    return out


def dead_reckon(d: np.ndarray, s_m: np.ndarray) -> np.ndarray:
    """Distance-to-point that is only refreshed now and then: between refreshes subtract the distance driven."""
    out = d.copy()
    fin = np.isfinite(d)
    if fin.sum() < 2:
        return out
    upd = fin.copy()
    upd[1:] &= ~(fin[:-1] & (d[1:] == d[:-1]))          # a new value (or first value after a gap) = an update
    idx = np.arange(len(d))
    last = np.maximum.accumulate(np.where(upd, idx, -1))
    ok = fin & (last >= 0)
    out[ok] = d[last[ok]] - (s_m[ok] - s_m[last[ok]])
    return out


def calibrate_steer_sign(trip, D, yaw_raw, v, hz):
    """Which way is positive on this car's steering-angle signal? Returns (+1 left-positive | -1 | 0 unknown, source).
    1) heading: turning samples where navigation yaw and steering yaw are both clear
    2) navigation turns: steering during TBT left/right turns"""
    yn = D["yaw_nav_dps"]
    m = np.isfinite(yn) & np.isfinite(yaw_raw) & (v > 3) & (np.abs(yn) > 4) & (np.abs(yaw_raw) > 2)
    if m.sum() >= 40:
        agree = np.mean(np.sign(yn[m]) == np.sign(yaw_raw[m]))
        if agree >= 0.85:
            return 1, "heading"
        if agree <= 0.15:
            return -1, "heading"
    st = trip["steer_deg"].astype(np.float64)
    td, tt = trip["tbt_dist_m"].astype(np.float64), trip["tbt_type"]
    votes = []
    for i in np.flatnonzero((td[:-1] > 0) & (td[1:] <= 0)) + 1:
        if tt[i - 1] not in (1, 2) or not np.isfinite(v[i]) or v[i] > 11:
            continue
        w = st[max(0, i - int(2 * hz)):i + int(4 * hz)]
        w = w[np.isfinite(w)]
        if len(w) < 10 or np.max(np.abs(w)) < 60:
            continue
        s_turn = np.sign(w[np.argmax(np.abs(w))])
        votes.append(s_turn if tt[i - 1] == 1 else -s_turn)
    if len(votes) >= 2 and abs(sum(votes)) == len(votes):
        return int(votes[0]), "navigation_turns"
    return 0, "unknown"


def calibrate_ratio(yaw_nav, yaw_steer, v, hz=BASE_HZ, clip=True):
    """The car's true steering ratio is unknown (the engine assumes 13.5). Compare the TOTAL heading change over
    each turn (navigation) with the integrated steering yaw over the same window: integration is immune to the
    ~1 Hz heading rate and its smoothing, which bias a sample-by-sample ratio low (= false praise risk)."""
    w = int(8 * hz)
    ok = np.isfinite(yaw_nav) & np.isfinite(yaw_steer) & (v > 3)
    if ok.sum() < 3 * w:
        return 1.0, "none"
    yn = np.where(ok, yaw_nav, 0.0)
    ys = np.where(ok, yaw_steer, 0.0)
    cn, cs, co = (np.concatenate([[0], np.cumsum(x)]) / hz for x in (yn, ys, ok.astype(float)))
    num = den = 0.0
    i, turns = 0, 0
    while i + w <= len(yn):
        if co[i + w] - co[i] >= w / hz - 0.05:           # window fully valid
            dn, ds = cn[i + w] - cn[i], cs[i + w] - cs[i]
            if abs(dn) > 35 and abs(ds) > 20 and np.sign(dn) == np.sign(ds):
                num += abs(dn); den += abs(ds); turns += 1
                i += w
                continue
        i += int(hz)
    if turns < 3:
        return 1.0, "none"
    return (float(np.clip(num / den, 0.75, 1.3)) if clip else float(num / den)), "heading"


def centred_diff(x: np.ndarray, hz: float, span_s: float = 1.0) -> np.ndarray:
    h = max(1, int(round(span_s * hz / 2)))
    d = np.full_like(x, np.nan, dtype=np.float64)
    d[h:-h] = (x[2 * h:] - x[:-2 * h]) / (2 * h / hz)
    return d


def derive(trip: dict, cfg: dict | None = None, vp: VehicleParams | None = None) -> dict:
    cfg = cfg or load_config()
    vp = vp or VehicleParams()
    hz = BASE_HZ
    max_age = cfg["general"]["max_signal_age_s"]
    out: dict[str, np.ndarray | float] = {}

    raw_v = trip["speed_kph"].astype(np.float64)
    speed_hz = _native_rate(raw_v, hz)
    # for 1 Hz sources allow interpolation across one missing sample
    v_kph = fill_gaps(raw_v, hz, max(max_age, 2.2 / max(speed_hz, 1e-3)) if speed_hz < 2 else max_age)
    v = v_kph / 3.6
    out["speed_native_hz"] = speed_hz
    k1 = int(hz)
    vs = np.convolve(np.nan_to_num(v_kph, nan=-1.0), np.ones(k1) / k1, mode="same")
    vs[~np.isfinite(v_kph)] = np.nan
    out["v_smooth_kph"] = vs
    out["v_kph"] = v_kph
    out["v_ms"] = v

    a = centred_diff(v, hz, 1.0)
    out["a_long_g"] = a / G
    if speed_hz >= cfg["general"]["min_sample_hz_for_jerk"]:
        a_f = np.where(np.isfinite(a), a, 0.0)
        win = int(hz * 1.5) | 1
        jerk = savgol_filter(a_f, win, 2, deriv=1, delta=1 / hz)
        jerk[~np.isfinite(a)] = np.nan
        out["jerk"] = jerk
    else:
        out["jerk"] = np.full_like(a, np.nan)

    # heading: compass bearing (clockwise) -> engine convention yaw > 0 = LEFT (ISO 8855, counter-clockwise)
    hd = trip["heading_deg"].astype(np.float64)
    if np.isfinite(hd).sum() > 10:
        hdu = hd.copy()
        m = np.isfinite(hdu)
        hdu[m] = np.rad2deg(np.unwrap(np.deg2rad(hdu[m])))
        hdu = fill_gaps(hdu, hz, max(max_age, 2.2))
        out["heading_unwrapped"] = hdu
        out["yaw_nav_dps"] = -centred_diff(hdu, hz, 2.0)
    else:
        out["heading_unwrapped"] = np.full_like(v, np.nan)
        out["yaw_nav_dps"] = np.full_like(v, np.nan)

    # Steering (Vehicle SDK SteeringWheel.getCurrentSteeringWheelAngle / registerSteeringWheelAngle, ON_CHANGE):
    # OFFICIAL API reference: "front-wheel steering angle in degrees. Leftward steering angles are negative.
    # This value does not represent the rotation angle of the steering wheel." -> road-wheel angle, no ratio.
    # The emulator's Virtual Console uses a +-540 deg slider, so the unit is also auto-detected from the data.
    # The collector holds the last value (no staleness limit here).
    steer_raw = fill_gaps(trip["steer_deg"].astype(np.float64), hz, max_age)
    veh = cfg.get("vehicle", {})
    unit, unit_src = veh.get("steer_unit", "auto"), "config"
    bound = trip.get("steer_bound_deg")
    bound = float(np.nanmax(bound)) if bound is not None and np.isfinite(bound).any() else np.nan
    if unit == "auto" and np.isfinite(bound):                          # OFFICIAL capability bounds decide first
        unit, unit_src = ("steering_wheel" if bound > 90 else "road_wheel"), "capability"
    if unit == "auto":
        big = np.nanpercentile(np.abs(steer_raw), 99.5) if np.isfinite(steer_raw).any() else 0.0
        if big > 45:                                                  # road wheels never turn > ~40 deg
            unit, unit_src = "steering_wheel", "range"
        else:                                                         # ambiguous: which unit matches the heading?
            unit, unit_src = "road_wheel", "official_doc"
            if np.isfinite(out["yaw_nav_dps"]).sum() > 100:
                fits = {}
                for u, r in (("road_wheel", 1.0), ("steering_wheel", vp.steering_ratio)):
                    y = np.rad2deg(v * np.tan(np.deg2rad(np.abs(steer_raw)) / r) / (vp.wheelbase_m * (1 + vp.understeer_k * v ** 2)))
                    sc, src = calibrate_ratio(np.abs(out["yaw_nav_dps"]), y, v, hz, clip=False)
                    if src == "heading":
                        fits[u] = abs(np.log(sc))
                if fits:
                    unit, unit_src = min(fits, key=fits.get), "heading"
            if unit_src == "official_doc" and veh.get("steer_unit_learned"):
                unit, unit_src = veh["steer_unit_learned"], "memory"
    out["steer_unit"], out["steer_unit_source"] = unit, unit_src
    ratio = vp.steering_ratio if unit == "steering_wheel" else 1.0
    # resolution: a road-wheel angle in 1-degree steps is too coarse for lateral g (1 deg ~ 0.1 g at 50 km/h)
    vals = np.unique(np.round(np.abs(steer_raw[np.isfinite(steer_raw)]), 3))
    step = float(np.min(np.diff(vals))) if len(vals) > 2 else np.nan
    out["steer_step_deg"] = step
    out["steer_too_coarse"] = bool(unit == "road_wheel" and np.isfinite(step) and step >= 0.45)
    yaw_raw = np.rad2deg(v * np.tan(np.deg2rad(steer_raw) / ratio) / (vp.wheelbase_m * (1 + vp.understeer_k * v ** 2)))
    sign, sign_src = calibrate_steer_sign(trip, out, yaw_raw, v, hz)
    if sign == 0 and veh.get("steer_sign", 0):
        sign, sign_src = int(veh["steer_sign"]), ("memory" if veh.get("steer_sign_learned") else "official_doc")
    scale, scale_src = calibrate_ratio(out["yaw_nav_dps"], yaw_raw * (sign or 1), v, hz)
    if scale_src == "none" and veh.get("ratio_scale"):
        scale, scale_src = float(veh["ratio_scale"]), "memory"
    out["steer_sign"], out["steer_sign_source"] = sign, sign_src
    out["ratio_scale"], out["ratio_scale_source"] = scale, scale_src
    steer = steer_raw * (sign or 1)
    out["steer_n_deg"] = steer                                   # left-positive when the sign is known
    out["delta_deg"] = steer / ratio                             # FRONT-WHEEL angle, left-positive, whatever the unit
    delta = np.deg2rad(steer) / ratio                            # road-wheel angle in radians
    yaw_steer = v * np.tan(delta) / (vp.wheelbase_m * (1 + vp.understeer_k * v ** 2)) * scale
    out["yaw_steer_dps"] = np.rad2deg(yaw_steer)
    # yaw produced by ONE step of the steering signal at the current speed (0.1 deg front-wheel ~ 1 deg/s at 100 km/h)
    step_rw = (step / ratio) if np.isfinite(step) else 0.0
    out["yaw_quantum_dps"] = np.rad2deg(np.nan_to_num(v) * np.deg2rad(step_rw) / vp.wheelbase_m) * scale

    # navigation distances arrive ~1 Hz and are held: dead-reckon between updates with distance travelled
    s_m = np.cumsum(np.nan_to_num(v) / hz)
    v_db = np.where(np.nan_to_num(v_kph) > 1.5, np.nan_to_num(v), 0.0)      # standstill deadband: speed noise
    s_db = np.cumsum(v_db / hz)                                             # must not "drive" through a red light
    for ch in ("tbt_dist_m", "caution_dist_m"):
        out[ch] = dead_reckon(trip[ch].astype(np.float64), s_db)

    # lateral acceleration: steering model is primary (10 Hz); nav heading is the cross-check
    a_lat = v * yaw_steer
    k = int(hz)  # 1 s moving average, same averaging as a_long
    a_lat_s = np.convolve(np.nan_to_num(a_lat), np.ones(k) / k, mode="same")
    a_lat_s[~np.isfinite(a_lat)] = np.nan
    out["a_lat_g"] = a_lat_s / G
    a_lat_nav = v * np.deg2rad(out["yaw_nav_dps"])
    out["a_lat_nav_g"] = a_lat_nav / G
    disagree = np.abs(out["a_lat_g"] - out["a_lat_nav_g"])
    out["lat_quality_ok"] = ~(np.isfinite(disagree) & (disagree > 0.12))

    # distance travelled (for per-km normalisation)
    out["s_m"] = s_m
    out["dt"] = 1.0 / hz
    return out
