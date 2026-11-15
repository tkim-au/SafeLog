"""
Ensemble of gate models (deep ensemble): K small TCNs trained with different seeds and widths,
probabilities averaged. Ensembles give better-calibrated confidence than one model, which is
exactly what a veto threshold needs. Used by tools/train_forever.py.
"""
from __future__ import annotations

import numpy as np
import torch
import torch.nn as nn

from data_spec.signals import DATA_VERSION
from model_training.tcn import GateTCN


class IncompatibleModel(Exception):
    """The checkpoint was trained on a different data contract (data_spec.signals.DATA_VERSION)."""


def _batches(n, bs, dev, shuffle=True):
    idx = torch.randperm(n, device=dev) if shuffle else torch.arange(n, device=dev)
    for i in range(0, n, bs):
        yield idx[i:i + bs]


def train_member(xt, st, yt, xv, sv, yv, width=32, epochs=20, lr=2e-3, seed=0, bs=512, log=print):
    """x tensors may be float16 on the GPU (half the memory); each batch is cast to float32."""
    dev = xt.device
    torch.manual_seed(seed)
    model = GateTCN(width).to(dev)
    pos = float(yt.float().mean())
    lossf = nn.BCEWithLogitsLoss(pos_weight=torch.tensor((1 - pos) / max(pos, 1e-3), device=dev).clamp(0.2, 5))
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=lr, total_steps=epochs * ((len(yt) + bs - 1) // bs))
    best, best_state = 1e9, None
    for ep in range(epochs):
        model.train()
        tot = 0.0
        for b in _batches(len(yt), bs, dev):
            opt.zero_grad()
            loss = lossf(model(xt[b].float(), st[b].float()), yt[b].float())
            loss.backward()
            opt.step()
            sched.step()
            tot += float(loss.detach()) * len(b)
        vl = float(nn.functional.binary_cross_entropy_with_logits(predict_logits(model, xv, sv), yv.float()))
        if vl < best:
            best, best_state = vl, {k: v.detach().clone() for k, v in model.state_dict().items()}
        if ep % 5 == 4 or ep == epochs - 1:
            log(f"    member seed={seed} width={width} epoch {ep + 1}/{epochs} train {tot / len(yt):.4f} val {vl:.4f}")
    model.load_state_dict(best_state)
    model.eval()
    return model, best


def predict_logits(model, x, s, bs=4096):
    model.eval()
    out = []
    with torch.no_grad():
        for i in range(0, len(x), bs):
            out.append(model(x[i:i + bs].float(), s[i:i + bs].float()))
    return torch.cat(out) if out else torch.zeros(0, device=x.device)


def ensemble_prob(models, x, s, temperature=1.0):
    ps = [torch.sigmoid(predict_logits(m, x, s) / temperature) for m in models]
    return torch.stack(ps).mean(0)


def fit_temperature(models, xv, sv, yv):
    """One temperature on the averaged logit (logit of the mean probability)."""
    p = ensemble_prob(models, xv, sv).clamp(1e-6, 1 - 1e-6)
    logit = torch.log(p / (1 - p))
    T = torch.ones(1, device=xv.device, requires_grad=True)
    opt = torch.optim.LBFGS([T], lr=0.1, max_iter=100)

    def closure():
        opt.zero_grad()
        l = nn.functional.binary_cross_entropy_with_logits(logit / T, yv.float())
        l.backward()
        return l
    opt.step(closure)
    return float(T.detach().clamp(0.3, 5.0))


def calibrated_prob(models, x, s, temperature):
    p = ensemble_prob(models, x, s).clamp(1e-6, 1 - 1e-6)
    return torch.sigmoid(torch.log(p / (1 - p)) / temperature)


def save(path, models, widths, temperature, thresholds, meta=None):
    meta = dict(meta or {})
    meta.setdefault("data_version", DATA_VERSION)
    torch.save({"members": [{"state": {k: v.cpu() for k, v in m.state_dict().items()}, "width": w}
                            for m, w in zip(models, widths)],
                "temperature": temperature, "thresholds": thresholds, "meta": meta}, path)


def checkpoint_version(ck) -> int:
    return int((ck.get("meta") or {}).get("data_version", 1))      # checkpoints without a version are v1


def load(path, device="cpu", require_version=True):
    ck = torch.load(path, map_location=device, weights_only=False)
    if "members" not in ck:   # single-model checkpoint from model_training/train.py
        ck = {"members": [{"state": ck["state"], "width": ck["width"]}], "temperature": ck["temperature"],
              "thresholds": ck["thresholds"], "meta": ck.get("meta", {})}
    v = checkpoint_version(ck)
    if require_version and v != DATA_VERSION:
        raise IncompatibleModel(f"{path} was trained on data contract v{v}; this code expects v{DATA_VERSION}. "
                                "Retrain (tools/train_forever.py or model_training/train.py) - the old model is not usable.")
    models = []
    for m in ck["members"]:
        net = GateTCN(m["width"]).to(device)
        net.load_state_dict(m["state"])
        net.eval()
        models.append(net)
    return models, ck["temperature"], ck["thresholds"], ck.get("meta", {})
