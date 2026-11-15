"""
Run the rule engine on every simulated trip, match to ground truth, and save one training
sample per rule judgment of the gated habits.

    python -m model_training.build_samples --data datasets/sim_v3 --out datasets/sim_v3/samples.npz
label y = 1 if the rule judgment would be a correct praise (truth SUCCESS), else 0.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor

import numpy as np

from evaluation.evaluate import load_records
from evaluation.truth import match, truth_judgments
from model_training.features import GATE_HABITS, judgment_tensor
from scoring_engine.derive import derive
from scoring_engine.engine import SafeLogEngine
from scoring_engine.habits import ABSTAIN, SUCCESS


def split_of(rid):
    k = (rid * 2654435761) % 100
    return 0 if k < 70 else 1 if k < 85 else 2


def _samples(rec):
    eng = SafeLogEngine()
    res = eng.run_trip(rec["obs"])
    tj = truth_judgments(rec["clean"], rec["truth"], eng.cfg)
    D = res["derived"]
    X, Sv, Y, H, R, SP = [], [], [], [], [], []
    for e, t in match(res["judgments"], tj):
        held = e is not None and e.meta.get("held")             # rule said SUCCESS, release gate holds it back
        if e is None or e.habit not in GATE_HABITS or (e.outcome == ABSTAIN and not held):
            continue
        x, s = judgment_tensor(rec["obs"], D, e)
        X.append(x); Sv.append(s)
        Y.append(1 if (t is not None and t.outcome == SUCCESS) else 0)
        H.append(GATE_HABITS.index(e.habit)); R.append(1 if (e.outcome == SUCCESS or held) else 0); SP.append(split_of(rec["id"]))
    return X, Sv, Y, H, R, SP


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="datasets/sim_v3")
    ap.add_argument("--out", default=None)
    ap.add_argument("--workers", type=int, default=2)
    a = ap.parse_args(argv)
    recs = load_records(a.data)
    acc = [[] for _ in range(6)]
    with ProcessPoolExecutor(max_workers=a.workers) as ex:
        for part in ex.map(_samples, recs, chunksize=4):
            for k in range(6):
                acc[k] += part[k]
    out = a.out or f"{a.data}/samples.npz"
    np.savez_compressed(out, x=np.array(acc[0], np.float32), s=np.array(acc[1], np.float32), y=np.array(acc[2], np.int8),
                        habit=np.array(acc[3], np.int8), rule_success=np.array(acc[4], np.int8), split=np.array(acc[5], np.int8))
    y = np.array(acc[2]); r = np.array(acc[4])
    print(f"samples={len(y)} rule_success={r.sum()} correct_praise={int((y[r == 1]).sum())} -> {out}")


if __name__ == "__main__":
    main()
