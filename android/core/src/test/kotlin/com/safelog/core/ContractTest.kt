package com.safelog.core

import com.safelog.core.engine.Scoring
import com.safelog.core.json.Json
import com.safelog.core.ledger.Codec
import com.safelog.core.ledger.DriverState
import com.safelog.core.ledger.Ledger
import com.safelog.core.ledger.LedgerEntry
import com.safelog.core.ledger.MissionCatalog
import com.safelog.core.ledger.QuizBank
import com.safelog.core.ledger.Rules
import com.safelog.core.ledger.ShopCatalog
import com.safelog.core.ledger.TripFacts
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * The car (this module) and the server (backend/server, Python) must apply the same reward rules.
 *  - backend/contract/catalog.json: missions, shop, quiz bank, tiers and labels — the server loads it, so there is
 *    one source of truth.
 *  - backend/contract/ledger_cases.json: scripted driver histories and the results this ledger gives; the server's
 *    tests replay them (backend/server/tests/test_ledger_cases.py).
 * Regenerate after a rule change:  WRITE_CONTRACT=1 (env) when running this test.
 */
class ContractTest {
    private fun contractDir(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val c = File(d, "backend/contract"); if (c.isDirectory) return c
            val c2 = File(d, "safelog/backend/contract"); if (c2.isDirectory) return c2
            d = d.parentFile
        }
        error("backend/contract not found from ${System.getProperty("user.dir")}")
    }

    private fun ms(s: String) = LocalDateTime.parse(s).atZone(Rules.ZONE).toInstant().toEpochMilli()

    fun catalog(): Map<String, Any?> = linkedMapOf(
        "missions" to MissionCatalog.ALL.map { linkedMapOf("id" to it.id, "habit" to it.habit, "title" to it.title, "kind" to it.kind,
            "minChances" to it.minChances, "minRate" to it.minRate, "points" to it.points, "rainOnly" to it.rainOnly) },
        "shop" to ShopCatalog.DEFAULT.map { linkedMapOf("id" to it.id, "name" to it.name, "brand" to it.brand, "category" to it.category,
            "price" to it.price, "art" to it.art, "image" to it.image) },
        "quiz" to QuizBank.ALL.map { linkedMapOf("id" to it.id, "question" to it.question, "choices" to it.choices, "answer" to it.answer,
            "explanation" to it.explanation, "tags" to it.tags.sorted(), "tip" to it.tip) },
        "tiers" to Scoring.TIER, "tierWeight" to Scoring.TIER_WEIGHT, "labels" to Scoring.KO_LABEL,
        "rules" to linkedMapOf("monthlyCap" to Rules.MONTHLY_CAP, "attendFirstMonth" to Rules.ATTEND_FIRST_MONTH,
            "attendLater" to Rules.ATTEND_LATER, "firstMonthDays" to Rules.FIRST_MONTH_DAYS, "weekDays" to Rules.WEEK_DAYS_FOR_ATTENDANCE,
            "weekBonus" to Rules.WEEK_BONUS, "streak4" to Rules.STREAK4_BONUS, "streak8" to Rules.STREAK8_BONUS, "stampEvery" to Rules.STAMP_EVERY,
            "stampBonus" to Rules.STAMP_BONUS, "quizRight" to Rules.QUIZ_RIGHT, "quizWrong" to Rules.QUIZ_WRONG, "missionXp" to Rules.MISSION_XP,
            "insuranceGoalKm" to Ledger.INSURANCE_GOAL_KM, "insuranceMaxDiscount" to Ledger.INSURANCE_MAX_DISCOUNT,
            "trainingOnAvg" to Rules.TRAINING_ON_AVG, "trainingOnTrip" to Rules.TRAINING_ON_TRIP, "trainingOffAvg" to Rules.TRAINING_OFF_AVG,
            "trainingOffStreak" to Rules.TRAINING_OFF_STREAK, "scoreWindow" to Rules.SCORE_WINDOW,
            "graduationXp" to Rules.GRADUATION_XP, "graduationPoints" to Rules.GRADUATION_POINTS, "tourXp" to Rules.TOUR_XP,
            "grades" to Rules.Grade.entries.map { linkedMapOf("name" to it.name, "label" to it.label, "discount" to it.discountPercent, "fromKm" to it.fromKm) }),
    )

    private fun facts(id: String, start: String, km: Double = 12.0, blocked: Boolean = false, risk: Boolean = false,
                      counts: Map<String, IntArray>, missions: List<Pair<String, Boolean>> = emptyList(), tierXp: Int = 20,
                      weakest: String? = null, durMin: Int = 30, score: Int = 100) =
        TripFacts(id, ms(start), ms(start) + durMin * 60_000L, km, blocked, risk, counts, tierXp, missions, weakestHabit = weakest, score = score)

    /** Scripted histories. Each step: trip / quiz / exchange / seed. */
    fun scenarios(): List<Pair<String, List<Map<String, Any?>>>> {
        val good = mapOf("speed_limit" to intArrayOf(5, 6), "turn_signal_turn" to intArrayOf(3, 3), "lane_change_signal" to intArrayOf(2, 3),
            "smooth_launch" to intArrayOf(6, 7), "right_turn_pause" to intArrayOf(1, 1), "seat_belts" to intArrayOf(1, 1))
        val out = ArrayList<Pair<String, List<Map<String, Any?>>>>()
        fun trip(f: TripFacts, share: Boolean = true) = linkedMapOf<String, Any?>("op" to "trip", "insuranceShare" to share, "facts" to Codec.facts(f))
        // 1. a first month: daily attendance, weekly stack, missions with and without recommendation, a risky trip
        val s1 = ArrayList<Map<String, Any?>>()
        val days = listOf("2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05", "2026-11-09", "2026-11-10", "2026-11-11", "2026-11-16", "2026-11-17", "2026-11-18", "2026-11-23", "2026-11-24", "2026-11-25")
        days.forEachIndexed { k, day ->
            val missions = when (k % 3) { 0 -> listOf("turn-signal" to true); 1 -> listOf("lane-signal" to false); else -> emptyList() }
            s1.add(trip(facts("a$k", "${day}T08:10:00", km = 10.0 + k, counts = good, missions = missions, weakest = "lane_change_signal")))
            if (k == 4) s1.add(trip(facts("a${k}b", "${day}T18:30:00", km = 25.0, blocked = true, counts = good, missions = listOf("turn-signal" to true))))
            if (k == 6) s1.add(trip(facts("a${k}c", "${day}T19:30:00", km = 7.5, risk = true, counts = good)))
        }
        s1.add(linkedMapOf("op" to "quiz", "now" to ms("2026-11-25T20:00:00"), "choice" to 1))
        s1.add(linkedMapOf("op" to "quiz", "now" to ms("2026-11-25T20:01:00"), "choice" to 0))
        s1.add(linkedMapOf("op" to "exchange", "now" to ms("2026-11-25T20:02:00"), "item" to "store5", "request" to "r-1"))
        s1.add(linkedMapOf("op" to "exchange", "now" to ms("2026-11-25T20:02:01"), "item" to "store5", "request" to "r-1"))
        s1.add(linkedMapOf("op" to "exchange", "now" to ms("2026-11-25T20:03:00"), "item" to "culture", "request" to "r-2"))
        out.add("first_month" to s1)
        // 2. monthly cap and the month boundary, stamps every 5 clears, second month attendance (+3)
        val s2 = ArrayList<Map<String, Any?>>()
        s2.add(linkedMapOf("op" to "seed", "now" to ms("2026-12-01T07:00:00"), "points" to 2975))
        for (k in 0 until 6) s2.add(trip(facts("b$k", "2026-12-0${k + 1}T08:00:00", counts = good, missions = listOf("turn-signal" to (k % 2 == 0)))))
        s2.add(trip(facts("c0", "2027-01-05T08:00:00", counts = good, missions = listOf("turn-signal" to false))))
        s2.add(trip(facts("c1", "2027-01-06T08:00:00", counts = mapOf("turn_signal_turn" to intArrayOf(1, 3)), missions = listOf("turn-signal" to false))))
        out.add("cap_and_stamps" to s2)
        // 3. unidentified driver, no insurance consent, rain mission
        val s3 = ArrayList<Map<String, Any?>>()
        s3.add(trip(facts("d0", "2026-11-02T08:00:00", counts = good).copy(identified = false), share = false))
        s3.add(trip(facts("d1", "2026-11-02T09:00:00", counts = mapOf("rain_speed" to intArrayOf(3, 3), "headlights" to intArrayOf(4, 4)),
            missions = listOf("rain-slow" to true)).copy(rain = true), share = false))
        out.add("unidentified_rain" to s3)
        // 4. 연수 모드: the driver turns it off, a bad trip only suggests it (notice "TrainingOffer", the mode stays),
        //    the driver accepts, a failed mission resets the streak, 3 passes + average >= 75 graduate (+300P +200XP,
        //    first time only), a later bad trip suggests it again, and 내 차 익히기 +50XP once
        val s4 = ArrayList<Map<String, Any?>>()
        s4.add(linkedMapOf("op" to "mode", "mode" to "Normal"))
        val lane = listOf("lane-signal" to false)
        val pass = good + mapOf("lane_change_signal" to intArrayOf(3, 3))
        val miss = good + mapOf("lane_change_signal" to intArrayOf(0, 3))
        listOf(96, 92, 58).forEachIndexed { k, sc -> s4.add(trip(facts("e$k", "2026-11-0${k + 2}T08:00:00", counts = good, score = sc))) }
        s4.add(linkedMapOf("op" to "mode", "mode" to "Training"))            // the driver pressed "켜기" on the suggestion
        listOf(80 to pass, 85 to miss, 90 to pass, 93 to pass, 95 to pass, 97 to pass).forEachIndexed { k, (sc, c) ->
            s4.add(trip(facts("f$k", "2026-11-${10 + k}T08:00:00", counts = c, missions = lane, score = sc)))
        }
        s4.add(linkedMapOf("op" to "tour"))
        s4.add(linkedMapOf("op" to "tour"))
        s4.add(trip(facts("g0", "2026-11-20T08:00:00", counts = good, score = 40)))
        s4.add(linkedMapOf("op" to "mode", "mode" to "Normal"))
        listOf(95, 95, 95).forEachIndexed { k, sc -> s4.add(trip(facts("g${k + 1}", "2026-11-2${k + 1}T08:00:00", counts = pass, missions = lane, score = sc))) }
        out.add("training_mode" to s4)
        return out
    }

    /** Runs a scenario on the Kotlin ledger and returns the result of every step. */
    @Suppress("UNCHECKED_CAST")
    fun run(steps: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val d = DriverState("case", "케이스")
        val shop = ShopCatalog.DEFAULT
        return steps.map { st ->
            when (st["op"]) {
                "trip" -> {
                    val f = Codec.factsFrom(st["facts"] as Map<String, Any?>)
                    val s = Ledger.settle(d, f, st["insuranceShare"] as Boolean, shop)
                    val a = s.arrival
                    linkedMapOf("points" to a.points, "breakdown" to s.pointsBreakdown, "xp" to a.xp, "xpToNextLevel" to a.xpToNextLevel,
                        "balance" to d.balance, "safeKmToday" to a.safeKmToday, "practiceCleared" to a.practiceCleared,
                        "practicePoints" to a.practicePoints, "nextChallenge" to a.nextChallenge, "noRewardReason" to a.noRewardReason,
                        "topHabit" to a.topHabit?.let { listOf(it.label, it.success, it.chances) },
                        "otherHabits" to a.otherHabits.map { listOf(it.label, it.success, it.chances) },
                        "nextReward" to listOf(a.nextReward.itemName, a.nextReward.remainingPoints, a.nextReward.price),
                        "insurance" to a.insurance?.let { listOf(it.safeKm, it.goalKm, it.renewal, it.monthsToRenewal, it.avgTripKm) },
                        "level" to d.level, "mode" to d.mode, "modeNotice" to d.modeNotice.also { d.modeNotice = null })
                }
                // the driver's own switch (SafeLogCore.setTrainingMode): no notice, the streak starts over
                "mode" -> { d.mode = st["mode"] as String; d.trainingStreak = 0; linkedMapOf("mode" to d.mode) }
                // S05 내 차 익히기 finished (SafeLogCore.tourObserve): +50 XP once per driver
                "tour" -> {
                    if (!d.tourRewarded) { d.tourRewarded = true; d.xpTotal += Rules.TOUR_XP }
                    linkedMapOf("xpTotal" to d.xpTotal, "level" to d.level)
                }
                "quiz" -> {
                    val now = (st["now"] as Number).toLong()
                    val q = Ledger.quiz(d, now)
                    linkedMapOf("quizId" to q.id, "earned" to Ledger.answerQuiz(d, (st["choice"] as Number).toInt(), now), "balance" to d.balance)
                }
                "exchange" -> {
                    val now = (st["now"] as Number).toLong()
                    val item = shop.first { it.id == st["item"] }
                    linkedMapOf("result" to Ledger.exchange(d, item, st["request"] as String, now).name, "balance" to d.balance)
                }
                "seed" -> { d.entries.add(LedgerEntry("seed", (st["now"] as Number).toLong(), "seed", (st["points"] as Number).toInt())); linkedMapOf("balance" to d.balance) }
                else -> error("op ${st["op"]}")
            }
        }
    }

    @Test fun contractFilesAreCurrent() {
        val dir = contractDir()
        val cat = Json.write(catalog(), pretty = true)
        val cases = Json.write(linkedMapOf("note" to "generated by android/core ContractTest; the server must reproduce every result",
            "cases" to scenarios().map { (name, steps) -> linkedMapOf("name" to name, "steps" to steps, "expect" to run(steps)) }), pretty = true)
        if (System.getenv("WRITE_CONTRACT") == "1") {
            File(dir, "catalog.json").writeText(cat + "\n")
            File(dir, "ledger_cases.json").writeText(cases + "\n")
        }
        assertEquals("catalog.json is stale: run ContractTest with WRITE_CONTRACT=1", cat + "\n", File(dir, "catalog.json").readText())
        assertEquals("ledger_cases.json is stale: run ContractTest with WRITE_CONTRACT=1", cases + "\n", File(dir, "ledger_cases.json").readText())
    }
}
