"""
Non-stop training loop for the dual-key gate (RTX 3060 Ti). Runs until you stop it.

Each ROUND:
  1. simulate a fresh batch of trips with NEW seeds and randomized conditions (sensor rate, dropouts,
     missing heading/navigation, steering noise, cluster speed bias, rain/night, extra cut-ins, driver type)
     -- done by CPU workers IN THE BACKGROUND while the GPU trains on the previous round
  2. add the samples to a rolling pool (newest first, capped by --max-samples)
  3. train an ensemble of K small TCNs on the GPU (different seeds/widths), mistakes the rules made
     (rule praised, truth says no) are oversampled x --hard-x
  4. temperature scaling + per-habit veto thresholds on the validation split
  5. CHAMPION vs CHALLENGER on a fixed holdout (seeds 9,000,000+, generated once, never trained on):
     the challenger replaces model_training/checkpoints/gate.pt only if it is at least as precise and keeps
     more true praise (or is more precise when the champion is below target). Every promoted model is
     also kept in model_training/checkpoints/history/.
  6. every --stress-every rounds: full stress test of the champion (evaluation/stress_test.py)

Stop:   Ctrl+C, or create the file  datasets/forever/STOP   (finishes the current step, then exits)
Resume: run the same command again -- it continues from datasets/forever/state.json

    python -m tools.train_forever --workers 6
"""
from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

TRAIN_SEED0 = 10_000_000
HOLDOUT_SEED0 = 9_000_000
CHAMPION = ROOT / "model_training/checkpoints/gate.pt"
HISTORY_DIR = ROOT / "model_training/checkpoints/history"
REPORTS = ROOT / "evaluation/reports"


# ----------------------------------------------------------------------------------------- data
def randomize(seed: int):
    """Domain randomization: every trip gets its own sensor quality, weather and traffic."""
    r = np.random.default_rng(seed ^ 0x5AFE)
    prof = {}
    if r.random() < 0.25:
        prof["speed_hz"] = int(r.choice([1, 2, 5]))
    if r.random() < 0.5:
        prof["dropouts"] = float(r.uniform(0, 4))
    if r.random() < 0.3:
        prof["p_heading"] = float(r.uniform(0, 1))
    if r.random() < 0.3:
        prof["p_nav"] = float(r.uniform(0, 1))
    if r.random() < 0.2:
        prof["p_navdata"] = float(r.uniform(0, 1))
    if r.random() < 0.3:
        prof["steer_noise"] = float(r.uniform(0, 1.2))
    if r.random() < 0.3:
        prof["max_speed_bias"] = float(r.uniform(1.0, 1.06))
    route = None
    if r.random() < 0.5:
        route = {}
        if r.random() < 0.3:
            route["rain"] = True
            route["heavy_rain"] = bool(r.random() < 0.3)
        if r.random() < 0.3:
            route["night"] = True
        if r.random() < 0.5:
            route["extra_forced_per_km"] = float(r.uniform(0.2, 1.5))   # the hard case for smooth_stop
        if r.random() < 0.3:
            route["kind"] = str(r.choice(["urban", "mixed", "rural", "long"]))
    arche = str(r.choice(["careful", "aggressive", "novice", "drowsy"])) if r.random() < 0.3 else None
    return arche, prof, route


def trip_samples(seed: int):
    """CPU worker: simulate one trip and turn its rule judgments into gate samples."""
    import warnings
    warnings.filterwarnings("ignore", category=RuntimeWarning)
    np.seterr(all="ignore")
    from model_training.build_samples import _samples
    from simulation.generate_dataset import _one
    arche, prof, route = randomize(seed)
    rec = _one(seed, arche, prof, route)
    X, S, Y, H, R, _sp = _samples(rec)
    n = len(Y)
    return (np.asarray(X, np.float16).reshape(n, *(np.shape(X)[1:] if n else (0, 0))),
            np.asarray(S, np.float16).reshape(n, -1) if n else np.zeros((0, 0), np.float16),
            np.asarray(Y, np.int8), np.asarray(H, np.int8), np.asarray(R, np.int8),
            np.full(n, seed, np.int64), len(rec["obs"]["t"]) / 36000)


