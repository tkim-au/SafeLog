package com.safelog.core

import com.safelog.core.contract.Frame
import com.safelog.core.contract.Trip
import com.safelog.core.ledger.MemoryStore
import com.safelog.core.sync.ServerApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Car <-> server end to end. Runs only when SAFELOG_TEST_SERVER is set (e.g. http://127.0.0.1:8099 with
 * `uvicorn safelog_server.app:app --port 8099` started from backend/server). The server must reach exactly the
 * balance the car's own ledger reached.
 */
class ServerSyncTest {
    @Test fun carAndServerAgree() {
        val url = System.getenv("SAFELOG_TEST_SERVER") ?: return
        var clock = System.currentTimeMillis() - 3 * 86_400_000L; val unused = java.time.LocalDateTime.parse("2026-11-02T08:00:00").atZone(com.safelog.core.ledger.Rules.ZONE).toInstant().toEpochMilli()
        val store = MemoryStore()
        val core = SafeLogCore(store, clock = { clock }, defaultDrivers = listOf("sync-${System.nanoTime() % 1_000_000}" to "민수"))
        core.device.serverUrl = url
        core.finishFirstRun(true, null)
        for ((k, name) in listOf("commute_city", "lane_practice", "rainy_evening").withIndex()) {
            core.selectMissions(listOf(listOf("turn-signal", "lane-signal", "school-zone")[k]))
            val trip = Trip.readCsv(javaClass.classLoader.getResourceAsStream("parity/$name.csv.gz")!!)
            for (i in 0 until trip.n) { core.onFrame(Frame.fromTrip(trip, i)).recorded?.let { core.settle(it, core.analyze(it)) }; clock += 100 }
            core.dismissArrival()
            clock += 24 * 3600_000L
        }
        core.answerQuiz(1)
        val r = core.sync()
        assertEquals(null, r.error)
        assertTrue(r.sent >= 4)
        assertEquals(core.driver.balance, r.serverBalance ?: ServerApi(url).obj("GET", "/drivers/${core.driver.id}/briefing")["pointsBalance"].let { (it as Number).toInt() })
        val again = core.sync()
        assertEquals(0, again.sent)
        // the driver's own 연수 모드 switch and a finished 내 차 익히기 reach the server too
        core.setTrainingMode(core.driver.mode != "Training")
        while (!core.snapshot().carTour.finished) core.demoTourStep()
        val r3 = core.sync()
        assertEquals(null, r3.error)
        assertEquals(2, r3.sent)
        @Suppress("UNCHECKED_CAST")
        val srv = (ServerApi(url).call("GET", "/drivers", null) as List<Map<String, Any?>>).first { it["id"] == core.driver.id }
        assertEquals(core.driver.mode, srv["mode"])
        // 오판정 의견 reaches the server once, and a trip driven with 데이터 기여 on carries its summary indicators
        val scene = core.snapshot().records.journal.first().scenes.first()
        core.disputeScene(scene.id); core.disputeScene(scene.id)
        core.updateSettings(core.device.settings.copy(dataContribution = true))
        val trip = Trip.readCsv(javaClass.classLoader.getResourceAsStream("parity/commute_city.csv.gz")!!)
        for (i in 0 until trip.n) { core.onFrame(Frame.fromTrip(trip, i)).recorded?.let { core.settle(it, core.analyze(it)) }; clock += 100 }
        val r4 = core.sync()
        assertEquals(null, r4.error)
        assertEquals("one dispute and one finish", 2, r4.sent)
        assertEquals(0, r4.dropped)
        assertEquals(core.driver.level, (srv["level"] as Number).toInt())
    }
}
