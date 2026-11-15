package com.safelog.app.vehicle

import com.safelog.core.contract.DataContract
import com.safelog.core.contract.Frame
import com.safelog.core.contract.Trip
import com.safelog.core.json.Json
import com.safelog.core.ledger.CarTour
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Debug builds only: car signals the Pleos emulator cannot produce, set from adb, so the judging and 내 차 익히기
 * can be tested on the emulator. The Virtual Console has no seat-belt buckle, no drive mode, no navigation events,
 * and its seat-position controls do not reach the SDK. Modifying the console is not allowed (Pleos SDK license 3.4),
 * so the values enter here, at the edge of the app, and take exactly the path a car's values take afterwards.
 *
 * Entry points (src/debug only): `qa.TestSignalReceiver` for adb (`android/tools/test-signal.sh`) and the test console
 * page served by `qa.TestConsoleProvider` on the device's 127.0.0.1:8077 (`test-signal.sh open`), which also shows
 * [seen], what the app receives. Release builds have none of them and never wrap a source (BuildConfig.DEBUG).
 * See docs/2_개발/시험_신호_넣기.md.
 *
 * - **frame**: data-contract channels ([DataContract.ALL_CHANNELS] names), held in every 10 Hz frame until cleared,
 *   like a car's ON_CHANGE value. The engine dead-reckons navigation distances from the moment a value changes,
 *   the same as after a NaviHelper update.
 * - **cabin**: 내 차 익히기 readings outside the contract (seat_position, defrost ...), delivered once when set.
 *   While one is set, the real source's readings of that signal are dropped.
 * - **place**: one navigation place name for the journal.
 *
 * Refused: `t` (the app sets the time base), telltales `tt_*` and the hazard light. A real car does not give them
 * to a third-party app (PRIVATE_CAR_SAFETY; TurnSignalValue has no hazard), so a test with them proves nothing.
 */
class TestSignalStore(private val clock: () -> Long = System::currentTimeMillis) {
    /** Where one-shot readings go: the running source wrapper. */
    interface Sink {
        fun cabin(signal: String, value: Any)
        fun place(name: String)
        /** The wrapped source's name and what it reads (channel -> where from), for the test console page. */
        val sourceName: String
        fun sourceStatus(): Map<String, String>
    }

    class Result(val frame: Map<String, Double>, val cabin: Map<String, Any>, val errors: List<String>, val listening: Boolean) {
        val ok get() = errors.isEmpty()
        fun toMap(): Map<String, Any?> = linkedMapOf("ok" to ok, "listening" to listening, "frame" to frame, "cabin" to cabin, "errors" to errors)
        fun json(): String = Json.write(toMap())
    }

    /** The latest frame from the source ([raw]) and what the app got after the overrides ([out]). */
    class Seen(val raw: Frame, val out: Frame, val atMs: Long)

    @Volatile var frame: Map<String, Double> = emptyMap(); private set
    @Volatile var cabin: Map<String, Any> = emptyMap(); private set
    @Volatile var seen: Seen? = null; private set
    private val sinks = CopyOnWriteArraySet<Sink>()

    fun attach(s: Sink) { sinks.add(s) }
    fun detach(s: Sink) { sinks.remove(s) }

    /** The running source (name, status), or null when the app is not listening to a car or replay. */
    fun source(): Pair<String, Map<String, String>>? = sinks.firstOrNull()?.let { it.sourceName to it.sourceStatus() }

    fun observed(raw: Frame, out: Frame) { seen = Seen(raw, out, clock()) }

    fun now() = clock()

    /**
     * One command. Order: [clear] (names or "all"), [set] ("channel=value,..."), [cabin] ("signal=value,..."),
     * [place]. A bad entry is reported in [Result.errors] and skipped; the rest still applies.
     */
    fun handle(clear: String? = null, set: String? = null, cabin: String? = null, place: String? = null): Result {
        val errors = ArrayList<String>()
        val deliver = ArrayList<Pair<String, Any>>()
        synchronized(this) {
            var f = frame
            var c = this.cabin
            for (name in names(clear)) {
                if (name == "all") { f = emptyMap(); c = emptyMap() } else { f = f - name; c = c - name }
            }
            for ((k, raw) in pairs(set, errors)) {
                frameValue(k, raw).onSuccess { f = f + (k to it) }.onFailure { errors.add(it.message ?: k) }
            }
            for ((k, raw) in pairs(cabin, errors)) {
                cabinValue(k, raw).onSuccess { c = c + (k to it); deliver.add(k to it) }.onFailure { errors.add(it.message ?: k) }
            }
            frame = f
            this.cabin = c
        }
        // outside the lock: the sinks call into the app (which takes its own lock)
        for ((k, v) in deliver) sinks.forEach { it.cabin(k, v) }
        place?.trim()?.takeIf { it.isNotEmpty() }?.let { p -> sinks.forEach { it.place(p) } }
        return Result(frame, this.cabin, errors, sinks.isNotEmpty())
    }

