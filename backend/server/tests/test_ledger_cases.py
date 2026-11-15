"""The server ledger must reproduce every result the car's ledger (Kotlin) gives — backend/contract/ledger_cases.json."""
import json
from pathlib import Path

import pytest

from safelog_server import rules
from safelog_server.catalog import SHOP

CASES = json.loads((Path(__file__).resolve().parents[2] / "contract" / "ledger_cases.json").read_text(encoding="utf-8"))["cases"]


def run(steps):
    d = rules.new_driver("case", "케이스")
    out = []
    for st in steps:
        op = st["op"]
        if op == "trip":
            s = rules.settle(d, st["facts"], st["insuranceShare"])
            a = s["arrival"]
            ins = a["insurance"]
            out.append({"points": a["points"], "breakdown": s["breakdown"], "xp": a["xp"], "xpToNextLevel": a["xpToNextLevel"],
                        "balance": rules.balance(d), "safeKmToday": a["safeKmToday"], "practiceCleared": a["practiceCleared"],
                        "practicePoints": a["practicePoints"], "nextChallenge": a["nextChallenge"], "noRewardReason": a["noRewardReason"],
                        "topHabit": [a["topHabit"]["label"], a["topHabit"]["success"], a["topHabit"]["chances"]] if a["topHabit"] else None,
                        "otherHabits": [[x["label"], x["success"], x["chances"]] for x in a["otherHabits"]],
                        "nextReward": [a["nextReward"]["itemName"], a["nextReward"]["remainingPoints"], a["nextReward"]["price"]],
                        "insurance": [ins["safeKm"], ins["goalKm"], ins["renewal"], ins["monthsToRenewal"], ins["avgTripKm"]] if ins else None,
                        "level": rules.level_for(d["xp_total"]), "mode": d["mode"], "modeNotice": d["mode_notice"]})
            d["mode_notice"] = None
        elif op == "mode":
            rules.set_mode(d, st["mode"])
            out.append({"mode": d["mode"]})
        elif op == "tour":
            rules.tour_complete(d)
            out.append({"xpTotal": d["xp_total"], "level": rules.level_for(d["xp_total"])})
        elif op == "quiz":
            q = rules.quiz_of(d, st["now"])
            out.append({"quizId": q["id"], "earned": rules.answer_quiz(d, st["choice"], st["now"]), "balance": rules.balance(d)})
        elif op == "exchange":
            item = next(x for x in SHOP if x["id"] == st["item"])
            out.append({"result": rules.exchange(d, item, st["request"], st["now"]), "balance": rules.balance(d)})
        elif op == "seed":
            d["entries"].append({"id": "seed", "ms": st["now"], "kind": "seed", "points": st["points"], "ref": ""})
            out.append({"balance": rules.balance(d)})
    return out


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_same_results_as_the_car(case):
    got = run(case["steps"])
    for k, (g, e) in enumerate(zip(got, case["expect"])):
        assert g == e, f"step {k} ({case['steps'][k]['op']}): server {g} != car {e}"
    assert len(got) == len(case["expect"])
