package com.safelog.app.vehicle

import android.content.Context
import com.safelog.core.contract.Frame
import com.safelog.core.contract.Trip
import com.safelog.core.json.Json
import com.safelog.core.json.list
import com.safelog.core.json.str
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class DemoTrip(val name: String, val title: String, val durationS: Double)

/**
 * Plays a recorded trip (app/src/main/assets/demo, exported from the PC simulator in data contract v3) through
 * exactly the same path as a real car: frames -> phase -> live praise -> recording -> engine -> ledger.
 * [speed] = playback speed (10 = a 10-minute drive in 1 minute) for the emulator demo.
 */
class ReplaySource(private val context: Context, private val demo: String, private val speed: Double = 10.0, private val onDone: () -> Unit = {}) : VehicleSource {
    override val name = "시연 주행"
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safelog-replay") }
    private var job: ScheduledFuture<*>? = null

    override fun start(onFrame: (Frame) -> Unit, onPlace: (String) -> Unit, onCabin: (String, Any?) -> Unit, onVehicleId: (String) -> Unit) {
        val trip = readTrip(context, demo)
        val meta = Json.obj(context.assets.open("demo/$demo.json").bufferedReader().readText())
        val places = meta.list("places").map { p -> (p as List<*>).let { (it[0] as Number).toDouble() to it[1] as String } }
        var k = 0
        var nextPlace = 0
        val periodUs = (100_000 / speed).toLong().coerceAtLeast(1_000)
        job = exec.scheduleAtFixedRate({
            if (k >= trip.n) { job?.cancel(false); onDone(); return@scheduleAtFixedRate }
            val f = Frame.fromTrip(trip, k)
            while (nextPlace < places.size && places[nextPlace].first <= f.t) { onPlace(places[nextPlace].second); nextPlace++ }
            onFrame(f)
            k++
        }, 0, periodUs, TimeUnit.MICROSECONDS)
    }

    override fun stop() { job?.cancel(false); exec.shutdown() }

    override fun status() = mapOf("시연" to demo)

    companion object {
        /**
         * The APK does not hold demo/x.csv.gz: the Android build (asset merge) gunzips assets ending in .gz and drops
         * the extension, so it ships demo/x.csv. Read that, keep .gz as a fallback, and decide by the gzip magic bytes.
         */
        fun readTrip(context: Context, demo: String): Trip {
            val raw = runCatching { context.assets.open("demo/$demo.csv") }.getOrNull() ?: context.assets.open("demo/$demo.csv.gz")
            return java.io.BufferedInputStream(raw).use { s ->
                s.mark(2)
                val gz = s.read() == 0x1f && s.read() == 0x8b
                s.reset()
                Trip.readCsv(s, gz)
            }
        }

        fun list(context: Context): List<DemoTrip> = runCatching {
            Json.parse(context.assets.open("demo/index.json").bufferedReader().readText()) as List<*>
        }.getOrDefault(emptyList<Any?>()).map { m ->
            @Suppress("UNCHECKED_CAST") (m as Map<String, Any?>).let { DemoTrip(it.str("name"), it.str("title"), (it["duration_s"] as Number).toDouble()) }
        }
    }
}
