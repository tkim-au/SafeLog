package com.safelog.core

import com.safelog.core.contract.Frame
import com.safelog.core.ledger.MemoryStore
import com.safelog.core.ledger.MissionCatalog
import com.safelog.core.ledger.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the driver hears and reads while driving (submitted plan, section 3): 평소 모드 is silent, the signal-wait
 * line is praise only, and one mission is practised per drive. Frames are hand-made: a right turn from navigation
 * 100 m ahead with the blinker on 60 m before it is one success of "회전 30m 전 깜빡이".
 */
class DrivingSoundTest {
    private val now = 1_793_000_000_000L

    private class Drive(val core: SafeLogCore) {
        private var t = 0
        var chimes = 0
        val spoken = ArrayList<String>()
        private val base = Frame(t = 0.0, speedKph = 30.0, gear = 3, brakeOn = 0, cruiseActive = 0, wiperActive = 0, night = 0)

        private fun feed(f: Frame): FrameEvents {
            val ev = core.onFrame(f.copy(t = t++ / 10.0))
            if (ev.chime) chimes++
            ev.speak?.let { spoken.add(it) }
            return ev
        }
        fun cruise(seconds: Int) = repeat(seconds * 10) { feed(base) }
        fun rightTurn(signalAtM: Double = 60.0) {
            var d = 100.0
            while (d > 0) { feed(base.copy(tbtType = 2, tbtDistM = d, turnSignal = if (d <= signalAtM) 2 else 0)); d -= 30 / 3.6 / 10 }
            feed(base.copy(tbtType = 2, tbtDistM = 0.0, turnSignal = 2))
            cruise(2)
        }
        fun waitAtLight(seconds: Int = 4) = repeat(seconds * 10) { feed(base.copy(speedKph = 0.0, brakeOn = 1)) }
        /** Gear P: the trip ends and is judged like on the car. Returns true when it was settled. */
        fun park(): Boolean {
            val rec = feed(Frame(t = 0.0, speedKph = 0.0, gear = 0, brakeOn = 1)).recorded ?: return false
            return core.settle(rec, core.analyze(rec)) != null
        }
    }

    private fun core(training: Boolean, mission: String?, signal: String = "Sometimes", insurance: Boolean = false) =
        SafeLogCore(MemoryStore(), clock = { now }).apply {
            finishFirstRun(true, null)
            updateSettings(device.settings.copy(signal = signal, insuranceShare = insurance))
            setTrainingMode(training)
            selectMissions(listOfNotNull(mission))
        }

    private val turnPraise = MissionCatalog.byId("turn-signal")!!.praise

    @Test fun normalModeIsSilentButStillRecords() {
        // even with a mission picked by hand, "가끔" and insurance sharing on: no chime, no line
        val d = Drive(core(training = false, mission = "turn-signal", insurance = true))
        d.cruise(20); d.rightTurn(); d.cruise(3); d.waitAtLight()
        assertEquals(0, d.chimes)
        assertEquals(emptyList<String>(), d.spoken)
        assertNull(d.core.idleLine)
        assertEquals(0, d.core.praiseCount)
        assertTrue("the drive is recorded and judged", d.park())
        assertEquals(1, d.core.driver.trips.size)
        assertEquals(listOf("turn-signal"), d.core.driver.trips.single().missionIds)
    }

    @Test fun trainingModePraisesASuccessOnceAndAtMostTwiceATrip() {
        val d = Drive(core(training = true, mission = "turn-signal"))
        d.cruise(5); d.waitAtLight()
        assertEquals("nothing is said before the first success", emptyList<String>(), d.spoken)
        d.rightTurn(); d.cruise(3); d.waitAtLight()
        assertEquals(listOf(turnPraise), d.spoken)
        assertEquals(turnPraise, d.core.idleLine)
        assertTrue("the chime played", d.chimes >= 1)
        d.cruise(3)
        assertNull("the line goes away when the car moves", d.core.idleLine)
        d.waitAtLight()
        assertEquals("the same success is not praised again", listOf(turnPraise), d.spoken)
        d.rightTurn(); d.cruise(3); d.waitAtLight()
        d.rightTurn(); d.cruise(3); d.waitAtLight()
        assertEquals("at most two lines a trip", listOf(turnPraise, turnPraise), d.spoken)
    }

    @Test fun everySuccessChimesOnce() {
        // plan v0.4 capped the chime at once per 5 minutes; the submitted plan says one short chime per success
        val d = Drive(core(training = true, mission = "turn-signal"))
        d.rightTurn(); d.cruise(3)
        assertEquals(1, d.chimes)
        d.rightTurn(); d.cruise(3); d.rightTurn(); d.cruise(3)
        assertEquals(3, d.chimes)
        assertEquals(3, d.core.praiseCount)
        // a turn without the blinker in time is no success and makes no sound
        d.rightTurn(signalAtM = 10.0); d.cruise(3)
        assertEquals(3, d.chimes)
    }

    @Test fun quietLevelKeepsTheChimeWithoutTheLine() {
        val d = Drive(core(training = true, mission = "turn-signal", signal = "Quiet"))
        d.rightTurn(); d.cruise(3); d.waitAtLight()
        assertTrue(d.chimes >= 1)
        assertEquals(emptyList<String>(), d.spoken)
    }

    @Test fun praiseLinesCarryNoCount() {
        for (m in MissionCatalog.ALL) {
            assertTrue(m.id, m.praise.endsWith("어요"))
            assertTrue(m.id, m.praise.none { it.isDigit() })
        }
    }

    @Test fun oneMissionPerDrive() {
        val c = core(training = true, mission = null)
        c.driver.missionClears["lane-signal"] = 30                 // used to unlock three missions
        assertEquals(1, Rules.MAX_MISSIONS)
        assertEquals(1, c.snapshot().briefing.maxSelectable)
        c.selectMissions(listOf("lane-signal", "turn-signal"))
        assertEquals("the latest pick wins", listOf("turn-signal"), c.snapshot().selectedMissionIds)
    }
}
