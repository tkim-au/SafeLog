"""
thresholds.yaml (engine repo) -> android/core/src/main/resources/safelog/engine_config.json

The Kotlin engine reads every threshold from this file, so the PC (Python) and the car (Kotlin) always use the
same numbers. Re-run after editing thresholds.yaml:
    python backend/tools/export_engine_config.py --engine-root D:\\TF_Hyundai\\development
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from _engine_path import add_engine_root


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine-root", default=None)
    ap.add_argument("--android", default=str(Path(__file__).resolve().parents[2] / "android"))
    a = ap.parse_args(argv)
    root = add_engine_root(a.engine_root)
    from data_spec.signals import DATA_VERSION
    from scoring_engine.derive import load_config
    cfg = load_config()
    cfg["data_version"] = DATA_VERSION
    cfg["source"] = str(Path("scoring_engine/config/thresholds.yaml"))
    out = Path(a.android) / "core" / "src" / "main" / "resources" / "safelog" / "engine_config.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(cfg, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"wrote {out} (data contract v{DATA_VERSION}) from {root}")


if __name__ == "__main__":
    main()
