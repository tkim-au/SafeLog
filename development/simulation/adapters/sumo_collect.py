"""
SUMO adapter: realistic multi-vehicle traffic (car-following, lane changes, junction priority)
recorded from one ego vehicle in the SafeLog/Pleos schema. CPU-only, runs thousands of trips.

    pip install eclipse-sumo libsumo
    python -m simulation.adapters.sumo_collect --trips 20 --out datasets/sumo_v3 [--net my_seoul.net.xml]

Without --net a 6x6 urban grid with 2-lane roads is generated (netgenerate). For a Korean
digital twin, export an area from OpenStreetMap and convert it:
    netconvert --osm-files gangnam.osm --output-file gangnam.net.xml --geometry.remove --ramps.guess --junctions.join
Ego driving styles map to SUMO vType parameters (accel, decel, sigma, tau, lcAssertive, speedFactor).
SUMO has no steering wheel, so steering is reconstructed from the heading rate with the same
bicycle model the engine uses; FCA is emulated as time-to-collision <= 1.8 s to the leader
(VTTI naturalistic trigger). Blinkers come from SUMO's own signal bits.
"""
from __future__ import annotations

import argparse
import math
import os
import pickle
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

from data_spec.signals import empty_trip
from simulation.simulator import L_WB, RATIO, K_US, observe

STYLES = {   # SUMO Krauss model parameters per archetype
    "careful": dict(accel=1.6, decel=2.0, sigma=0.2, tau=1.6, speedFactor=0.92, lcAssertive=0.6),
    "normal": dict(accel=2.4, decel=3.0, sigma=0.5, tau=1.1, speedFactor=1.0, lcAssertive=1.0),
    "aggressive": dict(accel=3.5, decel=4.5, sigma=0.7, tau=0.7, speedFactor=1.15, lcAssertive=2.0),
}


def _sumo_bin(name):
    """Find a SUMO program: the pip package first, then the installed SUMO (SUMO_HOME), then PATH."""
    homes = []
    try:
        import sumo
        homes.append(sumo.SUMO_HOME)
    except ImportError:
        pass
    if os.environ.get("SUMO_HOME"):
        homes.append(os.environ["SUMO_HOME"])
    for h in homes:
        for ext in ("", ".exe"):
            cand = os.path.join(h, "bin", name + ext)
            if os.path.exists(cand):
                return cand
    return name


def build_network(work: Path) -> Path:
    net = work / "grid.net.xml"
    subprocess.run([_sumo_bin("netgenerate"), "--grid", "--grid.number", "6", "--grid.length", "300",
                    "--default.lanenumber", "2", "--default.speed", "13.89", "--tls.guess", "true",
                    "--output-file", str(net)], check=True, capture_output=True)
    return net


