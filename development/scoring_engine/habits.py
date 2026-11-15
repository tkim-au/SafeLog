"""
Good-habit detectors and judges.

Design (see docs/SCORING_SPEC.md):
  detector  -> Candidate  ("an opportunity to show this habit happened here")
  judge     -> Judgment   (SUCCESS / MISS / ABSTAIN + margin + reason)

MISS is internal only. Users only ever see SUCCESS ("11 of 12 smooth stops").
ABSTAIN removes the opportunity from the denominator, so external events
(cut-ins, FCA, missing signals, cruise control) never make the ratio unfair.

Detectors and judges are separate so the simulator can feed ground-truth
candidates into the very same judges (that is how truth labels are produced).
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from data_spec.signals import BASE_HZ

SUCCESS, MISS, ABSTAIN = 1, 0, -1
HZ = BASE_HZ


@dataclass
class Candidate:
    habit: str
    i_ref: int                 # reference index used for matching / map pin
    i0: int
    i1: int
    meta: dict = field(default_factory=dict)


@dataclass
class Judgment:
    habit: str
    i_ref: int
    t_ref: float
    outcome: int
    level: str = ""            # "excellent" / "success" / ""
    value: float = float("nan")
    threshold: float = float("nan")
    margin: float = 0.0        # 0..1, how far inside the boundary (used to pick the journal's best scenes)
    reason: str = ""
    i0: int = 0
    i1: int = 0
    context: dict = field(default_factory=dict)
    meta: dict = field(default_factory=dict)


# ----------------------------------------------------------------------------- helpers
def runs(mask: np.ndarray, min_len: int = 1) -> list[tuple[int, int]]:
    m = np.asarray(mask, dtype=bool)
    if m.size == 0:
        return []
    d = np.diff(np.concatenate([[0], m.astype(np.int8), [0]]))
    starts, ends = np.flatnonzero(d == 1), np.flatnonzero(d == -1)
    return [(s, e) for s, e in zip(starts, ends) if e - s >= min_len]


def _ctx(trip, i):
    i = int(np.clip(i, 0, len(trip["t"]) - 1))
    return {
        "rain": int(trip["wiper_active"][i] == 1),
        "night": int(trip["night"][i] == 1 or trip["low_beam"][i] == 1 and trip["night"][i] == -1),
        "congestion": int(trip["congestion"][i]),
        "road_class": int(trip["road_class"][i]),
    }


def _abstain_checks(trip, D, cfg, i0, i1, need=("v",), forced_lookback=True, adas=True):
    """Return a reason string if the opportunity must be abstained, else ''."""
    n = len(trip["t"])
    i0, i1 = max(0, i0), min(n, i1)
    if "v" in need and not np.all(np.isfinite(D["v_kph"][i0:i1])):
        return "speed_missing"
    if "a" in need and not np.all(np.isfinite(D["a_long_g"][i0:i1])):
        return "accel_missing"
    if "steer" in need and not np.all(np.isfinite(trip["steer_deg"][i0:i1])):
        return "steering_missing"
    if "steer" in need and D.get("steer_too_coarse") and not cfg.get("_truth"):
        return "steering_resolution_too_coarse"
    if "steer" in need and D.get("steer_unit_source") == "official_doc" and not cfg.get("_truth"):
        return "steering_unit_unverified"    # no capability bounds / range / heading / memory yet: never guess
    if "brake" in need and not np.all(trip["brake_on"][i0:i1] >= 0):
        return "brake_missing"
    if "lat" in need and not np.all(D["lat_quality_ok"][i0:i1]):
        return "lateral_estimates_disagree"
    if adas and cfg["general"]["adas_exclusion"] and np.any(trip["cruise_active"][i0:i1] == 1):
        return "adas_engaged"
    if adas and cfg["general"]["adas_exclusion"] and "adas_unknown_ok" not in need and np.any(trip["cruise_active"][i0:i1] < 0):
        return "adas_state_unknown"     # SCC could be driving: the smoothness might be the car's
    if forced_lookback:
        lb = int(cfg["general"]["external_forcing_lookback_s"] * HZ)
        if np.any(trip["tt_fca"][max(0, i0 - lb):i1] == 1):
            return "external_forcing_fca"
    return ""


def _mk(habit, trip, D, cand, outcome, value=np.nan, thr=np.nan, margin=0.0, reason="", level=""):
    return Judgment(habit=habit, i_ref=cand.i_ref, t_ref=float(trip["t"][min(cand.i_ref, len(trip["t"]) - 1)]),
                    outcome=outcome, level=level, value=float(value), threshold=float(thr),
                    margin=float(np.clip(margin, 0, 1)), reason=reason, i0=cand.i0, i1=cand.i1,
                    context=_ctx(trip, cand.i_ref), meta=dict(cand.meta))


def kin_guard(D, cfg):
    if cfg.get("_truth"):
        return 0.0
    g = cfg["general"]
    return g["guard_kin_g_lowrate"] if D["speed_native_hz"] < 2 else g["guard_kin_g"]


def speed_guard(cfg, lim):
    """Speed praise needs a margin below the limit: 1 km/h display rounding + 1 % speed-reading uncertainty."""
    g = cfg["general"]
    return g.get("guard_speed_kph", 1.0) + g.get("guard_speed_frac", 0.01) * np.asarray(lim, dtype=float)


def lat_guard(D, cfg, thr):
    if cfg.get("_truth"):
        return 0.0
    unc = 0.0 if D.get("ratio_scale_source") in ("heading", "memory") else cfg["general"].get("steering_ratio_uncertainty", 0.12)
    return kin_guard(D, cfg) + unc * thr


def _kin_judge(habit, trip, D, cand, value, ok_thr, ex_thr, guard=0.0):
    if not np.isfinite(value):
        return _mk(habit, trip, D, cand, ABSTAIN, reason="kinematics_missing")
    if ok_thr - guard <= value < ok_thr:
        return _mk(habit, trip, D, cand, ABSTAIN, value, ok_thr, reason="inside_guard_band")
    if value < ok_thr:
        lvl = "excellent" if value < ex_thr else "success"
        return _mk(habit, trip, D, cand, SUCCESS, value, ok_thr, (ok_thr - value) / ok_thr, level=lvl)
    return _mk(habit, trip, D, cand, MISS, value, ok_thr, 0.0, reason="above_threshold")


# ============================================================================ DETECTORS
def detect_stops(trip, D, cfg):
    c = cfg["smooth_stop"]
    v = D["v_kph"]
    vv = np.nan_to_num(v, nan=-1)
    sm = np.nan_to_num(D["v_smooth_kph"], nan=-1)
    out = []
    below = (vv >= 0) & (vv < c["stop_speed_kph"])
    for s, _e in runs(below):
        if s == 0 or vv[s - 1] < 0:
            continue
        # walk back through the deceleration phase on 1-s smoothed speed
        i = s - 1
        lim = max(0, s - int(60 * HZ))
        while i > lim and sm[i - 1] >= sm[i] - 0.5 and sm[i - 1] >= 0:
            i -= 1
        i0 = max(0, i - int(2 * HZ))
        need = c["min_entry_speed_kph"] + (0 if cfg.get("_truth") else 2.0)   # 2 km/h guard (cluster bias)
        if max(vv[i], sm[i]) >= need:
            out.append(Candidate("smooth_stop", s, i0, s + 1, {"entry_kph": float(max(vv[i], sm[i]))}))
    return out


def judge_stop(trip, D, cfg, cand):
    c = cfg["smooth_stop"]
    unknown_scc = np.any(trip["cruise_active"][cand.i0:cand.i1] < 0)
    r = _abstain_checks(trip, D, cfg, cand.i0, cand.i1, need=("v", "a", "adas_unknown_ok") if unknown_scc else ("v", "a"))
    if r:
        return _mk("smooth_stop", trip, D, cand, ABSTAIN, reason=r)
    if unknown_scc and not np.any(trip["brake_on"][max(cand.i0, cand.i1 - int(4 * HZ)):cand.i1] == 1):
        return _mk("smooth_stop", trip, D, cand, ABSTAIN, reason="adas_state_unknown_no_pedal")
    if cand.meta.get("forced"):
        return _mk("smooth_stop", trip, D, cand, ABSTAIN, reason="external_forcing")
    peak = float(-np.nanmin(D["a_long_g"][cand.i0:cand.i1]))
    return _kin_judge("smooth_stop", trip, D, cand, peak, c["success_max_decel_g"], c["excellent_max_decel_g"], kin_guard(D, cfg))


def detect_launches(trip, D, cfg):
    c = cfg["smooth_launch"]
    vv = np.nan_to_num(D["v_kph"], nan=-1)
    out = []
    w = int(c["window_s"] * HZ)
    for s, e in runs((vv >= 0) & (vv < c["start_max_speed_kph"])):
        i0 = e - 1
        seg = vv[i0:i0 + w]
        hit = np.flatnonzero(seg >= c["end_speed_kph"] + (0 if cfg.get("_truth") else 2.0))
        if len(hit) and np.all(seg[:hit[0]] >= 0):
            out.append(Candidate("smooth_launch", i0, i0, i0 + w))
    return out


def judge_launch(trip, D, cfg, cand):
    c = cfg["smooth_launch"]
    r = _abstain_checks(trip, D, cfg, cand.i0, cand.i1, need=("v", "a"))
    if r:
        return _mk("smooth_launch", trip, D, cand, ABSTAIN, reason=r)
    if cand.meta.get("forced"):
        return _mk("smooth_launch", trip, D, cand, ABSTAIN, reason="external_forcing")
    peak = float(np.nanmax(D["a_long_g"][cand.i0:cand.i1]))
    return _kin_judge("smooth_launch", trip, D, cand, peak, c["success_max_accel_g"], c["excellent_max_accel_g"], kin_guard(D, cfg))


def detect_anticipation(trip, D, cfg):
    c = cfg["anticipatory_braking"]
    return [Candidate("anticipatory_braking", x.i_ref, x.i0, x.i1, dict(x.meta))
            for x in detect_stops(trip, D, cfg) if x.meta["entry_kph"] >= c["min_entry_speed_kph"]]


def judge_anticipation(trip, D, cfg, cand):
    c = cfg["anticipatory_braking"]
    r = _abstain_checks(trip, D, cfg, cand.i0, cand.i1, need=("v", "a", "brake"))
    if r:
        return _mk("anticipatory_braking", trip, D, cand, ABSTAIN, reason=r)
    if cand.meta.get("forced"):
        return _mk("anticipatory_braking", trip, D, cand, ABSTAIN, reason="external_forcing")
    a = D["a_long_g"][cand.i0:cand.i1]
    b = trip["brake_on"][cand.i0:cand.i1]
    pressed = np.flatnonzero(b == 1)
    if len(pressed) == 0:
        return _mk("anticipatory_braking", trip, D, cand, ABSTAIN, reason="no_brake_application")
    fb = pressed[0]
    coast = 0
    j = fb - 1
    while j >= 0 and b[j] == 0 and a[j] < -0.02:
        coast += 1
        j -= 1
    coast_s = coast / HZ
    peak = float(-np.nanmin(a))
    guard_s = 0.0 if cand.meta.get("truth_kin") else 0.5
    if coast_s >= c["min_coast_before_brake_s"] + guard_s and peak <= c["max_peak_decel_g"] - kin_guard(D, cfg) * (0 if cand.meta.get("truth_kin") else 1):
        return _mk("anticipatory_braking", trip, D, cand, SUCCESS, coast_s, c["min_coast_before_brake_s"],
                   min(1, coast_s / (2 * c["min_coast_before_brake_s"])), level="success")
    return _mk("anticipatory_braking", trip, D, cand, MISS, coast_s, c["min_coast_before_brake_s"], reason="late_or_hard")


def detect_turns(trip, D, cfg):
    """Turns come from navigation TBT. Without TBT, heading-detected turns are emitted
    as candidates that the judge will ABSTAIN on (no reference distance)."""
    out = []
    td, tt = D["tbt_dist_m"], trip["tbt_type"]
    n = len(td)
    ok = np.isfinite(td) & np.isin(tt, (1, 2, 3)) & (td < 2000)
    for s0, e0 in runs(ok):
        # split one run into manoeuvres where the countdown jumps up
        jumps = np.flatnonzero(np.diff(td[s0:e0]) > 20) + s0 + 1
        bounds = [s0] + list(jumps) + [e0]
        for a, b in zip(bounds[:-1], bounds[1:]):
            seg = td[a:b]
            if seg.min() > 5:
                continue
            # turn moment = when the car leaves the turn point: if it waited there (red light), the turn starts
            # when it pulls away, not when it arrived (navigation distance sits at ~0 m the whole time)
            close = np.flatnonzero(seg <= max(float(np.nanmin(seg)), 0.0) + 3.0)
            vv = np.nan_to_num(D["v_kph"][a:b])
            stopped = close[vv[close] < 3]
            if len(stopped):                                   # waited at the turn point -> turn when it pulls away
                after = np.flatnonzero(vv[stopped[-1]:] >= 3)
                k = int(stopped[-1] + (after[0] if len(after) else 0))
            else:
                hit = np.flatnonzero(seg <= 0.0)
                k = int(hit[0]) if len(hit) else int(np.argmin(seg))
            i_ref = a + min(k, len(seg) - 1)
            out.append(Candidate("turn", int(i_ref), int(a), min(n, int(i_ref) + int(8 * HZ)),
                                 {"dir": int(tt[i_ref]), "source": "tbt"}))
    # heading-based turns without TBT (judge abstains)
    yaw = np.nan_to_num(D["yaw_steer_dps"])
    win = int(10 * HZ)
    head = np.cumsum(yaw) / HZ
    tbt_idx = [c.i_ref for c in out]
    i = win
    while i < n:
        dh = head[i] - head[i - win]
        if abs(dh) > 60 and np.nanmean(np.nan_to_num(D["v_kph"][i - win:i])) < 40:
            if all(abs(i - k) > 15 * HZ for k in tbt_idx):
                pk = i - win + int(np.argmax(np.abs(yaw[i - win:i])))
                out.append(Candidate("turn", pk, i - win, i,
                                     {"dir": 1 if dh > 0 else 2, "source": "heading"}))
            i += win
        i += 5
    return out


def judge_turn_signal(trip, D, cfg, cand):
    c = cfg["turn_signal_turn"]
    if cand.meta.get("source") != "tbt" and not cand.meta.get("truth"):
        return _mk("turn_signal_turn", trip, D, cand, ABSTAIN, reason="no_navigation_reference")
    i_turn = cand.i_ref
    want = 1 if cand.meta["dir"] == 1 else 2 if cand.meta["dir"] == 2 else 1
    sig = trip["turn_signal"]
    td = D["tbt_dist_m"] if "dist_to_turn" not in cand.meta else cand.meta["dist_to_turn"]
    expressway = trip["road_class"][max(0, cand.i0)] == 2
    need = c["expressway_min_lead_m"] if expressway else c["urban_min_lead_m"]
    # continuous on-run of the right blinker ending at/after the turn
    j = i_turn
    if sig[j] != want:
        # maybe the blinker went off a moment before the turn point (auto-cancel), look back 2 s
        back = np.flatnonzero(sig[max(0, j - int(2 * HZ)):j + 1] == want)
        if len(back) == 0:
            return _mk("turn_signal_turn", trip, D, cand, MISS, 0, need, reason="no_signal")
        j = max(0, j - int(2 * HZ)) + back[-1]
    while j > 0 and sig[j - 1] == want:
        j -= 1
    lead = float(td[j]) if np.isfinite(td[j]) else np.nan
    if not np.isfinite(lead):
        return _mk("turn_signal_turn", trip, D, cand, ABSTAIN, reason="distance_missing")
    guard = 0 if cand.meta.get("truth") else cfg["general"]["guard_lead_m"]
    if need <= lead < need + guard:
        return _mk("turn_signal_turn", trip, D, cand, ABSTAIN, lead, need, reason="inside_guard_band")
    if lead >= need:
        return _mk("turn_signal_turn", trip, D, cand, SUCCESS, lead, need, min(1, (lead - need) / need + 0.2),
                   level="excellent" if lead >= 1.5 * need else "success")
    return _mk("turn_signal_turn", trip, D, cand, MISS, lead, need, reason="late_signal")


def judge_smooth_turn(trip, D, cfg, cand):
    c = cfg["smooth_turn"]
    i0, i1 = cand.i_ref - int(6 * HZ), cand.i_ref + int(8 * HZ)
    r = _abstain_checks(trip, D, cfg, i0, i1, need=("v", "steer", "adas_unknown_ok"))   # SCC does not turn at junctions
    if r:
        return _mk("smooth_turn", trip, D, cand, ABSTAIN, reason=r)
    peak = float(np.nanmax(np.abs(D["a_lat_g"][max(0, i0):i1])))
    return _kin_judge("smooth_turn", trip, D, cand, peak, c["success_max_lat_g"], c["excellent_max_lat_g"], lat_guard(D, cfg, c["success_max_lat_g"]))


def judge_right_turn_pause(trip, D, cfg, cand):
    c = cfg["right_turn_pause"]
    if cand.meta.get("dir") != 2 or trip["road_class"][cand.i_ref] == 2:
        return None
    if cand.meta.get("source") != "tbt" and not cand.meta.get("truth"):
        return _mk("right_turn_pause", trip, D, cand, ABSTAIN, reason="no_navigation_reference")
    td = D["tbt_dist_m"]
    idx = np.arange(cand.i0, cand.i_ref + 1)
    near = idx[(td[idx] <= c["window_m"][0]) & (td[idx] >= c["window_m"][1])]
    if len(near) == 0 or not np.all(np.isfinite(D["v_kph"][near])):
        return _mk("right_turn_pause", trip, D, cand, ABSTAIN, reason="speed_missing")
    vmin = float(np.min(D["v_kph"][near]))
    if vmin <= c["max_speed_kph"]:
        return _mk("right_turn_pause", trip, D, cand, SUCCESS, vmin, c["max_speed_kph"], 1 - vmin / c["max_speed_kph"], level="success")
    # success-only habit: we cannot know the light colour / pedestrians, so a non-stop is not a miss
    return _mk("right_turn_pause", trip, D, cand, ABSTAIN, vmin, c["max_speed_kph"], reason="success_only_habit")


def _rolling_median(x, k):
    from scipy.ndimage import median_filter
    return median_filter(x, size=k, mode="nearest")


def detect_lane_changes(trip, D, cfg):
    """A lane change leaves an S-shaped yaw signature: a yaw lobe followed by an opposite lobe
    of similar area, while the heading returns to its original value and the car moves
    sideways by about one lane width (~3.5 m). Curves produce a single-signed yaw offset that
    the 15-s rolling median removes; turns are excluded by navigation and heading-change checks."""
    c = cfg["lane_change_signal"]
    v = np.nan_to_num(D["v_ms"])
    yaw = np.nan_to_num(D["yaw_steer_dps"])
    n = len(yaw)
    yq = D.get("yaw_quantum_dps", np.zeros(n))
    if np.nanmax(yq, initial=0) > 0.2:      # coarse steering steps: average 1 s so rounding noise cancels
        yaw = np.convolve(yaw, np.ones(int(HZ)) / int(HZ), mode="same")
    base = _rolling_median(yaw, int(15 * HZ) | 1)
    dev = yaw - base
    thr = np.maximum(0.5, 1.5 * yq)          # a lobe must stand clearly above one steering step
    lobes = []
    for s0, e0 in runs(np.abs(dev) > thr, min_len=int(0.5 * HZ)):
        area = float(np.sum(dev[s0:e0]) / HZ)            # degrees of heading
        lobes.append((s0, e0, area))
    td = D["tbt_dist_m"]
    cands, k = [], 0
    while k < len(lobes) - 1:
        s1, e1, A1 = lobes[k]
        s2, e2, A2 = lobes[k + 1]
        ok = (np.sign(A1) != np.sign(A2) and (s2 - e1) / HZ <= 2.0 and 0.25 <= abs(A1 / A2) <= 4.0
              and abs(A1) <= 15 and (e2 - s1) / HZ <= c["max_duration_s"])
        if not ok:
            k += 1
            continue
        # onset: walk back while deviation keeps the first lobe's sign above the noise floor
        j = s1
        while j > 0 and s1 - j < int(1.5 * HZ) and np.sign(dev[j - 1]) == np.sign(A1) and abs(dev[j - 1]) > 0.1:
            j -= 1
        end = min(n, e2 + int(0.5 * HZ))
        psi = np.deg2rad(np.cumsum(dev[j:end]) / HZ)
        y = float(np.sum(v[j:end] * np.sin(psi)) / HZ)
        vm = float(np.mean(v[j:end])) * 3.6
        if abs(y) < c["min_lateral_offset_m"] or abs(y) > 5.5 or vm < c["min_speed_kph"]:
            k += 1
            continue
        meta = {"dir": 1 if A1 > 0 else 2, "lat_offset_m": abs(y), "duration_s": (end - j) / HZ}
        seg = td[max(0, j - 50):end]
        meta["near_turn"] = bool(np.any(np.isfinite(seg) & (seg < c["no_tbt_within_m"]) & np.isin(trip["tbt_type"][max(0, j - 50):end], (1, 2, 3))))
        meta["curving"] = bool(np.max(np.abs(base[j:end])) > 1.5)
        cands.append(Candidate("lane_change", int(j), int(j), int(end), meta))
        k += 2
    return cands


def judge_lane_change_signal(trip, D, cfg, cand):
    c = cfg["lane_change_signal"]
    if cand.meta.get("near_turn") or cand.meta.get("curving"):
        return _mk("lane_change_signal", trip, D, cand, ABSTAIN, reason="turn_or_curve_ambiguity")
    r = _abstain_checks(trip, D, cfg, cand.i0, cand.i1, need=("v", "steer"), adas=False)
    if r:
        return _mk("lane_change_signal", trip, D, cand, ABSTAIN, reason=r)
    if D.get("steer_sign", 1) == 0 and not cand.meta.get("truth"):
        return _mk("lane_change_signal", trip, D, cand, ABSTAIN, reason="steer_sign_unknown")
    want = cand.meta["dir"]
    sig = trip["turn_signal"]
    i_s = cand.i0
    look = sig[max(0, i_s - int(15 * HZ)):cand.i1]
    on = np.flatnonzero(look == want)
    if len(on) == 0:
        return _mk("lane_change_signal", trip, D, cand, MISS, np.nan, c["min_lead_s"], reason="no_signal")
    first = max(0, i_s - int(15 * HZ)) + on[0]
    lead = (i_s - first) / HZ
    guard = 0 if cand.meta.get("truth") else cfg["general"]["guard_lead_s"]
    if c["min_lead_s"] <= lead < c["min_lead_s"] + guard:
        return _mk("lane_change_signal", trip, D, cand, ABSTAIN, lead, c["min_lead_s"], reason="inside_guard_band")
    if lead >= c["min_lead_s"]:
        return _mk("lane_change_signal", trip, D, cand, SUCCESS, lead, c["min_lead_s"],
                   min(1, lead / (2 * c["min_lead_s"])), level="excellent" if lead >= 5 else "success")
    return _mk("lane_change_signal", trip, D, cand, MISS, lead, c["min_lead_s"], reason="late_signal")


def judge_smooth_lane_change(trip, D, cfg, cand):
    c = cfg["smooth_lane_change"]
    if cand.meta.get("near_turn") or cand.meta.get("curving"):
        return _mk("smooth_lane_change", trip, D, cand, ABSTAIN, reason="turn_or_curve_ambiguity")
    r = _abstain_checks(trip, D, cfg, cand.i0, cand.i1, need=("v", "steer"))
    if r:
        return _mk("smooth_lane_change", trip, D, cand, ABSTAIN, reason=r)
    peak = float(np.nanmax(np.abs(D["a_lat_g"][cand.i0:cand.i1])))
    # peak lateral acceleration of a lane change = 2*pi*W/T^2, so 0.2 g already implies T >= ~3.3 s
    guard = 0 if cand.meta.get("truth") else lat_guard(D, cfg, c["max_lat_accel_g"])
    if c["max_lat_accel_g"] - guard <= peak < c["max_lat_accel_g"]:
        return _mk("smooth_lane_change", trip, D, cand, ABSTAIN, peak, c["max_lat_accel_g"], reason="inside_guard_band")
    if peak < c["max_lat_accel_g"]:
        return _mk("smooth_lane_change", trip, D, cand, SUCCESS, peak, c["max_lat_accel_g"],
                   (c["max_lat_accel_g"] - peak) / c["max_lat_accel_g"], level="success")
    return _mk("smooth_lane_change", trip, D, cand, MISS, peak, c["max_lat_accel_g"], reason="abrupt")


def _zone_runs(mask):
    return [Candidate("zone", s, s, e) for s, e in runs(mask, min_len=int(3 * HZ))]


def detect_school(trip, D, cfg):
    return [Candidate("school_zone", c.i0, c.i0, c.i1) for c in _zone_runs(trip["school_zone"] == 1)]


def judge_school(trip, D, cfg, cand):
    c = cfg["school_zone"]
    r = _abstain_checks(trip, D, cfg, cand.i0, cand.i1, need=("v",), forced_lookback=False, adas=False)
    if r:
        return _mk("school_zone", trip, D, cand, ABSTAIN, reason=r)
    vmax = float(np.max(D["v_kph"][cand.i0:cand.i1]))
    cam = np.any(trip["camera_overspeed"][cand.i0:cand.i1] == 1)
    g = 0.0 if cfg.get("_truth") else speed_guard(cfg, np.array([c["success_max_speed_kph"]]))
    if c["success_max_speed_kph"] - g < vmax <= c["success_max_speed_kph"] and not cam:
        return _mk("school_zone", trip, D, cand, ABSTAIN, vmax, c["success_max_speed_kph"], reason="inside_guard_band")
    if vmax <= c["success_max_speed_kph"] and not cam:
        return _mk("school_zone", trip, D, cand, SUCCESS, vmax, c["success_max_speed_kph"],
                   (c["success_max_speed_kph"] - vmax) / 10, level="success")
    return _mk("school_zone", trip, D, cand, MISS, vmax, c["success_max_speed_kph"], reason="over_30")


def detect_caution_points(trip, D, cfg):
    out = []
    cd, ct = D["caution_dist_m"], trip["caution_type"]
    n = len(cd)
    for i in range(1, n):
        if np.isfinite(cd[i]) and np.isfinite(cd[i - 1]) and cd[i - 1] > 0 >= cd[i] and ct[i] in (2, 3) + tuple(cfg["caution_slowdown"]["types"]):
            # approach start
            j = i
            while j > 0 and np.isfinite(cd[j - 1]) and cd[j - 1] >= cd[j] and cd[j - 1] < 600:
                j -= 1
            k = i
            while k < n - 1 and np.isfinite(cd[k + 1]) and cd[k + 1] <= cd[k] and cd[k + 1] > -100:
                k += 1
            out.append(Candidate("caution", i, j, k + 1, {"ctype": int(ct[i])}))
    return out


def judge_caution(trip, D, cfg, cand):
    ct = cand.meta["ctype"]
    cd = D["caution_dist_m"] if "dist" not in cand.meta else cand.meta["dist"]
    idx = np.arange(cand.i0, cand.i1)
    v = D["v_kph"]
    if not np.all(np.isfinite(v[cand.i0:cand.i1])):
        return _mk({2: "speed_bump", 3: "railroad_stop"}.get(ct, "caution_slowdown"), trip, D, cand, ABSTAIN, reason="speed_missing")
    if ct == 2:
        c = cfg["speed_bump"]
        sel = idx[np.abs(cd[idx]) <= c["check_window_m"]]
        if len(sel) == 0:
            return _mk("speed_bump", trip, D, cand, ABSTAIN, reason="no_samples")
        vmax = float(np.max(v[sel]))
        g = 0.0 if cfg.get("_truth") else float(speed_guard(cfg, c["max_speed_kph"]))
        if c["max_speed_kph"] - g < vmax <= c["max_speed_kph"]:
            return _mk("speed_bump", trip, D, cand, ABSTAIN, vmax, c["max_speed_kph"], reason="inside_guard_band")
        ok = vmax <= c["max_speed_kph"]
        return _mk("speed_bump", trip, D, cand, SUCCESS if ok else MISS, vmax, c["max_speed_kph"],
                   (c["max_speed_kph"] - vmax) / c["max_speed_kph"] if ok else 0, level="success" if ok else "")
    if ct == 3:
        c = cfg["railroad_stop"]
        sel = idx[(cd[idx] <= c["stop_within_m"]) & (cd[idx] >= -2)]
        if len(sel) == 0:
            return _mk("railroad_stop", trip, D, cand, ABSTAIN, reason="no_samples")
        vmin = float(np.min(v[sel]))
        ok = vmin <= c["stop_speed_kph"] - (0 if cfg.get("_truth") else 0.5)   # guard: cluster rounding
        return _mk("railroad_stop", trip, D, cand, SUCCESS if ok else MISS, vmin, c["stop_speed_kph"], 1.0 if ok else 0,
                   level="success" if ok else "")
    c = cfg["caution_slowdown"]
    ref = idx[(cd[idx] <= c["ref_window_m"][0]) & (cd[idx] >= c["ref_window_m"][1])]
    chk = idx[(cd[idx] <= c["check_window_m"][0]) & (cd[idx] >= c["check_window_m"][1])]
    if len(ref) < 5 or len(chk) < 3:
        return _mk("caution_slowdown", trip, D, cand, ABSTAIN, reason="no_samples")
    vref, vchk = float(np.mean(v[ref])), float(np.mean(v[chk]))
    lim = np.nanmedian(trip["speed_limit_kph"][ref]) if np.isfinite(trip["speed_limit_kph"][ref]).any() else np.nan
    if np.isfinite(lim) and vref < 0.5 * lim:
        return _mk("caution_slowdown", trip, D, cand, ABSTAIN, reason="already_slow")
    red = 1 - vchk / max(vref, 1e-3)
    ok = red >= c["min_speed_reduction_frac"]
    return _mk("caution_slowdown", trip, D, cand, SUCCESS if ok else MISS, red, c["min_speed_reduction_frac"],
               min(1, red / 0.3) if ok else 0, level="success" if ok else "")


def _windows(n, w):
    return [(i, min(n, i + w)) for i in range(0, n - w + 1, w)]


def detect_speed_windows(trip, D, cfg):
    w = int(cfg["speed_limit"]["window_s"] * HZ)
    return [Candidate("speed_limit", (a + b) // 2, a, b) for a, b in _windows(len(trip["t"]), w)]


def judge_speed_limit(trip, D, cfg, cand):
    c = cfg["speed_limit"]
    a, b = cand.i0, cand.i1
    lim = trip["speed_limit_kph"][a:b]
    v = D["v_kph"][a:b]
    if np.mean(np.isfinite(lim)) < 0.9:
        return None   # no opportunity: limit unknown
    if np.any(trip["school_zone"][a:b] == 1):
        return None   # handled by school_zone habit
    if np.mean(np.isfinite(v)) < 0.95:
        return _mk("speed_limit", trip, D, cand, ABSTAIN, reason="speed_missing")
    if np.mean(trip["cruise_active"][a:b] == 1) > 0.5:
        return _mk("speed_limit", trip, D, cand, ABSTAIN, reason="adas_engaged")       # SCC / HDA may follow the limit itself
    if np.any(trip["cruise_active"][a:b] < 0):
        return _mk("speed_limit", trip, D, cand, ABSTAIN, reason="adas_state_unknown")
    m = np.isfinite(lim) & np.isfinite(v)
    if np.mean(v[m]) < 0.6 * np.mean(lim[m]):
        return None   # no real opportunity to speed (jam / slow road)
    g = 0.0 if cfg.get("_truth") else speed_guard(cfg, lim[m])
    frac = float(np.mean(v[m] <= lim[m] + c["tolerance_kph"] - g))
    ok = frac >= c["success_min_fraction"]
    return _mk("speed_limit", trip, D, cand, SUCCESS if ok else MISS, frac, c["success_min_fraction"],
               (frac - c["success_min_fraction"]) / (1 - c["success_min_fraction"] + 1e-9) if ok else 0,
               level="success" if ok else "")


def detect_rain_windows(trip, D, cfg):
    w = int(cfg["rain_speed"]["window_s"] * HZ)
    out = []
    for a, b in _windows(len(trip["t"]), w):
        if np.mean(trip["wiper_active"][a:b] == 1) >= 0.8:
            out.append(Candidate("rain_speed", (a + b) // 2, a, b))
    return out


def judge_rain(trip, D, cfg, cand):
    c = cfg["rain_speed"]
    a, b = cand.i0, cand.i1
    lim, v = trip["speed_limit_kph"][a:b], D["v_kph"][a:b]
    if np.mean(np.isfinite(lim)) < 0.9 or np.any(trip["school_zone"][a:b] == 1):
        return None
    if np.mean(np.isfinite(v)) < 0.95:
        return _mk("rain_speed", trip, D, cand, ABSTAIN, reason="speed_missing")
    if np.mean(trip["cruise_active"][a:b] == 1) > 0.5:
        return _mk("rain_speed", trip, D, cand, ABSTAIN, reason="adas_engaged")
    if np.any(trip["cruise_active"][a:b] < 0):
        return _mk("rain_speed", trip, D, cand, ABSTAIN, reason="adas_state_unknown")
    m = np.isfinite(lim) & np.isfinite(v)
    if np.mean(v[m]) < 0.5 * np.mean(lim[m]):
        return None
    g = 0.0 if cfg.get("_truth") else speed_guard(cfg, c["factor"] * lim[m])
    frac = float(np.mean(v[m] <= c["factor"] * lim[m] - g))
    ok = frac >= c["success_min_fraction"]
    return _mk("rain_speed", trip, D, cand, SUCCESS if ok else MISS, frac, c["success_min_fraction"],
               (frac - c["success_min_fraction"]) / (1 - c["success_min_fraction"] + 1e-9) if ok else 0, level="success" if ok else "")


def detect_light_windows(trip, D, cfg):
    w = int(60 * HZ)
    out = []
    for a, b in _windows(len(trip["t"]), w):
        need = (trip["night"][a:b] == 1) | (trip["wiper_active"][a:b] == 1)
        moving = np.nan_to_num(D["v_kph"][a:b]) > 5
        if np.mean(need & moving) > 0.8:
            out.append(Candidate("headlights", (a + b) // 2, a, b))
    return out


def judge_lights(trip, D, cfg, cand):
    lb = trip["low_beam"][cand.i0:cand.i1]
    if np.any(lb < 0):
        return _mk("headlights", trip, D, cand, ABSTAIN, reason="light_state_missing")
    frac = float(np.mean(lb == 1))
    ok = frac >= 0.95
    return _mk("headlights", trip, D, cand, SUCCESS if ok else MISS, frac, 0.95, 1.0 if ok else 0, level="success" if ok else "")


def _blocks_by_km(D, km, trip=None):
    s = D["s_m"]
    if trip is not None and np.isfinite(trip["odo_km"]).all():
        steps = np.diff(trip["odo_km"].astype(np.float64))
        steps = steps[steps > 0]
        if len(steps) and np.max(steps) <= 0.011:    # only a fine-resolution odometer beats integrated speed
            s = (trip["odo_km"].astype(np.float64) - float(trip["odo_km"][0])) * 1000
    total = s[-1]
    out = []
    for k in range(int(total // (km * 1000))):
        a = int(np.searchsorted(s, k * km * 1000))
        b = int(np.searchsorted(s, (k + 1) * km * 1000))
        out.append((a, b))
    return out


def detect_blocks(trip, D, cfg):
    out = []
    for a, b in _blocks_by_km(D, cfg["following_margin"]["block_km"], trip):
        out.append(Candidate("following_margin", (a + b) // 2, a, b))
        out.append(Candidate("lane_keeping", (a + b) // 2, a, b))
    return out


def judge_block(trip, D, cfg, cand):
    a, b = cand.i0, cand.i1
    if np.mean(trip["cruise_active"][a:b] == 1) > 0.5:
        return _mk(cand.habit, trip, D, cand, ABSTAIN, reason="adas_engaged")
    if np.any(trip["cruise_active"][a:b] < 0):
        return _mk(cand.habit, trip, D, cand, ABSTAIN, reason="adas_state_unknown")
    ch = "tt_fca" if cand.habit == "following_margin" else "tt_lka"
    x = trip[ch][a:b]
    if np.any(x < 0):
        return _mk(cand.habit, trip, D, cand, ABSTAIN, reason="telltale_unavailable")
    n_on = len(runs(x == 1))
    ok = n_on == 0
    return _mk(cand.habit, trip, D, cand, SUCCESS if ok else MISS, n_on, 0, 1.0 if ok else 0, level="success" if ok else "")


def detect_belts(trip, D, cfg):
    v = np.nan_to_num(D["v_kph"])
    hit = np.flatnonzero(v > cfg["seat_belts"]["check_at_speed_kph"])
    return [Candidate("seat_belts", int(hit[0]), int(hit[0]), int(hit[0]) + 1)] if len(hit) else []


def judge_belts(trip, D, cfg, cand):
    occ, buck = int(trip["seats_occupied"][cand.i_ref]), int(trip["belts_buckled"][cand.i_ref])
    if occ < 1 or buck < 0:
        return _mk("seat_belts", trip, D, cand, ABSTAIN, reason="seat_data_missing")
    ok = buck >= occ
    return _mk("seat_belts", trip, D, cand, SUCCESS if ok else MISS, buck, occ, 1.0 if ok else 0, level="success" if ok else "",
               reason="" if ok else "unbuckled_seat")


def detect_rest(trip, D, cfg):
    c = cfg["rest_breaks"]
    moving = (np.nan_to_num(D["v_kph"]) > 3) | (trip["gear"] == 3)
    parked = trip["gear"] == 0
    out = []
    # simple stint logic: split by parked runs >= min_break
    stints, last = [], 0
    for s, e in runs(parked, min_len=int(c["min_break_min"] * 60 * HZ)):
        stints.append((last, s, True))
        last = e
    stints.append((last, len(moving), False))
    for a, b, ended_by_break in stints:
        dur_min = np.sum(moving[a:b]) / HZ / 60
        if dur_min >= 100 or ended_by_break and dur_min >= 90:
            out.append(Candidate("rest_breaks", b - 1, a, b, {"stint_min": float(dur_min), "break": ended_by_break}))
    return out


def judge_rest(trip, D, cfg, cand):
    c = cfg["rest_breaks"]
    dur = cand.meta["stint_min"]
    ok = cand.meta["break"] and dur <= c["max_continuous_drive_min"]
    if not cand.meta["break"] and dur < c["max_continuous_drive_min"]:
        return None  # trip simply ended
    return _mk("rest_breaks", trip, D, cand, SUCCESS if ok else MISS, dur, c["max_continuous_drive_min"], 1.0 if ok else 0,
               level="success" if ok else "")


def detect_cruise_windows(trip, D, cfg):
    c = cfg["steady_cruise"]
    w = int(c["window_s"] * HZ)
    out = []
    for a, b in _windows(len(trip["t"]), w):
        if np.all(trip["road_class"][a:b] == 2):
            out.append(Candidate("steady_cruise", (a + b) // 2, a, b))
    return out


def judge_cruise(trip, D, cfg, cand):
    c = cfg["steady_cruise"]
    a, b = cand.i0, cand.i1
    v = D["v_kph"][a:b]
    if not np.all(np.isfinite(v)):
        return _mk("steady_cruise", trip, D, cand, ABSTAIN, reason="speed_missing")
    if np.min(v) < c["min_speed_kph"] or np.any(trip["congestion"][a:b] >= 1):
        return None
    if np.any(trip["cruise_active"][a:b] == 1):
        return _mk("steady_cruise", trip, D, cand, ABSTAIN, reason="adas_engaged")
    if np.any(trip["cruise_active"][a:b] < 0):
        return _mk("steady_cruise", trip, D, cand, ABSTAIN, reason="adas_state_unknown")   # a steady speed is exactly what SCC does
    if np.any(trip["turn_signal"][a:b] > 0) or np.any(trip["tt_fca"][a:b] == 1):
        return None   # manoeuvring or traffic interaction, not a cruise opportunity
    lim = trip["speed_limit_kph"][a:b]
    if not np.all(np.isfinite(lim)):
        return None   # limit unknown: a steady speed could be a steady overspeed
    g = 0.0 if cfg.get("_truth") else speed_guard(cfg, lim)
    if np.any(v > lim - g):
        return None   # never praise a steady speed above the limit (doc review 2026-11-06)
    sd = float(np.std(v))
    ok = sd <= c["max_speed_std_kph"]
    return _mk("steady_cruise", trip, D, cand, SUCCESS if ok else MISS, sd, c["max_speed_std_kph"],
               (c["max_speed_std_kph"] - sd) / c["max_speed_std_kph"] if ok else 0, level="success" if ok else "")


def detect_hazard(trip, D, cfg):
    c = cfg["hazard_warning"]
    v = D["v_kph"]
    w = int(c["within_s"] * HZ)
    out, i, n = [], w, len(v)
    while i < n:
        if trip["road_class"][i] == 2 and np.isfinite(v[i]) and np.isfinite(v[i - w]):
            if v[i - w] >= c["min_entry_speed_kph"] and v[i - w] - v[i] >= c["min_drop_kph"]:
                out.append(Candidate("hazard_warning", i, i - w, min(n, i + int(5 * HZ))))
                i += int(30 * HZ)
                continue
        i += 2
    return out


def judge_hazard(trip, D, cfg, cand):
    on = np.any(trip["turn_signal"][cand.i0:cand.i1] == 3)
    if on:
        return _mk("hazard_warning", trip, D, cand, SUCCESS, 1, 1, 1.0, level="success")
    return _mk("hazard_warning", trip, D, cand, ABSTAIN, reason="success_only_habit")


def detect_fca_onsets(trip, D, cfg):
    return [Candidate("fca_reaction", s, s, min(len(trip["t"]), s + int(3 * HZ))) for s, _e in runs(trip["tt_fca"] == 1)]


def judge_fca_reaction(trip, D, cfg, cand):
    b = trip["brake_on"][cand.i0:cand.i0 + int(1.0 * HZ) + 1]
    if not np.all(b >= 0):
        return _mk("fca_reaction", trip, D, cand, ABSTAIN, reason="brake_missing")
    if np.any(b == 1):
        lat = np.nanmax(np.abs(D["a_lat_g"][cand.i0:cand.i1])) if np.isfinite(D["a_lat_g"][cand.i0:cand.i1]).any() else 0
        if lat < 0.5:
            return _mk("fca_reaction", trip, D, cand, SUCCESS, 1, 1, 1.0, level="success")
    return _mk("fca_reaction", trip, D, cand, ABSTAIN, reason="success_only_habit")


def judge_blinker_cancel(trip, D, cfg, cand, end_idx):
    sig = trip["turn_signal"]
    lim = int(cfg["blinker_cancel"]["max_linger_s"] * HZ)
    seg = sig[end_idx:end_idx + lim + int(2 * HZ)]
    want = cand.meta.get("dir", 0)
    if len(seg) < lim:
        return None
    if np.any(sig[max(0, end_idx - int(3 * HZ)):end_idx + 1] == want):
        ok = not np.all(seg[:lim] == want)
        return _mk("blinker_cancel", trip, D, Candidate("blinker_cancel", end_idx, end_idx, end_idx + lim, dict(cand.meta)),
                   SUCCESS if ok else MISS, 0, lim / HZ, 1.0 if ok else 0, level="success" if ok else "")
    return None


# ============================================================================ REGISTRY
EVENT_HABITS = ["smooth_stop", "smooth_launch", "anticipatory_braking", "turn_signal_turn", "smooth_turn",
                "right_turn_pause", "lane_change_signal", "smooth_lane_change", "school_zone", "speed_bump",
                "railroad_stop", "caution_slowdown", "hazard_warning", "fca_reaction"]
WINDOW_HABITS = ["speed_limit", "rain_speed", "headlights", "following_margin", "lane_keeping", "seat_belts",
                 "rest_breaks", "steady_cruise", "blinker_cancel"]
ALL_HABITS = EVENT_HABITS + WINDOW_HABITS


def judge_candidates(trip, D, cfg, kind: str, cands: list[Candidate]) -> list[Judgment]:
    """Apply the judges for one candidate kind (used both by the engine and for truth)."""
    js: list[Judgment] = []
    for c in cands:
        if kind == "stop":
            js.append(judge_stop(trip, D, cfg, c))
            if c.meta.get("entry_kph", 0) >= cfg["anticipatory_braking"]["min_entry_speed_kph"] + (0 if cfg.get("_truth") else 2.0):
                js.append(judge_anticipation(trip, D, cfg, Candidate("anticipatory_braking", c.i_ref, c.i0, c.i1, dict(c.meta))))
        elif kind == "launch":
            js.append(judge_launch(trip, D, cfg, c))
        elif kind == "turn":
            js.append(judge_turn_signal(trip, D, cfg, c))
            js.append(judge_smooth_turn(trip, D, cfg, c))
            rt = judge_right_turn_pause(trip, D, cfg, c)
            if rt:
                js.append(rt)
            bc = judge_blinker_cancel(trip, D, cfg, c, min(len(trip["t"]) - 1, c.i_ref + int(4 * HZ)))
            if bc:
                js.append(bc)
        elif kind == "lane_change":
            js.append(judge_lane_change_signal(trip, D, cfg, c))
            js.append(judge_smooth_lane_change(trip, D, cfg, c))
            bc = judge_blinker_cancel(trip, D, cfg, c, min(len(trip["t"]) - 1, c.i1))
            if bc:
                js.append(bc)
        elif kind == "school":
            js.append(judge_school(trip, D, cfg, c))
        elif kind == "caution":
            js.append(judge_caution(trip, D, cfg, c))
        elif kind == "hazard":
            js.append(judge_hazard(trip, D, cfg, c))
        elif kind == "fca":
            js.append(judge_fca_reaction(trip, D, cfg, c))
    return [j for j in js if j is not None]


def run_window_habits(trip, D, cfg) -> list[Judgment]:
    js = []
    for c in detect_speed_windows(trip, D, cfg):
        js.append(judge_speed_limit(trip, D, cfg, c))
    for c in detect_rain_windows(trip, D, cfg):
        js.append(judge_rain(trip, D, cfg, c))
    for c in detect_light_windows(trip, D, cfg):
        js.append(judge_lights(trip, D, cfg, c))
    for c in detect_blocks(trip, D, cfg):
        js.append(judge_block(trip, D, cfg, c))
    for c in detect_belts(trip, D, cfg):
        js.append(judge_belts(trip, D, cfg, c))
    for c in detect_rest(trip, D, cfg):
        js.append(judge_rest(trip, D, cfg, c))
    for c in detect_cruise_windows(trip, D, cfg):
        js.append(judge_cruise(trip, D, cfg, c))
    return [j for j in js if j is not None]


def run_event_habits(trip, D, cfg) -> tuple[list[Judgment], dict[str, list[Candidate]]]:
    cands = {
        "stop": detect_stops(trip, D, cfg),
        "launch": detect_launches(trip, D, cfg),
        "turn": detect_turns(trip, D, cfg),
        "lane_change": detect_lane_changes(trip, D, cfg),
        "school": detect_school(trip, D, cfg),
        "caution": detect_caution_points(trip, D, cfg),
        "hazard": detect_hazard(trip, D, cfg),
        "fca": detect_fca_onsets(trip, D, cfg),
    }
    js = []
    for kind, cs in cands.items():
        js.extend(judge_candidates(trip, D, cfg, kind, cs))
    return js, cands
