"""
Internal risk monitors — NEVER shown to the user.

Used only for: (1) gating rewards (a risky trip earns no XP/points, per plan v0.4 F7),
(2) choosing the next practice recommendation (framed positively),
(3) the optional insurer-facing score (separate consent).
Forced events (FCA in the look-back window; a hard brake below the severe level in congested traffic or rain) are counted separately
and never gate rewards.
"""
from __future__ import annotations

import numpy as np

from data_spec.signals import BASE_HZ
from scoring_engine.habits import runs

HZ = BASE_HZ


def _events(mask, min_len=1):
    return runs(mask, min_len=min_len)


def risk_summary(trip, D, cfg) -> dict:
    c = cfg["risk_monitors"]
    a = D["a_long_g"]
    lat = D["a_lat_g"]
    v = D["v_kph"]
    lim = trip["speed_limit_kph"]
    fca = trip["tt_fca"] == 1
    lb = int(cfg["general"]["external_forcing_lookback_s"] * HZ)
    fca_near = np.convolve(fca.astype(float), np.ones(2 * lb + 1), mode="same") > 0
    adas = trip["cruise_active"] == 1
    # Outside the driver's control (submitted plan, sections 3-3 and 4-2): a short hard brake in congested traffic
    # (navigation) or in rain (wipers running) is recorded with that context and counted as forced, like braking
    # after an FCA. Only a hard brake that never reaches the severe level is excused: severe braking still counts
    # in any weather, so the reward gate for dangerous trips is unchanged.
    ctx = (trip["congestion"] >= 1) | (trip["wiper_active"] == 1)
    ctx_near = np.convolve(ctx.astype(float), np.ones(2 * lb + 1), mode="same") > 0
    severe = np.nan_to_num(a <= -c["severe_brake_g"], nan=0).astype(bool)

    def count(mask, excusable=False):
        evs = _events(np.nan_to_num(mask, nan=0).astype(bool) & ~adas)
        n_forced = sum(1 for s, e in evs
                       if fca_near[s:e].any() or (excusable and ctx_near[s:e].any() and not severe[s:e].any()))
        return len(evs) - n_forced, n_forced

    hb, hb_f = count(a <= -c["hard_brake_g"], excusable=True)
    sb, sb_f = count(a <= -c["severe_brake_g"])
    ha, _ = count(a >= c["hard_accel_g"])
    sa, _ = count(a >= c["severe_accel_g"])
    hl, _ = count(np.abs(lat) >= c["hard_lat_g"])
    over = np.isfinite(v) & np.isfinite(lim) & (v > lim * (1 + c["speeding_frac"]))
    sever = np.isfinite(v) & np.isfinite(lim) & (v > lim * (1 + c["severe_speeding_frac"]))
    sus = int(cfg["speed_limit"]["sustain_s_for_risk"] * HZ)
    spd = len(_events(over, sus))
    sspd = len(_events(sever, sus))
    school_over = int(np.any((trip["school_zone"] == 1) & np.isfinite(v) & (v > cfg["school_zone"]["limit_kph"] + 5)))
    km = float(D["s_m"][-1] / 1000)
    out = {
        "km": km,
        "hard_brake": hb, "hard_brake_forced": hb_f, "severe_brake": sb, "severe_brake_forced": sb_f,
        "hard_accel": ha, "severe_accel": sa, "hard_lateral": hl,
        "speeding_10s": spd, "severe_speeding_10s": sspd, "school_zone_overspeed": school_over,
        "fca_warnings": len(_events(fca)), "lka_warnings": len(_events(trip["tt_lka"] == 1)),
        "daw_warnings": len(_events(trip["tt_daw"] == 1)), "abs_esc": len(_events(trip["tt_abs_esc"] == 1)),
    }
    # reward gate (plan v0.4 F7): >20 % speeding or FCA => no XP/points; plus school-zone overspeed and
    # repeated unforced severe kinematic events.
    out["reward_blocked"] = bool(sspd > 0 or out["fca_warnings"] > 0 or school_over or (sb + sa) >= 2)
    return out
