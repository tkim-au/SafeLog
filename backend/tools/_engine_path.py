"""Find the SafeLog engine repo (development/) so these tools can import it.

Order: --engine-root argument, SAFELOG_ENGINE_ROOT environment variable, then this repo's development/.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

CANDIDATES = [
    Path(__file__).resolve().parents[2] / "development",  # this repo: backend/tools -> development
]


def add_engine_root(explicit: str | None = None) -> Path:
    roots = [Path(explicit)] if explicit else []
    if os.environ.get("SAFELOG_ENGINE_ROOT"):
        roots.append(Path(os.environ["SAFELOG_ENGINE_ROOT"]))
    roots += CANDIDATES
    for r in roots:
        if (r / "scoring_engine" / "engine.py").exists():
            sys.path.insert(0, str(r))
            return r
    raise SystemExit("SafeLog engine not found. Pass --engine-root development "
                     "or set SAFELOG_ENGINE_ROOT.")
