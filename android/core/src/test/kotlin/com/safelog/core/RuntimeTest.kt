package com.safelog.core

import com.safelog.core.contract.Frame
import com.safelog.core.contract.Trip
import com.safelog.core.drive.DrivePhase
import com.safelog.core.ledger.Codec
import com.safelog.core.ledger.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End to end on the car side: frames -> phase -> recording -> engine -> ledger -> arrival, as on the emulator. */
class RuntimeTest {
    private fun res(name: String) = javaClass.classLoader.getResourceAsStream(name) ?: error("missing $name")
    private var clockMs = java.time.LocalDateTime.parse("2026-11-02T08:00:00").atZone(com.safelog.core.ledger.Rules.ZONE).toInstant().toEpochMilli()

    private fun drive(core: SafeLogCore, name: String): Pair<Int, Int> {
        val trip = Trip.readCsv(res("parity/$name.csv.gz"))
        var chimes = 0; var phases = HashSet<DrivePhase>()
        var finished = 0
        for (k in 0 until trip.n) {
            val ev = core.onFrame(Frame.fromTrip(trip, k))
            clockMs += 100
            phases.add(core.phase)
            if (ev.chime) chimes++
            ev.recorded?.let { rec -> if (core.settle(rec, core.analyze(rec)) != null) finished++ }
        }
        assertTrue(DrivePhase.Driving in phases)
        return chimes to finished
    }

    @Test fun demoTripProducesArrivalAndPraise() {
        val store = MemoryStore()
        val core = SafeLogCore(store, clock = { clockMs })
        assertEquals(false, core.snapshot().firstRunDone)
        core.finishFirstRun(true, "토리")
        val missions = core.snapshot().briefing.missions
        assertTrue(missions.any { it.recommended })
        core.selectMissions(listOf("lane-signal"))
        val (chimes, finished) = drive(core, "lane_practice")
        assertEquals(1, finished)
        val snap = core.snapshot()
        val a = snap.arrival
        assertNotNull(a)
        a!!
        assertEquals("차선 바꾸기 3초 전 깜빡이", a.practiceResult!!.label)
        assertTrue("lane changes counted", a.practiceResult!!.chances >= 1)
        assertTrue("praise chime played", chimes >= 1)
        assertEquals(chimes, snap.praiseCount)
        assertTrue(a.points >= 10)                                   // attendance at least
        assertEquals("토리", snap.atlas.name)
        assertEquals(1, snap.records.journal.size)
        assertTrue(snap.records.journal[0].scenes.isNotEmpty())
        // persisted and reloaded
        val again = SafeLogCore(store, clock = { clockMs })
        assertEquals(core.driver.balance, again.driver.balance)
        assertEquals(1, again.driver.trips.size)
        Codec.driverFrom(Codec.driver(again.driver))                 // round trip
        core.dismissArrival()
        assertNull(core.snapshot().arrival)
        // 연수 모드 (new drivers start in it): the recommended mission is put back for the next departure
        val next = core.snapshot()
        if (next.briefing.mode == "Training") assertEquals(next.briefing.missions.filter { it.recommended }.map { it.id }, next.selectedMissionIds)
        else assertTrue(next.selectedMissionIds.isEmpty())
    }

    @Test fun nothingIsRecordedWithoutConsent() {
        val core = SafeLogCore(MemoryStore(), clock = { clockMs })
        core.finishFirstRun(false, null)
        val (chimes, _) = drive(core, "commute_city")
        assertEquals(0, chimes)
        assertNull(core.snapshot().arrival)
        assertEquals(0, core.driver.trips.size)
    }

    @Test fun heldHabitsNeverShown() {
        val core = SafeLogCore(MemoryStore(), clock = { clockMs })
        core.finishFirstRun(true, null)
        drive(core, "rainy_evening")
        val trip = core.driver.trips.single()
        for (h in listOf("smooth_stop", "steady_cruise", "anticipatory_braking")) assertTrue(h !in trip.counts)
        val labels = core.snapshot().records.habits.map { it.label }
        assertTrue(labels.none { it == "부드러운 정지" || it == "일정한 속도 유지" })
    }
}
