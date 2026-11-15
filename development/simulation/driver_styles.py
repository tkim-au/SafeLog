"""
Driver behaviour models.

Each archetype is a distribution over interpretable parameters; every simulated
driver is a random draw, and every manoeuvre adds per-event variability.
Values are anchored to the literature where possible:
  * comfortable stopping deceleration ~3.4 m/s^2 is the AASHTO design value (most drivers),
    careful drivers 1.2-2.0, aggressive 3.0-4.8 (>0.3 g = Tesla/Progressive hard-brake line)
  * lane change duration 5-6 s typical (UMTRI IVBSS), aggressive 2.5-4 s
  * turn-signal before lane change ~60-70 % in naturalistic data (Chalmers 2024, UMTRI SPFOT)
"""
from __future__ import annotations

from dataclasses import dataclass, asdict

import numpy as np


@dataclass
class DriverStyle:
    archetype: str
    b_comfort: float          # preferred stopping deceleration (m/s^2)
    onset_frac: float         # start braking when required decel reaches onset_frac * b_comfort
    coaster: float            # probability of coasting before braking
    coast_onset_frac: float
    a_launch: float           # launch acceleration (m/s^2)
    speed_factor: float       # cruise speed / limit
    speed_jitter: float
    school_factor: float      # speed in school zone / 30
    rain_factor: float        # speed in rain / normal
    curve_lat: float          # preferred lateral acceleration in curves (m/s^2)
    turn_speed_kph: float     # speed through a 90 deg urban turn
    p_signal_turn: float
    turn_lead_m: float        # mean blinker lead distance before a turn
    p_lc_signal: float
    lc_lead_s: float          # mean lead time when signalling before the LC
    p_lc_signal_after: float  # prob. that the blinker comes after steering starts
    lc_duration: float
    p_bump_slow: float
    p_rail_stop: float
    p_caution_slow: float
    p_right_pause: float
    p_hazard: float
    p_cruise: float
    p_belts_all: float
    p_lights_rain: float
    reaction_s: float
    weave: float              # steering noise amplitude (deg at the wheel)
    lka_per_km: float
    fca_per_km: float         # tailgating-induced FCA rate
    tau: float                # actuation lag (s) -> jerk
    drowsy: bool = False

    def as_dict(self):
        return asdict(self)


def _u(rng, lo, hi):
    return float(rng.uniform(lo, hi))


def sample_style(rng: np.random.Generator, archetype: str | None = None) -> DriverStyle:
    arche = archetype or rng.choice(["careful", "normal", "normal", "aggressive", "novice", "drowsy"],
                                    p=[0.22, 0.20, 0.20, 0.16, 0.14, 0.08])
    if arche == "careful":
        return DriverStyle(arche, _u(rng, 1.2, 2.2), _u(rng, 0.30, 0.55), _u(rng, 0.7, 0.95), _u(rng, 0.12, 0.25),
                           _u(rng, 1.0, 1.9), _u(rng, 0.88, 0.98), 0.02, _u(rng, 0.80, 0.95), _u(rng, 0.70, 0.82),
                           _u(rng, 1.2, 2.0), _u(rng, 14, 20), _u(rng, 0.95, 1.0), _u(rng, 45, 90), _u(rng, 0.92, 1.0),
                           _u(rng, 3.2, 5.0), 0.05, _u(rng, 5, 7), 0.97, 0.98, 0.9, 0.7, 0.6, 0.25, 0.99, 0.95,
                           _u(rng, 0.6, 0.9), _u(rng, 0.2, 0.5), 0.003, 0.002, _u(rng, 0.45, 0.7))
    if arche == "normal":
        return DriverStyle(arche, _u(rng, 1.8, 3.0), _u(rng, 0.55, 0.85), _u(rng, 0.35, 0.7), _u(rng, 0.2, 0.4),
                           _u(rng, 1.6, 2.6), _u(rng, 0.96, 1.06), 0.03, _u(rng, 0.92, 1.08), _u(rng, 0.85, 0.95),
                           _u(rng, 1.8, 2.8), _u(rng, 18, 26), _u(rng, 0.85, 0.97), _u(rng, 25, 55), _u(rng, 0.65, 0.85),
                           _u(rng, 1.5, 3.5), 0.35, _u(rng, 4, 6), 0.8, 0.85, 0.6, 0.45, 0.35, 0.3, 0.95, 0.75,
                           _u(rng, 0.8, 1.2), _u(rng, 0.4, 0.8), 0.01, 0.01, _u(rng, 0.35, 0.55))
    if arche == "aggressive":
        return DriverStyle(arche, _u(rng, 2.9, 4.8), _u(rng, 0.85, 1.0), _u(rng, 0.05, 0.3), _u(rng, 0.5, 0.8),
                           _u(rng, 2.6, 4.0), _u(rng, 1.04, 1.25), 0.05, _u(rng, 1.05, 1.45), _u(rng, 0.95, 1.05),
                           _u(rng, 2.8, 4.2), _u(rng, 25, 36), _u(rng, 0.55, 0.85), _u(rng, 8, 30), _u(rng, 0.3, 0.6),
                           _u(rng, 0.3, 1.8), 0.5, _u(rng, 2.5, 4.0), 0.4, 0.6, 0.25, 0.2, 0.15, 0.25, 0.85, 0.5,
                           _u(rng, 0.9, 1.4), _u(rng, 0.5, 1.0), 0.02, 0.05, _u(rng, 0.25, 0.4))
    if arche == "novice":
        return DriverStyle(arche, _u(rng, 1.5, 4.0), _u(rng, 0.5, 1.0), _u(rng, 0.2, 0.6), _u(rng, 0.2, 0.5),
                           _u(rng, 1.2, 3.2), _u(rng, 0.85, 1.02), 0.06, _u(rng, 0.85, 1.05), _u(rng, 0.80, 0.95),
                           _u(rng, 1.5, 3.2), _u(rng, 14, 28), _u(rng, 0.7, 0.95), _u(rng, 15, 60), _u(rng, 0.5, 0.9),
                           _u(rng, 0.8, 3.5), 0.4, _u(rng, 3.5, 6.5), 0.85, 0.9, 0.7, 0.5, 0.2, 0.05, 0.95, 0.8,
                           _u(rng, 1.0, 1.6), _u(rng, 0.8, 1.6), 0.03, 0.02, _u(rng, 0.3, 0.7))
    # drowsy: normal kinematics, late reactions, weaving, attention warnings
    s = sample_style(rng, "normal")
    s.archetype, s.drowsy = "drowsy", True
    s.reaction_s, s.weave, s.lka_per_km = _u(rng, 1.3, 2.2), _u(rng, 1.2, 2.2), 0.06
    s.p_signal_turn *= 0.9
    s.p_lc_signal *= 0.85
    return s
