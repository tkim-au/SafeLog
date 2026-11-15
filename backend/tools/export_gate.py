"""
Turn the PC's trained gate (model_training/checkpoints/gate.pt) into the bundle the Android app loads.

    python backend/tools/export_gate.py --engine-root development \\
        --checkpoint development\\model_training\\checkpoints\\gate.pt \\
        --out android/app/src/main/assets/gate/gate_v3.json

What it checks and does
  * refuses a checkpoint trained on another data contract (DATA_VERSION must be 3, like the app)
  * folds BatchNorm into the convolutions (inference only), writes float32 weights as base64
  * copies temperature, per-habit veto thresholds and the feature spec (window, channels, habit order)
    -> the app refuses a bundle whose feature spec differs from its own GateFeatures.kt
  * --fixture also writes test vectors so the Kotlin TcnGate can be checked against PyTorch
Drop the output file into android/app/src/main/assets/gate/ and rebuild: the app switches from
"rules only" to "rules + gate" by itself (the debug demo bar shows which one is active). Without a rebuild:
adb push gate_v3.json /sdcard/Download/ and press "모델 불러오기" in the debug demo bar.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
from pathlib import Path

import numpy as np

from _engine_path import add_engine_root


def b64(t) -> str:
    a = np.ascontiguousarray(t.detach().cpu().numpy().astype("<f4"))
    return base64.b64encode(a.tobytes()).decode("ascii")


def fold(conv, bn):
    """y = BN(conv(x)) -> conv'(x) with w' = w * g / sqrt(var + eps), b' = (b - mean) * g / sqrt(var + eps) + beta."""
    import torch
    w, b = conv.weight.detach(), conv.bias.detach() if conv.bias is not None else torch.zeros(conv.out_channels)
    scale = bn.weight.detach() / torch.sqrt(bn.running_var.detach() + bn.eps)
    return w * scale[:, None, None], (b - bn.running_mean.detach()) * scale + bn.bias.detach()


def member_dict(net, width):
    import torch.nn as nn
    blocks = []
    for blk in list(net.net)[:4]:
        w, b = fold(blk.conv, blk.bn)
        res = blk.res
        blocks.append({
            "cin": blk.conv.in_channels, "cout": blk.conv.out_channels, "dilation": blk.conv.dilation[0],
            "conv_w": b64(w), "conv_b": b64(b),
            "res_w": None if isinstance(res, nn.Identity) else b64(res.weight[:, :, 0]),
            "res_b": None if isinstance(res, nn.Identity) else b64(res.bias),
        })
    out = list(net.net)[4]
    fc1, fc2 = net.head[0], net.head[3]
    return {"width": width, "blocks": blocks, "out_w": b64(out.weight), "out_b": b64(out.bias),
            "fc1_w": b64(fc1.weight), "fc1_b": b64(fc1.bias), "fc2_w": b64(fc2.weight), "fc2_b": b64(fc2.bias)}


def export(checkpoint: str, out: str) -> dict:
    import torch
    from data_spec.signals import DATA_VERSION
    from model_training import features as F
    from model_training.ensemble import load
    models, temperature, thresholds, meta = load(checkpoint, "cpu", require_version=True)
    ck = torch.load(checkpoint, map_location="cpu", weights_only=False)
    widths = [m["width"] for m in ck["members"]] if "members" in ck else [ck["width"]]
    bundle = {
        "format": "safelog-gate", "format_version": 1, "data_version": DATA_VERSION,
        "feature_spec": {"pre": F.PRE, "post": F.POST, "channels": F.CHANNELS, "gate_habits": F.GATE_HABITS, "static": F.S},
        "temperature": float(temperature), "thresholds": {k: float(v) for k, v in thresholds.items()},
        "meta": {**{k: v for k, v in (meta or {}).items() if isinstance(v, (int, float, str, bool))},
                 "exported_at": dt.datetime.now().isoformat(timespec="seconds"),
                 "source_checkpoint": Path(checkpoint).name,
                 "source_sha256": hashlib.sha256(Path(checkpoint).read_bytes()).hexdigest()[:16]},
        "members": [member_dict(m, w) for m, w in zip(models, widths)],
    }
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    Path(out).write_text(json.dumps(bundle), encoding="utf-8")
    return bundle


def read_parity_trip(android: Path, name: str) -> dict:
    """Reads a parity trip CSV exactly as the Kotlin side does (so both see identical numbers)."""
    import gzip
    from data_spec.signals import INT_CHANNELS
    raw = gzip.open(android / "core" / "src" / "test" / "resources" / "parity" / f"{name}.csv.gz", "rt").read()
    rows = [r.split(",") for r in raw.strip().split("\n")]
    hdr, body = rows[0], rows[1:]
    trip = {}
    for c, ch in enumerate(hdr):
        col = [r[c] for r in body]
        if ch in INT_CHANNELS:
            trip[ch] = np.array([int(x) if x else -1 for x in col], dtype=np.int8)
        else:
            trip[ch] = np.array([float(x) if x else np.nan for x in col], dtype=np.float64 if ch == "t" else np.float32)
    return trip


def expected_probs(models, temperature: float, android: Path, trip_name: str) -> dict:
    """PyTorch probabilities for every gated judgment of a parity trip (the Kotlin TcnGate must reproduce them)."""
    import torch
    from model_training.ensemble import calibrated_prob
    from model_training.features import GATE_HABITS, judgment_tensor
    from scoring_engine.engine import SafeLogEngine
    trip = read_parity_trip(android, trip_name)
    res = SafeLogEngine().run_trip(trip)
    D, out = res["derived"], []
    for j in res["judgments"]:
        if j.habit in GATE_HABITS and (j.outcome == 1 or j.meta.get("held")):
            x, s = judgment_tensor(trip, D, j)
            with torch.no_grad():
                p = float(calibrated_prob(models, torch.tensor(x[None]), torch.tensor(s[None]), temperature)[0])
            out.append({"habit": j.habit, "i_ref": int(j.i_ref), "p": p})
    return {"trip": trip_name, "judgments": out}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine-root", default=None)
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--android", default=str(Path(__file__).resolve().parents[2] / "android"))
    ap.add_argument("--fixture", default=None, metavar="TRIP",
                    help="also write Kotlin test vectors for this parity trip (e.g. lane_practice): "
                         "core/src/test/resources/gate/model_gate_v3.json + model_gate_expected.json")
    a = ap.parse_args(argv)
    add_engine_root(a.engine_root)
    b = export(a.checkpoint, a.out)
    print(f"wrote {a.out}: {len(b['members'])} members, T={b['temperature']:.3f}, thresholds={b['thresholds']}")
    if a.fixture:
        import warnings
        warnings.filterwarnings("ignore"); np.seterr(all="ignore")
        from model_training.ensemble import load
        models, temperature, _, _ = load(a.checkpoint, "cpu", require_version=True)
        for m in models:
            m.eval()
        res_dir = Path(a.android) / "core" / "src" / "test" / "resources" / "gate"
        res_dir.mkdir(parents=True, exist_ok=True)
        (res_dir / "model_gate_v3.json").write_text(Path(a.out).read_text(encoding="utf-8"), encoding="utf-8")
        exp = expected_probs(models, float(temperature), Path(a.android), a.fixture)
        (res_dir / "model_gate_expected.json").write_text(json.dumps(exp), encoding="utf-8")
        print(f"test vectors: {len(exp['judgments'])} gated judgments on {a.fixture} -> {res_dir}")


if __name__ == "__main__":
    main()
