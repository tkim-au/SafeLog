package com.safelog.app.qa

import com.safelog.app.vehicle.TestSignalStore
import com.safelog.core.json.Json

/**
 * Debug builds only: the test console's routes, without Android or sockets (unit-tested on the JVM).
 *
 *   GET  /              the page (assets/test-console/index.html)
 *   GET  /api/state     overrides, the source and what it reads, the latest frame from the car and what the app got,
 *                       the app itself (app_info: screen, design, replay, engine, sync ...), and started_ms (a restart)
 *   GET  /api/catalog   settable channels, cabin signals, refused signals, and the app commands (app: trips, actions)
 *   POST /api/command   {"set": "k=v,...", "cabin": "k=v,...", "clear": "k,...|all", "place": "...", "app": "replay commute_city 10"},
 *                       as the adb receiver. "app" is one [DemoCommands] line (the buttons of the app's 시연 panel).
 *
 * The page is reached through `adb forward` on the computer's own browser. A web page from elsewhere in that browser
 * must not drive it, so a POST needs the header [HEADER] (a cross-site page cannot add it without a CORS preflight,
 * which this server never allows) and the Host must be 127.0.0.1 or localhost (no DNS rebinding).
 */
class TestConsoleApi(
    private val store: TestSignalStore,
    private val page: () -> ByteArray,
    private val log: (String) -> Unit = {},
    private val demo: DemoCommands? = null,
) {
    class Response(val status: Int, val type: String, val body: ByteArray)

    /** When this app process started the console. The test signals live in the process, so a new value tells the
     *  page the app was restarted (reinstall, crash, force stop) and what was put in is gone. */
    private val startedMs = store.now()

    fun handle(method: String, target: String, headers: Map<String, String>, body: String): Response {
        val path = target.substringBefore('?')
        val host = headers["host"]?.substringBefore(':')?.trim()?.lowercase()
        if (host != null && host != "127.0.0.1" && host != "localhost") return text(403, "127.0.0.1 또는 localhost로 여세요")
        return try {
            when {
                method == "GET" && (path == "/" || path == "/index.html") -> Response(200, "text/html; charset=utf-8", page())
                method == "GET" && path == "/api/state" -> json(200, state())
                method == "GET" && path == "/api/catalog" -> json(200, TestSignalStore.catalog() + ("app" to demo?.catalog()))
                method == "POST" && path == "/api/command" ->
                    if (headers[HEADER.lowercase()] != "1") text(403, "$HEADER: 1 헤더가 필요합니다") else command(body)
                else -> text(404, "없는 주소: $method $path")
            }
        } catch (t: Throwable) {
            text(400, "요청을 처리하지 못했습니다: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun command(body: String): Response {
        val m = if (body.isBlank()) emptyMap() else Json.obj(body)
        val cmd = linkedMapOf<String, Any?>()
        for (k in KEYS) (m[k] as? String)?.takeIf { it.isNotBlank() }?.let { cmd[k] = it }
        val out = run(cmd, store, demo)
        log(injectLine("web", cmd, out))
        return json(200, out)
    }

    fun state(): Map<String, Any?> {
        val seen = store.seen
        val src = store.source()
        return linkedMapOf(
            "listening" to (src != null),
            "source" to src?.first,
            "status" to src?.second,
            "frame" to store.frame,
            "cabin" to store.cabin,
            "app" to seen?.let { TestSignalStore.frameMap(it.out) },
            "car" to seen?.let { TestSignalStore.frameMap(it.raw) },
            "age_ms" to seen?.let { store.now() - it.atMs },
            "app_info" to demo?.info(),
            "started_ms" to startedMs,
        )
    }

    private fun json(status: Int, v: Any?) = Response(status, "application/json; charset=utf-8", Json.write(v).toByteArray(Charsets.UTF_8))
    private fun text(status: Int, s: String) = Response(status, "text/plain; charset=utf-8", s.toByteArray(Charsets.UTF_8))

    companion object {
        const val PORT = 8077
        const val HEADER = "X-SafeLog-Console"

        /** The command keys, in the order they are applied: test signals, then the app command. */
        val KEYS = listOf("clear", "set", "cabin", "place", "app")

        /**
         * One command from the page or adb: the test signals ([TestSignalStore.handle]), then the app command, if any.
         * The reply: ok, listening, frame, cabin, errors, and for an app command its message and the app's state.
         */
        fun run(cmd: Map<String, Any?>, store: TestSignalStore, demo: DemoCommands?): Map<String, Any?> {
            val r = store.handle(clear = cmd["clear"] as String?, set = cmd["set"] as String?, cabin = cmd["cabin"] as String?, place = cmd["place"] as String?)
            val app = (cmd["app"] as String?)?.let { demo?.run(it) ?: DemoCommands.Result(null, "app: 이 앱에서는 앱 명령을 받지 않습니다") }
            val errors = r.errors + listOfNotNull(app?.error)
            val out = linkedMapOf<String, Any?>("ok" to errors.isEmpty(), "listening" to r.listening, "frame" to r.frame, "cabin" to r.cabin, "errors" to errors)
            if (app != null) { out["message"] = app.message; out["app_info"] = demo?.info() }
            return out
        }

        /** One `SafeLogQA` log line per command (qa/soak collects them), the same for adb and the web page. */
        fun injectLine(via: String, cmd: Map<String, Any?>, out: Map<String, Any?>): String =
            Json.write(linkedMapOf<String, Any?>("ev" to "inject", "via" to via, "cmd" to cmd) + out.filterKeys { it != "app_info" })
    }
}
