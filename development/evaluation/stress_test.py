"""
Stress test: does praise stay trustworthy when conditions get hard?

Every condition uses FIXED seeds, so results are comparable between model versions.
Pass = praise precision >= 0.97 (the release bar) and no crash. A fuzz test also feeds
garbage (NaN storms, out-of-range values, unknown enums, empty and 1-sample trips) to make sure
the engine never throws and never praises without valid data.

    python -m evaluation.stress_test --gate model_training/checkpoints/gate.pt --trips 40 --workers 8
"""
from __future__ import annotations

import argparse
import json
import math
import time
import traceback
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

CONDITIONS = {
    #  name: (archetype, sensor profile, route modification)
    "baseline":           (None, {}, None),
    "speed_1hz":          (None, {"speed_hz": 1}, None),
    "heavy_dropouts":     (None, {"dropouts": 4.0}, None),
    "no_heading_no_nav":  (None, {"p_heading": 0.0, "p_nav": 0.0}, None),
    "no_nav_data":        (None, {"p_navdata": 0.0}, None),
    "noisy_steering_x4":  (None, {"steer_noise": 1.2}, None),
    "cluster_bias_6pct":  (None, {"max_speed_bias": 1.06}, None),
    "rain_night":         (None, {}, {"rain": True, "night": True}),
    "heavy_rain":         (None, {}, {"rain": True, "heavy_rain": True}),
    "cut_in_storm":       (None, {}, {"extra_forced_per_km": 1.5}),
    "expressway":         (None, {}, {"kind": "mixed"}),
    "rural_curves":       (None, {}, {"kind": "rural"}),
    "aggressive_drivers": ("aggressive", {}, None),
    "novice_drivers":     ("novice", {}, None),
    "drowsy_drivers":     ("drowsy", {}, None),
    "careful_drivers":    ("careful", {}, None),
    # --- data-contract risks found in the Pleos audit (data_spec/DATA_AUDIT.md)
    "steer_official":      (None, {"steer_sign": -1, "steer_wheel_unit": False, "steer_q": 0.1}, None),
    "steer_sign_positive": (None, {"steer_sign": 1, "steer_wheel_unit": False}, None),
    "steer_wheel_unit":    (None, {"steer_wheel_unit": True}, None),
    "steer_coarse_1deg":   (None, {"steer_wheel_unit": False, "steer_q": 1.0}, None),
    "car_geometry_+10pct": (None, {"steer_ratio_factor": 1.1, "steer_wheel_unit": False}, None),
    "car_geometry_-10pct": (None, {"steer_ratio_factor": 0.9, "steer_wheel_unit": False}, None),
    "cruise_state_unknown": (None, {"p_cruise_known": 0.0}, None),
    "nav_updates_1hz":     (None, {"nav_hz": 1.0}, None),
    "wiper_unknown_rain":  (None, {"p_wiper_unknown": 1.0}, {"rain": True}),
    "slow_callbacks":      (None, {"max_lag_s": 0.6}, None),
    "worst_case":         ("aggressive", {"speed_hz": 1, "dropouts": 3.0, "p_nav": 0.3, "steer_noise": 1.0, "steer_sign": 1,
                            "nav_hz": 1.0, "max_lag_s": 0.5},
                           {"rain": True, "night": True, "extra_forced_per_km": 1.0}),
}
SEED_BASE = 7_000_000          # never used for training (train_forever uses 10_000_000+, holdout 9_000_000+)
RELEASE_BAR = 0.97


def _quiet():
    import warnings
    warnings.filterwarnings("ignore", category=RuntimeWarning)
    np.seterr(all="ignore")


