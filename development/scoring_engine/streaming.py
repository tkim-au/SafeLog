"""
In-drive runtime (the only part that runs while the car is moving).

Pleos performance criteria (document.pleos.ai, vehicle-app-planning-guide/performance-resource-mgmt): non-navigation app
while driving < 5 % CPU, any app parked <= 25 %, RAM <= 400 MB. To stay far inside that, SafeLog splits work
in two phases:

  DRIVING  : DriveRecorder  — pack each 10 Hz frame into 24 bytes (ring buffer, ~0.86 MB/hour)
             ChimeDetector  — O(1) state machines for the ONE practice habit the driver picked,
                              so the praise chime can play. No floating arrays, no ML.
  PARKED   : SafeLogEngine (batch, all habits) + dual-key gate (TCN) on the recorded frames.

This file is the reference for the Kotlin port; tools/benchmark_runtime.py measures it.
"""
from __future__ import annotations

import struct
from collections import deque

import numpy as np

G = 9.80665
FRAME = struct.Struct("<HhhHhhBBBBBBBBxx")   # 24 bytes
# speed 0.01 km/h | steer 0.1 deg | tbt dm (-1 none) | heading 0.01 deg | caution dist m | limit km/h*10
# brake pedal switch (0/1, 255 unknown) | signal | caution type | tbt type | flags1 | flags2 | seats | belts
F1 = ["wiper_active", "low_beam", "night", "tt_fca", "tt_lka", "tt_daw", "cruise_active", "school_zone"]
F2 = ["tt_abs_esc", "tt_icy", "camera_overspeed", "tt_lca", "tt_tpms", "nav_active"]


def _i(x, scale, lo, hi, nan):
    if x != x:   # NaN
        return nan
    return int(min(hi, max(lo, round(x * scale))))


class DriveRecorder:
    """Compact frame log. 24 B/frame x 10 Hz = 240 B/s = 0.86 MB/h."""

    def __init__(self, max_hours: float = 6.0):
        self.buf = bytearray()
        self.max_frames = int(max_hours * 3600 * 10)

    def push(self, f: dict):
        if len(self.buf) // FRAME.size >= self.max_frames:
            return   # Kotlin version flushes to Room/SQLite every 60 s instead
        fl1 = sum(1 << k for k, n in enumerate(F1) if f.get(n, 0) == 1)
        fl2 = sum(1 << k for k, n in enumerate(F2) if f.get(n, 0) == 1)
        self.buf += FRAME.pack(
            _i(f["speed_kph"], 100, 0, 65534, 65535), _i(f["steer_deg"], 10, -32767, 32767, -32768),
            _i(f["tbt_dist_m"], 10, -32767, 32767, -32768), _i(f["heading_deg"], 100, 0, 65534, 65535),
            _i(f["caution_dist_m"], 1, -32767, 32767, -32768), _i(f["speed_limit_kph"], 10, 0, 32767, -32768),
            (int(f["brake_on"]) if f["brake_on"] >= 0 else 255), int(f["turn_signal"]) & 0xFF, int(f["caution_type"]) & 0xFF,
            int(f["tbt_type"]) & 0xFF, fl1, fl2, int(f["seats_occupied"]) & 0xFF, int(f["belts_buckled"]) & 0xFF)

    @property
    def nbytes(self):
        return len(self.buf)