def run_trip(net: Path, work: Path, seed: int, style: str, minutes: float = 12.0, gui: bool = False, delay_ms: int = 50,
             binary: str | None = None):
    if gui or binary:
        import traci            # socket client: needed for the GUI (libsumo has no GUI)
    else:
        import libsumo as traci
    rng = np.random.default_rng(seed)
    routes = work / f"r{seed}.rou.xml"
    tools = os.path.join(os.path.dirname(_sumo_bin("sumo")), "..", "tools")
    subprocess.run([sys.executable, os.path.join(tools, "randomTrips.py"), "-n", str(net), "-o", str(work / f"t{seed}.trips.xml"), "-r", str(routes),
                    "-e", "900", "-p", "1.5", "--seed", str(seed), "--validate"], check=True, capture_output=True)
    cmd = [_sumo_bin(binary or ("sumo-gui" if gui else "sumo")), "-n", str(net), "-r", str(routes), "--step-length", "0.1",
           "--lanechange.duration", "4", "--seed", str(seed), "--no-step-log", "true", "--no-warnings", "true"]
    if gui:
        cmd += ["--start", "--quit-on-end", "--delay", str(delay_ms)]
    traci.start(cmd)
    p = STYLES[style]
    traci.vehicletype.copy("DEFAULT_VEHTYPE", "ego")
    traci.vehicletype.setAccel("ego", p["accel"])
    traci.vehicletype.setDecel("ego", p["decel"])
    traci.vehicletype.setImperfection("ego", p["sigma"])
    traci.vehicletype.setTau("ego", p["tau"])
    traci.vehicletype.setSpeedFactor("ego", p["speedFactor"])
    traci.vehicletype.setParameter("ego", "lcAssertive", str(p["lcAssertive"]))
    edges = [e for e in traci.edge.getIDList() if not e.startswith(":")]
    # long random route for the ego
    a, b = rng.choice(edges, 2, replace=False)
    route = traci.simulation.findRoute(a, b).edges
    for _ in range(4):
        c = rng.choice(edges)
        nxt = traci.simulation.findRoute(route[-1], c).edges
        route = list(route) + list(nxt[1:])
    traci.route.add("egoroute", route)
    traci.vehicle.add("ego", "egoroute", typeID="ego", depart="now")
    tracked = False
    rec = {k: [] for k in ["v", "a", "sig", "head", "lim", "lane", "tbt_d", "tbt_t", "ttc", "x", "y"]}
    steps = int(minutes * 600)
    started = False
    for _ in range(steps):
        traci.simulationStep()
        if "ego" not in traci.vehicle.getIDList():
            if started:
                break
            continue
        started = True
        if gui and not tracked:
            traci.vehicle.setColor("ego", (255, 60, 60, 255))     # ego = red, followed by the camera
            traci.gui.trackVehicle("View #0", "ego")
            traci.gui.setZoom("View #0", 800)
            tracked = True
        v = traci.vehicle.getSpeed("ego")
        rec["v"].append(v); rec["a"].append(traci.vehicle.getAcceleration("ego"))
        bits = traci.vehicle.getSignals("ego")
        rec["sig"].append(3 if bits & 4 else 1 if bits & 2 else 2 if bits & 1 else 0)
        rec["head"].append(traci.vehicle.getAngle("ego"))
        lane = traci.vehicle.getLaneID("ego")
        rec["lim"].append(traci.lane.getMaxSpeed(lane) * 3.6 if not lane.startswith(":") else (rec["lim"][-1] if rec["lim"] else 50))
        rec["lane"].append(traci.vehicle.getLaneIndex("ego"))
        links = traci.vehicle.getNextLinks("ego")
        d_acc = traci.lane.getLength(lane) - traci.vehicle.getLanePosition("ego") if not lane.startswith(":") else 0.0
        td, tt = float("nan"), 0
        for lk in links[:4]:
            direction = lk[6]
            if direction in ("l", "L", "r", "R", "t"):
                td, tt = d_acc, {"l": 1, "L": 1, "r": 2, "R": 2, "t": 3}[direction]
                break
            d_acc += lk[7] + (traci.lane.getLength(lk[0]) if lk[0] else 0)
        rec["tbt_d"].append(td); rec["tbt_t"].append(tt)
        lead = traci.vehicle.getLeader("ego", 80)
        ttc = float("inf")
        if lead:
            dv = v - traci.vehicle.getSpeed(lead[0])
            if dv > 0.1:
                ttc = lead[1] / dv
        rec["ttc"].append(ttc)
    traci.close()
    return rec


