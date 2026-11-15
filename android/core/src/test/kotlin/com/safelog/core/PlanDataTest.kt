package com.safelog.core

import com.safelog.core.contract.Frame
import com.safelog.core.contract.Trip
import com.safelog.core.engine.EngineConfig
import com.safelog.core.engine.RiskSummary
import com.safelog.core.engine.SafeLogEngine
import com.safelog.core.json.Json
import com.safelog.core.ledger.CarTour
import com.safelog.core.ledger.MemoryStore
import com.safelog.core.ledger.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What stays on the car and what leaves it (submitted plan, sections 3-3, 5 and 6-3): raw recordings for 90 days and
 * never uploaded, nothing sent without the recording consent, summary indicators only with 데이터 기여, the driver's
 * "judged wrong" mark, 내 차 익히기 practice time and skips, and braking the driver could not help.
 */
class PlanDataTest {
    private val day = 86_400_000L
    private var clockMs = java.time.LocalDateTime.parse("2026-11-02T08:00:00").atZone(Rules.ZONE).toInstant().toEpochMilli()
    private fun res(name: String) = javaClass.classLoader.getResourceAsStream(name) ?: error("missing $name")

    private fun core(store: MemoryStore, consent: Boolean = true) = SafeLogCore(store, clock = { clockMs }).apply {
        setServerUrl("http://127.0.0.1:9")                 // a server is configured, nothing is ever sent in these tests
        finishFirstRun(consent, null)
    }

