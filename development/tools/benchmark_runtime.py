"""
Measure the runtime cost of SafeLog against the Pleos budget (driving < 5 % CPU, parked <= 25 %, RAM <= 400 MB).

    python -m tools.benchmark_runtime --data datasets/sim_v3 --trips 10

Reports the in-drive cost per 10 Hz frame (recorder + chime detector), the parked-phase cost
(batch engine + gate) per trip, and memory. Numbers are for THIS machine running Python; the
Kotlin port on the head unit must be re-measured with Android Studio Profiler on the emulator.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import time

import numpy as np

from data_spec.signals import CHANNELS
from evaluation.evaluate import load_records
from scoring_engine.engine import SafeLogEngine
from scoring_engine.streaming import ChimeDetector, DriveRecorder


def frames(trip):
    n = len(trip["t"])
    keys = [k for k in CHANNELS if k != "t"]
    cols = {k: trip[k].tolist() for k in keys}
    return [{k: cols[k][i] for k in keys} for i in range(n)]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="datasets/sim_v3")
    ap.add_argument("--trips", type=int, default=10)
    ap.add_argument("--gate", default="model_training/checkpoints/gate.pt")
    a = ap.parse_args(argv)
    recs = load_records(a.data, "test")[:a.trips]
    n_frames = 0
    t_rec = t_chime = 0.0
    rec_bytes = 0
    for r in recs:
        fr = frames(r["obs"])
        n_frames += len(fr)
        dr = DriveRecorder()
        t0 = time.perf_counter()
        for f in fr:
            dr.push(f)
        t_rec += time.perf_counter() - t0
        rec_bytes += dr.nbytes
        for practice in ("smooth_stop", "turn_signal_turn", "lane_change_signal"):
            cd = ChimeDetector(practice, steer_sign=-int(r["obs_meta"].get("steer_sign", -1)),
                               steer_unit="steering_wheel" if r["obs_meta"].get("steer_wheel_unit") else "road_wheel")
            t0 = time.perf_counter()
            for f in fr:
                cd.step(f)
            t_chime += (time.perf_counter() - t0) / 3
    drive_s = n_frames / 10
    per_frame_us = (t_rec + t_chime) / n_frames * 1e6
    cpu_share_drive = (t_rec + t_chime) / drive_s * 100
    # parked phase
    gate = None
    if os.path.exists(a.gate):
        from model_training.gate import load_gate
        gate = load_gate(a.gate)
    eng = SafeLogEngine(ml_gate=gate)
    t0 = time.perf_counter()
    for r in recs:
        eng.run_trip(r["obs"])
    t_batch = time.perf_counter() - t0
    params = 0
    if gate is not None:
        import torch
        params = sum(v.numel() for v in torch.load(a.gate, map_location="cpu", weights_only=False)["state"].values())
    out = {
        "machine": f"{platform.processor() or platform.machine()} / Python {platform.python_version()}",
        "trips": len(recs), "driving_hours": round(drive_s / 3600, 2),
        "in_drive_us_per_frame": round(per_frame_us, 2),
        "in_drive_cpu_percent_of_one_core": round(cpu_share_drive, 4),
        "recorder_MB_per_hour": round(rec_bytes / (drive_s / 3600) / 1e6, 3),
        "parked_batch_seconds_per_driving_hour": round(t_batch / (drive_s / 3600), 3),
        "parked_batch_seconds_per_trip": round(t_batch / len(recs), 3),
        "gate_parameters": params, "gate_size_kB_fp32": round(params * 4 / 1024, 1),
        "pleos_budget": "driving < 5 % CPU (non-navigation app), parked <= 25 %, RAM <= 400 MB (document.pleos.ai performance-resource-mgmt)",
    }
    from pathlib import Path
    Path("evaluation/reports").mkdir(parents=True, exist_ok=True)
    Path("evaluation/reports/benchmark.json").write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
