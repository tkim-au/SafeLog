"""
Evaluate the engine against simulator ground truth.

    python -m evaluation.evaluate --data datasets/sim_v3 --split test [--gate model_training/checkpoints/gate.pt]

Reports, per habit: praise precision (how often a "잘했어요" is really deserved),
praise recall (how many deserved praises we actually give), false praise by cause,
unfair internal misses, and abstain counts. Also broken down by speed sample rate.
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import pickle
from collections import defaultdict
from pathlib import Path

from evaluation.truth import habit_metrics, match, truth_judgments
from scoring_engine.engine import SafeLogEngine


def load_records(data_dir: str, split: str | None = None, seed_mod=(70, 15, 15)):
    from data_spec.signals import DATA_VERSION
    meta_p = Path(data_dir) / "dataset.json"
    if not meta_p.exists():
        raise SystemExit(f"no dataset at {data_dir} (or one made before data contract v{DATA_VERSION}). "
                         f"Generate it: python -m simulation.generate_dataset --out {data_dir} --trips 300 --workers 6")
    v = json.loads(meta_p.read_text(encoding="utf-8")).get("data_version", 1)
    if v != DATA_VERSION:
        raise SystemExit(f"{data_dir} holds data contract v{v}, this code expects v{DATA_VERSION}. "
                         f"Regenerate it: python -m simulation.generate_dataset --out {data_dir} ...")
    recs = []
    for p in sorted(glob.glob(str(Path(data_dir) / "shard_*.pkl"))):
        with open(p, "rb") as f:
            recs.extend(pickle.load(f))
    if split is None:
        return recs
    a, b, _c = seed_mod
    def which(r):
        k = (r["id"] * 2654435761) % 100
        return "train" if k < a else "val" if k < a + b else "test"
    return [r for r in recs if which(r) == split]


def run_pairs(records, engine: SafeLogEngine):
    allpairs, by_rate = [], defaultdict(list)
    for r in records:
        res = engine.run_trip(r["obs"])
        tj = truth_judgments(r["clean"], r["truth"], engine.cfg)
        pairs = match(res["judgments"], tj)
        allpairs += pairs
        rate = "1Hz" if r["obs_meta"]["speed_hz"] < 2 else "5-10Hz"
        by_rate[rate] += pairs
    return allpairs, by_rate


def summarise(metrics: dict) -> dict:
    tot = defaultdict(int)
    for h, d in metrics.items():
        for k in ("engine_success", "tp", "false_praise", "truth_success", "unfair_miss", "engine_abstain"):
            tot[k] += d.get(k, 0)
    return {"praise_precision": tot["tp"] / max(tot["engine_success"], 1),
            "praise_recall": tot["tp"] / max(tot["truth_success"], 1),
            "false_praise": tot["false_praise"], "praises": tot["engine_success"],
            "unfair_miss": tot["unfair_miss"], "abstains": tot["engine_abstain"]}


def to_markdown(metrics: dict, title: str) -> str:
    lines = [f"### {title}", "", "| habit | praises | precision | recall | false praise (phantom/wrong/forced) | unfair internal miss | abstain |",
             "|---|---:|---:|---:|---|---:|---:|"]
    for h in sorted(metrics):
        d = metrics[h]
        f = lambda x: "–" if (isinstance(x, float) and math.isnan(x)) else f"{x:.3f}"
        lines.append(f"| {h} | {d.get('engine_success', 0)} | {f(d['praise_precision'])} | {f(d['praise_recall'])} | "
                     f"{d['false_praise']} ({d.get('fp_phantom', 0)}/{d.get('fp_wrong', 0)}/{d.get('fp_forced', 0)}) | "
                     f"{d.get('unfair_miss', 0)} | {d.get('engine_abstain', 0)} |")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="datasets/sim_v3")
    ap.add_argument("--split", default="test", help="train / val / test, or 'all' for SUMO and CARLA data")
    ap.add_argument("--gate", default=None)
    ap.add_argument("--out", default="evaluation/reports")
    a = ap.parse_args(argv)
    gate = None
    if a.gate:
        from model_training.gate import load_gate
        gate = load_gate(a.gate)
    eng = SafeLogEngine(ml_gate=gate)
    recs = load_records(a.data, None if a.split == "all" else a.split)
    pairs, by_rate = run_pairs(recs, eng)
    m = habit_metrics(pairs)
    tag = "dual_key" if gate else "rules_only"
    rep = {"split": a.split, "trips": len(recs), "mode": tag, "overall": summarise(m), "per_habit": m,
           "by_rate": {k: summarise(habit_metrics(v)) for k, v in by_rate.items()}}
    Path(a.out).mkdir(parents=True, exist_ok=True)
    Path(a.out, f"eval_{tag}_{a.split}.json").write_text(json.dumps(rep, indent=2, default=float), encoding="utf-8")
    Path(a.out, f"eval_{tag}_{a.split}.md").write_text(to_markdown(m, f"{tag} — {a.split} ({len(recs)} trips)"), encoding="utf-8")
    print(json.dumps({"overall": rep["overall"], "by_rate": rep["by_rate"]}, indent=2))


if __name__ == "__main__":
    main()