def collect(parts):
    parts = [p for p in parts if len(p[2])]
    if not parts:
        return None
    return {"x": np.concatenate([p[0] for p in parts]), "s": np.concatenate([p[1] for p in parts]),
            "y": np.concatenate([p[2] for p in parts]), "habit": np.concatenate([p[3] for p in parts]),
            "rule_success": np.concatenate([p[4] for p in parts]), "trip": np.concatenate([p[5] for p in parts]),
            "hours": float(sum(p[6] for p in parts))}


def save_npz(path, d):
    from data_spec.signals import DATA_VERSION
    np.savez(path, **{k: v for k, v in d.items() if k not in ("hours", "data_version")}, hours=np.array(d["hours"]),
             data_version=np.array(DATA_VERSION))


def load_npz(path):
    z = np.load(path)
    d = {k: z[k] for k in z.files}
    d["hours"] = float(d["hours"])
    d["data_version"] = int(d.get("data_version", 1))
    return d


def load_pool(rounds_dir: Path, max_samples: int):
    files = sorted(rounds_dir.glob("round_*.npz"), reverse=True)        # newest first
    parts, n, hours = [], 0, 0.0
    from data_spec.signals import DATA_VERSION
    for f in files:
        d = load_npz(f)
        if d["data_version"] != DATA_VERSION:
            continue                      # never mix data contracts
        parts.append(d)
        n += len(d["y"])
        hours += d["hours"]
        if n >= max_samples:
            break
    pool = {k: np.concatenate([p[k] for p in parts])[:max_samples] for k in ("x", "s", "y", "habit", "rule_success", "trip")}
    return pool, len(parts), hours


# ----------------------------------------------------------------------------------------- model
def evaluate(models, T, th, d, dev):
    """Praise precision of rules vs rules+gate on a sample set (gate habits only)."""
    import torch
    from model_training.ensemble import calibrated_prob
    from model_training.features import GATE_HABITS
    x = torch.tensor(d["x"], device=dev)
    s = torch.tensor(d["s"], device=dev)
    p = calibrated_prob(models, x, s, T).float().cpu().numpy()
    y, h, r = d["y"], d["habit"], d["rule_success"]
    keep = np.zeros(len(y), bool)
    per = {}
    for k, name in enumerate(GATE_HABITS):
        m = (h == k) & (r == 1)
        km = m & (p >= th.get(name, 0.0))
        keep |= km
        if m.sum():
            per[name] = {"rule_prec": float(y[m].mean()), "gate_prec": float(y[km].mean()) if km.any() else float("nan"),
                         "kept": float(km.sum() / m.sum()), "n": int(m.sum())}
    rule = r == 1
    true_rule = int((y[rule] == 1).sum())
    return {"rule_precision": float(y[rule].mean()), "gate_precision": float(y[keep].mean()) if keep.any() else 0.0,
            "kept_true": int((y[keep] == 1).sum()), "true_rule": true_rule,
            "recall_kept": int((y[keep] == 1).sum()) / max(true_rule, 1),
            "false_praise": int((y[keep] == 0).sum()), "per_habit": per}


def better(ch, cp, target):
    """Is challenger `ch` better than champion `cp`? Precision first, then kept praise."""
    if ch["gate_precision"] < ch["rule_precision"]:
        return False                      # a gate must never be worse than the rules alone
    if cp is None:
        return True
    if cp["gate_precision"] >= target:
        return ch["gate_precision"] >= target and ch["kept_true"] > cp["kept_true"] and ch["false_praise"] <= cp["false_praise"] * 1.05 + 1
    return ch["gate_precision"] > cp["gate_precision"] + 1e-4


