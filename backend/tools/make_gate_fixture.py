"""
Test fixture for the Kotlin gate (NOT a trained model): a randomly initialised v3 ensemble with randomised
BatchNorm statistics, its exported bundle, and the probabilities PyTorch gives for the gated judgments of a
parity trip. The Kotlin GateParityTest must reproduce them. The app's assets stay empty until the real model
from the PC is exported with export_gate.py.
"""
from __future__ import annotations

import argparse
import json
import math
import warnings
from pathlib import Path

import numpy as np

from _engine_path import add_engine_root


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine-root", default=None)
    ap.add_argument("--android", default=str(Path(__file__).resolve().parents[2] / "android"))
    ap.add_argument("--trip", default="commute_city")
    a = ap.parse_args(argv)
    add_engine_root(a.engine_root)
    warnings.filterwarnings("ignore"); np.seterr(all="ignore")
    import torch
    from export_gate import export, expected_probs
    from model_training.ensemble import save
    from model_training.features import GATE_HABITS
    from model_training.tcn import GateTCN

    torch.manual_seed(7)
    models, widths = [], [32, 48, 32]
    for w in widths:
        m = GateTCN(w)
        for blk in list(m.net)[:4]:
            blk.bn.running_mean.uniform_(-0.3, 0.3)
            blk.bn.running_var.uniform_(0.5, 2.0)
            with torch.no_grad():
                blk.bn.weight.uniform_(0.5, 1.5)
                blk.bn.bias.uniform_(-0.2, 0.2)
        with torch.no_grad():                      # spread the outputs so every path of the network matters
            m.head[0].weight.mul_(4.0)
            m.head[3].weight.mul_(25.0)
        m.eval()
        models.append(m)
    res_dir = Path(a.android) / "core" / "src" / "test" / "resources" / "gate"
    res_dir.mkdir(parents=True, exist_ok=True)
    ck = res_dir / "random_gate.pt"
    thresholds = {h: 0.5 for h in GATE_HABITS}
    save(str(ck), models, widths, 1.3, thresholds, {"note": "random weights, test fixture only"})
    export(str(ck), str(res_dir / "random_gate_v3.json"))
    ck.unlink()

    exp = expected_probs(models, 1.3, Path(a.android), a.trip)
    out = exp["judgments"]
    (res_dir / "random_gate_expected.json").write_text(json.dumps(exp), encoding="utf-8")
    print(f"gate fixture: {len(out)} gated judgments")


if __name__ == "__main__":
    main()
