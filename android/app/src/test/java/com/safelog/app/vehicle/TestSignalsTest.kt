package com.safelog.app.vehicle

import com.safelog.core.RecordedTrip
import com.safelog.core.SafeLogCore
import com.safelog.core.contract.Frame
import com.safelog.core.contract.Trip
import com.safelog.core.engine.Outcome
import com.safelog.core.ledger.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debug test signals: parsing, the source wrapper, and the same path into the engine and 내 차 익히기 as a car's values. */
class TestSignalsTest {
    /** A source that emits what the test hands it, like the car would. */
    private class FakeSource(private val caps: Set<String>? = setOf("brake")) : VehicleSource {
        override val name = "가짜 차"
        var frame: ((Frame) -> Unit)? = null
        var cabin: ((String, Any?) -> Unit)? = null
        var stopped = false
        override fun start(onFrame: (Frame) -> Unit, onPlace: (String) -> Unit, onCabin: (String, Any?) -> Unit, onVehicleId: (String) -> Unit) {
            frame = onFrame; cabin = onCabin
        }
        override fun stop() { stopped = true }
        override fun capabilities() = caps
    }

    @Test fun frameOverridesAreHeldInEveryFrameAndTheRestPassesThrough() {
        val s = TestSignalStore()
        val r = s.handle(set = "belts_buckled=2, seats_occupied=2,tbt_type=LEFT,tbt_dist_m=150")
        assertTrue(r.errors.toString(), r.ok)
        repeat(3) { k ->
            val f = s.applyTo(Frame(t = k / 10.0, speedKph = 30.0, gear = 3, beltsBuckled = -1))
            assertEquals(2, f.beltsBuckled); assertEquals(2, f.seatsOccupied)
            assertEquals(1, f.tbtType); assertEquals(150.0, f.tbtDistM, 0.0)
            assertEquals(30.0, f.speedKph, 0.0); assertEquals(3, f.gear); assertEquals(k / 10.0, f.t, 0.0)
        }
        s.handle(clear = "tbt_dist_m")
        assertTrue(s.applyTo(Frame(t = 0.0)).tbtDistM.isNaN())
        assertEquals(2, s.applyTo(Frame(t = 0.0)).beltsBuckled)
        s.handle(clear = "all")
        assertTrue(s.frame.isEmpty())
        val same = Frame(t = 1.0, beltsBuckled = 1)
        assertTrue("no overrides -> the very same frame", s.applyTo(same) === same)
    }

    @Test fun namesAndUnknown() {
        val s = TestSignalStore()
        assertTrue(s.handle(set = "gear=D,drive_mode=SPORT,caution_type=SCHOOL,road_class=EXPRESSWAY,belts_buckled=unknown,speed_limit_kph=nan,school_zone=true").ok)
        val f = s.applyTo(Frame(t = 0.0, beltsBuckled = 2, speedLimitKph = 50.0))
        assertEquals(3, f.gear); assertEquals(2, f.driveMode); assertEquals(1, f.cautionType); assertEquals(2, f.roadClass)
        assertEquals(-1, f.beltsBuckled); assertTrue(f.speedLimitKph.isNaN()); assertEquals(1, f.schoolZone)
    }

    @Test fun signalsACarDoesNotGiveAppsAreRefusedAndBadEntriesSkipped() {
        val s = TestSignalStore()
        val r = s.handle(set = "t=5,tt_fca=1,speed_limit=50,belts_buckled=1.5,speed_kph=fast,seats_occupied=2", cabin = "hazard=true,seat_belt=1,seat_position=3")
        assertFalse(r.ok)
        assertEquals(r.errors.joinToString("\n"), 7, r.errors.size)
        assertTrue(r.errors.any { it.startsWith("tt_fca") && "PRIVATE_CAR_SAFETY" in it })
        assertTrue(r.errors.any { it.startsWith("hazard") })
        assertEquals("the valid entries still apply", mapOf("seats_occupied" to 2.0), s.frame)
        assertEquals(mapOf<String, Any>("seat_position" to 3.0), s.cabin)
    }

