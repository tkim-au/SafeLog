"""
CARLA adapter (GPU): high-fidelity vehicle physics, weather, night and dense traffic,
recorded in the SafeLog/Pleos schema. Default driver: CARLA Traffic Manager autopilot (--driver tm); optional
CARLA's BehaviorAgent (--driver agent: "cautious" / "normal" /
"aggressive") as the ego driver and adds blinker behaviour per style.

Requirements: CARLA 0.9.15 or 0.9.16 package (Windows or Ubuntu), RTX 3060 Ti 8 GB is at the
recommended level. Start the server first, low quality is enough (we do not need images):
    CarlaUE4.exe -quality-level=Low -RenderOffScreen          (Windows)
    ./CarlaUE4.sh -quality-level=Low -RenderOffScreen         (Linux)
then:
    pip install carla==0.9.15        # must match the server version
    python -m simulation.adapters.carla_collect --trips 30 --out datasets/carla_v3 --town Town10HD_Opt

Status: written against the CARLA 0.9.15 Python API and agents package; not yet executed on
the team PC. Run with --trips 1 first and check datasets/carla_v3/shard_0000.pkl.
"""
from __future__ import annotations

import argparse
import math
import pickle
import random
import sys
from pathlib import Path

import numpy as np

from data_spec.signals import empty_trip
from simulation.simulator import RATIO, observe

STYLE_SIGNAL = {  # probability of signalling and lead (m before turn, s before lane change)
    "cautious": (0.97, 60, 4.0), "normal": (0.85, 35, 2.0), "aggressive": (0.55, 15, 0.8),
}
WEATHERS = ["ClearNoon", "CloudyNoon", "WetNoon", "MidRainyNoon", "HardRainNoon", "ClearSunset", "WetCloudySunset", "ClearNight"]


def _add_agents_path(given):
    """Accept .../PythonAPI/carla OR .../PythonAPI/carla/agents, or find it under common CARLA folders."""
    cands = []
    if given:
        g = Path(given)
        cands += [g.parent if g.name == "agents" else g]
    for root in (r"D:\CARLA_0.9.16", r"C:\CARLA_0.9.16", r"D:\CARLA", r"C:\CARLA"):
        r = Path(root)
        cands += [r / "PythonAPI" / "carla", r / "WindowsNoEditor" / "PythonAPI" / "carla"]
    for c in cands:
        if (c / "agents" / "navigation" / "behavior_agent.py").exists():
            sys.path.insert(0, str(c))
            print(f"using CARLA agents from {c}")
            break
    else:
        raise SystemExit("CARLA 'agents' package not found. Pass --agents-path <folder that CONTAINS 'agents'>, "
                         "e.g. D:\\CARLA_0.9.16\\PythonAPI\\carla")
    missing = [m for m in ("shapely", "networkx") if __import__("importlib").util.find_spec(m) is None]
    if missing:
        raise SystemExit(f"CARLA agents need: pip install {' '.join(missing)}")


def run(args):
    import carla
    BehaviorAgent = RoadOption = None
    if args.driver == "agent":
        _add_agents_path(args.agents_path)
        from agents.navigation.behavior_agent import BehaviorAgent
        from agents.navigation.local_planner import RoadOption

    client = carla.Client(args.host, args.port)
    client.set_timeout(30.0)
    world = client.load_world(args.town) if args.town else client.get_world()
    settings = world.get_settings()
    # leftovers from an interrupted earlier run (same role names) are removed first
    for a in world.get_actors():
        if a.attributes.get("role_name", "").startswith("safelog"):
            a.destroy()
    settings.synchronous_mode = True
    settings.fixed_delta_seconds = 0.05         # 20 Hz physics: CARLA's agents are tuned for dt = 0.05 s
                                                # (0.1 s made the steering PID overshoot -> car left the road)
    settings.no_rendering_mode = not args.render  # physics only unless you want to watch
    world.apply_settings(settings)
    tm = client.get_trafficmanager(8000)
    tm.set_synchronous_mode(True)
    bp_lib = world.get_blueprint_library()
    spawns = world.get_map().get_spawn_points()
    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    recs = []
    actors = []
    try:
        _trips(args, carla, world, client, tm, bp_lib, spawns, out, recs, actors, BehaviorAgent, RoadOption)
    finally:                                     # always: remove our actors and give the simulator back (also on Ctrl+C)
        for a in actors:
            try:
                a.destroy()
            except Exception:
                pass
        settings.synchronous_mode = False
        settings.fixed_delta_seconds = None
        world.apply_settings(settings)
        tm.set_synchronous_mode(False)
    if recs:
        with open(out / "shard_0000.pkl", "wb") as f:
            pickle.dump(recs, f)
        from data_spec.signals import DATA_VERSION
        import json as _json
        (out / "dataset.json").write_text(_json.dumps({"data_version": DATA_VERSION, "trips": len(recs),
                                                       "source": "carla_collect"}), encoding="utf-8")
        print(f"wrote {len(recs)} trips -> {out}")


