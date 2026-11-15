"""
Generate a labelled dataset of simulated trips.

    python -m simulation.generate_dataset --trips 2000 --out datasets/sim_v3 --workers 8

Each shard (pickle) holds a list of records:
    {"id", "style", "clean": trip, "obs": trip, "obs_meta", "truth"}
`clean` = perfect signals (used only to build ground truth), `obs` = what the app receives.
"""
from __future__ import annotations

import argparse
import json
import pickle
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

from simulation.driver_styles import sample_style
from simulation.simulator import make_route, observe, simulate


def _one(seed: int, archetype: str | None = None, sensor_profile: dict | None = None,
         route_mod: dict | None = None) -> dict:
    """One labelled trip. route_mod (stress tests / domain randomisation):
    {"kind": "urban|rural|mixed|long", "rain": bool, "night": bool, "heavy_rain": bool,
     "extra_forced_per_km": float  (adds cut-ins / lead-car braking / emergency stops)}"""
    rng = np.random.default_rng(seed)
    style = sample_style(rng, archetype)
    route = None
    if route_mod:
        route = make_route(rng, route_mod.get("kind"))
        for k in ("rain", "night", "heavy_rain"):
            if k in route_mod:
                setattr(route, k, bool(route_mod[k]))
        dens = route_mod.get("extra_forced_per_km", 0.0)
        if dens:
            n_add = rng.poisson(dens * route.length / 1000)
            for x in rng.uniform(300, max(400, route.length - 300), n_add):
                route.events.append({"type": "forced", "s": float(x),
                                     "kind": str(rng.choice(["lead_brake", "cut_in", "emergency_stop"], p=[0.4, 0.4, 0.2]))})
            route.events.sort(key=lambda e: e["s"])
    clean, truth = simulate(rng, style, route)
    obs, meta = observe(rng, clean, sensor_profile)
    return {"id": seed, "style": style.archetype, "clean": clean, "obs": obs, "obs_meta": meta, "truth": truth}


def _shard(args):
    seeds, out, idx, profile = args[:4]
    route_mod = args[4] if len(args) > 4 else None
    archetype = args[5] if len(args) > 5 else None
    recs = [_one(s, archetype, profile, route_mod) for s in seeds]
    path = Path(out) / f"shard_{idx:04d}.pkl"
    with open(path, "wb") as f:
        pickle.dump(recs, f, protocol=pickle.HIGHEST_PROTOCOL)
    return str(path), len(recs), sum(len(r["clean"]["t"]) for r in recs)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--trips", type=int, default=400)
    ap.add_argument("--out", default="datasets/sim_v3")
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--per-shard", type=int, default=25)
    ap.add_argument("--seed", type=int, default=20261105)
    ap.add_argument("--speed-hz", type=float, default=None, help="force a speed sample rate (e.g. 1) for stress tests")
    a = ap.parse_args(argv)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    seeds = [a.seed + i for i in range(a.trips)]
    profile = {"speed_hz": a.speed_hz} if a.speed_hz else None
    jobs = [(seeds[i:i + a.per_shard], str(out), k, profile) for k, i in enumerate(range(0, len(seeds), a.per_shard))]
    t0 = time.time()
    total_steps = 0
    with ProcessPoolExecutor(max_workers=a.workers) as ex:
        for path, n, steps in ex.map(_shard, jobs):
            total_steps += steps
            print(f"  wrote {path} ({n} trips)", flush=True)
    hours = total_steps / 10 / 3600
    from data_spec.signals import DATA_VERSION
    meta = {"data_version": DATA_VERSION, "trips": a.trips, "seed": a.seed, "simulated_hours": round(hours, 1), "seconds": round(time.time() - t0, 1)}
    (out / "dataset.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(json.dumps(meta))


if __name__ == "__main__":
    main()
