"""
One command for the whole loop: simulate -> label -> train gate -> evaluate -> benchmark -> docs.

    python run_pipeline.py --trips 3000 --workers 8          # full run on the team PC (GPU used for training)
    python run_pipeline.py --trips 120 --workers 2 --quick   # smoke test
"""
import argparse
import os
import subprocess
import sys
import time


def sh(*args):
    print("\n$ " + " ".join(args), flush=True)
    t0 = time.time()
    env = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8")   # Korean Windows defaults to cp949
    subprocess.run([sys.executable, "-m", *args], check=True, env=env)
    print(f"  ({time.time() - t0:.0f} s)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trips", type=int, default=600)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--data", default="datasets/sim_v3")
    ap.add_argument("--epochs", type=int, default=25)
    ap.add_argument("--skip-sim", action="store_true")
    ap.add_argument("--quick", action="store_true")
    a = ap.parse_args()
    if not a.skip_sim:
        sh("simulation.generate_dataset", "--trips", str(a.trips), "--out", a.data, "--workers", str(a.workers))
    sh("model_training.build_samples", "--data", a.data, "--workers", str(a.workers))
    sh("model_training.train", "--samples", f"{a.data}/samples.npz", "--epochs", str(5 if a.quick else a.epochs))
    sh("evaluation.evaluate", "--data", a.data, "--split", "test")
    sh("evaluation.evaluate", "--data", a.data, "--split", "test", "--gate", "model_training/checkpoints/gate.pt")
    sh("tools.benchmark_runtime", "--data", a.data, "--trips", "10")
    sh("docs.build_docs")
    print("\nDone. Open docs/logic_board.html and evaluation/reports/*.md")


if __name__ == "__main__":
    main()
