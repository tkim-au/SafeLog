"""
SafeLog engine entry point: one trip in, judgments + risk + rewards out.

    from scoring_engine.engine import SafeLogEngine
    eng = SafeLogEngine()
    result = eng.run_trip(trip)              # trip: dict of arrays (data_spec.signals schema)

Optional `ml_gate`: a callable(trip, D, judgments) -> judgments that turns rule SUCCESS into
ABSTAIN when the learned second opinion is not confident (dual-key praise). See model_training/.
"""
from __future__ import annotations

import numpy as np

from scoring_engine.derive import derive, load_config
from scoring_engine.habits import run_event_habits, run_window_habits
from scoring_engine.risk_monitors import risk_summary
from scoring_engine.scoring import best_scenes, trip_rewards


class SafeLogEngine:
    def __init__(self, cfg: dict | None = None, ml_gate=None):
        self.cfg = cfg or load_config()
        self.ml_gate = ml_gate

    def run_trip(self, trip: dict, practice: list[str] | None = None) -> dict:
        if not self._judgeable(trip):
            # too short or no usable time base: judge nothing (never praise without data)
            return {"derived": None, "candidates": [], "judgments": [], "risk": {}, "skipped": "trip_too_short",
                    "rewards": trip_rewards([], {}, practice), "scenes": []}
        D = derive(trip, self.cfg)
        self._remember_calibration(D)
        ev, cands = run_event_habits(trip, D, self.cfg)
        win = run_window_habits(trip, D, self.cfg)
        judgments = ev + win
        if self.ml_gate is not None:
            judgments = self.ml_gate(trip, D, judgments)
        hold = set(self.cfg.get("release", {}).get("not_released", []))
        for j in judgments:
            if j.habit in hold and j.outcome == 1:
                # never shown to the driver, but kept visible to training / evaluation via meta["held"]
                j.outcome, j.reason = -1, "habit_not_released"
                j.meta["held"] = True
        risk = risk_summary(trip, D, self.cfg)
        rewards = trip_rewards(judgments, risk, practice)
        return {"derived": D, "candidates": cands, "skipped": None, "judgments": judgments, "risk": risk,
                "rewards": rewards, "scenes": best_scenes(judgments)}

    def _remember_calibration(self, D):
        """Per-car memory: a steering sign / ratio learned from heading or navigation turns on one trip is
        reused on later trips (the Kotlin app persists cfg['vehicle'] in its local store)."""
        veh = self.cfg.setdefault("vehicle", {})
        if D["steer_sign_source"] in ("heading", "navigation_turns"):
            veh["steer_sign"] = int(D["steer_sign"])
            veh["steer_sign_learned"] = True
        if D.get("steer_unit_source") in ("range", "heading"):
            veh["steer_unit_learned"] = D["steer_unit"]
        if D["ratio_scale_source"] == "heading":
            old = veh.get("ratio_scale")
            veh["ratio_scale"] = D["ratio_scale"] if not old else 0.7 * old + 0.3 * D["ratio_scale"]

    def _judgeable(self, trip: dict) -> bool:
        t = np.asarray(trip.get("t", ()), dtype=np.float64)
        if t.size < 2 or not np.isfinite(t[[0, -1]]).all():
            return False
        if any(np.asarray(v).shape[:1] != t.shape for v in trip.values()):
            return False                                  # channels of different length: corrupt recording
        return (t[-1] - t[0]) >= self.cfg["general"].get("min_trip_s", 30)