def _lead_vehicle(world, ego, tr, max_d):
    """Nearest vehicle ahead in roughly the same lane (for the simulated FCA warning)."""
    fwd = tr.get_forward_vector()
    best = None
    for a in world.get_actors().filter("*vehicle*"):
        if a.id == ego.id:
            continue
        d = a.get_location() - tr.location
        ahead = d.x * fwd.x + d.y * fwd.y
        side = abs(-d.x * fwd.y + d.y * fwd.x)
        if 0 < ahead < max_d and side < 1.8 and (best is None or ahead < best):
            best = ahead
    return (best is not None), (best if best is not None else max_d)


def _trips(args, carla, world, client, tm, bp_lib, spawns, out, recs, actors, BehaviorAgent, RoadOption):
    for k in range(args.trips):
        rnd = random.Random(k)
        style = ["cautious", "normal", "aggressive"][k % 3]
        wname = rnd.choice(WEATHERS)
        world.set_weather(getattr(carla.WeatherParameters, wname))
        weather = world.get_weather()
        # background traffic
        npcs = []
        for sp in rnd.sample(spawns, min(args.npcs, len(spawns) - 1)):
            bp = rnd.choice(bp_lib.filter("vehicle.*"))
            bp.set_attribute("role_name", "safelog_npc")
            v = world.try_spawn_actor(bp, sp)
            if v:
                actors.append(v)
                v.set_autopilot(True, 8000)
                npcs.append(v)
        ego_bp = bp_lib.find("vehicle.lincoln.mkz_2020")
        ego_bp.set_attribute("role_name", "safelog_ego")
        ego = None
        while ego is None:
            ego = world.try_spawn_actor(ego_bp, rnd.choice(spawns))
        actors.append(ego)
        world.tick()
        imu_bp = bp_lib.find("sensor.other.imu"); imu_bp.set_attribute("role_name", "safelog_imu")
        imu = world.spawn_actor(imu_bp, carla.Transform(), attach_to=ego)
        actors.append(imu)
        col_bp = bp_lib.find("sensor.other.collision"); col_bp.set_attribute("role_name", "safelog_col")
        col = world.spawn_actor(col_bp, carla.Transform(), attach_to=ego)
        actors.append(col)
        crash = {}
        col.listen(lambda e: crash.setdefault("what", e.other_actor.type_id))
        imu_acc = {"x": 0.0}
        imu.listen(lambda m: imu_acc.__setitem__("x", m.accelerometer.x))
        use_tm = args.driver == "tm"
        if use_tm:   # CARLA Traffic Manager autopilot: the robust built-in driver (same as the background cars)
            ego.set_autopilot(True, 8000)
            tm.vehicle_percentage_speed_difference(ego, {"cautious": 25.0, "normal": 5.0, "aggressive": -15.0}[style])
            tm.distance_to_leading_vehicle(ego, {"cautious": 4.0, "normal": 2.5, "aggressive": 1.2}[style])
            tm.auto_lane_change(ego, True)
            agent = None
        else:
            agent = BehaviorAgent(ego, behavior=style)
            agent.set_destination(rnd.choice(spawns).location)
        cmap = world.get_map()
        stuck = offroad = 0
        max_steer = ego.get_physics_control().wheels[0].max_steer_angle   # deg at the road wheel
        p_sig, lead_m, lead_s = STYLE_SIGNAL[style]
        rows = {c: [] for c in ["v", "brake", "steer", "head", "sig", "lim", "tbt_d", "tbt_t", "fca", "acc"]}
        lc_log, turn_log = [], []
        tw = {"best": None}
        signal_this = {}
        step = -1
        for tick in range(int(args.minutes * 1200)):
            if agent is not None:
                if agent.done():
                    agent.set_destination(rnd.choice(spawns).location)
                ego.apply_control(agent.run_step())
            world.tick()
            if crash:
                print(f"    collision with {crash['what']} at {tick / 1200:.1f} min -> trip ends here (data kept up to the crash)")
                break
            if tick % 2:                          # record every 2nd 20 Hz tick = 10 Hz SafeLog base rate
                continue
            step += 1
            control = ego.get_control()           # what was actually applied (agent or autopilot)
            if args.render:                       # camera follows the ego car (high enough to clear buildings)
                t = ego.get_transform()
                if args.view == "top":
                    cam = carla.Transform(t.location + carla.Location(z=45.0), carla.Rotation(pitch=-90, yaw=t.rotation.yaw))
                else:
                    back = t.get_forward_vector() * -12.0
                    cam = carla.Transform(t.location + carla.Location(x=back.x, y=back.y, z=9.0),
                                          carla.Rotation(pitch=-30, yaw=t.rotation.yaw))
                world.get_spectator().set_transform(cam)
            vel = ego.get_velocity()
            v = math.sqrt(vel.x ** 2 + vel.y ** 2 + vel.z ** 2)
            tr = ego.get_transform()
            on_road = cmap.get_waypoint(tr.location, project_to_road=False, lane_type=carla.LaneType.Driving) is not None
            if step % 300 == 0:                   # progress every 30 s (10 Hz steps): is the car actually driving?
                print(f"    {step / 600:4.1f} min  speed {v * 3.6:5.1f} km/h  at ({tr.location.x:7.1f}, {tr.location.y:7.1f})  "
                      f"throttle {control.throttle:.2f} brake {control.brake:.2f} steer {control.steer:+.2f}"
                      f"{'' if on_road else '  OFF-ROAD'}", flush=True)
            offroad = 0 if on_road else offroad + 1
            stuck = stuck + 1 if (v < 0.3 and not ego.is_at_traffic_light()) else 0
            if offroad > 30:
                print(f"    car left the road at ({tr.location.x:.1f}, {tr.location.y:.1f}) -> trip ends here")
                break
            if stuck > 600:
                print(f"    car stuck for 60 s at ({tr.location.x:.1f}, {tr.location.y:.1f}) -> trip ends here")
                break
            # upcoming manoeuvre: Traffic Manager action list, or the agent's local planner queue
            if agent is None:
                try:
                    queue = [(wp, str(o).split(".")[-1].upper().replace("_", "")) for o, wp in tm.get_all_actions(ego)][:60]
                except Exception:
                    queue = []
            else:
                queue = [(wp, o.name.upper().replace("_", "")) for wp, o in list(agent.get_local_planner().get_plan())[:60]]
            td, tt, lc = float("nan"), 0, None
            dist = 0.0
            prev = tr.location
            for wp, opt in queue:
                dist += wp.transform.location.distance(prev)
                prev = wp.transform.location
                if opt in ("LEFT", "RIGHT"):
                    td, tt = dist, 1 if opt == "LEFT" else 2
                    break
                if opt in ("CHANGELANELEFT", "CHANGELANERIGHT") and lc is None:
                    lc = (dist, 1 if opt == "CHANGELANELEFT" else 2)
            sig = 0
            if np.isfinite(td):
                key = ("t", round(td / 50))
                if key not in signal_this:
                    signal_this[key] = rnd.random() < p_sig
                if signal_this[key] and td <= lead_m:
                    sig = tt
            if lc is not None and v > 3:
                key = ("l", step // 100)
                if key not in signal_this:
                    signal_this[key] = rnd.random() < p_sig
                if signal_this[key] and lc[0] / max(v, 1) <= lead_s + 1.0:
                    sig = lc[1]
            lights = carla.VehicleLightState.NONE
            if sig == 1:
                lights |= carla.VehicleLightState.LeftBlinker
            elif sig == 2:
                lights |= carla.VehicleLightState.RightBlinker
            ego.set_light_state(carla.VehicleLightState(lights))
            hazard, d_obst = _lead_vehicle(world, ego, tr, 40.0)
            rows["v"].append(v); rows["brake"].append(control.brake * 100)
            rows["steer"].append(-control.steer * max_steer * RATIO)   # CARLA steer > 0 = RIGHT; clean channel is left-positive
            rows["head"].append((tr.rotation.yaw + 90) % 360); rows["sig"].append(sig)
            rows["lim"].append(ego.get_speed_limit()); rows["tbt_d"].append(td); rows["tbt_t"].append(tt)
            rows["fca"].append(int(hazard and d_obst / max(v, 0.1) < 1.8)); rows["acc"].append(imu_acc["x"])
            # The planner drops waypoints as the car passes them, so the distance to the turn never reaches 0:
            # a turn is DONE when its countdown disappears (or jumps to a farther manoeuvre) after getting close.
            # It is logged at the closest point, and that sample is set to 0 m like a navigation countdown.
            if np.isfinite(td) and (tw["best"] is None or td <= tw["best"][0]) and td < 25:
                tw["best"] = (td, len(rows["tbt_d"]) - 1, tt)
            if tw["best"] is not None and (not np.isfinite(td) or td > tw["best"][0] + 20 or tt != tw["best"][2]):
                d0, i0, dir0 = tw["best"]
                if d0 < 12 and (not turn_log or i0 - turn_log[-1]["i_turn"] > 50):
                    turn_log.append({"i_turn": i0, "dir": dir0, "id": i0})
                    rows["tbt_d"][i0] = 0.0
                tw["best"] = None
            if lc is not None and lc[0] < 6.0 and (not lc_log or step - lc_log[-1]["i0"] > 60):
                lc_log.append({"i0": step, "i1": step + 40, "dir": lc[1], "duration": 4.0, "sig_mode": "carla", "id": step})
        for a in [col, imu, ego] + npcs:
            try:
                a.destroy()
            except Exception:
                pass
            if a in actors:
                actors.remove(a)
        n = len(rows["v"])
        if n < 300:
            print(f"  trip {k}: only {n / 600:.1f} min recorded - skipped")
            continue
        trip = empty_trip(n)
        v = np.array(rows["v"])
        trip["speed_kph"][:] = v * 3.6; trip["brake_on"][:] = (np.array(rows["brake"]) > 1.0).astype(np.int8); trip["steer_deg"][:] = rows["steer"]
        trip["heading_deg"][:] = rows["head"]; trip["turn_signal"][:] = rows["sig"]; trip["speed_limit_kph"][:] = rows["lim"]
        trip["tbt_dist_m"][:] = rows["tbt_d"]; trip["tbt_type"][:] = rows["tbt_t"]; trip["tt_fca"][:] = rows["fca"]
        trip["odo_km"][:] = np.cumsum(v) / 10 / 1000; trip["gear"][:] = 3; trip["road_class"][:] = 0
        rain = int(weather.precipitation > 30)
        night = int(weather.sun_altitude_angle < 0)
        trip["wiper_active"][:] = rain; trip["night"][:] = night; trip["low_beam"][:] = int(night or rain)
        for ch in ("tt_lka", "tt_lca", "tt_daw", "tt_abs_esc", "tt_icy", "tt_tpms", "cruise_active", "school_zone",
                   "camera_overspeed", "congestion", "caution_type"):
            trip[ch][:] = 0
        trip["seats_occupied"][:] = 1; trip["belts_buckled"][:] = 1; trip["nav_active"][:] = 1; trip["drive_mode"][:] = 1
        truth = {"turns": turn_log, "lane_changes": lc_log, "forced": [], "true_accel": np.array(rows["acc"], np.float32),
                 "weather": wname}
        obs, meta = observe(np.random.default_rng(k), trip)
        recs.append({"id": 50000 + k, "style": style, "clean": trip, "obs": obs, "obs_meta": meta, "truth": truth})
        print(f"  trip {k}: {style} {wname} {n / 600:.1f} min, {len(turn_log)} turns, {len(lc_log)} LCs"
              + (f", ended by collision with {crash['what']}" if crash else ""), flush=True)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--trips", type=int, default=3)
    ap.add_argument("--minutes", type=float, default=10)
    ap.add_argument("--out", default="datasets/carla_v3")
    ap.add_argument("--town", default="Town10HD_Opt")
    ap.add_argument("--npcs", type=int, default=40)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=2000)
    ap.add_argument("--render", action="store_true", help="watch in the CARLA window (start the server without -RenderOffScreen)")
    ap.add_argument("--driver", choices=["tm", "agent"], default="tm",
                    help="tm = CARLA Traffic Manager autopilot (robust, default); agent = PythonAPI BehaviorAgent")
    ap.add_argument("--view", choices=["chase", "top"], default="chase", help="--render camera: chase (behind, raised) or top-down")
    ap.add_argument("--agents-path", default=None, help="path to CARLA/PythonAPI/carla if 'agents' is not importable")
    run(ap.parse_args(argv))


if __name__ == "__main__":
    main()