def train_ensemble(pool, args, round_no, dev, log):
    import torch
    from model_training.ensemble import fit_temperature, train_member
    from model_training.train import pick_thresholds
    val = (pool["trip"] * 2654435761 % 100) < 15                  # trip-level split: no leakage between trips
    tr = ~val
    idx = np.flatnonzero(tr)
    hard = idx[(pool["rule_success"][idx] == 1) & (pool["y"][idx] == 0)]
    if args.hard_x > 1 and len(hard):
        idx = np.concatenate([idx] + [hard] * (args.hard_x - 1))
    t = lambda a, m: torch.tensor(a[m], device=dev)
    xt, st, yt = t(pool["x"], idx), t(pool["s"], idx), t(pool["y"], idx)
    xv, sv, yv = t(pool["x"], val), t(pool["s"], val), t(pool["y"], val)
    log(f"  training {args.members} members on {len(idx):,} samples ({len(hard):,} hard x{args.hard_x}), val {int(val.sum()):,}")
    widths = [args.widths[k % len(args.widths)] for k in range(args.members)]
    models = []
    for k, w in enumerate(widths):
        m, vl = train_member(xt, st, yt, xv, sv, yv, width=w, epochs=args.epochs, lr=args.lr,
                             seed=round_no * 100 + k, bs=args.batch, log=log)
        models.append(m)
    T = fit_temperature(models, xv, sv, yv)
    from model_training.ensemble import calibrated_prob
    p = calibrated_prob(models, xv, sv, T).float().cpu().numpy()
    th, rep = pick_thresholds(p, pool["y"][val], pool["habit"][val], pool["rule_success"][val], args.target)
    del xt, st, yt, xv, sv, yv
    if dev == "cuda":
        torch.cuda.empty_cache()
    return models, widths, T, th, rep


# ----------------------------------------------------------------------------------------- machine care
def keep_awake(on=True):
    if sys.platform == "win32":
        import ctypes
        ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001
        ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS | (ES_SYSTEM_REQUIRED if on else 0))


def gpu_status():
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=temperature.gpu,utilization.gpu,memory.used,power.draw",
                              "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=10).stdout
        t, u, m, p = [float(v) for v in out.strip().split("\n")[0].split(",")]
        return {"temp_c": t, "util_pct": u, "mem_mb": m, "power_w": p}
    except Exception:
        return {}


def cool_down(max_temp, log, stop):
    while True:
        g = gpu_status()
        if not g or g["temp_c"] < max_temp or stop():
            return g
        log(f"  GPU at {g['temp_c']:.0f} C (limit {max_temp}) - pausing 60 s")
        time.sleep(60)


