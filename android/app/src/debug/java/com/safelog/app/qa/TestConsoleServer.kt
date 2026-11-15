package com.safelog.app.qa

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Debug builds only: a minimal HTTP/1.1 server for [TestConsoleApi], bound to the device's loopback address, so it
 * is reached only through `adb forward`. One request per connection (Connection: close). No dependencies.
 */
class TestConsoleServer(private val api: TestConsoleApi, private val port: Int, private val log: (String) -> Unit = {}) {
    @Volatile private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "safelog-console").apply { isDaemon = true } }

    /** The bound port (useful with port 0 in tests), or −1 before [start]. */
    val boundPort get() = socket?.localPort ?: -1

    fun start(): Boolean {
        val ss = ServerSocket()
        return try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8)
            socket = ss
            Thread({ acceptLoop(ss) }, "safelog-console-accept").apply { isDaemon = true }.start()
            true
        } catch (e: IOException) {
            log("port $port: $e")
            runCatching { ss.close() }
            false
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (_: IOException) { break }
            pool.execute { serve(s) }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.use {
                it.soTimeout = 5000
                val input = it.getInputStream().buffered()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 3) return respond(it, TestConsoleApi.Response(400, TEXT, "bad request".toByteArray()))
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 0..MAX_BODY) return respond(it, TestConsoleApi.Response(413, TEXT, "too large".toByteArray()))
                val body = ByteArray(length)
                var got = 0
                while (got < length) {
                    val n = input.read(body, got, length - got)
                    if (n < 0) return
                    got += n
                }
                respond(it, api.handle(parts[0], parts[1], headers, String(body, Charsets.UTF_8)))
            }
        } catch (e: IOException) {
            log("request: $e")
        }
    }

    private fun respond(s: Socket, r: TestConsoleApi.Response) {
        val head = "HTTP/1.1 ${r.status} ${REASON[r.status] ?: "Status"}\r\n" +
            "Content-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    /** One header line without CR LF; null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > MAX_LINE) throw IOException("header line too long")
        }
        return b.toString("ISO-8859-1")
    }

    companion object {
        private const val TEXT = "text/plain; charset=utf-8"
        private const val MAX_LINE = 8 * 1024
        private const val MAX_BODY = 64 * 1024
        private val REASON = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 413 to "Payload Too Large")
    }
}
