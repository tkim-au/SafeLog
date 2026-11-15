"""
Export simulated trips for the Android app.

  demo    -> android/app/src/main/assets/demo/<name>.csv.gz + <name>.json   (emulator demo replay)
  parity  -> android/core/src/test/resources/parity/<name>.csv.gz + <name>.expected.json
             (the Python engine's judgments; the Kotlin port must reproduce them exactly)

Run from anywhere (needs the engine repo, i.e. development):
    python backend/tools/export_trips.py --engine-root development --android android

CSV format (data contract v3, one row per 0.1 s): header = data_spec.signals.CHANNELS order,
empty cell = NaN (float) and -1 is written for unknown ints. Floats are written as the exact
value the engine sees (float32 -> shortest repr of the same double), so Python and Kotlin read
identical numbers.
"""
from __future__ import annotations

import argparse
import gzip
import io
import json
import math
import warnings
from pathlib import Path

import numpy as np

from _engine_path import add_engine_root

# Clean, official-format sensor profile for the emulator demo (10 Hz speed, front-wheel steering left
# negative at 0.1 deg, cruise and wiper state known, navigation on). Parity trips use random profiles.
DEMO_PROFILE = {"speed_hz": 10, "steer_sign": -1, "steer_wheel_unit": False, "steer_q": 0.1, "p_bound_unknown": 0.0,
                "p_cruise_known": 1.0, "p_wiper_unknown": 0.0, "p_heading": 1.0, "p_nav": 1.0, "p_navdata": 1.0,
                "nav_hz": 1.0, "dropouts": 0}

DEMOS = [
    # name, title (Korean, shown in the demo picker), seed, archetype, route mods
    ("commute_city", "출근길 · 시내 10분", 124, "careful", {"kind": "urban"}),
    ("rainy_evening", "비 오는 저녁 · 스쿨존", 157, "careful", {"kind": "urban"}),
    ("lane_practice", "차선 바꾸기 연습", 122, "careful", {"kind": "urban"}),
]

# name, seed, archetype, sensor profile, route mods: chosen to walk every code path of the engine
PARITY = [
    ("p01_baseline", 7_000_001, None, None, None),
    ("p02_speed_1hz", 7_010_003, None, {"speed_hz": 1}, None),
    ("p03_wheel_unit", 7_180_002, None, {"steer_wheel_unit": True}, None),
    ("p04_cruise_unknown", 7_220_004, None, {"p_cruise_known": 0.0}, {"kind": "mixed"}),
    ("p05_no_nav", 7_040_001, None, {"p_nav": 0.0, "p_heading": 0.0}, None),
    ("p06_rain_night", 7_070_002, None, None, {"rain": True, "night": True}),
    ("p07_aggressive", 7_120_005, "aggressive", None, {"kind": "mixed"}),
    ("p08_coarse_steer", 7_190_001, None, {"steer_q": 1.0, "steer_wheel_unit": False}, None),
    ("p09_expressway", 7_100_002, None, None, {"kind": "rural"}),
    ("p10_cut_ins", 7_090_003, None, None, {"extra_forced_per_km": 1.5}),
    ("p11_dropouts", 7_020_002, None, {"dropouts": 4}, None),
    ("p12_railroad", 501, None, None, {"kind": "rural"}),
    ("p13_rest_break", 902, None, None, {"kind": "long"}),
    ("p14_unit_from_range", 7_180_005, None, {"p_bound_unknown": 1.0, "steer_wheel_unit": True}, None),
    ("p15_unit_from_heading", 7_000_013, None, {"p_bound_unknown": 1.0, "steer_wheel_unit": False, "p_heading": 1.0, "steer_q": 0.1}, {"kind": "urban"}),
    ("p16_unit_unverified", 7_000_004, None, {"p_bound_unknown": 1.0, "steer_wheel_unit": False, "p_heading": 0.0}, None),
]

# Place names for the journal (the real app takes them from NaviHelper CurrentLocationInfo)
PLACES = ["서초 교차로", "양재 합류", "강남역 사거리", "반포 램프", "잠원 사거리", "한남 교차로", "역삼 사거리",
          "논현 교차로", "신사 사거리", "학동 사거리", "선릉역 사거리", "대치 교차로"]


def fmt(x, is_int):
    if is_int:
        return str(int(x))
    if not np.isfinite(x):
        return ""
    return repr(float(x))


def write_csv(trip, channels, ints, path: Path):
    buf = io.StringIO()
    buf.write(",".join(channels) + "\n")
    cols = [trip[c] for c in channels]
    flags = [c in ints for c in channels]
    for i in range(len(trip["t"])):
        buf.write(",".join(fmt(col[i], f) for col, f in zip(cols, flags)) + "\n")
    with gzip.open(path, "wt", encoding="utf-8", compresslevel=9) as f:
        f.write(buf.getvalue())


