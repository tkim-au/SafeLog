package com.safelog.app.qa

import com.safelog.app.vehicle.TestSignalStore
import com.safelog.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** The debug test console: routes, the guards against other web pages, and the server over a real loopback socket. */
class TestConsoleTest {
    private val page = "<!doctype html><title>세이프로그 시험 신호</title>".toByteArray()
    private val local = mapOf("host" to "127.0.0.1:8077")

    @Test fun routes() {
        val store = TestSignalStore()
        val logged = ArrayList<String>()
        val api = TestConsoleApi(store, { page }, { logged.add(it) })

        val root = api.handle("GET", "/", local, "")
        assertEquals(200, root.status); assertTrue(root.type.startsWith("text/html")); assertTrue(String(root.body).contains("시험 신호"))

        val cmd = api.handle("POST", "/api/command", local + ("x-safelog-console" to "1"), """{"set":"belts_buckled=2","cabin":"seat_position=3"}""")
        assertEquals(200, cmd.status)
        val res = Json.obj(String(cmd.body))
        assertEquals(true, res["ok"])
        assertEquals(mapOf("belts_buckled" to 2.0), store.frame)
        assertTrue("one SafeLogQA line per web command", logged.single().contains("\"via\":\"web\""))

        val st = Json.obj(String(api.handle("GET", "/api/state?x=1", local, "").body))
        assertEquals(false, st["listening"])
        assertEquals(mapOf("belts_buckled" to 2.0), st["frame"])
        assertEquals(mapOf("seat_position" to 3.0), st["cabin"])

        val cat = Json.obj(String(api.handle("GET", "/api/catalog", local, "").body))
        assertTrue((cat["cabin"] as List<*>).contains("seat_position"))

        val bad = Json.obj(String(api.handle("POST", "/api/command", local + ("x-safelog-console" to "1"), """{"set":"tt_fca=1"}""").body))
        assertEquals(false, bad["ok"])
        assertEquals(404, api.handle("GET", "/nope", local, "").status)
        assertEquals(400, api.handle("POST", "/api/command", local + ("x-safelog-console" to "1"), "not json").status)
    }

    /** The page compares started_ms between polls: the same process keeps it, a restarted one has a new value. */
    @Test fun stateTellsWhenTheAppProcessStarted() {
        var now = 1_000L
        val api = TestConsoleApi(TestSignalStore { now }, { page })
        now = 9_000L
        val st = Json.obj(String(api.handle("GET", "/api/state", local, "").body))
        assertEquals(1_000L, (st["started_ms"] as Number).toLong())
        val again = Json.obj(String(api.handle("GET", "/api/state", local, "").body))
        assertEquals(st["started_ms"], again["started_ms"])
        val restarted = Json.obj(String(TestConsoleApi(TestSignalStore { now }, { page }).handle("GET", "/api/state", local, "").body))
        assertEquals(9_000L, (restarted["started_ms"] as Number).toLong())
    }

    @Test fun appCommands() {
        val store = TestSignalStore()
        val target = DemoCommandsTest.FakeTarget()
        val logged = ArrayList<String>()
        val api = TestConsoleApi(store, { page }, { logged.add(it) }, DemoCommands(target))
        val post = local + ("x-safelog-console" to "1")

        // test signals and an app command in one request: signals first, then the app
        val res = Json.obj(String(api.handle("POST", "/api/command", post, """{"set":"belts_buckled=2","app":"replay commute_city 30"}""").body))
        assertEquals(true, res["ok"])
        assertEquals(mapOf("belts_buckled" to 2.0), store.frame)
        assertEquals(listOf("replay commute_city 30.0"), target.calls)
        assertTrue((res["message"] as String).contains("30배속"))
        assertEquals(mapOf("screen" to "live"), res["app_info"])
        assertTrue("the log line has the command and its message", logged.single().contains("\"app\":\"replay commute_city 30\"") && logged.single().contains("30배속"))

        val refused = Json.obj(String(api.handle("POST", "/api/command", post, """{"app":"sample park"}""").body))
        assertEquals(false, refused["ok"])
        assertTrue((refused["errors"] as List<*>).single().toString().contains("screen sample"))

        val st = Json.obj(String(api.handle("GET", "/api/state", local, "").body))
        assertEquals(mapOf("screen" to "live"), st["app_info"])
        val cat = Json.obj(String(api.handle("GET", "/api/catalog", local, "").body))
        assertEquals(2, ((cat["app"] as Map<*, *>)["trips"] as List<*>).size)

        // without the app side (the old constructor) an app command is refused, signals still work
        val bare = TestConsoleApi(TestSignalStore(), { page })
        val r = Json.obj(String(bare.handle("POST", "/api/command", post, """{"set":"drive_mode=ECO","app":"sync"}""").body))
        assertEquals(false, r["ok"])
        assertEquals(mapOf("drive_mode" to 0.0), r["frame"])
    }

    @Test fun otherWebPagesCannotDriveIt() {
        val store = TestSignalStore()
        val api = TestConsoleApi(store, { page })
        // a cross-site form or no-cors fetch cannot add the custom header
        assertEquals(403, api.handle("POST", "/api/command", local, """{"set":"belts_buckled=2"}""").status)
        // DNS rebinding: the page reached under another host name
        assertEquals(403, api.handle("POST", "/api/command", mapOf("host" to "evil.example:8077", "x-safelog-console" to "1"), """{"set":"belts_buckled=2"}""").status)
        assertEquals(403, api.handle("GET", "/api/state", mapOf("host" to "evil.example"), "").status)
        assertTrue("nothing was set", store.frame.isEmpty())
        assertEquals(200, api.handle("GET", "/api/state", mapOf("host" to "localhost:8078"), "").status)
    }

    @Test fun serverAnswersOverLoopback() {
        val store = TestSignalStore()
        val server = TestConsoleServer(TestConsoleApi(store, { page }), 0)
        assertTrue(server.start())
        try {
            val base = "http://127.0.0.1:${server.boundPort}"
            val get = URL("$base/").openConnection() as HttpURLConnection
            assertEquals(200, get.responseCode)
            assertTrue(get.inputStream.readBytes().contentEquals(page))

            val post = URL("$base/api/command").openConnection() as HttpURLConnection
            post.requestMethod = "POST"
            post.doOutput = true
            post.setRequestProperty("Content-Type", "application/json")
            post.setRequestProperty("X-SafeLog-Console", "1")
            post.outputStream.use { it.write("""{"set":"drive_mode=SPORT,tbt_dist_m=150"}""".toByteArray()) }
            assertEquals(200, post.responseCode)
            assertEquals(true, Json.obj(post.inputStream.readBytes().toString(Charsets.UTF_8))["ok"])
            assertEquals(mapOf("drive_mode" to 2.0, "tbt_dist_m" to 150.0), store.frame)

            val state = URL("$base/api/state").openConnection() as HttpURLConnection
            assertEquals(200, state.responseCode)
            assertEquals("no-store", state.getHeaderField("Cache-Control"))
        } finally {
            server.stop()
        }
    }
}
