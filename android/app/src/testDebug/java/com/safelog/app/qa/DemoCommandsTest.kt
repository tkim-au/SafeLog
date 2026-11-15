package com.safelog.app.qa

import com.safelog.app.vehicle.DemoTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 시연 panel's buttons as console / adb commands: parsing, checks, and what reaches the app. */
class DemoCommandsTest {
    /** Records the calls; the state is set by the test. */
    class FakeTarget : DemoTarget {
        val calls = ArrayList<String>()
        override val trips = listOf(DemoTrip("commute_city", "출근길 · 시내 10분", 600.0), DemoTrip("lane_practice", "차선 바꾸기 연습", 300.0))
        override var replaying: String? = null
        override var sampleShown = false
        override var parked = true
        override var tourNext: String? = "시트 위치 맞추기"
        override var training = false
        var model: () -> String = { "기기 파일" }

        override fun replay(trip: String, speed: Double) { calls.add("replay $trip $speed"); replaying = trip }
        override fun arrive() { calls.add("arrive"); replaying = null }
        override fun tourStep() { calls.add("tour-step") }
        override fun newCar() { calls.add("new-car") }
        override fun modeFlip() { calls.add("mode-flip"); training = !training }
        override fun seedHistory() { calls.add("seed-history") }
        override fun points(n: Int) { calls.add("points $n") }
        override fun loadModel() = model().also { calls.add("load-model") }
        override fun sync() = "동기화 2건 · 서버 잔액 120P".also { calls.add("sync") }
        override fun showSample(on: Boolean) { calls.add("sample-screen $on"); sampleShown = on }
        override fun sample(moment: String) { calls.add("sample $moment") }
        override fun info() = mapOf("screen" to if (sampleShown) "sample" else "live")
    }

    private val t = FakeTarget()
    private val d = DemoCommands(t)

    private fun ok(line: String): String {
        val r = d.run(line)
        assertNull("$line: ${r.error}", r.error)
        return r.message!!
    }

    private fun refused(line: String): String {
        val before = t.calls.size
        val r = d.run(line)
        assertNotNull("$line should be refused", r.error)
        assertEquals("$line: nothing reached the app", before, t.calls.size)
        return r.error!!
    }

    @Test fun replayAndArrive() {
        assertTrue(ok("replay commute_city").contains("10배속"))
        assertEquals("replay commute_city 10.0", t.calls.last())
        assertTrue(refused("replay lane_practice").contains("commute_city"))   // one at a time, as the panel does
        assertTrue(ok("arrive").contains("commute_city"))
        refused("arrive")
        ok("  replay   lane_practice  ×30 ")
        assertEquals("replay lane_practice 30.0", t.calls.last())
        t.replaying = null
        ok("REPLAY commute_city x1")
        t.replaying = null
        assertTrue(refused("replay").contains("commute_city lane_practice"))
        assertTrue(refused("replay rainy_evening").contains("없는 시연 주행"))
        refused("replay commute_city 0")
        refused("replay commute_city 61")
        refused("replay commute_city fast")
        refused("replay commute_city 10 extra")
    }

    @Test fun tourModeCar() {
        assertTrue(ok("tour-step").contains("시트 위치 맞추기"))
        t.parked = false
        assertTrue(refused("tour-step").contains("주차"))
        t.parked = true; t.tourNext = null
        assertTrue(refused("tour-step").contains("new-car"))
        ok("new-car")
        assertTrue(ok("mode-flip").contains("켰습니다"))
        assertTrue(ok("mode-flip").contains("껐습니다"))
        assertEquals(listOf("tour-step", "new-car", "mode-flip", "mode-flip"), t.calls)
    }

    @Test fun recordsPointsModelSync() {
        ok("seed-history")
        ok("points"); ok("points +1200")
        assertEquals(listOf("seed-history", "points 500", "points 1200"), t.calls)
        refused("points 0"); refused("points lots"); refused("points 100001")
        assertTrue(ok("load-model").contains("기기 파일"))
        t.model = { throw IllegalStateException("/sdcard/Download/gate_v3.json: 파일 없음") }
        assertTrue(d.run("load-model").error!!.contains("파일 없음"))
        assertEquals("동기화 2건 · 서버 잔액 120P", ok("sync"))
    }

    @Test fun screenSample() {
        assertTrue(refused("sample park").contains("screen sample"))   // sample moments only on the sample screen
        ok("screen sample")
        ok("sample park"); ok("sample insurance")
        refused("sample fly")
        ok("screen live")
        refused("screen other")
        refused("design B")   // the A/B design switch is gone: one design since 2026-11-10
        assertEquals(
            listOf("sample-screen true", "sample park", "sample insurance", "sample-screen false"),
            t.calls,
        )
    }

    @Test fun unknownOrEmpty() {
        assertTrue(refused("fly").contains("replay"))
        refused("")
        refused("sync now")
    }

    @Test fun catalogForThePage() {
        val c = d.catalog()
        assertEquals(listOf("commute_city", "lane_practice"), (c["trips"] as List<*>).map { (it as Map<*, *>)["name"] })
        assertTrue((c["actions"] as List<*>).containsAll(listOf("replay", "arrive", "tour-step", "screen")))
    }
}