    fun current() = Result(frame, cabin, emptyList(), sinks.isNotEmpty())

    /** [f] with every frame override written over it (the source's own values elsewhere). */
    fun applyTo(f: Frame): Frame {
        val ov = frame
        if (ov.isEmpty()) return f
        val tr = Trip.empty(1)
        f.writeTo(tr, 0)
        for ((k, v) in ov) if (k in INT) tr.i(k)[0] = v.toInt() else tr.d(k)[0] = v
        return Frame.fromTrip(tr, 0)
    }

    /** True while [signal] is set here: the real source's reading of it is dropped. */
    fun blocksCabin(signal: String) = signal in cabin

    /** What the car "reports" thanks to the overrides, in 내 차 익히기 signal names (shows those tour steps). */
    fun impliedCaps(): Set<String> = frame.keys.mapNotNull { TOUR_SIGNAL[it] }.toSet() + cabin.keys

    fun status(): Map<String, String> {
        if (frame.isEmpty() && cabin.isEmpty()) return emptyMap()
        val parts = frame.map { (k, v) -> "$k=${num(v)}" } + cabin.map { (k, v) -> "cabin $k=${if (v is Double) num(v) else v}" }
        return mapOf("시험 신호" to parts.joinToString(", "))
    }

    companion object {
        private val INT = DataContract.INT_CHANNELS.toSet()
        private val FLOAT = DataContract.FLOAT_CHANNELS.toSet()
        private val SETTABLE = DataContract.ALL_CHANNELS.filter { it != "t" && !it.startsWith("tt_") }
        private const val NOT_IN_CAR = "실제 차에서 앱이 읽을 수 없는 신호라 넣지 않습니다"

        /** Contract channel -> the 내 차 익히기 signal it feeds (CarTour.signalsOf). */
        private val TOUR_SIGNAL = mapOf(
            "gear" to "gear", "belts_buckled" to "belt", "brake_on" to "brake", "turn_signal" to "turn_signal",
            "low_beam" to "low_beam", "wiper_active" to "wiper", "drive_mode" to "drive_mode", "steer_deg" to "steering",
        )

        /** Names for the enum channels (same numbers as DataContract / data_spec/signals.py). */
        private val NAMES: Map<String, Map<String, Int>> = mapOf(
            "gear" to mapOf("P" to DataContract.GEAR_P, "R" to DataContract.GEAR_R, "N" to DataContract.GEAR_N, "D" to DataContract.GEAR_D),
            "turn_signal" to mapOf("OFF" to DataContract.SIGNAL_OFF, "LEFT" to DataContract.SIGNAL_LEFT, "RIGHT" to DataContract.SIGNAL_RIGHT),
            "drive_mode" to mapOf("ECO" to 0, "NORMAL" to 1, "SPORT" to 2),
            "tbt_type" to mapOf(
                "NONE" to DataContract.TBT_NONE, "LEFT" to DataContract.TBT_LEFT, "RIGHT" to DataContract.TBT_RIGHT,
                "UTURN" to DataContract.TBT_UTURN, "STRAIGHT" to DataContract.TBT_STRAIGHT,
            ),
            "caution_type" to mapOf(
                "NONE" to 0, "SCHOOL" to DataContract.CAUTION_SCHOOL, "SPEED_BUMP" to DataContract.CAUTION_SPEED_BUMP,
                "RAILROAD" to DataContract.CAUTION_RAILROAD, "ACCIDENT_PRONE" to DataContract.CAUTION_ACCIDENT_PRONE,
                "FOG" to DataContract.CAUTION_FOG, "WILDLIFE" to DataContract.CAUTION_WILDLIFE, "DOWNHILL" to DataContract.CAUTION_DOWNHILL,
                "FALLING_ROCK" to DataContract.CAUTION_FALLING_ROCK, "NARROW" to DataContract.CAUTION_NARROW,
                "SLIPPERY" to DataContract.CAUTION_SLIPPERY,
            ),
            "road_class" to mapOf("URBAN" to DataContract.ROAD_URBAN, "RURAL" to DataContract.ROAD_RURAL, "EXPRESSWAY" to DataContract.ROAD_EXPRESSWAY),
        )

        /** 내 차 익히기 signals a cabin reading may carry (every step's signal), minus the hazard light. */
        private val CABIN = (CarTour.REQUIRED + CarTour.EXTRAS.flatMap { it.steps }).map { it.signal }.toSet() - "hazard"

        private fun names(s: String?) = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        private fun pairs(s: String?, errors: MutableList<String>): List<Pair<String, String>> = names(s).mapNotNull { p ->
            val k = p.substringBefore('=', "").trim()
            val v = p.substringAfter('=', "").trim()
            if (k.isEmpty() || v.isEmpty()) { errors.add("$p: 이름=값 모양이 아님"); null } else k to v
        }

        private fun frameValue(k: String, raw: String): kotlin.Result<Double> = runCatching {
            require(k != "t") { "t: 시각은 앱이 정합니다" }
            require(!k.startsWith("tt_")) { "$k: 경고등은 $NOT_IN_CAR (PRIVATE_CAR_SAFETY)" }
            require(k in INT || k in FLOAT) { "$k: 데이터 계약에 없는 채널 (${SETTABLE.joinToString(" ")})" }
            val u = raw.uppercase()
            if (k in FLOAT) {
                if (u == "NAN" || u == "UNKNOWN") return@runCatching Double.NaN
                raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("$k=$raw: 숫자가 아님")
            } else {
                val named = NAMES[k]?.get(u)
                when {
                    named != null -> named.toDouble()
                    u == "UNKNOWN" -> -1.0
                    u == "TRUE" || u == "ON" -> 1.0
                    u == "FALSE" || u == "OFF" -> 0.0
                    else -> raw.toIntOrNull()?.toDouble()
                        ?: throw IllegalArgumentException("$k=$raw: 정수가 아님" + (NAMES[k]?.let { " (이름: ${it.keys.joinToString(" ")})" } ?: ""))
                }
            }
        }

        private fun cabinValue(k: String, raw: String): kotlin.Result<Any> = runCatching {
            require(k != "hazard") { "hazard: 비상등은 $NOT_IN_CAR (TurnSignalValue에 비상등 없음)" }
            require(k in CABIN) { "$k: 내 차 익히기 신호가 아님 (${CABIN.sorted().joinToString(" ")})" }
            when (raw.uppercase()) {
                "TRUE", "ON" -> true
                "FALSE", "OFF" -> false
                else -> raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("$k=$raw: true/false 또는 숫자가 아님")
            }
        }

        private fun num(v: Double) = if (v.isNaN()) "nan" else if (v == Math.rint(v)) v.toLong().toString() else v.toString()

        /** [f] as contract name -> value: ints as Int (−1 = unknown), floats as Double or null when unknown. */
        fun frameMap(f: Frame): Map<String, Any?> {
            val tr = Trip.empty(1)
            f.writeTo(tr, 0)
            return DataContract.ALL_CHANNELS.associateWith { k -> if (k in INT) tr.i(k)[0] else tr.d(k)[0].takeIf { it.isFinite() } }
        }

        /** What the test console page may offer: settable channels (kind, value names), cabin signals, refused signals. */
        fun catalog(): Map<String, Any?> = linkedMapOf(
            "channels" to SETTABLE.map { k -> linkedMapOf("id" to k, "kind" to if (k in INT) "int" else "float", "names" to (NAMES[k] ?: emptyMap())) },
            "cabin" to CABIN.sorted(),
            "refused" to linkedMapOf(
                "t" to "시각은 앱이 정합니다",
                "tt_*" to "경고등은 $NOT_IN_CAR (PRIVATE_CAR_SAFETY)",
                "hazard" to "비상등은 $NOT_IN_CAR (TurnSignalValue에 비상등 없음)",
            ),
        )
    }
}

