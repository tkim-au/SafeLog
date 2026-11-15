"""
Train the dual-key gate on the GPU (RTX 3060 Ti: a few minutes for 100k samples).

    python -m model_training.train --samples datasets/sim_v3/samples.npz --out model_training/checkpoints/gate.pt

After training: temperature scaling on the validation split, then a per-habit veto threshold
chosen so that validation praise precision >= --target (default 0.99) while keeping as many
praises as possible. If the rules alone already reach the target for a habit, its threshold is 0.
"""
from __future__ import annotations

import argparse
import json

import numpy as np
import torch
import torch.nn as nn

from data_spec.signals import DATA_VERSION
from model_training.features import GATE_HABITS
from model_training.tcn import GateTCN


def _tensors(d, m, dev):
    return (torch.tensor(d["x"][m], device=dev), torch.tensor(d["s"][m], device=dev),
            torch.tensor(d["y"][m].astype(np.float32), device=dev))


def predict(model, x, s, bs=2048):
    model.eval()
    out = []
    with torch.no_grad():
        for i in range(0, len(x), bs):
            out.append(model(x[i:i + bs], s[i:i + bs]))
    return torch.cat(out) if out else torch.zeros(0)


def pick_thresholds(p, y, habit, rule, target):
    th, report = {}, {}
    for k, h in enumerate(GATE_HABITS):
        m = (habit == k) & (rule == 1)
        if m.sum() == 0:
            th[h] = 0.0
            continue
        base_prec = y[m].mean()
        best = 0.0
        if base_prec < target:
            found = False
            for t in np.linspace(0, 0.99, 100):
                keep = m & (p >= t)
                if keep.sum() >= max(5, 0.3 * m.sum()) and y[keep].mean() >= target:
                    best, found = float(t), True
                    break
            if not found:
                # target unreachable: maximise precision while keeping at least 60 % of the praises
                cands = [(y[m & (p >= t)].mean(), -t, t) for t in np.linspace(0, 0.99, 100)
                         if (m & (p >= t)).sum() >= 0.6 * m.sum()]
                best = float(max(cands)[2]) if cands else 0.0
        keep = m & (p >= best)
        th[h] = best
        report[h] = {"val_rule_precision": float(base_prec), "val_gate_precision": float(y[keep].mean()) if keep.any() else None,
                     "val_kept_fraction": float(keep.sum() / m.sum()), "threshold": best, "n": int(m.sum())}
    return th, report


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", default="datasets/sim_v3/samples.npz")
    ap.add_argument("--out", default="model_training/checkpoints/gate.pt")
    ap.add_argument("--epochs", type=int, default=25)
    ap.add_argument("--target", type=float, default=0.99)
    ap.add_argument("--width", type=int, default=32)
    a = ap.parse_args(argv)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print("device:", dev, torch.cuda.get_device_name(0) if dev == "cuda" else "")
    d = dict(np.load(a.samples))
    tr, va = d["split"] == 0, d["split"] == 1
    xt, st, yt = _tensors(d, tr, dev)
    xv, sv, yv = _tensors(d, va, dev)
    torch.manual_seed(0)
    model = GateTCN(a.width).to(dev)
    print("parameters:", sum(p.numel() for p in model.parameters()))
    pos = float(yt.mean())
    lossf = nn.BCEWithLogitsLoss(pos_weight=torch.tensor((1 - pos) / max(pos, 1e-3), device=dev).clamp(0.2, 5))
    opt = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.epochs)
    best, best_state = 1e9, None
    for ep in range(a.epochs):
        model.train()
        perm = torch.randperm(len(yt), device=dev)
        tot = 0.0
        for i in range(0, len(perm), 256):
            b = perm[i:i + 256]
            opt.zero_grad()
            loss = lossf(model(xt[b], st[b]), yt[b])
            loss.backward()
            opt.step()
            tot += float(loss.detach()) * len(b)
        sched.step()
        vl = float(nn.functional.binary_cross_entropy_with_logits(predict(model, xv, sv), yv))
        if vl < best:
            best, best_state = vl, {k: v.detach().clone() for k, v in model.state_dict().items()}
        print(f"epoch {ep + 1:02d} train {tot / len(yt):.4f} val {vl:.4f}")
    model.load_state_dict(best_state)
    # temperature scaling
    logits = predict(model, xv, sv)
    T = torch.ones(1, device=dev, requires_grad=True)
    o = torch.optim.LBFGS([T], lr=0.1, max_iter=100)
    def closure():
        o.zero_grad()
        l = nn.functional.binary_cross_entropy_with_logits(logits / T, yv)
        l.backward()
        return l
    o.step(closure)
    temp = float(T.detach())
    p = torch.sigmoid(logits / temp).cpu().numpy()
    th, rep = pick_thresholds(p, d["y"][va], d["habit"][va], d["rule_success"][va], a.target)
    torch.save({"state": model.cpu().state_dict(), "width": a.width, "temperature": temp, "thresholds": th,
                "target": a.target, "meta": {"data_version": DATA_VERSION}}, a.out)
    print(json.dumps({"temperature": temp, "thresholds": th, "val_report": rep}, indent=2))


if __name__ == "__main__":
    main()
