package com.safelog.core.sync

import com.safelog.core.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for backend/server (FastAPI). Plain HttpURLConnection + the core JSON codec, so it runs on Android
 * and in JVM tests. Every call is idempotent on the server (trip id / request id), so retries are safe.
 */
class ServerApi(private val baseUrl: String, private val timeoutMs: Int = 5000) {
    class HttpError(val code: Int, msg: String) : Exception("HTTP $code: $msg")

    fun call(method: String, path: String, body: Any? = null): Any? {
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(Json.write(body).toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        c.disconnect()
        if (code !in 200..299) throw HttpError(code, text.take(200))
        return if (text.isBlank()) null else Json.parse(text)
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(method: String, path: String, body: Any? = null) = call(method, path, body) as Map<String, Any?>

    fun health() = obj("GET", "/health")
    fun ensureDriver(id: String, name: String, mode: String) = obj("POST", "/drivers", mapOf("id" to id, "name" to name, "mode" to mode))
    /** [metrics]: summary indicators for the data-contribution programme; empty unless the driver opted in. */
    fun finish(driverId: String, insuranceShare: Boolean, facts: Map<String, Any?>, metrics: List<Any?> = emptyList()) =
        obj("POST", "/drives/${facts["id"]}/finish", mapOf("driverId" to driverId, "insuranceShare" to insuranceShare, "facts" to facts, "metrics" to metrics))

    /** 오판정 의견: the driver marked a journal scene as judged wrong (the server keeps one mark per scene). */
    fun dispute(driverId: String, tripId: String, sceneId: String, what: String, good: Boolean) =
        obj("POST", "/drives/$tripId/scenes/$sceneId/dispute", mapOf("driverId" to driverId, "what" to what, "good" to good))
    fun exchange(requestId: String, driverId: String, itemId: String) =
        obj("POST", "/exchanges", mapOf("requestId" to requestId, "driverId" to driverId, "itemId" to itemId))
    fun answerQuiz(driverId: String, quizId: String, choice: Int, ms: Long) =
        obj("POST", "/drivers/$driverId/quiz/$quizId/answer", mapOf("choice" to choice, "ms" to ms))
    fun consent(driverId: String, recording: Boolean, insurance: Boolean, data: Boolean) =
        obj("POST", "/drivers/$driverId/consent", mapOf("recording" to recording, "insurance" to insurance, "data" to data))

    /** The driver switched 연수 모드 by hand (the engine's own switch is replayed by the server from the trip facts). */
    fun setMode(driverId: String, mode: String) = obj("PUT", "/drivers/$driverId/mode", mapOf("mode" to mode))

    /** S05 내 차 익히기 finished: +50 XP once per driver (the server ignores repeats). */
    fun tourComplete(driverId: String, metrics: Map<String, Any?> = emptyMap()) = obj("POST", "/drivers/$driverId/tour/complete", metrics)
}
