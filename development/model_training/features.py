"""
Turn an engine judgment into a fixed-size tensor for the learned second opinion.

Window: 18 s before to 6 s after the reference point, 10 Hz -> 240 steps x 17 channels.
Static vector: habit one-hot + rule measurements. Everything comes from the OBSERVED trip
(what the car app sees), never from ground truth.
"""
from __future__ import annotations

import numpy as np

GATE_HABITS = ["smooth_stop", "smooth_launch", "anticipatory_braking", "smooth_turn",
               "turn_signal_turn", "lane_change_signal", "smooth_lane_change"]
PRE, POST = 180, 60
T = PRE + POST
CHANNELS = ["v", "v_mask", "a_long", "brake", "steer", "yaw", "sig_l", "sig_r", "sig_h", "tbt", "tbt_mask",
            "limit_ratio", "fca", "lka", "cruise", "wiper", "school"]
C = len(CHANNELS)
S = len(GATE_HABITS) + 7


def _win(x, i, fill=0.0):
    n = len(x)
    out = np.full(T, fill, dtype=np.float32)
    a, b = i - PRE, i + POST
    sa, sb = max(0, a), min(n, b)
    if sb > sa:
        out[sa - a:sb - a] = x[sa:sb]
    return out


def judgment_tensor(trip, D, j):
    i = int(j.i_ref)
    v = D["v_kph"]
    lim = trip["speed_limit_kph"].astype(np.float64)
    ratio = np.where(np.isfinite(v) & np.isfinite(lim) & (lim > 0), np.nan_to_num(v) / np.where(lim > 0, lim, 1), 0)
    sig = trip["turn_signal"]
    tbt = D["tbt_dist_m"].astype(np.float64)          # dead-reckoned between ~1 Hz navigation updates
    chans = [
        _win(np.nan_to_num(v) / 100, i), _win(np.isfinite(v).astype(float), i),
        _win(np.clip(np.nan_to_num(D["a_long_g"]) * 3, -3, 3), i), _win((trip["brake_on"] == 1).astype(float), i),
        _win(np.clip(np.nan_to_num(D["delta_deg"]) / 6.0, -6, 6), i),       # front-wheel deg, unit-independent
        _win(np.clip(np.nan_to_num(D["yaw_steer_dps"]) / 20, -5, 5), i),
        _win((sig == 1).astype(float), i), _win((sig == 2).astype(float), i), _win((sig == 3).astype(float), i),
        _win(np.clip(np.nan_to_num(tbt, nan=300) / 300, -0.1, 1), i, 1.0), _win(np.isfinite(tbt).astype(float), i),
        _win(np.clip(ratio, 0, 2), i), _win((trip["tt_fca"] == 1).astype(float), i),
        _win((trip["tt_lka"] == 1).astype(float), i), _win((trip["cruise_active"] == 1).astype(float) - (trip["cruise_active"] < 0) * 0.5, i),
        _win((trip["wiper_active"] == 1).astype(float) - (trip["wiper_active"] < 0) * 0.5, i), _win((trip["school_zone"] == 1).astype(float), i),
    ]
    x = np.stack(chans, 0)
    s = np.zeros(S, dtype=np.float32)
    s[GATE_HABITS.index(j.habit)] = 1
    k = len(GATE_HABITS)
    s[k] = np.nan_to_num(j.value) / (abs(np.nan_to_num(j.threshold)) + 1e-3)
    s[k + 1] = j.margin
    s[k + 2] = min(D["speed_native_hz"], 10) / 10
    s[k + 3] = j.meta.get("entry_kph", 0) / 100
    s[k + 4] = float(j.meta.get("source", "") == "tbt")
    s[k + 5] = float(D["steer_sign"] != 0)
    s[k + 6] = float(np.all(trip["cruise_active"] >= 0))
    return x, s