class ChimeDetector:
    """O(1)-per-frame detectors for the practice habit. Emits ('chime', habit) on success."""

    def __init__(self, practice: str, hz: float = 10.0, guard_g: float = 0.02, steer_sign: int = -1,
                 steer_unit: str = "road_wheel"):
        """steer_sign: x(-1) makes the official left-negative front-wheel angle left-positive (calibration memory
        may override it). 0 = unknown -> the lane-change chime stays silent. steer_unit: road_wheel (official)
        or steering_wheel (emulator slider)."""
        self.practice, self.hz, self.guard = practice, hz, guard_g
        self.v_hist = deque(maxlen=int(hz) + 1)      # 1-s window for delta-v
        self.state = "idle"
        self.peak = 0.0
        self.entry = 0.0
        self.fca_age = 1e9
        self.sig_on_dist = None
        self.prev_sig = 0
        self.prev_td = None
        self.td_est = None
        self.td_raw_prev = None
        self.lc = _LCOnline(hz, steer_sign, 13.5 if steer_unit == "steering_wheel" else 1.0)

    def step(self, f: dict):
        out = None
        v = f["speed_kph"]
        self.fca_age = 0 if f["tt_fca"] == 1 else self.fca_age + 1
        if v == v:
            self.v_hist.append(v)
        a_g = ((self.v_hist[-1] - self.v_hist[0]) / 3.6 / ((len(self.v_hist) - 1) / self.hz) / G
               if len(self.v_hist) == self.v_hist.maxlen else 0.0)
        if self.practice in ("smooth_stop", "anticipatory_braking"):
            if self.state == "idle" and v == v and v >= 32 and a_g < -0.03:
                self.state, self.peak, self.entry = "decel", 0.0, v
            elif self.state == "decel":
                self.peak = max(self.peak, -a_g)
                if v == v and v < 1.0:
                    ok = (self.peak < 0.30 - self.guard and self.fca_age > 3 * self.hz and f["cruise_active"] != 1)
                    out = ("chime", "smooth_stop") if ok else None
                    self.state = "idle"
                elif a_g > 0.05:
                    self.state = "idle"
        elif self.practice == "turn_signal_turn":
            td_raw, sig = f["tbt_dist_m"], f["turn_signal"]
            # NaviHelper refreshes the distance ~1 Hz: dead-reckon between refreshes (O(1))
            if td_raw != td_raw:
                self.td_est = None
            elif self.td_raw_prev is None or td_raw != self.td_raw_prev or self.td_est is None:
                self.td_est = td_raw
            elif v == v:
                self.td_est -= v / 3.6 / self.hz
            self.td_raw_prev = td_raw if td_raw == td_raw else None
            td = self.td_est if self.td_est is not None else float("nan")
            want = {1: 1, 2: 2}.get(f["tbt_type"], 0)
            if td == td and want:
                if sig == want and self.prev_sig != want:
                    self.sig_on_dist = td
                if sig != want and self.prev_sig == want and td > 5:
                    self.sig_on_dist = None
                if self.prev_td is not None and self.prev_td > 0 >= td:
                    need = 100 if f.get("road_class", 0) == 2 else 30
                    if self.sig_on_dist is not None and self.sig_on_dist >= need + 5:
                        out = ("chime", "turn_signal_turn")
                    self.sig_on_dist = None
            self.prev_td = td if td == td else None
            self.prev_sig = sig
        elif self.practice == "lane_change_signal":
            ev = self.lc.step(f)
            if ev:
                out = ("chime", "lane_change_signal")
        return out


class _LCOnline:
    """Streaming version of the S-shaped yaw detector (needs only the last ~12 s)."""

    def __init__(self, hz, steer_sign=-1, ratio=1.0):
        self.hz = hz
        self.sign = steer_sign
        self.ratio = ratio
        self.yaw = deque(maxlen=int(15 * hz))
        self.sig_since = None
        self.t = 0
        self.lobes = deque(maxlen=3)
        self.cur = None

    def step(self, f):
        self.t += 1
        if self.sign == 0:
            return False
        v = f["speed_kph"] / 3.6 if f["speed_kph"] == f["speed_kph"] else 0.0
        delta = np.deg2rad(self.sign * f["steer_deg"] / self.ratio) if f["steer_deg"] == f["steer_deg"] else 0.0
        yaw = np.rad2deg(v * np.tan(delta) / (2.895 * (1 + 0.0025 * v * v)))
        self.yaw.append(yaw)
        base = sorted(self.yaw)[len(self.yaw) // 2]           # 15-s running median (Kotlin: two-heap median)
        dev = yaw - base
        sig = f["turn_signal"]
        if sig in (1, 2) and self.sig_since is None:
            self.sig_since = (self.t, sig)
        if sig == 0 and self.sig_since and self.t - self.sig_since[0] > 15 * self.hz:
            self.sig_since = None
        if abs(dev) > 0.5:
            if self.cur is None:
                self.cur = [self.t, 0.0]
            self.cur[1] += dev / self.hz
        elif self.cur is not None:
            if self.t - self.cur[0] >= 0.5 * self.hz:
                self.lobes.append((self.cur[0], self.t, self.cur[1]))
            self.cur = None
            if len(self.lobes) >= 2:
                (s1, e1, a1), (s2, e2, a2) = self.lobes[-2], self.lobes[-1]
                if np.sign(a1) != np.sign(a2) and (s2 - e1) <= 2 * self.hz and 0.25 <= abs(a1 / a2) <= 4 and v * 3.6 >= 40:
                    d = 1 if a1 > 0 else 2
                    self.lobes.clear()
                    if self.sig_since and self.sig_since[1] == d and (s1 - self.sig_since[0]) / self.hz >= 3.5:
                        self.sig_since = None
                        return True
        return False