# ----------------------------------------------------------------------------------------- main loop
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=str(ROOT / "datasets/forever"))
    ap.add_argument("--workers", type=int, default=6, help="CPU processes simulating trips (leave 1-2 cores free)")
    ap.add_argument("--trips-per-round", type=int, default=400)
    ap.add_argument("--holdout-trips", type=int, default=600)
    ap.add_argument("--max-samples", type=int, default=250_000, help="rolling pool size (8 KB each on the GPU)")
    ap.add_argument("--members", type=int, default=3)
    ap.add_argument("--widths", type=int, nargs="+", default=[32, 48])
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--hard-x", type=int, default=3, help="oversample rule false-praise samples")
    ap.add_argument("--target", type=float, default=0.99)
    ap.add_argument("--stress-every", type=int, default=5)
    ap.add_argument("--stress-trips", type=int, default=30)
    ap.add_argument("--max-temp", type=float, default=83.0)
    ap.add_argument("--max-rounds", type=int, default=0, help="rounds to run in this session, 0 = forever")
    ap.add_argument("--keep-rounds", type=int, default=200, help="delete older round files beyond this")
    a = ap.parse_args(argv)

    import torch
    from model_training import ensemble

    base = Path(a.dir)
    (base / "rounds").mkdir(parents=True, exist_ok=True)
    HISTORY_DIR.mkdir(parents=True, exist_ok=True)
    REPORTS.mkdir(parents=True, exist_ok=True)
    logf = open(base / "train_forever.log", "a", encoding="utf-8")

    def log(msg):
        line = f"[{datetime.now():%m-%d %H:%M:%S}] {msg}"
        print(line, flush=True)
        logf.write(line + "\n")
        logf.flush()

    from data_spec.signals import DATA_VERSION
    old_state = base / "state.json"
    if old_state.exists():
        ov = json.loads(old_state.read_text(encoding="utf-8")).get("data_version", 1)
        if ov != DATA_VERSION:
            logf.close()
            arch = base.with_name(f"{base.name}_v{ov}_retired")
            base.rename(arch)
            (base / "rounds").mkdir(parents=True, exist_ok=True)
            logf = open(base / "train_forever.log", "a", encoding="utf-8")
            for f in ("forever_history.csv", "forever_progress.md"):
                if (REPORTS / f).exists():
                    (REPORTS / f).replace(REPORTS / f.replace(".", f"_v{ov}."))
            print(f"data contract changed v{ov} -> v{DATA_VERSION}: old rounds moved to {arch} (not usable), starting fresh")
    stop_file = base / "STOP"
    stop_file.unlink(missing_ok=True)
    stop = lambda: stop_file.exists()
    state_p = base / "state.json"
    state = json.loads(state_p.read_text(encoding="utf-8")) if state_p.exists() else {"round": 0, "champion": None, "promotions": 0}
    state["data_version"] = DATA_VERSION
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    log(f"start: device {dev} {torch.cuda.get_device_name(0) if dev == 'cuda' else '(no GPU! training will be slow)'}; "
        f"round {state['round']}; workers {a.workers}")
    keep_awake(True)
    ex = ProcessPoolExecutor(max_workers=a.workers)

    def gen(seeds):
        return ex.map(trip_samples, seeds, chunksize=4)

    try:
        # ---- fixed holdout (generated once)
        hold_p = base / "holdout.npz"
        if not hold_p.exists():
            log(f"generating fixed holdout: {a.holdout_trips} trips (seeds {HOLDOUT_SEED0}+) - one time only")
            hd = collect(list(gen(range(HOLDOUT_SEED0, HOLDOUT_SEED0 + a.holdout_trips))))
            save_npz(hold_p, hd)
        hold = load_npz(hold_p)
        log(f"holdout: {len(hold['y']):,} samples, {hold['hours']:.0f} driving hours")
        champ_eval = None
        if CHAMPION.exists():
            try:
                models, T, th, meta = ensemble.load(CHAMPION, dev)
                champ_eval = evaluate(models, T, th, hold, dev)
            except ensemble.IncompatibleModel as e:
                log(f"current gate.pt is not usable: {e}")
                retired = HISTORY_DIR / "gate_retired_old_data_contract.pt"
                if not retired.exists():
                    shutil.copy2(CHAMPION, retired)
                CHAMPION.replace(CHAMPION.with_name("gate_old_contract.pt.bak"))
        if champ_eval is not None:
            log(f"champion on holdout: precision {champ_eval['gate_precision'] * 100:.2f}% "
                f"(rules {champ_eval['rule_precision'] * 100:.2f}%), keeps {champ_eval['recall_kept'] * 100:.1f}% of true praise")

        hist_p = REPORTS / "forever_history.csv"
        new_hist = not hist_p.exists()
        hist = open(hist_p, "a", newline="", encoding="utf-8")
        hw = csv.writer(hist)
        if new_hist:
            hw.writerow(["time", "round", "pool_samples", "pool_hours", "gen_s", "train_s", "rule_prec", "challenger_prec",
                         "challenger_kept", "champion_prec", "champion_kept", "promoted", "gpu_temp", "gpu_util"])

        def seeds_for(r):
            s0 = TRAIN_SEED0 + r * 100_000
            return range(s0, s0 + a.trips_per_round)

        r = state["round"] + 1
        pending = gen(seeds_for(r)) if not (base / "rounds" / f"round_{r:05d}.npz").exists() else None
        done_this_run = 0
        while not stop():
            t0 = time.time()
            rp = base / "rounds" / f"round_{r:05d}.npz"
            if pending is not None:
                d = collect(list(pending))
                if d is not None:
                    save_npz(rp, d)
            gen_s = time.time() - t0
            # start simulating the NEXT round now, so the CPU works while the GPU trains
            pending = None if stop() else gen(seeds_for(r + 1))
            pool, n_rounds, hours = load_pool(base / "rounds", a.max_samples)
            log(f"round {r}: new samples ready ({gen_s:.0f} s wait); pool {len(pool['y']):,} samples from {n_rounds} rounds, ~{hours:.0f} h of driving")
            g = cool_down(a.max_temp, log, stop)
            t1 = time.time()
            models, widths, T, th, rep = train_ensemble(pool, a, r, dev, log)
            train_s = time.time() - t1
            ch = evaluate(models, T, th, hold, dev)
            promoted = better(ch, champ_eval, a.target)
            log(f"  challenger: precision {ch['gate_precision'] * 100:.2f}% keeps {ch['recall_kept'] * 100:.1f}%  |  champion: "
                + (f"{champ_eval['gate_precision'] * 100:.2f}% keeps {champ_eval['recall_kept'] * 100:.1f}%" if champ_eval else "none")
                + f"  ->  {'PROMOTED' if promoted else 'kept champion'}  (train {train_s:.0f} s)")
            meta = {"round": r, "pool_samples": int(len(pool["y"])), "pool_hours": hours, "holdout": ch,
                    "trained": datetime.now().isoformat(timespec="seconds"), "target": a.target, "val_report": rep}
            if promoted:
                tmp = CHAMPION.with_suffix(".tmp")
                ensemble.save(tmp, models, widths, T, th, meta)
                if CHAMPION.exists():
                    shutil.copy2(CHAMPION, HISTORY_DIR / f"gate_before_r{r:05d}.pt")
                tmp.replace(CHAMPION)                                  # atomic: never a half-written gate.pt
                shutil.copy2(CHAMPION, HISTORY_DIR / f"gate_r{r:05d}.pt")
                champ_eval = ch
                state["champion"] = r
                state["promotions"] += 1
            g2 = gpu_status() or g or {}
            hw.writerow([datetime.now().isoformat(timespec="seconds"), r, len(pool["y"]), round(hours), round(gen_s), round(train_s),
                         round(ch["rule_precision"], 5), round(ch["gate_precision"], 5), round(ch["recall_kept"], 4),
                         round(champ_eval["gate_precision"], 5) if champ_eval else "", round(champ_eval["recall_kept"], 4) if champ_eval else "", int(promoted),
                         g2.get("temp_c", ""), g2.get("util_pct", "")])
            hist.flush()
            state["round"] = r
            state_p.write_text(json.dumps(state, indent=2), encoding="utf-8")
            if champ_eval:
                write_progress(state, champ_eval, hist_p)
            old = sorted((base / "rounds").glob("round_*.npz"))[:-a.keep_rounds]
            for f in old:
                f.unlink()
            if a.stress_every and r % a.stress_every == 0 and not stop():
                log("  stress test of the champion ...")
                from evaluation.stress_test import run_stress
                run_stress(str(CHAMPION), a.stress_trips, a.workers, str(REPORTS), tag="latest", log=log)
            done_this_run += 1
            if a.max_rounds and done_this_run >= a.max_rounds:
                break
            r += 1
    except KeyboardInterrupt:
        log("Ctrl+C - stopping (progress is saved; run the same command to resume)")
    finally:
        ex.shutdown(wait=False, cancel_futures=True)
        keep_awake(False)
        log(f"stopped after round {state['round']}; champion from round {state['champion']}; {state['promotions']} promotions")
        logf.close()


