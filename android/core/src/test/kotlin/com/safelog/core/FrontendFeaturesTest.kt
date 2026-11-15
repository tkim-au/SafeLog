package com.safelog.core

import com.safelog.core.contract.Frame
import com.safelog.core.ledger.CarTour
import com.safelog.core.ledger.Codec
import com.safelog.core.ledger.DriverState
import com.safelog.core.ledger.Ledger
import com.safelog.core.ledger.MemoryStore
import com.safelog.core.ledger.Rules
import com.safelog.core.ledger.TripFacts
import com.safelog.core.ledger.ShopCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The back-end side of the front end's 2026-11-08 additions: 연수 모드 switch, S05 내 차 익히기, avatars, reward image. */
class FrontendFeaturesTest {
    private var now = 1_793_000_000_000L
    private val day = 86_400_000L

    private fun facts(k: Int, score: Int, missions: List<Pair<String, Boolean>> = emptyList(), cleared: Boolean = true) = TripFacts(
        id = "t$k", startMs = now + k * day, endMs = now + k * day + 1_800_000, km = 12.0, blocked = false, riskMoment = score < 100,
        counts = mapOf("lane_change_signal" to if (cleared) intArrayOf(3, 3) else intArrayOf(0, 3), "smooth_stop" to intArrayOf(4, 5)),
        tierXp = 12, missions = missions, score = score,
    )

    @Test fun trainingModeSwitchesOnAndOffByTheRules() {
        val d = DriverState("a", "가", mode = "Normal")
        val shop = ShopCatalog.DEFAULT
        // four good trips, then one below 60 -> 연수 모드 is only SUGGESTED; the driver turns it on (setTrainingMode)
        for (k in 0 until 4) Ledger.settle(d, facts(k, 95), false, shop)
        assertEquals("Normal", d.mode)
        Ledger.settle(d, facts(4, 55), false, shop)
        assertEquals("the engine does not switch by itself", "Normal", d.mode)
        assertEquals("TrainingOffer", d.modeNotice)
        d.modeNotice = null
        d.mode = "Training"; d.trainingStreak = 0                            // the driver pressed "켜기"
        // 3 consecutive training trips with the mission passed + recent average >= 75 -> back to normal, +300P +200XP once
        val m = listOf("lane-signal" to false)
        Ledger.settle(d, facts(5, 90, m), false, shop)
        Ledger.settle(d, facts(6, 90, m, cleared = false), false, shop)      // a failed mission resets the streak
        assertEquals(0, d.trainingStreak)
        Ledger.settle(d, facts(7, 92, m), false, shop)
        Ledger.settle(d, facts(8, 92, m), false, shop)
        assertEquals("Training", d.mode)
        val xpBefore = d.xpTotal
        val st = Ledger.settle(d, facts(9, 94, m), false, shop)
        assertEquals("Normal", d.mode)
        assertEquals("TrainingOff", d.modeNotice)
        assertEquals(Rules.GRADUATION_POINTS, st.pointsBreakdown["graduation"])
        assertTrue(d.xpTotal - xpBefore >= Rules.GRADUATION_XP)
        // a weak trip after graduation is a new suggestion, never a switch
        Ledger.settle(d, facts(10, 30), false, shop)
        assertEquals("Normal", d.mode)
        assertEquals("TrainingOffer", d.modeNotice)
        assertEquals(listOf(95, 95, 95, 95, 55, 90, 90, 92, 92, 94, 30), d.trips.map { it.score })
    }

    @Test fun weightedRecentScore() {
        assertEquals(100.0, Rules.recentScore(emptyList()), 1e-9)
        // weights 1..5, oldest -> newest
        assertEquals((1 * 50 + 2 * 60 + 3 * 70 + 4 * 80 + 5 * 90) / 15.0, Rules.recentScore(listOf(0, 50, 60, 70, 80, 90)), 1e-9)
    }

    @Test fun userSwitchHasNoNoticeAndPreselectsTheRecommendedMission() {
        val core = SafeLogCore(MemoryStore(), clock = { now })
        core.finishFirstRun(true, null)
        core.setTrainingMode(false)
        core.selectMissions(emptyList())
        assertEquals("Normal", core.snapshot().briefing.mode)
        assertNull(core.snapshot().modeNotice)
        core.setTrainingMode(true)
        val s = core.snapshot()
        assertEquals("Training", s.drivers.first().mode)
        assertNull(s.modeNotice)
        assertEquals(s.briefing.missions.filter { it.recommended }.map { it.id }, s.selectedMissionIds)
        core.demoModeFlip()
        assertEquals("TrainingOff", core.snapshot().modeNotice)
        core.dismissModeNotice()
        assertNull(core.snapshot().modeNotice)
        // in 평소 모드 the engine's move is a suggestion: the mode stays until the driver accepts
        core.demoModeFlip()
        assertEquals("TrainingOffer", core.snapshot().modeNotice)
        assertEquals("Normal", core.snapshot().briefing.mode)
    }