def places_for(trip):
    """A place name each time navigation counts down to a turn or a caution point (demo only)."""
    out, k = [[0.0, "출발지"]], 0
    td = trip["tbt_dist_m"].astype(float)
    cd = trip["caution_dist_m"].astype(float)
    ct = trip["caution_type"]
    names = {1: "어린이보호구역", 2: "과속방지턱 구간", 3: "철길 건널목"}
    for i in range(1, len(td)):
        if np.isfinite(td[i]) and (not np.isfinite(td[i - 1]) or td[i] > td[i - 1] + 50) and td[i] < 900:
            out.append([round(float(trip["t"][i]), 1), PLACES[k % len(PLACES)]]); k += 1
        if np.isfinite(cd[i]) and not np.isfinite(cd[i - 1]) and ct[i] in names:
            out.append([round(float(trip["t"][i]), 1), names[int(ct[i])]])
    return out


def jdict(j):
    def num(x):
        return None if x is None or (isinstance(x, float) and not math.isfinite(x)) else float(x)
    return {"habit": j.habit, "i_ref": int(j.i_ref), "i0": int(j.i0), "i1": int(j.i1), "outcome": int(j.outcome),
            "level": j.level, "value": num(j.value), "threshold": num(j.threshold), "margin": float(j.margin),
            "reason": j.reason, "context": {k: int(v) for k, v in j.context.items()}, "held": bool(j.meta.get("held", False))}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine-root", default=None)
    ap.add_argument("--android", default=str(Path(__file__).resolve().parents[2] / "android"))
    ap.add_argument("--only", choices=["demo", "parity", "all"], default="all")
    a = ap.parse_args(argv)
    add_engine_root(a.engine_root)
    warnings.filterwarnings("ignore")
    np.seterr(all="ignore")
    from data_spec.signals import CHANNELS, DATA_VERSION, INT_CHANNELS
    from scoring_engine.engine import SafeLogEngine
    from simulation.generate_dataset import _one

    channels = list(CHANNELS)
    android = Path(a.android)
    if a.only in ("demo", "all"):
        out = android / "app" / "src" / "main" / "assets" / "demo"
        out.mkdir(parents=True, exist_ok=True)
        index = []
        for name, title, seed, arche, mods in DEMOS:
            rec = _one(seed, arche, DEMO_PROFILE, mods)
            trip = rec["obs"]
            write_csv(trip, channels, set(INT_CHANNELS), out / f"{name}.csv.gz")
            meta = {"name": name, "title": title, "data_version": DATA_VERSION, "hz": 10,
                    "duration_s": float(trip["t"][-1]), "seed": seed, "places": places_for(trip),
                    "rain": bool(np.any(trip["wiper_active"] == 1)), "night": bool(np.any(trip["night"] == 1))}
            (out / f"{name}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
            index.append({"name": name, "title": title, "duration_s": meta["duration_s"]})
            print(f"demo  {name}: {meta['duration_s'] / 60:.1f} min")
        (out / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    if a.only in ("parity", "all"):
        out = android / "core" / "src" / "test" / "resources" / "parity"
        out.mkdir(parents=True, exist_ok=True)
        cases = [(n, s, ar, DEMO_PROFILE, m) for n, _t, s, ar, m in DEMOS] + PARITY
        for name, seed, arche, prof, mods in cases:
            rec = _one(seed, arche, prof, mods)
            trip = rec["obs"]
            write_csv(trip, channels, set(INT_CHANNELS), out / f"{name}.csv.gz")
            eng = SafeLogEngine()
            res = eng.run_trip(trip)
            D = res["derived"]
            expected = {
                "name": name, "data_version": DATA_VERSION, "n": int(len(trip["t"])),
                "derived": None if D is None else {
                    "speed_native_hz": float(D["speed_native_hz"]), "steer_unit": D["steer_unit"],
                    "steer_unit_source": D["steer_unit_source"], "steer_sign": int(D["steer_sign"]),
                    "steer_sign_source": D["steer_sign_source"], "ratio_scale": float(D["ratio_scale"]),
                    "ratio_scale_source": D["ratio_scale_source"],
                    "steer_step_deg": None if not np.isfinite(D["steer_step_deg"]) else float(D["steer_step_deg"]),
                    "steer_too_coarse": bool(D["steer_too_coarse"]), "km": float(D["s_m"][-1] / 1000)},
                "risk": {k: (bool(v) if isinstance(v, (bool, np.bool_)) else float(v)) for k, v in res["risk"].items()},
                "rewards": {"xp": res["rewards"]["xp"], "reward_blocked": res["rewards"]["reward_blocked"],
                            "lines": [l["text"] for l in res["rewards"]["all_good_points"]]},
                "judgments": [jdict(j) for j in res["judgments"]],
                "calibration": dict(eng.cfg.get("vehicle", {})),
            }
            (out / f"{name}.expected.json").write_text(json.dumps(expected, ensure_ascii=False), encoding="utf-8")
            print(f"parity {name}: {len(trip['t'])} rows, {len(res['judgments'])} judgments")


if __name__ == "__main__":
    main()