    private fun drive(core: SafeLogCore, name: String) {
        val trip = Trip.readCsv(res("parity/$name.csv.gz"))
        for (k in 0 until trip.n) {
            core.onFrame(Frame.fromTrip(trip, k)).recorded?.let { core.settle(it, core.analyze(it)) }
            clockMs += 100
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun outbox(store: MemoryStore): List<Map<String, Any?>> =
        (store.read("outbox.json")?.let { Json.parse(it) as List<Any?> } ?: emptyList()).map { it as Map<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun payload(op: Map<String, Any?>) = op["payload"] as Map<String, Any?>

    @Test fun rawRecordingStaysOnTheCarForNinetyDaysAndIsNeverQueued() {
        val store = MemoryStore()
        val c = core(store)
        drive(c, "lane_practice")
        val first = store.list("raw/")
        assertEquals(1, first.size)
        assertTrue("the raw signals are there", store.read(first[0])!!.lineSequence().first().contains("speed_kph"))
        assertTrue("and are not in anything queued for the server", outbox(store).none { Json.write(it).contains("speed_kph") })
        // a trip 91 days later: the old recording is deleted, the new one kept
        clockMs += 91 * day
        c.dismissArrival()
        drive(c, "commute_city")
        val later = store.list("raw/")
        assertEquals(1, later.size)
        assertNotEquals(first[0], later[0])
        // "기록 지우기" removes the driver's recordings too
        c.clearRecords()
        assertEquals(emptyList<String>(), store.list("raw/"))
    }

    @Test fun nothingButTheConsentFlagLeavesWithoutTheRecordingConsent() {
        val store = MemoryStore()
        val c = core(store, consent = false)
        c.setTrainingMode(false); c.answerQuiz(0)
        assertEquals(emptyList<Any?>(), outbox(store).map { it["kind"] })
        c.finishFirstRun(true, null)
        c.setTrainingMode(true)
        assertEquals(listOf<Any?>("mode"), outbox(store).map { it["kind"] })
        c.withdrawConsent()                                  // the withdrawal itself must reach the server
        assertEquals(listOf<Any?>("mode", "consent"), outbox(store).map { it["kind"] })
        c.setTrainingMode(false)
        assertEquals(2, outbox(store).size)
    }

    @Test fun summaryIndicatorsLeaveOnlyWithDataContribution() {
        val store = MemoryStore()
        val c = core(store)
        drive(c, "lane_practice")
        val off = outbox(store).single { it["kind"] == "finish" }
        assertEquals("off by default", emptyList<Any?>(), payload(off)["metrics"])
        assertFalse("the trip facts carry no indicators", (payload(off)["facts"] as Map<*, *>).containsKey("metrics"))

        c.updateSettings(c.device.settings.copy(dataContribution = true))
        c.dismissArrival(); clockMs += day
        drive(c, "commute_city")
        @Suppress("UNCHECKED_CAST")
        val metrics = payload(outbox(store).last { it["kind"] == "finish" })["metrics"] as List<Map<String, Any?>>
        assertTrue(metrics.isNotEmpty())
        for (m in metrics) assertEquals(setOf("habit", "success", "value", "tS", "rain", "night", "congestion", "roadClass"), m.keys)
        val held = EngineConfig.bundled().notReleased
        assertTrue("released habits only", metrics.none { it["habit"] in held })
    }

    @Test fun aSceneCanBeMarkedAsJudgedWrongOnce() {
        val store = MemoryStore()
        val c = core(store)
        drive(c, "lane_practice")
        val scene = c.snapshot().records.journal.first().scenes.first()
        assertFalse(scene.disputed)
        val balance = c.driver.balance
        c.disputeScene(scene.id); c.disputeScene(scene.id); c.disputeScene("no-such-scene")
        assertTrue(c.snapshot().records.journal.first().scenes.first().disputed)
        assertEquals("points do not change", balance, c.driver.balance)
        val sent = outbox(store).filter { it["kind"] == "dispute" }
        assertEquals(1, sent.size)
        assertEquals(scene.id, payload(sent[0])["sceneId"])
        // kept across a restart
        assertTrue(SafeLogCore(store, clock = { clockMs }).snapshot().records.journal.first().scenes.first().disputed)
    }

    @Test fun carTourPracticeTimeSkipsAndRetry() {
        val store = MemoryStore()
        val c = core(store)
        c.updateSettings(c.device.settings.copy(dataContribution = true))
        c.openCarTour()
        clockMs += 60_000
        c.openCarTour()                                      // opening again does not restart the clock
        c.skipCarStep("seat")
        while (c.snapshot().carTour.required.any { !it.done }) {
            val pending = c.snapshot().carTour.required.first { !it.done }
            for ((sig, v) in CarTour.demoSignals(pending.id)) c.onCabin(sig, v)
            clockMs += 5_000
        }
        val n = c.snapshot().carTour.required.size
        val tour = payload(outbox(store).single { it["kind"] == "tour" })
        assertEquals(n, (tour["steps"] as Number).toInt())
        assertEquals("the skipped step counts even though the car noticed it later", 1, (tour["skipped"] as Number).toInt())
        assertEquals((60_000L + (n - 1) * 5_000L) / 1000, (tour["durationS"] as Number).toLong())

        // "다시 해 보기": the step opens again and must be done on the car; an on-then-off step needs both again
        val xp = c.driver.xpTotal
        c.retryCarStep("hazard")
        assertFalse(c.snapshot().carTour.required.first { it.id == "hazard" }.done)
        assertFalse(c.snapshot().carTour.finished)
        assertEquals("hazard", c.snapshot().carTour.next?.id)
        c.onCabin("hazard", false)
        assertFalse("off alone is not enough", c.snapshot().carTour.required.first { it.id == "hazard" }.done)
        c.onCabin("hazard", true); c.onCabin("hazard", false)
        assertTrue(c.snapshot().carTour.finished)
        assertEquals("no second reward", xp, c.driver.xpTotal)
        assertEquals(1, outbox(store).count { it["kind"] == "tour" })
        c.retryCarStep("no-such-step")
        // without 데이터 기여 the server only learns that the tour was finished
        val store2 = MemoryStore(); val c2 = core(store2)
        c2.openCarTour()
        while (!c2.snapshot().carTour.finished) c2.demoTourStep()
        assertEquals(emptyMap<String, Any?>(), payload(outbox(store2).single { it["kind"] == "tour" }))
    }

    private fun risk(decelG: Double, wiper: Int = 0, congestion: Int = 0): RiskSummary {
        val frames = ArrayList<Frame>()
        var v = 70.0
        fun add() = frames.add(Frame(t = frames.size / 10.0, speedKph = v, gear = 3, brakeOn = 0, cruiseActive = 0, wiperActive = wiper, night = 0, congestion = congestion))
        repeat(250) { add() }
        repeat(20) { v -= decelG * 9.80665 * 3.6 / 10; add() }          // 2 s of braking
        repeat(250) { add() }
        return SafeLogEngine(EngineConfig.bundled()).runTrip(Trip.fromFrames(frames), emptyList()).risk
    }

    @Test fun hardBrakeInRainOrCongestionIsNotHeldAgainstTheDriver() {
        val dry = risk(0.38)
        assertEquals(1, dry.hardBrake); assertEquals(0, dry.hardBrakeForced); assertEquals(0, dry.severeBrake)
        assertTrue(dry.anyRiskMoment)
        for (r in listOf(risk(0.38, wiper = 1), risk(0.38, congestion = 1), risk(0.38, congestion = 2))) {
            assertEquals(0, r.hardBrake); assertEquals(1, r.hardBrakeForced)
            assertFalse("no risk moment: the safe distance still counts", r.anyRiskMoment)
        }
        // a severe brake counts in any weather, so the reward gate for dangerous trips is unchanged
        val severe = risk(0.60, wiper = 1, congestion = 2)
        assertEquals(1, severe.hardBrake); assertEquals(0, severe.hardBrakeForced); assertEquals(1, severe.severeBrake)
        assertTrue(severe.anyRiskMoment)
    }
}
