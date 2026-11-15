package com.safelog.core.contract

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * SafeLog data contract — identical to `data_spec/signals.py` on the training PC.
 *
 * Every model, dataset and recorded trip carries [DATA_VERSION]. The gate refuses a model trained on a different
 * version (the app then runs on rules only), so a model trained on wrongly-shaped data can never be used.
 *   v3 (2026-11-06): steering = FRONT-WHEEL angle, left negative; telltales not readable by third-party apps;
 *       turn signal has no hazard state. Speed in km/h (AAOS PERF_VEHICLE_SPEED m/s x 3.6, |v|).
 */
object DataContract {
    const val DATA_VERSION = 3
    const val BASE_HZ = 10.0
    const val G = 9.80665

    /** Channel order = Python CHANNELS order (CSV header order). */
    val FLOAT_CHANNELS = listOf(
        "t", "speed_kph", "steer_deg", "steer_bound_deg", "heading_deg", "odo_km", "outside_temp_c",
        "speed_limit_kph", "tbt_dist_m", "caution_dist_m",
    )
    val INT_CHANNELS = listOf(
        "gear", "brake_on", "cruise_active", "drive_mode", "turn_signal", "wiper_active", "low_beam", "night",
        "seats_occupied", "belts_buckled", "tt_fca", "tt_lka", "tt_lca", "tt_daw", "tt_icy", "tt_abs_esc", "tt_tpms",
        "road_class", "tbt_type", "caution_type", "school_zone", "camera_overspeed", "congestion", "nav_active",
    )
    val ALL_CHANNELS = listOf(
        "t", "speed_kph", "gear", "brake_on", "steer_deg", "steer_bound_deg", "heading_deg", "odo_km", "cruise_active",
        "drive_mode", "turn_signal", "wiper_active", "low_beam", "night", "outside_temp_c", "seats_occupied",
        "belts_buckled", "tt_fca", "tt_lka", "tt_lca", "tt_daw", "tt_icy", "tt_abs_esc", "tt_tpms", "speed_limit_kph",
        "road_class", "tbt_dist_m", "tbt_type", "caution_type", "caution_dist_m", "school_zone", "camera_overspeed",
        "congestion", "nav_active",
    )

    // enums (same numbers as the Python contract)
    const val GEAR_P = 0; const val GEAR_R = 1; const val GEAR_N = 2; const val GEAR_D = 3
    const val SIGNAL_OFF = 0; const val SIGNAL_LEFT = 1; const val SIGNAL_RIGHT = 2
    const val TBT_NONE = 0; const val TBT_LEFT = 1; const val TBT_RIGHT = 2; const val TBT_UTURN = 3; const val TBT_STRAIGHT = 4
    const val CAUTION_SCHOOL = 1; const val CAUTION_SPEED_BUMP = 2; const val CAUTION_RAILROAD = 3
    const val CAUTION_ACCIDENT_PRONE = 5; const val CAUTION_FOG = 6; const val CAUTION_WILDLIFE = 8
    const val CAUTION_DOWNHILL = 9; const val CAUTION_FALLING_ROCK = 10; const val CAUTION_NARROW = 11; const val CAUTION_SLIPPERY = 12
    const val ROAD_URBAN = 0; const val ROAD_RURAL = 1; const val ROAD_EXPRESSWAY = 2
}

/**
 * A recorded trip: equal-length columns on a 10 Hz time base. Missing float = NaN, unknown int = -1.
 * Float columns hold the exact values the Python engine sees (float32 values widened to double).
 */
class Trip(val n: Int, private val f: Map<String, DoubleArray>, private val i: Map<String, IntArray>) {
    fun d(name: String): DoubleArray = f[name] ?: error("no float channel $name")
    fun i(name: String): IntArray = i[name] ?: error("no int channel $name")

    val t get() = d("t")

    fun slice(from: Int, to: Int): Trip =
        Trip(to - from, f.mapValues { it.value.copyOfRange(from, to) }, i.mapValues { it.value.copyOfRange(from, to) })