    @Test fun wrapperDeliversCabinShadowsTheCarAndAddsCapabilities() {
        val store = TestSignalStore()
        val car = FakeSource()
        val w = TestSignalSource(car, store)
        val got = ArrayList<Pair<String, Any?>>()
        val frames = ArrayList<Frame>()
        w.start(onFrame = { frames.add(it) }, onPlace = {}, onCabin = { k, v -> got.add(k to v) })

        car.cabin!!("seat_position", 0.0)                      // the car's own reading passes
        val r = store.handle(cabin = "seat_position=1")
        assertTrue(r.listening)
        car.cabin!!("seat_position", 0.0)                      // shadowed while the test value is set
        car.cabin!!("defrost", true)                           // other signals still pass
        store.handle(clear = "seat_position")
        car.cabin!!("seat_position", 0.0)
        assertEquals(listOf("seat_position" to 0.0, "seat_position" to 1.0, "defrost" to true, "seat_position" to 0.0), got)

        store.handle(set = "belts_buckled=1", cabin = "fan=2")
        car.frame!!(Frame(t = 0.0, brakeOn = 1))
        assertEquals(1, frames.single().beltsBuckled)
        assertEquals(setOf("brake", "belt", "fan"), w.capabilities())
        assertEquals("belts_buckled=1, cabin fan=2", w.status()["시험 신호"])
        assertNull("unknown capabilities stay unknown (every tour step shown)", TestSignalSource(FakeSource(caps = null), store).capabilities())

        w.stop()
        assertTrue(car.stopped)
        assertFalse("nothing listens after stop", store.handle().listening)
    }

    @Test fun theConsoleSeesWhatTheCarSentAndWhatTheAppGot() {
        var now = 1_000L
        val store = TestSignalStore(clock = { now })
        val car = FakeSource()
        assertNull("not listening yet", store.source())
        TestSignalSource(car, store).start(onFrame = {}, onPlace = {}, onCabin = { _, _ -> })
        assertEquals("가짜 차", store.source()!!.first)

        store.handle(set = "belts_buckled=2,speed_limit_kph=50")
        car.frame!!(Frame(t = 0.0, speedKph = 31.5, gear = 3, beltsBuckled = -1))
        now = 1_250L
        val seen = store.seen!!
        assertEquals(250L, store.now() - seen.atMs)
        val app = TestSignalStore.frameMap(seen.out)
        val raw = TestSignalStore.frameMap(seen.raw)
        assertEquals(2, app["belts_buckled"]); assertEquals(-1, raw["belts_buckled"])
        assertEquals(50.0, app["speed_limit_kph"]); assertNull("unknown float -> null", raw["speed_limit_kph"])
        assertEquals(31.5, app["speed_kph"]); assertEquals(3, app["gear"])

        val cat = TestSignalStore.catalog()
        @Suppress("UNCHECKED_CAST") val ids = (cat["channels"] as List<Map<String, Any?>>).map { it["id"] }
        assertTrue("belts_buckled" in ids && "tbt_dist_m" in ids)
        assertFalse("t and telltales are not offered", "t" in ids || ids.any { (it as String).startsWith("tt_") })
        assertFalse("hazard" in (cat["cabin"] as List<*>))
    }

    @Test fun injectedBeltAndSeatCompleteCarTourStepsLikeTheCar() {
        val now = 1_793_000_000_000L
        val core = SafeLogCore(MemoryStore(), clock = { now })
        core.finishFirstRun(true, "토리")
        val store = TestSignalStore()
        val car = FakeSource(caps = null)
        TestSignalSource(car, store).start(onFrame = { core.onFrame(it) }, onPlace = {}, onCabin = { k, v -> core.onCabin(k, v) })
        fun done(id: String) = core.snapshot().carTour.required.first { it.id == id }.done
        val parked = Frame(t = 0.0, speedKph = 0.0, gear = 0, brakeOn = 0, seatsOccupied = 1, beltsBuckled = -1)

        repeat(5) { car.frame!!(parked) }
        assertFalse(done("belt"))
        store.handle(set = "belts_buckled=1")
        repeat(5) { car.frame!!(parked) }
        assertTrue("belt step done from the injected buckle", done("belt"))

        store.handle(cabin = "seat_position=1")
        assertFalse("a seat step needs a change", done("seat"))
        store.handle(cabin = "seat_position=2")
        assertTrue(done("seat"))
    }