def _run_condition(args):
    name, n_trips, gate_path = args
    _quiet()
    from evaluation.truth import habit_metrics, match, truth_judgments
    from scoring_engine.engine import SafeLogEngine
    from simulation.generate_dataset import _one
    arche, prof, route = CONDITIONS[name]
    gate = None
    if gate_path:
        from model_training.gate import load_gate
        gate = load_gate(gate_path)
    eng = SafeLogEngine(ml_gate=gate)
    pairs, crashes, t_engine, hours = [], 0, 0.0, 0.0
    offset = list(CONDITIONS).index(name) * 10_000
    for k in range(n_trips):
        rec = _one(SEED_BASE + offset + k, arche, prof, route)
        hours += len(rec["obs"]["t"]) / 36000
        try:
            t0 = time.perf_counter()
            res = eng.run_trip(rec["obs"])
            t_engine += time.perf_counter() - t0
            pairs += match(res["judgments"], truth_judgments(rec["clean"], rec["truth"], eng.cfg))
        except Exception:
            crashes += 1
            traceback.print_exc()
    m = habit_metrics(pairs)
    held = {}
    for e, t in pairs:                      # habits held back by the release gate: what WOULD the driver see?
        if e is not None and e.meta.get("held"):
            h = held.setdefault(e.habit, [0, 0])
            h[0] += 1
            h[1] += int(t is not None and t.outcome == 1)
    tp = sum(d.get("tp", 0) for d in m.values())
    es = sum(d.get("engine_success", 0) for d in m.values())
    ts = sum(d.get("truth_success", 0) for d in m.values())
    fp = sum(d.get("false_praise", 0) for d in m.values())
    prec = tp / es if es else float("nan")
    worst = sorted(((d["praise_precision"], h) for h, d in m.items()
                    if d.get("engine_success", 0) >= 10 and not math.isnan(d["praise_precision"])))[:3]
    return name, {"trips": n_trips, "hours": round(hours, 1), "praises": es, "precision": prec,
                  "recall": tp / ts if ts else float("nan"), "false_praise_per_100_trips": 100 * fp / max(n_trips, 1),
                  "crashes": crashes, "engine_ms_per_trip": 1000 * t_engine / max(n_trips, 1),
                  "worst_habits": [(h, round(p, 3)) for p, h in worst],
                  "held_habits": {h: {"n": v[0], "precision": v[1] / v[0]} for h, v in held.items()},
                  "pass": bool(crashes == 0 and (math.isnan(prec) or prec >= RELEASE_BAR))}