def write_progress(state, champ, hist_p):
    rows = list(csv.DictReader(open(hist_p, encoding="utf-8")))
    lines = ["# Non-stop training progress", "",
             f"Rounds done: **{state['round']}** · promotions: **{state['promotions']}** · champion from round **{state['champion'] or 'original gate.pt'}**", "",
             f"Champion on the fixed holdout: praise precision **{champ['gate_precision'] * 100:.2f}%** "
             f"(rules alone {champ['rule_precision'] * 100:.2f}%), keeps **{champ['recall_kept'] * 100:.1f}%** of true praise, "
             f"{champ['false_praise']} false praises out of {champ['kept_true'] + champ['false_praise']}.", "",
             "| habit | rules precision | gate precision | praise kept | n |", "|---|---:|---:|---:|---:|"]
    for h, v in champ["per_habit"].items():
        lines.append(f"| {h} | {v['rule_prec'] * 100:.2f}% | {v['gate_prec'] * 100:.2f}% | {v['kept'] * 100:.1f}% | {v['n']} |")
    lines += ["", "Last 15 rounds:", "", "| round | pool | challenger precision | kept | promoted | GPU °C |", "|---:|---:|---:|---:|:-:|---:|"]
    for row in rows[-15:]:
        lines.append(f"| {row['round']} | {int(row['pool_samples']):,} | {float(row['challenger_prec']) * 100:.2f}% | "
                     f"{float(row['challenger_kept']) * 100:.1f}% | {'yes' if row['promoted'] == '1' else ''} | {row['gpu_temp']} |")
    (REPORTS / "forever_progress.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
