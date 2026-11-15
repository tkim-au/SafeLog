"""
SafeLog backend API (front-end spec section 11). JSON field names follow the Android models (camelCase).

Run:   uvicorn safelog_server.app:app --host 0.0.0.0 --port 8080
       (the Android emulator reaches the host at http://10.0.2.2:8080)

The car keeps working offline with the same rules (android/core ledger); when a server is configured it sends each
finished trip's facts (no raw driving data) and the server's ledger mirrors and backs up the driver's points.
"""
from __future__ import annotations

import os
import time
import uuid
from datetime import date, timedelta
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from . import rules
from .catalog import MISSIONS, QUIZ, SHOP, label, tier, weight
from .store import Store

app = FastAPI(title="SafeLog backend", version="1.0")
store = Store(os.environ.get("SAFELOG_DB", "safelog.db"))


def now_ms() -> int:
    return int(time.time() * 1000)


def need_driver(did: str) -> dict:
    d = store.driver(did)
    if d is None:
        raise HTTPException(404, f"driver {did} not found")
    return d


# ------------------------------------------------------------------------------------------------ models
class DriverIn(BaseModel):
    id: str | None = None
    name: str
    mode: str = "Training"


class MissionIn(BaseModel):
    id: str
    recommended: bool = False


class SceneIn(BaseModel):
    id: str
    time: str
    place: str
    what: str
    good: bool
    compare: str | None = None


class TripFactsIn(BaseModel):
    id: str
    startMs: int
    endMs: int
    km: float = Field(ge=0, le=3000)
    blocked: bool
    riskMoment: bool
    counts: dict[str, list[int]]
    tierXp: int = Field(ge=0, le=1000)
    missions: list[MissionIn] = []
    rain: bool = False
    night: bool = False
    scenes: list[SceneIn] = []
    summary: str = ""
    tags: list[str] = []
    identified: bool = True
    weakestHabit: str | None = None
    score: int = Field(default=100, ge=0, le=100)     # internal trip score for the 연수 모드 switch; never shown


class MetricIn(BaseModel):
    """One judged situation of a trip (data contribution): what it was, how it went, the measured value, the context."""
    habit: str = Field(max_length=40)
    success: bool
    value: float | None = None
    tS: int = Field(ge=0)
    rain: int = 0
    night: int = 0
    congestion: int = 0
    roadClass: int = 0


class FinishIn(BaseModel):
    driverId: str
    insuranceShare: bool = False
    facts: TripFactsIn
    # summary indicators for the data-contribution programme: empty unless the driver turned 데이터 기여 on
    metrics: list[MetricIn] = Field(default=[], max_length=300)


class ExchangeIn(BaseModel):
    requestId: str
    driverId: str
    itemId: str


class AnswerIn(BaseModel):
    choice: int
    ms: int | None = None       # when the driver answered (the car may sync later); defaults to now


class ConsentIn(BaseModel):
    recording: bool
    insurance: bool = False
    data: bool = False


# ------------------------------------------------------------------------------------------------ helpers
def driver_view(d: dict) -> dict:
    return {"id": d["id"], "name": d["name"], "mode": d["mode"], "level": rules.level_for(d["xp_total"])}


def briefing(d: dict, raining: bool, night: bool, ms: int) -> dict:
    today = rules.today(ms)
    offered = [m for m in MISSIONS.values() if not m["rainOnly"] or raining]
    rec = d.get("recommended")
    if not rec or rec["date"] != today.isoformat() or rec["id"] not in MISSIONS:
        rid = offered[(today - rules.EPOCH).days % len(offered)]["id"]     # the car picks from its habit profile
        d["recommended"] = rec = {"date": today.isoformat(), "id": rid}
    wk = rules.week_start(today)
    week = [t for t in d["trips"] if rules.week_start(rules.trip_date(t)) == wk]
    days = {rules.trip_date(t) for t in week if t["attended"]}
    return {
        "driverName": d["name"], "mode": d["mode"],
        "practices": [{"id": m["id"], "title": m["title"], "kind": m["kind"], "recommended": m["id"] == rec["id"], "points": m["points"]} for m in offered],
        "maxSelectable": 1,                                                 # one mission per drive (same as Rules.MAX_MISSIONS on the car)
        "pointsBalance": rules.balance(d), "nextReward": rules.next_reward(d, ms),
        "weekMissions": {"label": "이번 주 달성한 미션", "success": sum(len(t["cleared"]) for t in week), "chances": sum(len(t["missions"]) for t in week)},
        "attendance": {"daysThisWeek": [wk + timedelta(days=i) in days for i in range(7)],
                       "weekStreak": rules.week_streak(d, today, False)},
    }


