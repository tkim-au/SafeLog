"""
Ground-truth judgments and engine-vs-truth matching.

Truth = the SAME judges applied to perfect signals, with the simulator's privileged
knowledge injected: which manoeuvres really were lane changes / turns, and which hard
stops were forced by another vehicle. Window habits use the same windows on clean data.
"""
from __future__ import annotations

from collections import defaultdict

import numpy as np

from scoring_engine.derive import derive
from scoring_engine.habits import (ABSTAIN, MISS, SUCCESS, Candidate, detect_caution_points, detect_fca_onsets,
                                   detect_hazard, detect_launches, detect_school, detect_stops, judge_candidates,
                                   run_window_habits)

HZ = 10


def truth_judgments(clean: dict, truth: dict, cfg: dict):
    cfg = dict(cfg, _truth=True)
    # the simulator's clean channel is a steering-WHEEL angle, left-positive: say so, never let truth guess
    cfg["vehicle"] = {"steer_unit": "steering_wheel", "steer_sign": 1, "ratio_scale": None}
    D = derive(clean, cfg)
    nofca = dict(clean)
    nofca["tt_fca"] = np.zeros_like(clean["tt_fca"])
    forced = [(f["i0"] - int(2 * HZ), f["i1"]) for f in truth["forced"]]

    def is_forced(i0, i1):
        return any(a < i1 and i0 < b for a, b in forced)

    js = []
    stops = detect_stops(nofca, D, cfg)
    for c in stops:
        c.meta["forced"] = is_forced(c.i0, c.i1)
        c.meta["truth_kin"] = True
    js += judge_candidates(nofca, D, cfg, "stop", stops)
    js += judge_candidates(nofca, D, cfg, "launch", detect_launches(nofca, D, cfg))
    turns = []
    for t in truth["turns"]:
        i = t["i_turn"]
        turns.append(Candidate("turn", i, max(0, i - 600), min(len(clean["t"]), i + 80),
                               {"dir": t["dir"], "source": "truth", "truth": True}))
    js += judge_candidates(nofca, D, cfg, "turn", turns)
    lcs = []
    for l in truth["lane_changes"]:
        lcs.append(Candidate("lane_change", l["i0"], l["i0"], l["i1"],
                             {"dir": l["dir"], "duration_s": l["duration"], "truth": True,
                              "near_turn": False, "curving": False}))
    js += judge_candidates(nofca, D, cfg, "lane_change", lcs)
    js += judge_candidates(nofca, D, cfg, "school", detect_school(clean, D, cfg))
    js += judge_candidates(nofca, D, cfg, "caution", detect_caution_points(clean, D, cfg))
    js += judge_candidates(clean, D, cfg, "hazard", detect_hazard(clean, D, cfg))
    js += judge_candidates(clean, D, cfg, "fca", detect_fca_onsets(clean, D, cfg))
    js += run_window_habits(clean, D, cfg)
    return js


TOL_S = defaultdict(lambda: 4.0, {"smooth_turn": 6.0, "turn_signal_turn": 6.0, "right_turn_pause": 6.0, "blinker_cancel": 6.0, "lane_change_signal": 3.0, "smooth_lane_change": 3.0, "following_margin": 90.0,
                                  "lane_keeping": 90.0, "rest_breaks": 120.0, "seat_belts": 5.0, "speed_limit": 1.0,
                                  "rain_speed": 1.0, "headlights": 1.0, "steady_cruise": 1.0})


def match(engine_js, truth_js):
    """Greedy nearest-time matching per habit. Returns list of (engine_j | None, truth_j | None)."""
    by_e, by_t = defaultdict(list), defaultdict(list)
    for j in engine_js:
        by_e[j.habit].append(j)
    for j in truth_js:
        by_t[j.habit].append(j)
    pairs = []
    for h in set(by_e) | set(by_t):
        E = sorted(by_e[h], key=lambda j: j.t_ref)
        T = sorted(by_t[h], key=lambda j: j.t_ref)
        used = set()
        tol = TOL_S[h]
        for e in E:
            best, bd = None, tol + 1e-9
            for k, t in enumerate(T):
                if k in used:
                    continue
                d = abs(t.t_ref - e.t_ref)
                if d <= bd:
                    best, bd = k, d
            if best is None:
                pairs.append((e, None))
            else:
                used.add(best)
                pairs.append((e, T[best]))
        for k, t in enumerate(T):
            if k not in used:
                pairs.append((None, t))
    return pairs


def habit_metrics(pairs):
    """Per-habit praise precision / recall and abstain behaviour."""
    m = defaultdict(lambda: defaultdict(int))
    for e, t in pairs:
        h = (e or t).habit
        if e is not None:
            m[h]["engine_" + {SUCCESS: "success", MISS: "miss", ABSTAIN: "abstain"}[e.outcome]] += 1
        if t is not None:
            m[h]["truth_" + {SUCCESS: "success", MISS: "miss", ABSTAIN: "abstain"}[t.outcome]] += 1
        if e is not None and e.outcome == SUCCESS:
            if t is not None and t.outcome == SUCCESS:
                m[h]["tp"] += 1
            elif t is None:
                m[h]["fp_phantom"] += 1         # praised an opportunity that never happened
            elif t.outcome == MISS:
                m[h]["fp_wrong"] += 1           # praised a real failure
            else:
                m[h]["fp_forced"] += 1          # praised something that should have been abstained
        if e is not None and e.outcome == MISS and t is not None and t.outcome == SUCCESS:
            m[h]["unfair_miss"] += 1
    out = {}
    for h, d in m.items():
        es = d["engine_success"]
        ts = d["truth_success"]
        fp = d["fp_phantom"] + d["fp_wrong"] + d["fp_forced"]
        out[h] = dict(d)
        out[h]["praise_precision"] = d["tp"] / es if es else float("nan")
        out[h]["praise_recall"] = d["tp"] / ts if ts else float("nan")
        out[h]["false_praise"] = fp
    return out