/** The one store the debug receiver writes to and every wrapped source reads from (one per process). */
object TestSignals {
    val store = TestSignalStore()
}

/**
 * Wraps the running source (car or demo replay): frames get the frame overrides, cabin overrides are delivered and
 * shadow the source's own readings, capabilities include what is injected. With nothing set it changes nothing.
 */
class TestSignalSource(val inner: VehicleSource, private val store: TestSignalStore = TestSignals.store) : VehicleSource, TestSignalStore.Sink {
    override val name get() = inner.name
    override val sourceName get() = inner.name
    override fun sourceStatus() = inner.status()
    @Volatile private var cabinOut: ((String, Any?) -> Unit)? = null
    @Volatile private var placeOut: ((String) -> Unit)? = null

    override fun start(onFrame: (Frame) -> Unit, onPlace: (String) -> Unit, onCabin: (String, Any?) -> Unit, onVehicleId: (String) -> Unit) {
        cabinOut = onCabin
        placeOut = onPlace
        store.attach(this)
        inner.start(
            onFrame = { f -> store.applyTo(f).also { out -> store.observed(f, out); onFrame(out) } },
            onPlace = onPlace,
            onCabin = { sig, v -> if (!store.blocksCabin(sig)) onCabin(sig, v) },
            onVehicleId = onVehicleId,
        )
    }

    override fun stop() {
        store.detach(this)
        cabinOut = null
        placeOut = null
        inner.stop()
    }

    override fun capabilities(): Set<String>? = inner.capabilities()?.let { it + store.impliedCaps() }
    override fun status(): Map<String, String> = inner.status() + store.status()

    override fun cabin(signal: String, value: Any) { cabinOut?.invoke(signal, value) }
    override fun place(name: String) { placeOut?.invoke(name) }
}