    @Test fun carTourDetectsControlsWhileParkedAndRewardsOnce() {
        val store = MemoryStore()
        val core = SafeLogCore(store, clock = { now })
        core.finishFirstRun(true, null)
        assertTrue("offered on first install", core.snapshot().carTour.offer)
        core.dismissCarTourOffer()
        assertFalse(core.snapshot().carTour.offer)
        val parked = Frame(t = 0.0, speedKph = 0.0, gear = 0, brakeOn = 0)
        core.onFrame(parked)
        assertTrue(core.snapshot().carTour.required.first { it.id == "gear_p" }.done)
        core.onFrame(parked.copy(turnSignal = 1)); core.onFrame(parked.copy(turnSignal = 2)); core.onFrame(parked.copy(turnSignal = 0))
        assertTrue(core.snapshot().carTour.required.first { it.id == "signals" }.done)
        core.onCabin("hazard", true)
        assertFalse("hazard needs on, then off", core.snapshot().carTour.required.first { it.id == "hazard" }.done)
        core.onCabin("hazard", false)
        assertTrue(core.snapshot().carTour.required.first { it.id == "hazard" }.done)
        core.skipCarStep("seat")
        assertTrue(core.snapshot().carTour.required.first { it.id == "seat" }.skipped)
        assertFalse(core.snapshot().carTour.finished)
        val xp0 = core.driver.xpTotal
        while (core.snapshot().carTour.required.any { !it.done }) {
            val before = core.snapshot().carTour.required.count { it.done }
            // skipped steps are passed over by "next": complete them through the detection directly
            val pending = core.snapshot().carTour.required.first { !it.done }
            for ((sig, v) in CarTour.demoSignals(pending.id)) core.onCabin(sig, v)
            assertTrue(core.snapshot().carTour.required.count { it.done } > before)
        }
        assertTrue(core.snapshot().carTour.finished)
        assertEquals(xp0 + Rules.TOUR_XP, core.driver.xpTotal)
        core.demoTourStep(); core.demoTourStep()
        assertEquals("XP only once", xp0 + Rules.TOUR_XP, core.driver.xpTotal)
        // nothing moves while driving
        core.onFrame(Frame(t = 0.0, speedKph = 30.0, gear = 3, brakeOn = 0))
        core.onCabin("ac", true)
        assertFalse(core.snapshot().carTour.extraGroups.flatMap { it.steps }.first { it.id == "ac" }.done)
        // persisted; another car starts over and is offered again
        val again = SafeLogCore(store, clock = { now })
        assertTrue(again.snapshot().carTour.finished)
        again.onVehicleId("car-A")
        assertTrue(again.snapshot().carTour.finished)          // first VIN seen: the same tour
        again.onVehicleId("car-B")
        assertFalse(again.snapshot().carTour.finished)
        assertTrue(again.snapshot().carTour.offer)
    }

    @Test fun capabilitiesHideStepsTheCarCannotReport() {
        val core = SafeLogCore(MemoryStore(), clock = { now })
        assertEquals(10, core.snapshot().carTour.required.size)            // unknown: all (demo)
        core.vehicleCaps = setOf("belt", "brake", "turn_signal", "low_beam", "wiper", "steering")
        val ids = core.snapshot().carTour.required.map { it.id }
        assertEquals(listOf("belt", "brake", "gear_p", "signals", "lights", "wiper"), ids)
        assertEquals(listOf("그 밖에"), core.snapshot().carTour.extraGroups.map { it.title })
    }

    @Test fun avatarAndRewardImage() {
        val core = SafeLogCore(MemoryStore(), clock = { now })
        assertEquals("atlas", core.snapshot().drivers.first().avatar)
        core.setAvatar(core.driver.id, "letter:3")
        assertEquals("letter:3", core.snapshot().drivers.first().avatar)
        assertEquals("letter:3", Codec.driverFrom(Codec.driver(core.driver)).avatar)
        assertEquals("reward_coffee", ShopCatalog.DEFAULT.first { it.id == "coffee" }.image)
        // the next reward carries the same picture as the shop item it names
        for (credit in listOf(0, 1000, 1200, 1500)) {
            core.demoCredit(credit)
            val r = core.snapshot().briefing.nextReward
            assertEquals(ShopCatalog.DEFAULT.first { it.name == r.itemName }.image, r.image)
        }
    }
}