def records(d: dict, ms: int) -> dict:
    today = rules.today(ms)
    wk = rules.week_start(today)
    visible = [t for t in d["trips"] if t["start_ms"] >= d.get("cleared_before_ms", 0)]
    this_week = [t for t in visible if rules.week_start(rules.trip_date(t)) == wk]
    good = sum(c[0] for t in this_week for c in t["counts"].values())
    safe = rules.safe_mileage(d, ms)
    return {"weekDrives": len(this_week), "goodMoments": good, "safeMileageKm": int(safe), "grade": rules.grade_of(safe)["name"],
            "journal": [{"date": rules.trip_date(t).isoformat(), "summary": t["summary"], "scenes": t["scenes"]}
                        for t in sorted(visible, key=lambda t: (-rules.trip_date(t).toordinal(), t["start_ms"]))],
            "insurance": rules.insurance_view(d, ms)}


# ------------------------------------------------------------------------------------------------ routes
@app.get("/health")
def health():
    return {"ok": True, "drivers": len(store.drivers())}


@app.get("/drivers")
def list_drivers():
    return [driver_view(d) for d in store.drivers()]


@app.post("/drivers")
def create_driver(body: DriverIn):
    with store.tx():
        did = body.id or f"driver-{uuid.uuid4().hex[:8]}"
        d = store.driver(did)
        if d is None:
            d = rules.new_driver(did, body.name, body.mode)
        d["name"] = body.name          # the mode is the ledger's (engine switch or PUT /mode), not the caller's
        store.put_driver(d)
    return driver_view(d)


class ModeIn(BaseModel):
    mode: str = Field(pattern="^(Training|Normal)$")


@app.put("/drivers/{did}/mode")
def put_mode(did: str, body: ModeIn):
    """The driver switched 연수 모드 by hand (parked). The engine's own switch happens in /finish from the trip facts."""
    with store.tx():
        d = need_driver(did)
        rules.set_mode(d, body.mode)
        store.put_driver(d)
    return driver_view(d)


class TourIn(BaseModel):
    """Practice summary (data contribution only): seconds from opening the tour to the last required step, the number
    of required steps, and how many of them were skipped at least once."""
    durationS: int | None = Field(default=None, ge=0)
    steps: int | None = Field(default=None, ge=0, le=50)
    skipped: int | None = Field(default=None, ge=0, le=50)


@app.post("/drivers/{did}/tour/complete")
def tour_complete(did: str, body: TourIn | None = None):
    """S05 내 차 익히기: all required steps done on the car. +50 XP and the badge, once per driver (repeats are no-ops)."""
    metrics = body.model_dump() if body is not None and body.steps is not None else None
    with store.tx():
        d = need_driver(did)
        granted = rules.tour_complete(d, metrics)
        store.put_driver(d)
    return {**driver_view(d), "granted": granted, "xpTotal": d["xp_total"]}


@app.get("/drivers/{did}/briefing")
def get_briefing(did: str, raining: bool = False, night: bool = False):
    with store.tx():
        d = need_driver(did)
        b = briefing(d, raining, night, now_ms())
        store.put_driver(d)
    return b


@app.post("/drives/{drive_id}/finish")
def finish_drive(drive_id: str, body: FinishIn):
    if body.facts.id != drive_id:
        raise HTTPException(400, "drive id mismatch")
    rid = f"finish:{body.driverId}:{drive_id}"
    with store.tx():
        if (prev := store.response(rid)) is not None:
            return prev
        d = need_driver(body.driverId)
        if not store.consent(body.driverId)["recording"]:
            raise HTTPException(409, "recording consent withdrawn")
        facts = body.facts.model_dump()
        facts["counts"] = {h: [int(c[0]), int(c[1])] for h, c in facts["counts"].items()}
        s = rules.settle(d, facts, body.insuranceShare)
        if body.metrics:
            rules.add_metrics(d, drive_id, [m.model_dump() for m in body.metrics])
        resp = {**s["arrival"], "pointsBalance": rules.balance(d), "breakdown": s["breakdown"]}
        store.put_driver(d)
        store.put_response(rid, body.driverId, resp)
    return resp


