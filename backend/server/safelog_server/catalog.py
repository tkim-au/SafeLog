"""Missions, shop, quiz bank, tiers and rule constants — loaded from backend/contract/catalog.json, which the
Android core module writes (ContractTest). One source of truth for the car and the server."""
from __future__ import annotations

import json
from pathlib import Path

CATALOG_PATH = Path(__file__).resolve().parents[2] / "contract" / "catalog.json"
CAT = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))

MISSIONS = {m["id"]: m for m in CAT["missions"]}
MISSION_BY_HABIT = {m["habit"]: m for m in CAT["missions"]}
SHOP = CAT["shop"]
QUIZ = CAT["quiz"]
TIER = CAT["tiers"]
TIER_WEIGHT = CAT["tierWeight"]
LABEL = CAT["labels"]
R = CAT["rules"]
GRADES = R["grades"]
MIN_OPPS_TO_REPORT = 3


def tier(h: str) -> str:
    return TIER.get(h, "C")


def weight(h: str) -> float:
    return float(TIER_WEIGHT.get(tier(h), 1.0))


def label(h: str) -> str:
    return LABEL.get(h, h)


def mission_passed(m: dict, success: int, chances: int) -> bool:
    return chances >= m["minChances"] and success / max(chances, 1) >= m["minRate"]
