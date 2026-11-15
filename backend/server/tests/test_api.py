import json
import os
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

os.environ["SAFELOG_DB"] = str(Path(tempfile.mkdtemp()) / "test.db")
from safelog_server.app import app  # noqa: E402

CASES = json.loads((Path(__file__).resolve().parents[2] / "contract" / "ledger_cases.json").read_text(encoding="utf-8"))["cases"]


@pytest.fixture()
def client():
    return TestClient(app)


def test_finish_is_idempotent_and_matches_the_car(client):
    client.post("/drivers", json={"id": "minsu", "name": "민수"})
    case = next(c for c in CASES if c["name"] == "first_month")
    for step, exp in zip(case["steps"], case["expect"]):
        if step["op"] != "trip":
            continue
        f = step["facts"]
        r = client.post(f"/drives/{f['id']}/finish", json={"driverId": "minsu", "insuranceShare": step["insuranceShare"], "facts": f})
        assert r.status_code == 200, r.text
        a = r.json()
        assert a["points"] == exp["points"] and a["xp"] == exp["xp"] and a["pointsBalance"] == exp["balance"]
        again = client.post(f"/drives/{f['id']}/finish", json={"driverId": "minsu", "insuranceShare": step["insuranceShare"], "facts": f})
        assert again.json() == a                                   # retried upload: no double points


def test_exchange_once_per_request_and_quiz_hides_answer(client):
    client.post("/drivers", json={"id": "jihoon", "name": "지훈"})
    q = client.get("/drivers/jihoon/quiz/today").json()
    assert "answer" not in q
    a = client.post(f"/drivers/jihoon/quiz/{q['quizId']}/answer", json={"choice": 0}).json()
    assert a["reward"] in (2, 5) and "explanation" in a
    a2 = client.post(f"/drivers/jihoon/quiz/{q['quizId']}/answer", json={"choice": 1}).json()
    assert a2["reward"] == 0
    r = client.post("/exchanges", json={"requestId": "x1", "driverId": "jihoon", "itemId": "store5"}).json()
    assert r["result"] == "NOT_ENOUGH_POINTS"
    assert len(client.get("/shop/items").json()) == 10


def test_briefing_and_records_shape(client):
    client.post("/drivers", json={"id": "seoyeon", "name": "서연"})
    b = client.get("/drivers/seoyeon/briefing").json()
    assert b["driverName"] == "서연" and any(p["recommended"] for p in b["practices"]) and len(b["attendance"]["daysThisWeek"]) == 7
    rec = client.get("/drivers/seoyeon/records").json()
    assert rec["grade"] == "Basic" and rec["journal"] == []
    client.post("/drivers/seoyeon/consent", json={"recording": False})
    case = next(c for c in CASES if c["name"] == "first_month")
    f = case["steps"][0]["facts"]
    r = client.post(f"/drives/{f['id']}/finish", json={"driverId": "seoyeon", "facts": f})
    assert r.status_code == 409                                   # no intake without consent


def test_training_mode_and_car_tour_over_http(client):
    """The 연수 모드 golden case over the API: the engine's switch comes from the trip facts, the driver's own from PUT /mode."""
    client.post("/drivers", json={"id": "mina", "name": "미나"})
    case = next(c for c in CASES if c["name"] == "training_mode")
    for step, exp in zip(case["steps"], case["expect"]):
        if step["op"] == "mode":
            assert client.put("/drivers/mina/mode", json={"mode": step["mode"]}).json()["mode"] == exp["mode"]
        elif step["op"] == "tour":
            r = client.post("/drivers/mina/tour/complete").json()
            assert r["xpTotal"] == exp["xpTotal"]
        else:
            f = step["facts"]
            a = client.post(f"/drives/{f['id']}/finish", json={"driverId": "mina", "insuranceShare": step["insuranceShare"], "facts": f}).json()
            assert a["points"] == exp["points"] and a["breakdown"] == exp["breakdown"], (f["id"], a, exp)
            assert next(x for x in client.get("/drivers").json() if x["id"] == "mina")["mode"] == exp["mode"]
    # POST /drivers again (the car's sync) does not overwrite the ledger's mode
    before = next(x for x in client.get("/drivers").json() if x["id"] == "mina")["mode"]
    client.post("/drivers", json={"id": "mina", "name": "미나", "mode": "Training" if before == "Normal" else "Normal"})
    assert next(x for x in client.get("/drivers").json() if x["id"] == "mina")["mode"] == before
    assert client.put("/drivers/mina/mode", json={"mode": "Sometimes"}).status_code == 422


def test_dispute_tour_metrics_and_contributed_indicators(client):
    """오판정 의견 is kept once per scene, and the data-contribution summaries are stored only when the car sends them."""
    client.post("/drivers", json={"id": "eunyoung", "name": "은영"})
    case = next(c for c in CASES if c["name"] == "first_month")
    f = dict(case["steps"][0]["facts"], scenes=[{"id": "a0-1", "time": "08:12", "place": "서초대로", "what": "회전 45m 전 깜빡이", "good": True, "compare": None}])
    metrics = [{"habit": "turn_signal_turn", "success": True, "value": 45.0, "tS": 120, "rain": 0, "night": 0, "congestion": 1, "roadClass": 0}]
    r = client.post(f"/drives/{f['id']}/finish", json={"driverId": "eunyoung", "facts": f, "metrics": metrics})
    assert r.status_code == 200, r.text
    d1 = client.post(f"/drives/{f['id']}/scenes/a0-1/dispute", json={"driverId": "eunyoung", "what": "회전 45m 전 깜빡이", "good": True}).json()
    d2 = client.post(f"/drives/{f['id']}/scenes/a0-1/dispute", json={"driverId": "eunyoung", "what": "회전 45m 전 깜빡이", "good": True}).json()
    assert d1 == {"ok": True, "new": True, "disputes": 1} and d2 == {"ok": True, "new": False, "disputes": 1}
    t = client.post("/drivers/eunyoung/tour/complete", json={"durationS": 105, "steps": 8, "skipped": 1}).json()
    assert t["granted"] is True
    from safelog_server.app import store
    saved = store.driver("eunyoung")
    assert saved["contributed"] == [{"tripId": f["id"], "metrics": metrics}]
    assert saved["tour_metrics"] == {"durationS": 105, "steps": 8, "skipped": 1}
    assert saved["trips"][0]["scenes"][0]["disputed"] is True
    # a driver who did not opt in sends neither: nothing is stored
    client.post("/drivers", json={"id": "junghoon", "name": "정훈"})
    client.post(f"/drives/{f['id']}/finish", json={"driverId": "junghoon", "facts": f})
    assert client.post("/drivers/junghoon/tour/complete").json()["granted"] is True
    plain = store.driver("junghoon")
    assert "contributed" not in plain and "tour_metrics" not in plain