class DisputeIn(BaseModel):
    driverId: str
    what: str = Field(max_length=80)
    good: bool


@app.post("/drives/{drive_id}/scenes/{scene_id}/dispute")
def dispute_scene(drive_id: str, scene_id: str, body: DisputeIn):
    """오판정 의견: the driver says this journal scene was judged wrong. Kept for review; points do not change."""
    with store.tx():
        d = need_driver(body.driverId)
        new = rules.add_dispute(d, drive_id, scene_id, body.what, body.good, now_ms())
        store.put_driver(d)
    return {"ok": True, "new": new, "disputes": len(d.get("disputes", []))}


@app.get("/drivers/{did}/records")
def get_records(did: str):
    return records(need_driver(did), now_ms())


@app.get("/shop/items")
def shop_items():
    return SHOP


@app.post("/exchanges")
def post_exchange(body: ExchangeIn):
    rid = f"exchange:{body.driverId}:{body.requestId}"
    with store.tx():
        if (prev := store.response(rid)) is not None:
            return prev
        d = need_driver(body.driverId)
        item = next((x for x in SHOP if x["id"] == body.itemId), None)
        if item is None:
            resp = {"result": "SOLD_OUT", "pointsBalance": rules.balance(d), "nextReward": rules.next_reward(d, now_ms())}
        else:
            r = rules.exchange(d, item, body.requestId, now_ms())
            resp = {"result": r, "pointsBalance": rules.balance(d), "nextReward": rules.next_reward(d, now_ms())}
            if r == "OK":
                resp["coupon"] = {"code": uuid.uuid5(uuid.NAMESPACE_URL, rid).hex[:12].upper(), "sentTo": "phone"}
        store.put_driver(d)
        store.put_response(rid, body.driverId, resp)
    return resp


@app.get("/drivers/{did}/quiz/today")
def quiz_today(did: str):
    with store.tx():
        d = need_driver(did)
        st = rules.quiz_of(d, now_ms())
        store.put_driver(d)
    q = next(x for x in QUIZ if x["id"] == st["id"])
    out = {"quizId": q["id"], "question": q["question"], "choices": q["choices"], "picked": st["picked"]}
    if st["picked"] is not None:                               # answer and explanation only after answering (spec 12)
        out |= {"answer": q["answer"], "explanation": q["explanation"]}
    return out


@app.post("/drivers/{did}/quiz/{quiz_id}/answer")
def quiz_answer(did: str, quiz_id: str, body: AnswerIn):
    with store.tx():
        d = need_driver(did)
        at = body.ms or now_ms()
        if abs(at - now_ms()) > 7 * 86_400_000:
            raise HTTPException(409, "answer too old")
        st = rules.quiz_of(d, at)
        if st["id"] != quiz_id:
            raise HTTPException(409, "not that day's quiz")
        reward = rules.answer_quiz(d, body.choice, at)
        store.put_driver(d)
    q = next(x for x in QUIZ if x["id"] == quiz_id)
    return {"picked": d["quiz"]["picked"], "answer": q["answer"], "explanation": q["explanation"], "reward": reward,
            "pointsBalance": rules.balance(d)}


@app.get("/drivers/{did}/atlas")
def atlas(did: str):
    d = need_driver(did)
    xp, span = rules.level_progress(d["xp_total"])
    return {"name": d["atlas_name"], "level": rules.level_for(d["xp_total"]), "xp": xp, "xpToNext": span, "voice": d["voice"]}


@app.put("/drivers/{did}/atlas/voice/{voice}")
def set_voice(did: str, voice: str):
    with store.tx():
        d = need_driver(did)
        d["voice"] = voice
        store.put_driver(d)
    return {"voice": voice}


@app.post("/drivers/{did}/consent")
def consent(did: str, body: ConsentIn):
    """Withdrawing recording consent stops trip intake and insurance linkage (points already earned stay)."""
    with store.tx():
        need_driver(did)
        store.set_consent(did, body.recording, body.insurance and body.recording, body.data and body.recording, now_ms())
    return store.consent(did)


@app.delete("/drivers/{did}/records")
def clear_records(did: str):
    """Clears the journal and per-habit history only. Points, grade and insurance progress stay (screen text)."""
    with store.tx():
        d = need_driver(did)
        d["cleared_before_ms"] = now_ms() + 1
        store.put_driver(d)
    return {"ok": True}
