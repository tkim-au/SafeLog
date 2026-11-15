"""
Trip viewer: draws one trip as a picture — the route seen from above, and the signals the car
gave (speed, brake, steering, blinker) with every judgment the engine made.
Green ▲ = praise shown to the driver, grey ● = abstained, red ✕ = internal miss (never shown).

    pip install matplotlib
    python -m tools.trip_viewer --data datasets/sim_v3 --trip 0            -> evaluation/reports/trip_<id>.png
    python -m tools.trip_viewer --data datasets/sumo_v1 --trip 1 --show     (opens a window)
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from evaluation.evaluate import load_records
from scoring_engine.engine import SafeLogEngine


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="datasets/sim_v3")
    ap.add_argument("--trip", type=int, default=0, help="index in the dataset (0 = first trip)")
    ap.add_argument("--minutes", type=float, default=None, help="only plot the first N minutes")
    ap.add_argument("--show", action="store_true")
    ap.add_argument("--out", default="evaluation/reports")
    a = ap.parse_args(argv)
    import matplotlib
    if not a.show:
        matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    have = {f.name for f in font_manager.fontManager.ttflist}
    plt.rcParams["font.family"] = [f for f in ("Malgun Gothic", "AppleGothic", "Noto Sans CJK KR", "DejaVu Sans") if f in have] or ["sans-serif"]
    plt.rcParams["axes.unicode_minus"] = False

    rec = load_records(a.data)[a.trip]
    trip = rec["obs"]
    res = SafeLogEngine().run_trip(trip)
    D = res["derived"]
    n = len(trip["t"]) if a.minutes is None else min(len(trip["t"]), int(a.minutes * 600))
    t = trip["t"][:n] / 60
    # route from above: integrate heading and speed (clean signals, for the picture only)
    clean = rec["clean"]
    hd = np.deg2rad(np.nan_to_num(clean["heading_deg"][:n]))
    v = np.nan_to_num(clean["speed_kph"][:n]) / 3.6
    x, y = np.cumsum(v * np.cos(hd)) / 10, np.cumsum(v * np.sin(hd)) / 10

    fig = plt.figure(figsize=(15, 9))
    gs = fig.add_gridspec(4, 2, width_ratios=[1, 2.2], hspace=0.35)
    ax_map = fig.add_subplot(gs[:, 0])
    ax_map.plot(x, y, color="0.6", lw=1.2)
    for j in res["judgments"]:
        if j.i_ref >= n:
            continue
        style = {1: ("^", "tab:green", 70), -1: ("o", "0.75", 18), 0: ("x", "tab:red", 40)}[j.outcome]
        ax_map.scatter(x[j.i_ref], y[j.i_ref], marker=style[0], c=style[1], s=style[2], zorder=3)
    ax_map.set_aspect("equal")
    ax_map.set_title(f"Route from above · trip {rec['id']} · {rec['style']}")
    ax_map.set_xlabel("m"); ax_map.set_ylabel("m")

    panels = [("speed km/h", D["v_kph"][:n], trip["speed_limit_kph"][:n]),
              ("brake pedal (0/1)", trip["brake_on"][:n].astype(float), None),
              ("steering °", trip["steer_deg"][:n], None),
              ("blinker", trip["turn_signal"][:n].astype(float), None)]
    axes = []
    for k, (name, sig, ref) in enumerate(panels):
        ax = fig.add_subplot(gs[k, 1], sharex=axes[0] if axes else None)
        ax.plot(t, sig, lw=0.8, color="tab:blue")
        if ref is not None:
            ax.plot(t, ref, lw=1, color="0.5", ls="--", label="limit")
            ax.legend(loc="upper right", fontsize=8)
        ax.set_ylabel(name)
        if k == 3:
            ax.set_yticks([0, 1, 2, 3], ["off", "L", "R", "haz"])
        axes.append(ax)
    top = axes[0]
    ymax = np.nanmax(D["v_kph"][:n]) if np.isfinite(D["v_kph"][:n]).any() else 100
    for j in res["judgments"]:
        if j.i_ref >= n or j.outcome != 1:
            continue
        top.scatter(t[j.i_ref], ymax * 1.05, marker="^", c="tab:green", s=30)
    axes[-1].set_xlabel("minutes")
    praises = sum(1 for j in res["judgments"] if j.outcome == 1)
    lines = " · ".join(g["text"] for g in res["rewards"]["good_points"])
    fig.suptitle(f"Shown to the driver: {lines}   ({praises} praises, XP {res['rewards']['xp']})", fontsize=11)
    Path(a.out).mkdir(parents=True, exist_ok=True)
    out = Path(a.out) / f"trip_{rec['id']}.png"
    fig.savefig(out, dpi=110, bbox_inches="tight")
    print(f"saved {out}")
    if a.show:
        plt.show()


if __name__ == "__main__":
    main()
