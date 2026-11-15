package com.safelog.core.engine

import com.safelog.core.contract.Trip
import com.safelog.core.gate.PraiseGate

/** One parked-phase run of the engine on a recorded trip. */
class TripResult(
    val derived: Derived?,
    val judgments: List<Judgment>,
    val risk: RiskSummary,
    val rewards: Scoring.TripRewards,
    val scenes: List<Judgment>,
    val skipped: String?,
)

/**
 * Port of scoring_engine/engine.py. Rules -> optional learned gate (veto only) -> release hold.
 * The gate is applied BEFORE the release hold so held habits are gated too (their dual-key precision can be measured).
 */
class SafeLogEngine(val cfg: EngineConfig = EngineConfig.bundled(), var gate: PraiseGate? = null) {

    fun runTrip(trip: Trip, practice: List<String> = emptyList()): TripResult {
        if (!judgeable(trip)) {
            return TripResult(null, emptyList(), RiskSummary(), Scoring.tripRewards(emptyList(), null, practice), emptyList(), "trip_too_short")
        }
        val d = Derive.derive(trip, cfg)
        rememberCalibration(d)
        var judgments: List<Judgment> = Habits.runEventHabits(trip, d, cfg) + Habits.runWindowHabits(trip, d, cfg)
        gate?.let { judgments = it.apply(trip, d, judgments) }
        val hold = cfg.notReleased
        for (j in judgments) {
            if (j.habit in hold && j.outcome == Outcome.SUCCESS) {
                j.outcome = Outcome.ABSTAIN
                j.reason = "habit_not_released"
                j.meta["held"] = true
            }
        }
        val risk = Risk.summary(trip, d, cfg)
        val rewards = Scoring.tripRewards(judgments, risk, practice)
        return TripResult(d, judgments, risk, rewards, Scoring.bestScenes(judgments), null)
    }

    /** Per-car memory: steering sign / unit / ratio learned on one trip is reused on later trips (persisted by the app). */
    private fun rememberCalibration(d: Derived) {
        val veh = cfg.vehicle
        if (d.steerSignSource == "heading" || d.steerSignSource == "navigation_turns") {
            veh.steerSign = d.steerSign; veh.steerSignLearned = true
        }
        if (d.steerUnitSource == "range" || d.steerUnitSource == "heading") veh.steerUnitLearned = d.steerUnit
        if (d.ratioScaleSource == "heading") {
            val old = veh.ratioScale
            veh.ratioScale = if (old == null || old == 0.0) d.ratioScale else 0.7 * old + 0.3 * d.ratioScale
        }
    }

    private fun judgeable(trip: Trip): Boolean {
        val t = trip.t
        if (trip.n < 2 || !Np.isFin(t[0]) || !Np.isFin(t[trip.n - 1])) return false
        return t[trip.n - 1] - t[0] >= cfg.num("general", "min_trip_s", 30.0)
    }
}
