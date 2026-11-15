"""
SafeLog-Sim: a signal-level digital twin of a Pleos car on Korean roads.

It does not render images. It simulates exactly what a third-party Pleos app can see
(speed, brake pedal switch, steering angle, blinkers, telltales, navigation events, wipers, lights, seats)
plus the ground truth that the app can NOT see (true acceleration, real lane changes,
whether a hard stop was forced by another car). That is what we need to train and test
a habit detector: thousands of labelled trips, including the hard cases (cut-ins, curves
that look like lane changes, 1 Hz speed, sensor dropouts, cruise control, navigation off).

Physics: point-mass longitudinal model with first-order actuation lag, kinematic bicycle
model for steering, driver behaviour from driver_styles. Runs at 10 Hz.
For higher-fidelity physics and traffic use the CARLA / SUMO adapters, which emit the same schema.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from data_spec.signals import BASE_HZ, empty_trip
from simulation.driver_styles import DriverStyle, sample_style

DT = 1.0 / BASE_HZ
L_WB, RATIO, K_US = 2.895, 13.5, 0.0025
DRAG = 0.25  # m/s^2 rolling + aero at urban speeds (pedal-free coast without engine braking)


# ============================================================================ route
@dataclass
class Route:
    length: float
    seg_s: np.ndarray          # segment starts
    seg_limit: np.ndarray      # km/h
    seg_class: np.ndarray      # 0 urban 1 rural 2 expressway
    curves: list = field(default_factory=list)      # (s0, s1, kappa)
    events: list = field(default_factory=list)      # dicts sorted by s
    rain: bool = False
    heavy_rain: bool = False
    night: bool = False
    temp_c: float = 15.0

    def at(self, s):
        k = int(np.searchsorted(self.seg_s, s, side="right") - 1)
        k = max(0, min(k, len(self.seg_s) - 1))
        return float(self.seg_limit[k]), int(self.seg_class[k])

    def curvature(self, s):
        for s0, s1, kap in self.curves:
            if s0 <= s < s1:
                return kap
        return 0.0


def make_route(rng: np.random.Generator, kind: str | None = None) -> Route:
    kind = kind or rng.choice(["urban", "urban", "mixed", "rural", "long"], p=[0.45, 0.0, 0.3, 0.2, 0.05])
    segs, s = [], 0.0
    if kind == "urban":
        L = rng.uniform(4000, 12000)
        plan = [("urban", L)]
    elif kind == "rural":
        L = rng.uniform(8000, 20000)
        plan = [("urban", 1500), ("rural", L - 3000), ("urban", 1500)]
    elif kind == "long":
        plan = [("urban", 2000), ("expressway", rng.uniform(150_000, 190_000)), ("urban", 2000)]
    else:
        plan = [("urban", rng.uniform(2000, 4000)), ("expressway", rng.uniform(8000, 25000)), ("urban", rng.uniform(2000, 4000))]
    seg_s, seg_lim, seg_cls = [], [], []
    curves, events = [], []
    for road, length in plan:
        s0 = s
        while s < s0 + length:
            ln = rng.uniform(600, 2500)
            if road == "urban":
                lim, cls = rng.choice([50, 50, 60, 40]), 0
            elif road == "rural":
                lim, cls = rng.choice([60, 70, 80]), 1
            else:
                lim, cls = rng.choice([100, 100, 110, 90]), 2
            seg_s.append(s); seg_lim.append(lim); seg_cls.append(cls)
            s += ln
        e_end = s
        # ---- events per road type
        x = s0 + 150
        while x < e_end - 150:
            if road == "urban":
                x += rng.uniform(250, 700)
                r = rng.random()
                if r < 0.32:
                    events.append({"type": "stop_line", "s": x, "dwell": rng.uniform(4, 45)})
                elif r < 0.55:
                    events.append({"type": "turn", "s": x, "dir": int(rng.choice([1, 2])), "R": rng.uniform(11, 18),
                                   "stop_before": bool(rng.random() < 0.35), "dwell": rng.uniform(3, 30)})
                elif r < 0.62:
                    events.append({"type": "school", "s": x, "s1": x + rng.uniform(200, 400)})
                    x += 400
                elif r < 0.70:
                    events.append({"type": "bump", "s": x})
                elif r < 0.73:
                    events.append({"type": "caution", "s": x, "ctype": 5})
                elif r < 0.85:
                    events.append({"type": "lc", "s": x, "dir": int(rng.choice([1, 2]))})
                elif r < 0.90:
                    events.append({"type": "forced", "s": x, "kind": str(rng.choice(["lead_brake", "cut_in", "emergency_stop"], p=[0.45, 0.35, 0.2]))})
                elif r < 0.93:
                    events.append({"type": "jam", "s": x, "s1": x + rng.uniform(300, 900)})
                    x += 900
                elif r < 0.935:
                    events.append({"type": "caution", "s": x, "ctype": 3})   # railroad crossing
            elif road == "rural":
                x += rng.uniform(500, 1500)
                r = rng.random()
                if r < 0.35:
                    ln = rng.uniform(150, 400)
                    R = rng.uniform(120, 400)
                    sign = rng.choice([-1, 1])
                    curves.append((x, x + ln, sign / R))
                    if R < 200:
                        events.append({"type": "caution", "s": x, "ctype": 4})
                    x += ln
                elif r < 0.45:
                    events.append({"type": "caution", "s": x, "ctype": int(rng.choice([5, 8]))})
                elif r < 0.6:
                    events.append({"type": "lc", "s": x, "dir": int(rng.choice([1, 2]))})
                elif r < 0.7:
                    events.append({"type": "stop_line", "s": x, "dwell": rng.uniform(5, 40)})
                elif r < 0.75:
                    events.append({"type": "forced", "s": x, "kind": str(rng.choice(["lead_brake", "cut_in"]))})
                elif r < 0.78:
                    events.append({"type": "caution", "s": x, "ctype": 3})
            else:
                x += rng.uniform(800, 3000)
                r = rng.random()
                if r < 0.45:
                    events.append({"type": "lc", "s": x, "dir": int(rng.choice([1, 2]))})
                elif r < 0.62:
                    ln = rng.uniform(300, 900)
                    R = rng.uniform(500, 1500)
                    curves.append((x, x + ln, rng.choice([-1, 1]) / R))
                    x += ln
                elif r < 0.70:
                    events.append({"type": "queue_tail", "s": x})
                elif r < 0.76:
                    events.append({"type": "forced", "s": x, "kind": str(rng.choice(["lead_brake", "cut_in"]))})
                elif r < 0.80:
                    events.append({"type": "caution", "s": x, "ctype": int(rng.choice([5, 6]))})
                elif r < 0.82 and kind == "long":
                    events.append({"type": "rest", "s": x, "dwell": rng.uniform(600, 1200)})
        s = e_end
        if road == "urban" and len(plan) > 1 and road is plan[0][0] and False:
            pass
    events.append({"type": "stop_line", "s": s - 30, "dwell": 5, "final": True})
    events.sort(key=lambda e: e["s"])
    rain = rng.random() < 0.25
    night = rng.random() < 0.3
    route = Route(length=s, seg_s=np.array(seg_s), seg_limit=np.array(seg_lim, float), seg_class=np.array(seg_cls),
                  curves=curves, events=events, rain=rain, heavy_rain=rain and rng.random() < 0.2, night=night,
                  temp_c=float(rng.uniform(-5, 30)))
    # mark expressway segment boundaries as turns? (keep IC entry/exit as TBT "straight/keep")
    return route


# ============================================================================ simulation
def _steer_from_kappa(kappa, v):
    return math.degrees(math.atan(L_WB * kappa * (1 + K_US * v * v))) * RATIO


def simulate(rng: np.random.Generator, style: DriverStyle | None = None, route: Route | None = None,
             max_minutes: float = 240.0):
    style = style or sample_style(rng)
    route = route or make_route(rng)
    n_max = int(max_minutes * 60 * BASE_HZ)

    # pre-allocate as python lists (faster appends than numpy for unknown length)
    rec = {k: [] for k in ["v", "a", "brake", "steer", "head", "sig", "s", "limit", "cls", "tbt_d", "tbt_t",
                           "c_t", "c_d", "school", "jam", "cruise", "fca", "lka", "daw", "abs", "gear", "forced"]}
    truth = {"turns": [], "lane_changes": [], "forced": [], "style": style.as_dict()}
    s = v = a = 0.0
    psi = rng.uniform(0, 360)
    gear = 0
    t_idx = 0
    events = [dict(e, passed=False, id=i) for i, e in enumerate(route.events)]
    # per-event behaviour draws
    for e in events:
        if e["type"] == "turn":
            e["signal"] = rng.random() < style.p_signal_turn
            e["lead"] = max(3.0, rng.normal(style.turn_lead_m, style.turn_lead_m * 0.35))
            e["v_turn"] = max(8, rng.normal(style.turn_speed_kph, 3)) / 3.6
            e["linger"] = rng.random() < 0.03
            e["right_pause"] = e["dir"] == 2 and rng.random() < style.p_right_pause
        if e["type"] == "lc":
            r = rng.random()
            if r < style.p_lc_signal * (1 - style.p_lc_signal_after):
                e["sig_mode"], e["lead_s"] = "before", max(0.2, rng.normal(style.lc_lead_s, 0.8))
            elif r < style.p_lc_signal:
                e["sig_mode"], e["lead_s"] = "after", -rng.uniform(0.3, 1.5)
            else:
                e["sig_mode"], e["lead_s"] = "none", float("nan")
            e["T"] = max(2.0, rng.normal(style.lc_duration, 0.6))
            e["W"] = rng.uniform(3.2, 3.6)
            e["linger"] = rng.random() < 0.04
        if e["type"] == "bump":
            e["v_req"] = (rng.uniform(15, 28) if rng.random() < style.p_bump_slow else rng.uniform(32, 45)) / 3.6
        if e["type"] == "caution":
            if e["ctype"] == 3:
                e["stop"] = rng.random() < style.p_rail_stop
            else:
                e["reduce"] = rng.uniform(0.12, 0.3) if rng.random() < style.p_caution_slow else rng.uniform(0.0, 0.06)
        if e["type"] == "queue_tail":
            e["hazard"] = rng.random() < style.p_hazard
        if e["type"] == "forced":
            e["mag"] = {"lead_brake": rng.uniform(3.5, 6.5), "cut_in": rng.uniform(2.2, 4.0),
                        "emergency_stop": rng.uniform(5.0, 7.5)}[e["kind"]]
            e["dur"] = rng.uniform(1.0, 2.5)
            rt = max(0.4, rng.normal(style.reaction_s, 0.2))
            e["rt"] = rt
            e["fca"] = rng.random() < ({"lead_brake": 0.6, "cut_in": 0.3, "emergency_stop": 0.85}[e["kind"]] + (0.25 if rt > 1.1 else 0))

    dwell = 0.0
    brake_latch = False
    latch_eid = None
    coast_dec: dict = {}
    resting = False
    start_hold = 3.0
    forced_active = None
    lc_active = None
    lc_pending_sig = None
    turn_active = None
    cruise_on = False
    cruise_set = 0.0
    hazard_timer = 0.0
    sig_linger = 0.0
    sig_linger_val = 0
    weave_state = 0.0
    fca_timer = 0.0
    lka_timer = 0.0
    daw_timer = 0.0
    abs_timer = 0.0
    speed_jit = rng.normal(0, style.speed_jitter)
    done = False
    final_park = 0.0
    trip_minutes = 0.0

    while t_idx < n_max and not done:
        lim, cls = route.at(s)
        kap_road = route.curvature(s)
        # ---------------------------------------------------------- upcoming constraints
        nxt = [e for e in events if not e["passed"] and -5 < e["s"] - s < 350]
        in_school = any(e["type"] == "school" and e["s"] <= s <= e["s1"] for e in events)
        in_jam = any(e["type"] == "jam" and e["s"] <= s <= e["s1"] for e in events)
        v_cruise = lim / 3.6 * (style.speed_factor + speed_jit)
        if route.rain:
            v_cruise *= style.rain_factor * (0.85 if route.heavy_rain else 1.0)
        if in_school:
            v_cruise = min(v_cruise, 30 / 3.6 * style.school_factor)
        if in_jam:
            v_cruise = min(v_cruise, (12 + 10 * math.sin(t_idx * DT / 9.0)) / 3.6)
        if abs(kap_road) > 1e-6:
            v_cruise = min(v_cruise, math.sqrt(style.curve_lat / abs(kap_road)))
        cons = []   # (distance, v_req, eid)
        for e in nxt:
            d = e["s"] - s
            tp = e["type"]
            if tp in ("stop_line", "rest"):
                cons.append((d, 0.0, e["id"]))
            elif tp == "turn":
                cons.append((d, 0.0 if e["stop_before"] or e.get("right_pause") else e["v_turn"], e["id"]))
            elif tp == "bump":
                cons.append((d, e["v_req"], e["id"]))
            elif tp == "school" and d > 0:
                cons.append((d, 30 / 3.6 * style.school_factor, e["id"]))
            elif tp == "caution":
                if e["ctype"] == 3:
                    cons.append((d - 5, 0.0 if e["stop"] else 18 / 3.6, e["id"]))
                elif d < 250:
                    cons.append((d, v_cruise * (1 - e["reduce"]), e["id"]))
            elif tp == "queue_tail" and d < 140:
                cons.append((d, 20 / 3.6, e["id"]))
        # speed-limit signs are visible ahead: a lower limit becomes a constraint at the sign
        k_next = int(np.searchsorted(route.seg_s, s, side="right"))
        if k_next < len(route.seg_s) and route.seg_s[k_next] - s < 200:
            nl = route.seg_limit[k_next] / 3.6 * (style.speed_factor + speed_jit)
            if route.rain:
                nl *= style.rain_factor
            cons.append((route.seg_s[k_next] - s, nl, -2))
        if turn_active is not None:
            ta = turn_active["e"]
            v_cruise = min(v_cruise, ta["v_turn"] * (1.1 if style.a_launch < 2.6 else 1.3))
        for s0, s1, kap in route.curves:
            if 0 < s0 - s < 300:
                cons.append((s0 - s, min(v_cruise, math.sqrt(style.curve_lat / abs(kap))), -1))
        a_req, a_req_v, a_req_eid = 0.0, None, None
        for d, vr, eid in cons:
            if v > vr + 0.05:
                ar = (v * v - vr * vr) / (2 * max(d, 0.3))
                if ar > a_req:
                    a_req, a_req_v, a_req_eid = ar, vr, eid
        # ---------------------------------------------------------- longitudinal decision
        brake_mode, coasting = False, False
        is_forced = 0
        if start_hold > 0:
            start_hold -= DT
            gear = 0 if start_hold > 1.0 else 3
            a_cmd = 0.0
            v = 0.0
        elif final_park > 0:
            final_park -= DT
            gear = 0
            a_cmd, v = 0.0, 0.0
            if final_park <= 0:
                done = True
        elif dwell > 0:
            dwell -= DT
            a_cmd, v = 0.0, 0.0
            brake_mode = True
        elif forced_active is not None:
            fe = forced_active
            fe["t"] += DT
            is_forced = 1
            if fe["t"] < fe["rt"]:
                a_cmd = min(a, 0.0) if fe["kind"] != "emergency_stop" else -DRAG
            elif fe["kind"] == "emergency_stop":
                a_cmd = -fe["mag"]
                brake_mode = True
                if v < 0.2:
                    forced_active = None
                    dwell = rng.uniform(3, 8)
                    v = 0.0
            elif fe["t"] < fe["rt"] + fe["dur"]:
                a_cmd = -fe["mag"]
                brake_mode = True
            else:
                forced_active = None
                a_cmd = 0.0
        else:
            onset = style.onset_frac * style.b_comfort
            if a_req <= 0.02 or a_req_eid != latch_eid:
                brake_latch = False
            if a_req_eid is not None and a_req_eid not in coast_dec:
                coast_dec[a_req_eid] = rng.random() < style.coaster
            if brake_latch or a_req >= onset:
                brake_latch, latch_eid = True, a_req_eid
                floor = 0.4 if (a_req_v is not None and a_req_v < 0.1 and v < 3) else 0.0
                a_cmd = -min(max(a_req * 1.02, floor), 9.0)
                brake_mode = True
                cruise_on = False
            elif a_req >= style.coast_onset_frac * style.b_comfort and coast_dec.get(a_req_eid, False):
                a_cmd = -(DRAG + 0.35)
                coasting = True
                cruise_on = False
            else:
                if cruise_on:
                    a_cmd = 0.6 * (cruise_set - v)
                else:
                    a_max = style.a_launch if v < 8 else style.a_launch * 0.55
                    a_cmd = float(np.clip(0.8 * (v_cruise - v), -1.2, a_max))
                    if a_cmd < -DRAG:
                        brake_mode = a_cmd < -0.8
                # cruise engagement on expressway
                if (not cruise_on and cls == 2 and abs(v - v_cruise) < 1.0 and not cons
                        and rng.random() < style.p_cruise * DT / 60):
                    cruise_on, cruise_set = True, v
        # ---------------------------------------------------------- dynamics
        a += (a_cmd - a) * min(1.0, DT / style.tau)
        v = max(0.0, v + a * DT)
        if v == 0.0 and a < 0:
            a = 0.0
        s += v * DT
        if v > 0.5:
            trip_minutes += DT / 60
        # ---------------------------------------------------------- event passing / triggers
        for e in nxt:
            if e["passed"]:
                continue
            d = e["s"] - s
            tp = e["type"]
            if tp in ("stop_line", "rest") and d < 4.0 and v < 1.0:
                e["passed"] = True
                v, a = 0.0, 0.0
                brake_latch = False
                if e.get("final"):
                    final_park = 6.0
                else:
                    dwell = e["dwell"]
                    resting = tp == "rest"
            elif tp == "turn":
                if (e["stop_before"] or e.get("right_pause")) and d < 4.0 and v < 1.0 and not e.get("stopped"):
                    brake_latch = False
                    e["stopped"] = True
                    v, a = 0.0, 0.0
                    dwell = e["dwell"] if e["stop_before"] else rng.uniform(0.8, 2.5)
                    e["stopped"] = True
                    # make the constraint go away after the dwell
                    e["stop_before"] = False
                    e["right_pause"] = False
                    e["v_turn"] = max(e["v_turn"], 4 / 3.6)
                if d <= 0 and turn_active is None:
                    turn_active = {"e": e, "s0": s, "i": t_idx}
                    e["passed"] = True
                    truth["turns"].append({"i_turn": t_idx, "dir": e["dir"], "id": e["id"]})
            elif tp == "lc" and lc_active is None and turn_active is None:
                lead = e["lead_s"] if e["sig_mode"] == "before" else 0.0
                if d - v * lead <= 0 and v > 25 / 3.6:
                    if e["sig_mode"] == "before" and lc_pending_sig is None and lead > 0:
                        lc_pending_sig = {"e": e, "t": 0.0}
                    if d <= 0:
                        lc_active = {"e": e, "t": 0.0, "i0": t_idx}
                        e["passed"] = True
                        lc_pending_sig = None
                elif d < -50:
                    e["passed"] = True
                    if lc_pending_sig is not None and lc_pending_sig["e"] is e:
                        lc_pending_sig = None
            elif tp == "forced" and d <= 0:
                e["passed"] = True
                if v > 15 / 3.6:
                    forced_active = {"kind": e["kind"], "mag": e["mag"], "dur": e["dur"], "rt": e["rt"], "t": 0.0, "i0": t_idx}
                    cruise_on = False
                    if e["fca"]:
                        fca_timer = rng.uniform(0.6, 1.6)
                    truth["forced"].append({"i0": t_idx, "kind": e["kind"], "fca": e["fca"],
                                            "i1": t_idx + int((e["rt"] + e["dur"] + 4) * BASE_HZ)})
                else:
                    pass
            elif tp == "queue_tail" and d <= 0:
                e["passed"] = True
            elif tp == "queue_tail" and d < 140 and e.get("hazard") and hazard_timer <= 0 and not e.get("hz_done"):
                if a < -1.0:
                    hazard_timer = rng.uniform(3, 6)
                    e["hz_done"] = True
            elif tp in ("bump", "school", "caution") and d < -5:
                e["passed"] = True
        if forced_active is not None and forced_active["kind"] == "emergency_stop" and truth["forced"]:
            truth["forced"][-1]["i1"] = max(truth["forced"][-1]["i1"], t_idx + int(4 * BASE_HZ))
        # ---------------------------------------------------------- lateral
        kap = kap_road
        if turn_active is not None:
            e = turn_active["e"]
            arc = math.pi / 2 * e["R"]
            ds = s - turn_active["s0"]
            if ds < arc:
                kap += (1 if e["dir"] == 1 else -1) / e["R"]
            else:
                turn_active = None
                if e["signal"]:
                    sig_linger = 15.0 if e["linger"] else rng.uniform(0.3, 1.0)
                    sig_linger_val = 1 if e["dir"] == 1 else 2
        if lc_active is not None:
            e = lc_active["e"]
            lc_active["t"] += DT
            tau = lc_active["t"] / e["T"]
            if tau < 1.0 and v > 1:
                ydd = (2 * math.pi * e["W"] / e["T"] ** 2) * math.sin(2 * math.pi * tau)
                kap += (1 if e["dir"] == 1 else -1) * ydd / (v * v)
            else:
                truth["lane_changes"].append({"i0": lc_active["i0"], "i1": t_idx, "dir": e["dir"],
                                              "duration": e["T"], "sig_mode": e["sig_mode"], "id": e["id"]})
                if e["sig_mode"] != "none":
                    sig_linger = 12.0 if e["linger"] else rng.uniform(0.2, 0.8)
                    sig_linger_val = e["dir"]
                lc_active = None
        weave_state += (-weave_state / 2.0) * DT + style.weave * 0.6 * math.sqrt(DT) * rng.normal()
        if style.drowsy and trip_minutes > 25:
            weave_state += style.weave * 0.4 * math.sqrt(DT) * rng.normal()
        steer = _steer_from_kappa(kap, v) + weave_state
        kap_eff = math.tan(math.radians(steer / RATIO)) / (L_WB * (1 + K_US * v * v))
        psi = (psi + math.degrees(v * kap_eff * DT)) % 360
        # ---------------------------------------------------------- blinker logic
        sig = 0
        upcoming_turn = next((e for e in events if e["type"] == "turn" and not e["passed"] and e["s"] - s < 400), None)
        if hazard_timer > 0:
            hazard_timer -= DT
            sig = 3
        elif upcoming_turn is not None and upcoming_turn["signal"] and upcoming_turn["s"] - s <= upcoming_turn["lead"]:
            sig = 1 if upcoming_turn["dir"] == 1 else 2
        elif turn_active is not None and turn_active["e"]["signal"]:
            sig = 1 if turn_active["e"]["dir"] == 1 else 2
        elif lc_pending_sig is not None:
            sig = lc_pending_sig["e"]["dir"]
        elif lc_active is not None and lc_active["e"]["sig_mode"] == "before":
            sig = lc_active["e"]["dir"]
        elif lc_active is not None and lc_active["e"]["sig_mode"] == "after" and lc_active["t"] >= -lc_active["e"]["lead_s"]:
            sig = lc_active["e"]["dir"]
        elif sig_linger > 0:
            sig_linger -= DT
            sig = sig_linger_val
        # ---------------------------------------------------------- telltales
        km_step = v * DT / 1000
        if fca_timer <= 0 and rng.random() < style.fca_per_km * km_step and v > 8:
            fca_timer = rng.uniform(0.5, 1.2)
        if lka_timer <= 0 and rng.random() < style.lka_per_km * km_step * (1 + abs(weave_state)) and v > 16:
            lka_timer = rng.uniform(0.8, 2.0)
        if style.drowsy and trip_minutes > 30 and daw_timer <= 0 and rng.random() < 0.0004:
            daw_timer = rng.uniform(3, 6)
        if a < -7.0 and abs_timer <= 0:
            abs_timer = 1.0
        fca_on, lka_on, daw_on, abs_on = int(fca_timer > 0), int(lka_timer > 0), int(daw_timer > 0), int(abs_timer > 0)
        fca_timer -= DT; lka_timer -= DT; daw_timer -= DT; abs_timer -= DT
        # ---------------------------------------------------------- navigation channels
        nt = next((e for e in events if e["type"] == "turn" and e["s"] - s > -2 and (not e["passed"] or turn_active and turn_active["e"] is e)), None)
        tbt_d = (nt["s"] - s) if nt is not None and nt["s"] - s < 2000 else float("nan")
        tbt_t = nt["dir"] if nt is not None and np.isfinite(tbt_d) else 0
        cp = next((e for e in events if e["type"] in ("caution", "bump") and -100 < e["s"] - s < 600), None)
        c_t = (cp["ctype"] if cp["type"] == "caution" else 2) if cp else 0
        c_d = (cp["s"] - s) if cp else float("nan")
        brake = 0.0
        if brake_mode or (a < -(DRAG + 0.45) and not coasting):
            brake = float(np.clip((-a - DRAG) / 0.085, 3, 100))
        if v == 0.0 and gear == 3:
            brake = max(brake, 25.0)
        # ---------------------------------------------------------- record
        rec["v"].append(v); rec["a"].append(a); rec["brake"].append(brake); rec["steer"].append(steer)
        rec["head"].append(psi); rec["sig"].append(sig); rec["s"].append(s); rec["limit"].append(lim)
        rec["cls"].append(cls); rec["tbt_d"].append(tbt_d); rec["tbt_t"].append(tbt_t); rec["c_t"].append(c_t)
        rec["c_d"].append(c_d); rec["school"].append(int(in_school)); rec["jam"].append(2 if in_jam else 0)
        rec["cruise"].append(int(cruise_on)); rec["fca"].append(fca_on); rec["lka"].append(lka_on)
        if resting and dwell <= 0:
            resting = False
        rec["daw"].append(daw_on); rec["abs"].append(abs_on); rec["gear"].append(0 if (resting and dwell > 0) else gear)
        rec["forced"].append(is_forced)
        t_idx += 1

    n = len(rec["v"])
    trip = empty_trip(n)
    trip["speed_kph"][:] = np.array(rec["v"]) * 3.6
    # The car app only sees the pedal SWITCH (pressed / not pressed), never the pedal position.
    trip["brake_on"][:] = (np.array(rec["brake"]) > 1.0).astype(np.int8)
    truth["brake_pct"] = np.array(rec["brake"], dtype=np.float32)       # kept for analysis only
    trip["steer_deg"][:] = rec["steer"]
    # navigation / Android Location bearing: compass degrees, 0 = north, CLOCKWISE (psi above is counter-clockwise)
    trip["heading_deg"][:] = (-np.array(rec["head"])) % 360
    trip["turn_signal"][:] = rec["sig"]
    trip["gear"][:] = rec["gear"]
    trip["odo_km"][:] = 10000 + np.array(rec["s"]) / 1000
    trip["cruise_active"][:] = rec["cruise"]
    trip["drive_mode"][:] = 1
    trip["wiper_active"][:] = int(route.rain)
    trip["night"][:] = int(route.night)
    lights_on = route.night or (route.rain and rng.random() < style.p_lights_rain)
    trip["low_beam"][:] = int(lights_on)
    trip["outside_temp_c"][:] = route.temp_c
    occ = int(rng.choice([1, 1, 2, 2, 3, 4]))
    trip["seats_occupied"][:] = occ
    trip["belts_buckled"][:] = occ if rng.random() < style.p_belts_all else occ - 1
    trip["tt_fca"][:] = rec["fca"]; trip["tt_lka"][:] = rec["lka"]; trip["tt_lca"][:] = 0
    trip["tt_daw"][:] = rec["daw"]; trip["tt_abs_esc"][:] = rec["abs"]
    trip["tt_icy"][:] = int(route.temp_c < 3 and route.rain)
    trip["tt_tpms"][:] = 0
    trip["speed_limit_kph"][:] = rec["limit"]
    trip["road_class"][:] = rec["cls"]
    trip["tbt_dist_m"][:] = rec["tbt_d"]
    trip["tbt_type"][:] = rec["tbt_t"]
    trip["caution_type"][:] = rec["c_t"]
    trip["caution_dist_m"][:] = rec["c_d"]
    trip["school_zone"][:] = rec["school"]
    trip["camera_overspeed"][:] = ((np.array(rec["school"]) == 1) & (np.array(rec["v"]) * 3.6 > 33)).astype(np.int8)
    trip["congestion"][:] = rec["jam"]
    trip["nav_active"][:] = 1
    truth["true_accel"] = np.array(rec["a"], dtype=np.float32)
    truth["forced_mask"] = np.array(rec["forced"], dtype=np.int8)
    truth["route"] = {"kind_len_km": route.length / 1000, "rain": route.rain, "night": route.night}
    return trip, truth


# ============================================================================ sensor model
def _lag(x, rng, max_lag_s):
    """ON_CHANGE callback latency: shift a held signal later by 0..max_lag_s."""
    k = int(round(rng.uniform(0, max_lag_s) * 10))
    if k <= 0:
        return x
    return np.concatenate([np.full(k, x[0]), x[:-k]])


def _hold(x, hz, rng):
    """Event channel updated at ~hz and held in between (NaviHelper distances)."""
    if hz >= 10:
        return x
    step = max(1, int(round(10 / hz)))
    off = int(rng.integers(0, step))
    idx = np.arange(len(x))
    src = np.maximum(0, ((idx - off) // step) * step + off)
    src = np.where(idx < off, 0, src)
    return x[np.minimum(src, len(x) - 1)]


def observe(rng: np.random.Generator, clean: dict, profile: dict | None = None) -> tuple[dict, dict]:
    """Turn a clean trip into what the app would actually receive."""
    p = profile or {}
    obs = {k: v.copy() for k, v in clean.items()}
    n = len(obs["t"])
    speed_hz = p.get("speed_hz") or float(rng.choice([10, 10, 10, 10, 5, 1, 1], p=[0.2, 0.2, 0.2, 0.2, 0.05, 0.1, 0.05]))
    bias = rng.uniform(p.get("min_speed_bias", 0.995), p.get("max_speed_bias", 1.03))   # never under-reads by > 0.5 %
    v = obs["speed_kph"].astype(np.float64) * bias + rng.normal(0, 0.15, n)
    q = rng.choice([0.1, 0.1, 1.0])
    v = np.maximum(0, np.round(v / q) * q)
    step = int(round(10 / speed_hz))
    mask = np.ones(n, bool)
    mask[::step] = False
    v[mask] = np.nan
    n_drop = rng.poisson(p.get("dropouts", 0.4))
    for _ in range(n_drop):
        a0 = int(rng.integers(0, max(1, n - 300)))
        v[a0:a0 + int(rng.uniform(5, 25) * 10)] = np.nan
    obs["speed_kph"] = v.astype(np.float32)
    # ---- steering (Vehicle SDK SteeringWheel API, ON_CHANGE). OFFICIAL: FRONT-WHEEL angle in degrees, leftward
    #      NEGATIVE, "not the rotation angle of the steering wheel". The simulator's clean channel is a
    #      steering-wheel angle (left-positive) -> convert. A minority of trips deliver the steering-WHEEL angle
    #      (emulator Virtual Console slider) or the opposite sign, to keep the engine's auto-detection honest.
    #      Resolution of the real signal is unknown -> randomised; car geometry vs the engine's model +-10 %.
    ratio_err = float(p.get("steer_ratio_factor") or rng.uniform(0.9, 1.1))
    wheel_unit = bool(p.get("steer_wheel_unit", rng.random() < 0.15))
    sign = int(p.get("steer_sign") or (-1 if rng.random() < 0.9 else 1))        # -1 = left negative (official)
    if wheel_unit:
        sq = float(p.get("steer_q") or rng.choice([1.0, 0.1]))
        st = obs["steer_deg"].astype(np.float64) * ratio_err + rng.normal(0, p.get("steer_noise", 0.3), n)
        st = np.clip(np.round(st / sq) * sq, -540, 540)
    else:
        sq = float(p.get("steer_q") or rng.choice([0.1, 0.05, 0.01, 0.5, 1.0], p=[0.45, 0.2, 0.15, 0.1, 0.1]))
        st = obs["steer_deg"].astype(np.float64) / 13.5 * ratio_err + rng.normal(0, p.get("steer_noise", 0.3) / 13.5, n)
        st = np.clip(np.round(st / sq) * sq, -45, 45)
    obs["steer_deg"] = _lag(st * (1 if sign > 0 else -1), rng, p.get("max_lag_s", 0.3)).astype(np.float32)
    # capability bounds (checkSteeringWheelAngleCapabilities); sometimes the call fails -> unknown
    obs["steer_bound_deg"][:] = np.nan if rng.random() < p.get("p_bound_unknown", 0.15) else (540.0 if wheel_unit else float(rng.uniform(35, 45)))
    # ---- brake pedal switch + turn signal: ON_CHANGE callbacks with IPC latency
    obs["brake_on"] = _lag(obs["brake_on"].astype(np.float64), rng, p.get("max_lag_s", 0.3)).astype(np.int8)
    obs["turn_signal"] = _lag(obs["turn_signal"].astype(np.float64), rng, p.get("max_lag_s", 0.3)).astype(np.int8)
    # ---- navigation distances arrive as events (~1 Hz), held in between
    nav_hz = float(p.get("nav_hz") or rng.choice([1.0, 1.0, 2.0, 10.0]))
    for ch in ("tbt_dist_m", "caution_dist_m"):
        obs[ch] = _hold(obs[ch], nav_hz, rng).astype(np.float32)
    # ---- telltales: OFFICIAL API reference: Safety.getTelltalesInfo / registerTelltales need
    #      PRIVATE_CAR_SAFETY (signature|privileged) -> a third-party app NEVER sees warning lamps.
    #      Low tyre pressure comes from the Tire API (CAR_SAFETY, normal) and stays.
    for ch in ("tt_fca", "tt_lka", "tt_lca", "tt_daw", "tt_icy", "tt_abs_esc"):
        obs[ch][:] = -1
    fca_visible = False
    # ---- turn signal: OFFICIAL TurnSignalValue = OFF / RIGHT / LEFT (no hazard value)
    obs["turn_signal"] = np.where(obs["turn_signal"] == 3, 0, obs["turn_signal"]).astype(np.int8)
    # ---- caution points: OFFICIAL CautionPointType has no sharp-curve and no icy-road type -> never delivered
    gone = np.isin(obs["caution_type"], (4, 7))
    obs["caution_type"] = np.where(gone, 0, obs["caution_type"]).astype(np.int8)
    obs["caution_dist_m"] = np.where(gone, np.nan, obs["caution_dist_m"]).astype(np.float32)
    # ---- cruise / SCC state (DrivingMode): may be unsupported on some models
    cruise_known = rng.random() < p.get("p_cruise_known", 0.85)
    if not cruise_known:
        obs["cruise_active"][:] = -1
    # ---- wiper: Wiper.getCurrentWiperState (OFFICIAL enum OTHER/OFF/ON/SERVICE = the wipers' actual operation, like
    #      AAOS WINDSHIELD_WIPERS_STATE) -> rain evidence also with the switch on AUTO. Unsupported / OTHER -> -1.
    if rng.random() < p.get("p_wiper_unknown", 0.1):
        obs["wiper_active"][:] = -1
    # ---- odometer resolution unknown (0.1 or 1 km)
    oq = float(rng.choice([0.1, 1.0]))
    obs["odo_km"] = (np.floor(obs["odo_km"].astype(np.float64) / oq) * oq).astype(np.float32)
    has_heading = rng.random() < p.get("p_heading", 0.6)
    if has_heading:
        h = obs["heading_deg"] + rng.normal(0, 0.8, n)
        hm = np.ones(n, bool); hm[::10] = False
        h[hm] = np.nan
        obs["heading_deg"] = (h % 360).astype(np.float32)
    else:
        obs["heading_deg"][:] = np.nan
    nav = rng.random() < p.get("p_nav", 0.75)
    if not nav:
        obs["tbt_dist_m"][:] = np.nan
        obs["tbt_type"][:] = 0
        obs["nav_active"][:] = 0
    if rng.random() > p.get("p_navdata", 0.92):
        for ch in ("speed_limit_kph", "caution_dist_m"):
            obs[ch][:] = np.nan
        obs["caution_type"][:] = 0
        obs["school_zone"][:] = 0
    if rng.random() < 0.05:
        obs["seats_occupied"][:] = -1
        obs["belts_buckled"][:] = -1
    meta = {"speed_hz": speed_hz, "speed_bias": bias, "has_heading": has_heading, "nav": nav, "dropouts": n_drop,
            "steer_sign": sign, "steer_ratio_factor": ratio_err, "steer_q": sq, "steer_wheel_unit": wheel_unit, "nav_hz": nav_hz,
            "fca_visible": bool(fca_visible), "cruise_known": bool(cruise_known), "odo_q": oq}
    return obs, meta