    companion object {
        fun empty(n: Int, hz: Double = DataContract.BASE_HZ): Trip {
            val f = DataContract.FLOAT_CHANNELS.associateWith { DoubleArray(n) { Double.NaN } }.toMutableMap()
            f["t"] = DoubleArray(n) { it / hz }
            val i = DataContract.INT_CHANNELS.associateWith { IntArray(n) { -1 } }
            return Trip(n, f, i)
        }

        /** Builds a trip from recorded frames (the app's live recorder). */
        fun fromFrames(frames: List<Frame>): Trip {
            val n = frames.size
            val tr = empty(n)
            for ((k, fr) in frames.withIndex()) fr.writeTo(tr, k)
            return tr
        }

        /** Reads the CSV (optionally gzipped) written by `backend/tools/export_trips.py`. */
        fun readCsv(input: InputStream, gz: Boolean = true): Trip {
            val r = BufferedReader(InputStreamReader(if (gz) GZIPInputStream(input) else input, Charsets.UTF_8))
            val header = r.readLine().split(',')
            val rows = ArrayList<List<String>>()
            while (true) {
                val line = r.readLine() ?: break
                if (line.isNotEmpty()) rows.add(line.split(','))
            }
            val n = rows.size
            val tr = empty(n)
            for ((c, name) in header.withIndex()) {
                if (name in DataContract.INT_CHANNELS) {
                    val col = tr.i(name)
                    for (k in 0 until n) col[k] = rows[k][c].let { if (it.isEmpty()) -1 else it.toInt() }
                } else if (name in DataContract.FLOAT_CHANNELS) {
                    val col = tr.d(name)
                    for (k in 0 until n) col[k] = rows[k][c].let { if (it.isEmpty()) Double.NaN else it.toDouble() }
                }
            }
            return tr
        }
    }
}

/**
 * One 10 Hz sample as assembled by the vehicle collector (Pleos Vehicle SDK + AAOS + NaviHelper),
 * or read from a demo trip. ON_CHANGE channels are HELD by the collector (last value), never timed out.
 */
