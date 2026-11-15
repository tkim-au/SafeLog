package com.safelog.core

import com.safelog.core.ledger.DriverState
import com.safelog.core.ledger.ExchangeResult
import com.safelog.core.ledger.Ledger
import com.safelog.core.ledger.Rules
import com.safelog.core.ledger.ShopCatalog
import com.safelog.core.ledger.TripFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LedgerTest {
    private fun ms(s: String) = LocalDateTime.parse(s).atZone(Rules.ZONE).toInstant().toEpochMilli()
    private fun trip(id: String, start: String, km: Double = 12.0, blocked: Boolean = false, risk: Boolean = false,
                     counts: Map<String, IntArray> = mapOf("turn_signal_turn" to intArrayOf(4, 4), "lane_change_signal" to intArrayOf(4, 5)),
                     missions: List<Pair<String, Boolean>> = emptyList(), tierXp: Int = 20) =
        TripFacts(id, ms(start), ms(start) + 30 * 60_000, km, blocked, risk, counts, tierXp, missions)

    @Test fun costMatchesFrontEnd() {
        assertEquals(1643, Rules.cost(1643, Rules.Grade.Basic))
        assertEquals(1560, Rules.cost(1643, Rules.Grade.Silver))   // 1643 x 95 / 100 = 1560.85 -> 1560
        assertEquals(Rules.Grade.Silver, Rules.Grade.of(1240.0))
    }

    @Test fun levelCurve() {
        assertEquals(1, Rules.levelFor(0)); assertEquals(2, Rules.levelFor(100)); assertEquals(5, Rules.levelFor(500))
        assertEquals(10, Rules.levelFor(1500)); assertEquals(11, Rules.levelFor(1800))
        assertEquals(180 to 300, Rules.levelProgress(1980))         // front-end sample: level 11, 180 / 300
    }

    @Test fun attendanceMissionAndWeek() {
        val d = DriverState("a", "민수")
        val shop = ShopCatalog.DEFAULT
        // Monday: first trip of the day + recommended mission cleared (4/5 >= 80 %, >= 3 chances)
        val s1 = Ledger.settle(d, trip("t1", "2026-11-02T08:00:00", missions = listOf("lane-signal" to true)), true, shop)
        assertEquals(mapOf("attendance" to 10, "mission:lane-signal" to 20), s1.pointsBreakdown)
        assertEquals(30, s1.arrival.points)
        assertEquals(20, s1.arrival.practicePoints)
        assertTrue(s1.arrival.practiceCleared)
        assertEquals(20 + 20, s1.arrival.xp)                        // tier XP + recommended mission XP x2
        // second trip the same day: no second attendance
        val s2 = Ledger.settle(d, trip("t2", "2026-11-02T18:00:00"), true, shop)
        assertEquals(0, s2.arrival.points)
        // Tuesday, Wednesday -> 3rd day of the week = weekly +30
        Ledger.settle(d, trip("t3", "2026-11-03T08:00:00"), true, shop)
        val s4 = Ledger.settle(d, trip("t4", "2026-11-04T08:00:00"), true, shop)
        assertEquals(mapOf("attendance" to 10, "week" to 30), s4.pointsBreakdown)
        assertEquals(10 + 10 + 10 + 30 + 20, d.balance)
        // safe km: 4 trips x 12 km, insurance on
        assertEquals(48, s4.arrival.insurance!!.safeKm)
        assertEquals(12, s4.arrival.safeKmToday)
    }

    @Test fun riskyTripEarnsNothingButStillShowsGoodPoints() {
        val d = DriverState("a", "민수")
        val s = Ledger.settle(d, trip("t1", "2026-11-02T08:00:00", blocked = true, missions = listOf("lane-signal" to true)), true, ShopCatalog.DEFAULT)
        assertEquals(0, s.arrival.points); assertEquals(0, s.arrival.xp); assertEquals(0, s.arrival.safeKmToday)
        assertTrue(s.arrival.topHabit != null)                       // good points are still shown
        assertTrue(s.arrival.noRewardReason != null)
        assertEquals(0, d.balance)
    }

    @Test fun monthlyCap() {
        val d = DriverState("a", "민수")
        d.entries.add(com.safelog.core.ledger.LedgerEntry("seed", ms("2026-11-01T09:00:00"), "seed", 2995))
        val s = Ledger.settle(d, trip("t1", "2026-11-02T08:00:00", missions = listOf("lane-signal" to true)), false, ShopCatalog.DEFAULT)
        assertEquals(5, s.arrival.points)                            // 3,000P a month
    }

    @Test fun quizOnceAndExchangeIdempotent() {
        val d = DriverState("a", "민수")
        val now = ms("2026-11-02T20:00:00")
        val q = Ledger.quiz(d, now)
        assertEquals(Rules.QUIZ_RIGHT, Ledger.answerQuiz(d, q.answer, now))
        assertEquals(0, Ledger.answerQuiz(d, (q.answer + 1) % 3, now))
        d.entries.add(com.safelog.core.ledger.LedgerEntry("seed", now, "seed", 995))
        val store5 = ShopCatalog.DEFAULT.first { it.id == "store5" }
        assertEquals(ExchangeResult.OK, Ledger.exchange(d, store5, "req-1", now))
        assertEquals(ExchangeResult.OK, Ledger.exchange(d, store5, "req-1", now))   // same request id: debited once
        assertEquals(1000 - 400, d.balance)
        val culture = ShopCatalog.DEFAULT.first { it.id == "culture" }
        assertEquals(ExchangeResult.NOT_ENOUGH_POINTS, Ledger.exchange(d, culture, "req-2", now))
        assertEquals(600, d.balance)
    }
}