def fuzz_test(n_cases=200, seed=0):
    """Garbage in must never crash the engine, and must never produce praise from invalid speed."""
    from data_spec.signals import CHANNELS, empty_trip
    from scoring_engine.engine import SafeLogEngine
    _quiet()
    rng = np.random.default_rng(seed)
    eng = SafeLogEngine()
    crashes, bad_praise = 0, 0
    for k in range(n_cases):
        n = int(rng.choice([0, 1, 2, 5, 50, 600, 3000]))
        trip = empty_trip(max(n, 1))
        if n == 0:
            trip = {c: v[:0] for c, v in trip.items()}
        for c, (dt, *_r) in CHANNELS.items():
            if c == "t" or n == 0:
                continue
            mode = rng.integers(0, 4)
            if dt.startswith("f"):
                vals = rng.normal(0, 200, n)
                if mode == 0:
                    vals[:] = np.nan
                elif mode == 1:
                    vals[rng.random(n) < 0.5] = np.nan
                elif mode == 2:
                    vals = rng.choice([np.inf, -np.inf, 1e9, -1e9, 0.0], n)
                trip[c][:n] = vals
            else:
                trip[c][:n] = rng.integers(-128, 127, n) if mode < 2 else rng.integers(-1, 4, n)
        if n > 2 and rng.random() < 0.1:                  # corrupt recording: one channel cut short
            c = str(rng.choice([c for c in CHANNELS if c != "t"]))
            trip[c] = trip[c][: n // 2]
        if n > 2 and rng.random() < 0.05:                 # broken clock
            trip["t"] = trip["t"].copy(); trip["t"][-1] = np.nan
        all_nan_speed = n == 0 or not np.isfinite(trip["speed_kph"]).any()
        try:
            with np.errstate(all="ignore"):
                res = eng.run_trip(trip)
            kin = [j for j in res["judgments"] if j.outcome == 1 and j.habit in ("smooth_stop", "smooth_launch", "speed_limit")]
            if all_nan_speed and kin:
                bad_praise += 1
        except Exception:
            crashes += 1
    return {"cases": n_cases, "crashes": crashes, "praise_without_speed": bad_praise,
            "pass": crashes == 0 and bad_praise == 0}


def run_stress(gate_path=None, trips=40, workers=4, out="evaluation/reports", tag=None, conditions=None, log=print):
    names = conditions or list(CONDITIONS)
    t0 = time.time()
    results = {}
    with ProcessPoolExecutor(max_workers=workers) as ex:
        for name, r in ex.map(_run_condition, [(n, trips, gate_path) for n in names]):
            results[name] = r
            log(f"  {name:20s} precision {r['precision'] * 100:6.2f}%  recall {r['recall'] * 100:5.1f}%  "
                f"false/100 trips {r['false_praise_per_100_trips']:5.1f}  crashes {r['crashes']}  {'PASS' if r['pass'] else 'FAIL'}")
    fz = fuzz_test()
    log(f"  {'fuzz (garbage input)':20s} crashes {fz['crashes']}  praise-without-speed {fz['praise_without_speed']}  {'PASS' if fz['pass'] else 'FAIL'}")
    rep = {"gate": gate_path, "trips_per_condition": trips, "seconds": round(time.time() - t0, 1),
           "conditions": results, "fuzz": fz,
           "all_pass": all(r["pass"] for r in results.values()) and fz["pass"]}
    Path(out).mkdir(parents=True, exist_ok=True)
    name = f"stress_{tag}" if tag else "stress"
    Path(out, f"{name}.json").write_text(json.dumps(rep, indent=2, default=float), encoding="utf-8")
    lines = [f"### Stress test ({trips} trips per condition, gate: {gate_path or 'none'})", "",
             "| condition | trips | hours | praises | precision | recall | false / 100 trips | crashes | worst habits | result |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---|---|"]
    for n, r in results.items():
        lines.append(f"| {n} | {r['trips']} | {r['hours']} | {r['praises']} | {r['precision'] * 100:.2f}% | {r['recall'] * 100:.1f}% | "
                     f"{r['false_praise_per_100_trips']:.1f} | {r['crashes']} | {', '.join(f'{h} {p}' for h, p in r['worst_habits'])} | "
                     f"{'PASS' if r['pass'] else 'FAIL'} |")
    lines.append(f"| fuzz: garbage input | {fz['cases']} cases | | | | | | {fz['crashes']} | praise without speed: {fz['praise_without_speed']} | "
                 f"{'PASS' if fz['pass'] else 'FAIL'} |")
    lines += ["", "Habits held back by the release gate (not shown to drivers) - precision they WOULD have:", "",
              "| condition | " + " | ".join(sorted({h for r in results.values() for h in r["held_habits"]})) + " |"]
    hh = sorted({h for r in results.values() for h in r["held_habits"]})
    lines.append("|---|" + "---:|" * len(hh))
    for n, r in results.items():
        lines.append(f"| {n} | " + " | ".join(f"{r['held_habits'][h]['precision'] * 100:.1f}% ({r['held_habits'][h]['n']})"
                                             if h in r["held_habits"] else "" for h in hh) + " |")
    Path(out, f"{name}.md").write_text("\n".join(lines), encoding="utf-8")
    log(f"  stress test {'ALL PASS' if rep['all_pass'] else 'has FAILURES'} -> {Path(out, name + '.md')}")
    return rep


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--gate", default=None)
    ap.add_argument("--trips", type=int, default=40)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--only", nargs="*", default=None, help="subset of conditions")
    ap.add_argument("--out", default="evaluation/reports")
    a = ap.parse_args(argv)
    run_stress(a.gate, a.trips, a.workers, a.out, conditions=a.only)


if __name__ == "__main__":
    main()
