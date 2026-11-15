"""
Export a SafeLog trip as a JSON-lines replay for the Kotlin app's FakeVehicleAdapter
(used in the Pleos Connect emulator where the Virtual Console has no speed card).

    python -m simulation.adapters.pleos_replay_export --data datasets/sim_v3 --trip 0 --out replay.jsonl

Each line: {"t": seconds, "api": "<Pleos/AAOS callback>", "value": ...}; only changes are written.
"""
from __future__ import annotations

import argparse
import json
import math

from evaluation.evaluate import load_records

API = {   # official callback / property names (Vehicle SDK v2.3.2, NaviHelper v3.0.5, AAOS)
    "speed_kph": "AAOS.PERF_VEHICLE_SPEED (m/s in the car; km/h here)",
    "brake_on": "Brake.registerBrakePedalStatus -> BrakePedalStatusListener (Boolean)",
    "steer_deg": "SteeringWheel.registerSteeringWheelAngle -> onSteeringWheelAngleUpdated(Float?) front-wheel deg, left negative",
    "steer_bound_deg": "SteeringWheel.checkSteeringWheelAngleCapabilities -> AreaBounds<Float>",
    "turn_signal": "TurnSignal.registerTurnSignalState (TurnSignalValue OFF/RIGHT/LEFT)",
    "gear": "AAOS.GEAR_SELECTION", "wiper_active": "Wiper.getCurrentWiperState (WiperState ON/OFF)",
    "low_beam": "Light.getCurrentLowBeamLightState (LightState)",
    "cruise_active": "DrivingMode.registerCruiseControlState (CruiseControl)",
    "speed_limit_kph": "NaviHelper.onCurrentRoadSpeedLimit(Int) every second",
    "tbt_dist_m": "NaviHelper.onTBTInfo(TBTInfo.distance m)", "tbt_type": "NaviHelper.onTBTInfo(TBTInfo.type TurnType)",
    "caution_type": "NaviHelper.onCautionPointInfo(CautionPoint.type)",
    "caution_dist_m": "NaviHelper.onCautionPointInfo(CautionPoint.remainDistance m)",
}   # telltales are NOT exported: Safety.registerTelltales needs PRIVATE_CAR_SAFETY (not available to third-party apps)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="datasets/sim_v3")
    ap.add_argument("--trip", type=int, default=0)
    ap.add_argument("--out", default="replay.jsonl")
    a = ap.parse_args(argv)
    rec = load_records(a.data)[a.trip]
    trip = rec["obs"]
    last = {}
    with open(a.out, "w", encoding="utf-8") as f:
        for i in range(len(trip["t"])):
            for ch, api in API.items():
                v = trip[ch][i].item()
                if isinstance(v, float) and math.isnan(v):
                    continue
                v = round(v, 2) if isinstance(v, float) else v
                if last.get(ch) != v:
                    f.write(json.dumps({"t": round(float(trip["t"][i]), 1), "api": api, "value": v}) + "\n")
                    last[ch] = v
    print(f"wrote {a.out} for trip {rec['id']} ({rec['style']})")


if __name__ == "__main__":
    main()