    /**
     * 90 s: 10 s up to [cruise] km/h, cruise, 10 s down to 0. A car with seat occupancy but no readable buckle and no
     * navigation, like the emulator. [at]: frame -> "set|clear" test-signal commands, sent as the trip goes.
     */
    private fun drive(store: TestSignalStore, at: Map<Int, String> = emptyMap(), blinkFrom: Int = -1, blinkTo: Int = -1, cruise: Double = 30.0): Trip {
        val frames = ArrayList<Frame>()
        for (k in 0 until 900) {
            at[k]?.let { store.handle(set = it.substringBefore('|').ifBlank { null }, clear = it.substringAfter('|', "").ifBlank { null }) }
            val v = cruise * minOf(1.0, k / 100.0, (900 - k) / 100.0)
            val signal = if (k in blinkFrom until blinkTo) 1 else 0
            frames.add(store.applyTo(Frame(t = k / 10.0, speedKph = v, gear = 3, brakeOn = 0, steerDeg = 0.0, turnSignal = signal, seatsOccupied = 2, beltsBuckled = -1)))
        }
        return Trip.fromFrames(frames)
    }

    private fun outcomes(trip: Trip, habit: String): List<Int> {
        val core = SafeLogCore(MemoryStore())
        val res = core.analyze(RecordedTrip(trip, 0L, emptyList(), emptyList(), "driver-1"))
        assertNull(res.skipped)
        return res.judgments.filter { it.habit == habit }.map { it.outcome }
    }

    @Test fun injectedBeltsAndTurnReachTheEngine() {
        // without test signals: the buckle is unknown -> abstain; no turn-by-turn -> no blinker judgment
        assertEquals(listOf(Outcome.ABSTAIN), outcomes(drive(TestSignalStore()), "seat_belts"))
        assertTrue(outcomes(drive(TestSignalStore()), "turn_signal_turn").isEmpty())

        val s = TestSignalStore()
        s.handle(set = "belts_buckled=2")
        assertEquals(listOf(Outcome.SUCCESS), outcomes(drive(s), "seat_belts"))

        // a left turn announced 150 m ahead at 20 s (held; the engine dead-reckons it to 0 at ~38 s),
        // left blinker from 29 s (~75 m before the turn), turn-by-turn cleared at 42 s
        val turn = mapOf(200 to "tbt_type=LEFT,tbt_dist_m=150,nav_active=1|", 420 to "|tbt_type,tbt_dist_m")
        assertEquals(listOf(Outcome.SUCCESS), outcomes(drive(TestSignalStore(), turn, blinkFrom = 290, blinkTo = 400), "turn_signal_turn"))
        // the same turn with the blinker only ~8 m before it: late (the law asks for 30 m)
        assertEquals(listOf(Outcome.MISS), outcomes(drive(TestSignalStore(), turn, blinkFrom = 370, blinkTo = 400), "turn_signal_turn"))

        // a school zone for 20 s: 25 km/h keeps the 30 km/h limit, 40 km/h does not
        val school = mapOf(300 to "school_zone=1|", 500 to "|school_zone")
        assertEquals(listOf(Outcome.SUCCESS), outcomes(drive(TestSignalStore(), school, cruise = 25.0), "school_zone"))
        assertEquals(listOf(Outcome.MISS), outcomes(drive(TestSignalStore(), school, cruise = 40.0), "school_zone"))
    }
}
