"""
Live 2D view of the non-stop training (tools/train_forever.py), in the browser.

    .\\.venv\\Scripts\\python -m tools.live_view            (or double-click LIVE_VIEW.cmd)
    -> http://localhost:8765 opens by itself

What it shows
  * left: one trip of the CURRENT training round, re-simulated with the very same seed the trainer uses
    (TRAIN_SEED0 + round x 100,000 + k), drawn from above and played back: road and lanes, the car, blinkers,
    brake lights, wipers / rain, night, school zones, speed-limit signs, cut-ins and lead cars braking.
  * every judgment the rule engine makes pops up where it happened, with what the CHAMPION gate did:
      green  praise kept (truth agrees)        red    praise kept but truth disagrees (false praise)
      blue   gate blocked a false praise        orange gate blocked a true praise (lost)
      grey   held habit / internal miss (never shown in the app)
  * right: training progress from datasets/forever (round, pool, champion vs challenger, precision history).

Read-only: it never writes into datasets/forever or the checkpoints. It runs on the CPU, one trip at a time,
so the GPU training is not disturbed. Stop it with Ctrl+C.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import threading
import time
import warnings
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

FOREVER = ROOT / "datasets" / "forever"
CHAMPION = ROOT / "model_training" / "checkpoints" / "gate.pt"
HISTORY = ROOT / "evaluation" / "reports" / "forever_history.csv"
PAGE = Path(__file__).with_name("live_view.html")
TRAIN_SEED0 = 10_000_000
VIEW_HZ = 5                 # samples per second sent to the page (the trip is 10 Hz)
LANE_W = 3.5


# ----------------------------------------------------------------------------------------- training status
def _tail(path: Path, n: int) -> list[str]:
    if not path.exists():
        return []
    with open(path, "rb") as f:
        f.seek(0, 2)
        size = f.tell()
        f.seek(max(0, size - 64_000))
        data = f.read()
    for enc in ("utf-8", "utf-16"):
        try:
            text = data.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        text = data.decode("utf-8", errors="replace")
    return [ln.rstrip() for ln in text.splitlines() if ln.strip()][-n:]


_champ_cache: dict = {"mtime": None}


def champion():
    """The current gate.pt (reloaded when the trainer promotes a new one)."""
    if not CHAMPION.exists():
        return None
    mt = CHAMPION.stat().st_mtime
    if _champ_cache["mtime"] != mt:
        from model_training import ensemble
        try:
            models, T, th, meta = ensemble.load(str(CHAMPION), "cpu")
            for m in models:
                m.eval()
            _champ_cache.update(mtime=mt, models=models, T=float(T), th=th, meta=meta or {}, error=None)
        except Exception as e:                                    # e.g. an old data contract
            _champ_cache.update(mtime=mt, models=None, T=1.0, th={}, meta={}, error=str(e))
    return _champ_cache


def status() -> dict:
    state = {}
    p = FOREVER / "state.json"
    if p.exists():
        try:
            state = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            pass
    hist = []
    if HISTORY.exists():
        with open(HISTORY, newline="", encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        for r in rows[-400:]:
            def num(k):
                try:
                    return float(r.get(k) or "nan")
                except ValueError:
                    return float("nan")
            hist.append({"round": int(num("round")) if r.get("round") else None, "rule": num("rule_prec"),
                         "chPrec": num("challenger_prec"), "chKept": num("challenger_kept"),
                         "cpPrec": num("champion_prec"), "cpKept": num("champion_kept"),
                         "promoted": str(r.get("promoted", "")).lower() in ("1", "true", "yes"),
                         "hours": num("pool_hours"), "gpuTemp": num("gpu_temp"), "time": r.get("time")})
    log = FOREVER / "train_forever.log"
    alive = log.exists() and time.time() - log.stat().st_mtime < 900
    ch = champion()
    meta = (ch or {}).get("meta") or {}
    hold = meta.get("holdout") or {}
    return {
        "round": state.get("round"), "promotions": state.get("promotions"), "alive": alive,
        "log": _tail(log, 14),
        "champion": {"round": meta.get("round"), "trained": meta.get("trained"),
                     "precision": hold.get("gate_precision"), "rulePrecision": hold.get("rule_precision"),
                     "kept": hold.get("recall_kept"), "poolHours": meta.get("pool_hours"),
                     "thresholds": (ch or {}).get("th"), "error": (ch or {}).get("error")},
        "history": hist,
        "stop": (FOREVER / "STOP").exists(),
    }


# ----------------------------------------------------------------------------------------- one trip
def _slice(d: dict, n: int) -> dict:
    return {k: (v[:n] if isinstance(v, np.ndarray) and v.ndim >= 1 and len(v) >= n else v) for k, v in d.items()}


def _category(outcome: str, held: bool, gated: bool, kept: bool | None, truth_ok: bool | None) -> str:
    if outcome == "MISS":
        return "miss"
    if outcome == "ABSTAIN" and not held:
        return "abstain"
    if held and not gated:
        return "held"
    if gated and kept is False:
        return "blocked_good" if not truth_ok else "blocked_lost"
    if held:
        return "held"
    return "praise_ok" if truth_ok else "praise_false"


def trip(round_no: int, k: int, minutes: float) -> dict:
    warnings.filterwarnings("ignore")
    np.seterr(all="ignore")
    import torch
    from evaluation.truth import match, truth_judgments
    from model_training.ensemble import calibrated_prob
    from model_training.features import GATE_HABITS, judgment_tensor
    from scoring_engine.engine import SafeLogEngine
    from scoring_engine.habits import ABSTAIN, MISS, SUCCESS
    from scoring_engine.scoring import KO_LABEL
    from simulation.generate_dataset import _one
    from tools.train_forever import randomize

    t0 = time.time()
    seed = TRAIN_SEED0 + round_no * 100_000 + k
    arche, prof, route = randomize(seed)
    rec = _one(seed, arche, prof, route)
    n = min(len(rec["clean"]["t"]), int(minutes * 60 * 10))
    clean, obs = _slice(rec["clean"], n), _slice(rec["obs"], n)
    tr = dict(rec["truth"])
    for key in ("brake_pct", "true_accel", "forced_mask"):
        if isinstance(tr.get(key), np.ndarray):
            tr[key] = tr[key][:n]
    tr["turns"] = [x for x in tr["turns"] if x["i_turn"] < n]
    tr["lane_changes"] = [x for x in tr["lane_changes"] if x["i1"] < n]
    tr["forced"] = [x for x in tr["forced"] if x["i0"] < n]

    eng = SafeLogEngine()
    res = eng.run_trip(obs)
    tj = truth_judgments(clean, tr, eng.cfg)
    D = res["derived"]
    ch = champion()
    js = []
    for e, t in match(res["judgments"], tj):
        if e is None:
            continue
        held = bool(e.meta.get("held"))
        outcome = {SUCCESS: "SUCCESS", MISS: "MISS", ABSTAIN: "ABSTAIN"}.get(e.outcome, str(e.outcome))
        if outcome == "ABSTAIN" and not held:
            continue                                             # not judged: nothing to show
        gated = bool(ch and ch.get("models") and e.habit in GATE_HABITS and (e.outcome == SUCCESS or held))
        p = kept = None
        if gated:
            x, s = judgment_tensor(obs, D, e)
            with torch.no_grad():
                p = float(calibrated_prob(ch["models"], torch.tensor(x[None]), torch.tensor(s[None]), ch["T"])[0])
            kept = p >= float(ch["th"].get(e.habit, 0.5))
        truth_ok = None if t is None else bool(t.outcome == SUCCESS)
        js.append({"i": int(e.i_ref) // (10 // VIEW_HZ), "habit": e.habit, "label": KO_LABEL.get(e.habit, e.habit),
                   "outcome": outcome, "held": held, "gated": gated, "p": p, "kept": kept, "truth": truth_ok,
                   "cat": _category(outcome, held, gated, kept, truth_ok), "reason": e.reason})

    # --- 2D path: dead reckoning of the TRUE heading and speed; the road centre leaves out the lane offset
    step = 10 // VIEW_HZ
    v = np.nan_to_num(clean["speed_kph"].astype(float)) / 3.6
    h = np.radians(np.nan_to_num(clean["heading_deg"].astype(float)))
    x = np.cumsum(v * np.sin(h) * 0.1)
    y = np.cumsum(v * np.cos(h) * 0.1)
    off = np.zeros(n)
    lane = 0.0
    for lc in sorted(tr["lane_changes"], key=lambda q: q["i0"]):
        i0, i1 = int(lc["i0"]), int(lc["i1"])
        d = (1.0 if lc["dir"] == 2 else -1.0) * LANE_W            # 2 = right, 1 = left
        ramp = np.clip((np.arange(n) - i0) / max(1, i1 - i0), 0, 1)
        off = off + ramp * d
        lane += d
    cx = x - off * np.cos(h)                                      # right normal of compass heading = (cos h, -sin h)
    cy = y + off * np.sin(h)
    idx = np.arange(0, n, step)
    ints = lambda key: [int(z) for z in np.asarray(clean[key])[idx]]
    lim = np.asarray(clean["speed_limit_kph"], float)[idx]
    samples = {
        "t": [round(float(z), 1) for z in np.asarray(clean["t"])[idx]],
        "x": [round(float(z), 2) for z in x[idx]], "y": [round(float(z), 2) for z in y[idx]],
        "cx": [round(float(z), 2) for z in cx[idx]], "cy": [round(float(z), 2) for z in cy[idx]],
        "h": [round(float(z), 1) for z in np.degrees(h[idx])], "off": [round(float(z), 2) for z in off[idx]],
        "v": [round(float(z), 1) for z in (v[idx] * 3.6)], "lim": [None if not math.isfinite(z) else int(z) for z in lim],
        "brake": ints("brake_on"), "sig": ints("turn_signal"), "wiper": ints("wiper_active"), "night": ints("night"),
        "school": ints("school_zone"), "fca": ints("tt_fca"),
    }
    events = [{"kind": f["kind"], "i0": int(f["i0"]) // step, "i1": int(f["i1"]) // step, "fca": bool(f.get("fca"))} for f in tr["forced"]]
    turns = [{"i": int(q["i_turn"]) // step, "dir": int(q["dir"])} for q in tr["turns"]]
    lanes = [{"i0": int(q["i0"]) // step, "i1": int(q["i1"]) // step, "dir": int(q["dir"]), "sig": q.get("sig_mode")} for q in tr["lane_changes"]]
    style = rec.get("style") or {}
    return {
        "seed": seed, "round": round_no, "k": k, "archetype": arche, "profile": prof, "route": route,
        "style": style if isinstance(style, str) else str(style), "minutes": round(n / 600, 1), "fullMinutes": round(len(rec["clean"]["t"]) / 600, 1),
        "hz": VIEW_HZ, "samples": samples, "judgments": js, "forced": events, "turns": turns, "laneChanges": lanes,
        "laneMin": round(float(off.min()), 2), "laneMax": round(float(off.max()), 2),
        "km": round(float(np.sum(v) * 0.1 / 1000), 2), "computeS": round(time.time() - t0, 1),
        "gate": {"error": (ch or {}).get("error"), "round": ((ch or {}).get("meta") or {}).get("round")},
    }


def _json(obj) -> bytes:
    """JSON without NaN / inf (the page gets null)."""
    def clean(o):
        if isinstance(o, float):
            return o if math.isfinite(o) else None
        if isinstance(o, dict):
            return {str(k): clean(v) for k, v in o.items()}
        if isinstance(o, (list, tuple)):
            return [clean(v) for v in o]
        if isinstance(o, (np.floating,)):
            return clean(float(o))
        if isinstance(o, (np.integer,)):
            return int(o)
        if isinstance(o, np.bool_):
            return bool(o)
        return o
    return json.dumps(clean(obj), default=str, ensure_ascii=False).encode("utf-8")


# ----------------------------------------------------------------------------------------- server
_lock = threading.Lock()          # one simulation at a time (CPU friendly)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code: int, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        try:
            if u.path in ("/", "/index.html"):
                return self._send(200, PAGE.read_bytes(), "text/html; charset=utf-8")
            if u.path == "/api/status":
                return self._send(200, _json(status()), "application/json; charset=utf-8")
            if u.path == "/api/trip":
                st = status()
                r = int(q.get("round", [st.get("round") or 1])[0])
                k = int(q.get("k", [0])[0])
                minutes = float(q.get("minutes", [15])[0])
                with _lock:
                    data = trip(r, k, minutes)
                return self._send(200, _json(data), "application/json; charset=utf-8")
            return self._send(404, b"not found", "text/plain")
        except Exception as e:                                    # show the error on the page instead of a dead socket
            import traceback
            return self._send(500, _json({"error": f"{type(e).__name__}: {e}", "trace": traceback.format_exc()[-2000:]}), "application/json; charset=utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true")
    a = ap.parse_args(argv)
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    url = f"http://localhost:{a.port}"
    print(f"SafeLog live view: {url}   (read-only; Ctrl+C to stop)")
    if not a.no_browser:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