data class Frame(
    val t: Double,
    val speedKph: Double = Double.NaN,
    val gear: Int = -1,
    val brakeOn: Int = -1,
    val steerDeg: Double = Double.NaN,
    val steerBoundDeg: Double = Double.NaN,
    val headingDeg: Double = Double.NaN,
    val odoKm: Double = Double.NaN,
    val cruiseActive: Int = -1,
    val driveMode: Int = -1,
    val turnSignal: Int = 0,
    val wiperActive: Int = -1,
    val lowBeam: Int = -1,
    val night: Int = -1,
    val outsideTempC: Double = Double.NaN,
    val seatsOccupied: Int = -1,
    val beltsBuckled: Int = -1,
    val ttFca: Int = -1, val ttLka: Int = -1, val ttLca: Int = -1, val ttDaw: Int = -1,
    val ttIcy: Int = -1, val ttAbsEsc: Int = -1, val ttTpms: Int = -1,
    val speedLimitKph: Double = Double.NaN,
    val roadClass: Int = 0,
    val tbtDistM: Double = Double.NaN,
    val tbtType: Int = 0,
    val cautionType: Int = 0,
    val cautionDistM: Double = Double.NaN,
    val schoolZone: Int = 0,
    val cameraOverspeed: Int = 0,
    val congestion: Int = 0,
    val navActive: Int = -1,
) {
    fun writeTo(tr: Trip, k: Int) {
        tr.d("t")[k] = t; tr.d("speed_kph")[k] = speedKph; tr.i("gear")[k] = gear; tr.i("brake_on")[k] = brakeOn
        tr.d("steer_deg")[k] = steerDeg; tr.d("steer_bound_deg")[k] = steerBoundDeg; tr.d("heading_deg")[k] = headingDeg
        tr.d("odo_km")[k] = odoKm; tr.i("cruise_active")[k] = cruiseActive; tr.i("drive_mode")[k] = driveMode
        tr.i("turn_signal")[k] = turnSignal; tr.i("wiper_active")[k] = wiperActive; tr.i("low_beam")[k] = lowBeam
        tr.i("night")[k] = night; tr.d("outside_temp_c")[k] = outsideTempC; tr.i("seats_occupied")[k] = seatsOccupied
        tr.i("belts_buckled")[k] = beltsBuckled; tr.i("tt_fca")[k] = ttFca; tr.i("tt_lka")[k] = ttLka; tr.i("tt_lca")[k] = ttLca
        tr.i("tt_daw")[k] = ttDaw; tr.i("tt_icy")[k] = ttIcy; tr.i("tt_abs_esc")[k] = ttAbsEsc; tr.i("tt_tpms")[k] = ttTpms
        tr.d("speed_limit_kph")[k] = speedLimitKph; tr.i("road_class")[k] = roadClass; tr.d("tbt_dist_m")[k] = tbtDistM
        tr.i("tbt_type")[k] = tbtType; tr.i("caution_type")[k] = cautionType; tr.d("caution_dist_m")[k] = cautionDistM
        tr.i("school_zone")[k] = schoolZone; tr.i("camera_overspeed")[k] = cameraOverspeed; tr.i("congestion")[k] = congestion
        tr.i("nav_active")[k] = navActive
    }

    companion object {
        fun fromTrip(tr: Trip, k: Int) = Frame(
            t = tr.d("t")[k], speedKph = tr.d("speed_kph")[k], gear = tr.i("gear")[k], brakeOn = tr.i("brake_on")[k],
            steerDeg = tr.d("steer_deg")[k], steerBoundDeg = tr.d("steer_bound_deg")[k], headingDeg = tr.d("heading_deg")[k],
            odoKm = tr.d("odo_km")[k], cruiseActive = tr.i("cruise_active")[k], driveMode = tr.i("drive_mode")[k],
            turnSignal = tr.i("turn_signal")[k], wiperActive = tr.i("wiper_active")[k], lowBeam = tr.i("low_beam")[k],
            night = tr.i("night")[k], outsideTempC = tr.d("outside_temp_c")[k], seatsOccupied = tr.i("seats_occupied")[k],
            beltsBuckled = tr.i("belts_buckled")[k], ttFca = tr.i("tt_fca")[k], ttLka = tr.i("tt_lka")[k], ttLca = tr.i("tt_lca")[k],
            ttDaw = tr.i("tt_daw")[k], ttIcy = tr.i("tt_icy")[k], ttAbsEsc = tr.i("tt_abs_esc")[k], ttTpms = tr.i("tt_tpms")[k],
            speedLimitKph = tr.d("speed_limit_kph")[k], roadClass = tr.i("road_class")[k], tbtDistM = tr.d("tbt_dist_m")[k],
            tbtType = tr.i("tbt_type")[k], cautionType = tr.i("caution_type")[k], cautionDistM = tr.d("caution_dist_m")[k],
            schoolZone = tr.i("school_zone")[k], cameraOverspeed = tr.i("camera_overspeed")[k], congestion = tr.i("congestion")[k],
            navActive = tr.i("nav_active")[k],
        )
    }
}

/** CSV in the same format as backend/tools/export_trips.py (for "데이터 기여" uploads and debugging). */
fun Trip.toCsv(): String {
    val sb = StringBuilder()
    sb.append(DataContract.ALL_CHANNELS.joinToString(",")).append('\n')
    for (k in 0 until n) {
        DataContract.ALL_CHANNELS.forEachIndexed { c, name ->
            if (c > 0) sb.append(',')
            if (name in DataContract.INT_CHANNELS) sb.append(i(name)[k])
            else { val x = d(name)[k]; if (!x.isNaN()) sb.append(x) }
        }
        sb.append('\n')
    }
    return sb.toString()
}
