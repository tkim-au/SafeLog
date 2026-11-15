package com.safelog.core

import com.safelog.core.contract.Frame
import com.safelog.core.ledger.Codec
import com.safelog.core.ledger.Experience
import com.safelog.core.ledger.MemoryStore
import com.safelog.core.ledger.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile step and shared cars (submitted plan, sections 3-1 and 4): driving experience decides the starting
 * mode, "is this car new to you" decides the 내 차 익히기 offer, and every driver of the car has their own ledger.
 */
class DriverProfileTest {
    private val now = 1_793_000_000_000L
    private fun core(store: MemoryStore = MemoryStore()) = SafeLogCore(store, clock = { now }).apply { finishFirstRun(true, null) }

    @Test fun experienceDecidesTheStartingMode() {
        val c = core()
        assertFalse("not asked yet", c.snapshot().profileDone)
        assertEquals("Training", c.snapshot().briefing.mode)                      // until the driver answers
        c.setDriverProfile("정훈", Experience.EXPERIENCED, carFamiliar = true)
        var s = c.snapshot()
        assertTrue(s.profileDone)
        assertEquals("정훈", s.briefing.driverName)
        assertEquals("Normal", s.briefing.mode)
        assertEquals("평소 모드 leaves the selection empty", emptyList<String>(), s.selectedMissionIds)
        assertFalse("has driven this car: no offer", s.carTour.offer)

        for (exp in listOf(Experience.NOVICE, Experience.RETURNING)) {
            val d = core()
            d.setDriverProfile("  ", exp, carFamiliar = false)
            s = d.snapshot()
            assertEquals("a blank name keeps the current one", "운전자", s.briefing.driverName)
            assertEquals(exp, "Training", s.briefing.mode)
            assertEquals(exp, s.briefing.missions.filter { it.recommended }.map { it.id }, s.selectedMissionIds)
            assertTrue("first time in this car: offered", s.carTour.offer)
        }
    }

    @Test fun theModeCanStillBeSwitchedByHandAfterwards() {
        val c = core()
        c.setDriverProfile(null, Experience.EXPERIENCED, carFamiliar = true)
        c.setTrainingMode(true)
        assertEquals("Training", c.snapshot().briefing.mode)
    }

    @Test fun answersSurviveARestartAndUnknownValuesAreRefused() {
        val store = MemoryStore()
        val c = core(store)
        c.setDriverProfile("지수", "Expert", carFamiliar = false)               // not one of Experience.ALL
        assertFalse(c.snapshot().profileDone)
        c.setDriverProfile("지수", Experience.NOVICE, carFamiliar = false)
        val again = SafeLogCore(store, clock = { now })
        assertTrue(again.snapshot().profileDone)
        assertEquals(Experience.NOVICE, again.driver.experience)
        assertEquals(false, again.driver.carFamiliar)
        val round = Codec.driverFrom(Codec.driver(again.driver))
        assertEquals(Experience.NOVICE, round.experience)
        assertEquals(false, round.carFamiliar)
        // a driver saved before the step existed reads as "not asked"
        assertNull(Codec.driverFrom(Codec.driver(com.safelog.core.ledger.DriverState("old", "예전"))).experience)
    }

    @Test fun anotherDriverOfTheSameCarHasTheirOwnEverything() {
        val store = MemoryStore()
        val c = core(store)
        c.setDriverProfile("남편", Experience.EXPERIENCED, carFamiliar = true)
        c.onVehicleId("car-A")
        c.demoCredit(500)
        val first = c.driver.id

        val id = c.addDriver("은영", Experience.RETURNING, carFamiliar = false)!!
        var s = c.snapshot()
        assertEquals("the new driver becomes the current one", id, s.driverId)
        assertNotEquals(first, id)
        assertEquals(listOf("남편", "은영"), s.drivers.map { it.name })
        assertEquals(listOf("Normal", "Training"), s.drivers.map { it.mode })
        assertTrue(s.profileDone)
        assertEquals("her own balance", 0, s.briefing.pointsBalance)
        assertTrue("her first time in this car", s.carTour.offer)
        assertEquals("her own recommended mission is pre-selected", s.briefing.missions.filter { it.recommended }.map { it.id }, s.selectedMissionIds)

        c.selectDriver(first)
        s = c.snapshot()
        assertEquals(500, s.briefing.pointsBalance)
        assertEquals("Normal", s.briefing.mode)
        assertFalse(s.carTour.offer)

        // persisted: both drivers come back, the last selected one is current
        val again = SafeLogCore(store, clock = { now })
        assertEquals(listOf("남편", "은영"), again.snapshot().drivers.map { it.name })
        assertEquals(first, again.snapshot().driverId)
        // a blank name gets a number
        assertEquals("운전자 3", again.driverState(again.addDriver("", Experience.NOVICE, carFamiliar = true)!!)!!.name)
    }

    @Test fun addingIsRefusedWhileDrivingAndAboveTheLimit() {
        val c = core()
        c.onFrame(Frame(t = 0.0, speedKph = 30.0, gear = 3, brakeOn = 0))
        assertNull("not while driving", c.addDriver("가", Experience.NOVICE, false))
        c.onFrame(Frame(t = 0.0, speedKph = 0.0, gear = 0, brakeOn = 1))
        assertNull("unknown experience", c.addDriver("가", "Pro", false))
        while (c.snapshot().drivers.size < Rules.MAX_DRIVERS) c.addDriver("", Experience.NOVICE, false)
        assertNull(c.addDriver("넘침", Experience.NOVICE, false))
        assertEquals(Rules.MAX_DRIVERS, c.snapshot().drivers.size)
    }
}