def to_trip(rec, rng):
    n = len(rec["v"])
    trip = empty_trip(n)
    v = np.array(rec["v"]); head = np.array(rec["head"])
    trip["speed_kph"][:] = v * 3.6
    a = np.array(rec["a"])
    trip["brake_on"][:] = (a < -0.7).astype(np.int8)        # pedal switch only, like the real car
    yaw = np.gradient(np.rad2deg(np.unwrap(np.deg2rad(-head)))) * 10    # SUMO angle is clockwise
    kap = np.where(v > 1, np.deg2rad(yaw) / np.maximum(v, 1), 0)
    trip["steer_deg"][:] = np.degrees(np.arctan(L_WB * kap * (1 + K_US * v * v))) * RATIO
    trip["heading_deg"][:] = head % 360          # SUMO angle is already a compass bearing (0 = north, clockwise)
    trip["turn_signal"][:] = rec["sig"]
    trip["gear"][:] = 3
    trip["odo_km"][:] = np.cumsum(v) / 10 / 1000
    trip["speed_limit_kph"][:] = rec["lim"]
    trip["road_class"][:] = 0
    trip["tbt_dist_m"][:] = rec["tbt_d"]; trip["tbt_type"][:] = rec["tbt_t"]
    trip["tt_fca"][:] = (np.array(rec["ttc"]) <= 1.8).astype(np.int8)
    for ch in ("tt_lka", "tt_lca", "tt_daw", "tt_abs_esc", "tt_icy", "tt_tpms", "cruise_active", "wiper_active", "low_beam",
               "night", "school_zone", "camera_overspeed", "congestion"):
        trip[ch][:] = 0
    trip["caution_type"][:] = 0
    trip["seats_occupied"][:] = 1; trip["belts_buckled"][:] = 1; trip["nav_active"][:] = 1; trip["drive_mode"][:] = 1
    lane = np.array(rec["lane"])
    lcs = [{"i0": max(0, int(i) - 20), "i1": int(i) + 20, "dir": 1 if lane[i + 1] > lane[i] else 2, "duration": 4.0,
            "sig_mode": "sumo", "id": k} for k, i in enumerate(np.flatnonzero(np.diff(lane) != 0))]
    turns = []
    td = np.array(rec["tbt_d"])
    for i in range(1, n):
        if np.isfinite(td[i - 1]) and td[i - 1] > 0 and (not np.isfinite(td[i]) or td[i] > td[i - 1] + 20 or td[i] <= 0):
            turns.append({"i_turn": i, "dir": int(rec["tbt_t"][i - 1]), "id": i})
    truth = {"turns": turns, "lane_changes": lcs, "forced": [], "true_accel": a.astype(np.float32)}
    obs, meta = observe(rng, trip)
    return trip, obs, meta, truth


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--trips", type=int, default=6)
    ap.add_argument("--out", default="datasets/sumo_v3")
    ap.add_argument("--net", default=None)
    ap.add_argument("--minutes", type=float, default=12)
    ap.add_argument("--gui", action="store_true", help="watch the ego car in sumo-gui (slower, for demos)")
    ap.add_argument("--delay", type=int, default=50, help="GUI delay per step in ms (0 = as fast as possible)")
    ap.add_argument("--binary", default=None, help=argparse.SUPPRESS)
    a = ap.parse_args(argv)
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="sumo_"))
    net = Path(a.net) if a.net else build_network(work)
    recs = []
    for k in range(a.trips):
        style = list(STYLES)[k % 3]
        rng = np.random.default_rng(1000 + k)
        rec = run_trip(net, work, 1000 + k, style, a.minutes, gui=a.gui, delay_ms=a.delay, binary=a.binary)
        if len(rec["v"]) < 600:
            continue
        clean, obs, meta, truth = to_trip(rec, rng)
        recs.append({"id": 1000 + k, "style": style, "clean": clean, "obs": obs, "obs_meta": meta, "truth": truth})
        print(f"  trip {k}: {style}, {len(rec['v']) / 600:.1f} min, {len(truth['lane_changes'])} LCs, {len(truth['turns'])} turns")
    with open(out / "shard_0000.pkl", "wb") as f:
        pickle.dump(recs, f)
    from data_spec.signals import DATA_VERSION
    import json as _json
    (out / "dataset.json").write_text(_json.dumps({"data_version": DATA_VERSION, "trips": len(recs),
                                                   "source": __name__.rsplit(".", 1)[-1]}), encoding="utf-8")
    print(f"wrote {len(recs)} trips to {out}")


if __name__ == "__main__":
    main()
