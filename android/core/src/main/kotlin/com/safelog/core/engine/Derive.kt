package com.safelog.core.engine

import com.safelog.core.contract.DataContract
import com.safelog.core.contract.Trip
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.tan

/** Port of scoring_engine/derive.py. See that file for the reasoning behind each step. */
class Derived(
    val n: Int,
    val speedNativeHz: Double,
    val vKph: DoubleArray,
    val vMs: DoubleArray,
    val vSmoothKph: DoubleArray,
    val aLongG: DoubleArray,
    val yawNavDps: DoubleArray,
    val steerUnit: String,
    val steerUnitSource: String,
    val steerStepDeg: Double,
    val steerTooCoarse: Boolean,
    val steerSign: Int,
    val steerSignSource: String,
    val ratioScale: Double,
    val ratioScaleSource: String,
    val deltaDeg: DoubleArray,
    val yawSteerDps: DoubleArray,
    val yawQuantumDps: DoubleArray,
    val tbtDistM: DoubleArray,
    val cautionDistM: DoubleArray,
    val aLatG: DoubleArray,
    val aLatNavG: DoubleArray,
    val latQualityOk: BooleanArray,
    val sM: DoubleArray,
) {
    val km get() = if (n > 0) sM[n - 1] / 1000 else 0.0
}

object VehicleParams {
    const val WHEELBASE_M = 2.895      // Hyundai Grandeur (GN7) published wheelbase
    const val STEERING_RATIO = 13.5    // only if a car / the emulator sends the steering-WHEEL angle
    const val UNDERSTEER_K = 0.0025    // s^2/m^2, mid-size sedan assumption
}

object Derive {
    private const val HZ = DataContract.BASE_HZ
    private const val G = DataContract.G

    fun nativeRate(x: DoubleArray, hz: Double): Double {
        var cnt = 0
        var first = -1
        var last = -1
        for (i in x.indices) if (Np.isFin(x[i])) { cnt++; if (first < 0) first = i; last = i }
        if (cnt < 2) return 0.0
        val span = (last - first) / hz
        return if (span > 0) (cnt - 1) / span else 0.0
    }

    /** Distance-to-point refreshed now and then: between refreshes subtract the distance driven. */
    fun deadReckon(d: DoubleArray, sM: DoubleArray): DoubleArray {
        val out = d.copyOf()
        val n = d.size
        val fin = BooleanArray(n) { Np.isFin(d[it]) }
        if (fin.count { it } < 2) return out
        val upd = fin.copyOf()
        for (i in 1 until n) upd[i] = upd[i] && !(fin[i - 1] && d[i] == d[i - 1])
        var last = -1
        for (i in 0 until n) {
            if (upd[i]) last = i
            if (fin[i] && last >= 0) out[i] = d[last] - (sM[i] - sM[last])
        }
        return out
    }

    private fun yaw(v: DoubleArray, steerDeg: DoubleArray, ratio: Double, absSteer: Boolean = false): DoubleArray =
        DoubleArray(v.size) {
            val s = if (absSteer) abs(steerDeg[it]) else steerDeg[it]
            val vv = v[it]
            Math.toDegrees(vv * tan(Math.toRadians(s) / ratio) /
                (VehicleParams.WHEELBASE_M * (1 + VehicleParams.UNDERSTEER_K * vv * vv)))
        }

    data class Cal(val value: Double, val source: String)

    fun calibrateRatio(yawNav: DoubleArray, yawSteer: DoubleArray, v: DoubleArray, hz: Double = HZ, clip: Boolean = true): Cal {
        val w = (8 * hz).toInt()
        val n = yawNav.size
        val ok = BooleanArray(n) { Np.isFin(yawNav[it]) && Np.isFin(yawSteer[it]) && v[it] > 3 }
        if (ok.count { it } < 3 * w) return Cal(1.0, "none")
        val cn = DoubleArray(n + 1); val cs = DoubleArray(n + 1); val co = DoubleArray(n + 1)
        var a = 0.0; var b = 0.0; var c = 0.0
        for (i in 0 until n) {
            a += if (ok[i]) yawNav[i] else 0.0
            b += if (ok[i]) yawSteer[i] else 0.0
            c += if (ok[i]) 1.0 else 0.0
            cn[i + 1] = a / hz; cs[i + 1] = b / hz; co[i + 1] = c / hz
        }
        var num = 0.0; var den = 0.0
        var i = 0; var turns = 0
        while (i + w <= n) {
            if (co[i + w] - co[i] >= w / hz - 0.05) {
                val dn = cn[i + w] - cn[i]
                val ds = cs[i + w] - cs[i]
                if (abs(dn) > 35 && abs(ds) > 20 && Np.sign(dn) == Np.sign(ds)) {
                    num += abs(dn); den += abs(ds); turns += 1
                    i += w
                    continue
                }
            }
            i += hz.toInt()
        }
        if (turns < 3) return Cal(1.0, "none")
        return Cal(if (clip) (num / den).coerceIn(0.75, 1.3) else num / den, "heading")
    }

    fun calibrateSteerSign(trip: Trip, yawNav: DoubleArray, yawRaw: DoubleArray, v: DoubleArray, hz: Double): Pair<Int, String> {
        val n = v.size
        var cnt = 0; var agree = 0
        for (i in 0 until n) {
            val yn = yawNav[i]; val yr = yawRaw[i]
            if (Np.isFin(yn) && Np.isFin(yr) && v[i] > 3 && abs(yn) > 4 && abs(yr) > 2) {
                cnt++
                if (Np.sign(yn) == Np.sign(yr)) agree++
            }
        }
        if (cnt >= 40) {
            val ag = agree.toDouble() / cnt
            if (ag >= 0.85) return 1 to "heading"
            if (ag <= 0.15) return -1 to "heading"
        }
        val st = trip.d("steer_deg")
        val td = trip.d("tbt_dist_m")
        val tt = trip.i("tbt_type")
        val votes = ArrayList<Int>()
        for (i in 1 until n) {
            if (!(td[i - 1] > 0 && td[i] <= 0)) continue
            if (tt[i - 1] != 1 && tt[i - 1] != 2) continue
            if (!Np.isFin(v[i]) || v[i] > 11) continue
            val from = maxOf(0, i - (2 * hz).toInt())
            val to = minOf(n, i + (4 * hz).toInt())
            val w = (from until to).map { st[it] }.filter { Np.isFin(it) }
            if (w.size < 10 || w.maxOf { abs(it) } < 60) continue
            var best = 0
            for (k in w.indices) if (abs(w[k]) > abs(w[best])) best = k
            val sTurn = Np.sign(w[best]).toInt()
            votes.add(if (tt[i - 1] == 1) sTurn else -sTurn)
        }
        if (votes.size >= 2 && abs(votes.sum()) == votes.size) return votes[0] to "navigation_turns"
        return 0 to "unknown"
    }

    fun derive(trip: Trip, cfg: EngineConfig): Derived {
        val hz = HZ
        val n = trip.n
        val maxAge = cfg.num("general", "max_signal_age_s")
        val rawV = trip.d("speed_kph")
        val speedHz = nativeRate(rawV, hz)
        val vKph = Np.fillGaps(rawV, hz, if (speedHz < 2) maxOf(maxAge, 2.2 / maxOf(speedHz, 1e-3)) else maxAge)
        val v = DoubleArray(n) { vKph[it] / 3.6 }
        val vs = Np.movingAverageSame(Np.nanToNum(vKph, -1.0), hz.toInt())
        for (i in 0 until n) if (!Np.isFin(vKph[i])) vs[i] = Double.NaN
        val a = Np.centredDiff(v, hz, 1.0)
        val aLongG = DoubleArray(n) { a[it] / G }

        // heading: compass bearing (clockwise) -> yaw > 0 = LEFT
        val hd = trip.d("heading_deg")
        val yawNav: DoubleArray
        if (hd.count { Np.isFin(it) } > 10) {
            val hdu = hd.copyOf()
            val idx = (0 until n).filter { Np.isFin(hd[it]) }
            val un = Np.unwrapDeg(DoubleArray(idx.size) { hd[idx[it]] })
            for ((k, i) in idx.withIndex()) hdu[i] = un[k]
            val filled = Np.fillGaps(hdu, hz, maxOf(maxAge, 2.2))
            val cd = Np.centredDiff(filled, hz, 2.0)
            yawNav = DoubleArray(n) { -cd[it] }
        } else {
            yawNav = DoubleArray(n) { Double.NaN }
        }

        // steering: front-wheel angle (official), unit auto-detected
        val steerRaw = Np.fillGaps(trip.d("steer_deg"), hz, maxAge)
        val veh = cfg.vehicle
        var unit = veh.steerUnit
        var unitSrc = "config"
        val boundArr = trip.d("steer_bound_deg")
        val bound = Np.nanMax(boundArr, 0, n)
        if (unit == "auto" && Np.isFin(bound)) {
            unit = if (bound > 90) "steering_wheel" else "road_wheel"; unitSrc = "capability"
        }
        if (unit == "auto") {
            val big = if (steerRaw.any { Np.isFin(it) }) Np.nanPercentile(DoubleArray(n) { abs(steerRaw[it]) }, 99.5) else 0.0
            if (big > 45) {
                unit = "steering_wheel"; unitSrc = "range"
            } else {
                unit = "road_wheel"; unitSrc = "official_doc"
                if (yawNav.count { Np.isFin(it) } > 100) {
                    val fits = LinkedHashMap<String, Double>()
                    val absNav = DoubleArray(n) { abs(yawNav[it]) }
                    for ((u, r) in listOf("road_wheel" to 1.0, "steering_wheel" to VehicleParams.STEERING_RATIO)) {
                        val y = yaw(v, steerRaw, r, absSteer = true)
                        val c = calibrateRatio(absNav, y, v, hz, clip = false)
                        if (c.source == "heading") fits[u] = abs(ln(c.value))
                    }
                    if (fits.isNotEmpty()) {
                        unit = fits.minByOrNull { it.value }!!.key; unitSrc = "heading"
                    }
                }
                if (unitSrc == "official_doc" && veh.steerUnitLearned != null) {
                    unit = veh.steerUnitLearned!!; unitSrc = "memory"
                }
            }
        }
        val ratio = if (unit == "steering_wheel") VehicleParams.STEERING_RATIO else 1.0
        val vals = steerRaw.filter { Np.isFin(it) }.map { Math.rint(abs(it) * 1000.0) / 1000.0 }.distinct().sorted()
        var step = Double.NaN
        if (vals.size > 2) {
            step = Double.POSITIVE_INFINITY
            for (k in 1 until vals.size) step = minOf(step, vals[k] - vals[k - 1])
        }
        val tooCoarse = unit == "road_wheel" && Np.isFin(step) && step >= 0.45
        val yawRaw = yaw(v, steerRaw, ratio)
        var (sign, signSrc) = calibrateSteerSign(trip, yawNav, yawRaw, v, hz)
        if (sign == 0 && veh.steerSign != 0) {
            sign = veh.steerSign; signSrc = if (veh.steerSignLearned) "memory" else "official_doc"
        }
        val s1 = if (sign == 0) 1 else sign
        var cal = calibrateRatio(yawNav, DoubleArray(n) { yawRaw[it] * s1 }, v, hz)
        if (cal.source == "none" && veh.ratioScale != null && veh.ratioScale != 0.0) cal = Cal(veh.ratioScale!!, "memory")
        val scale = cal.value
        val steer = DoubleArray(n) { steerRaw[it] * s1 }
        val deltaDeg = DoubleArray(n) { steer[it] / ratio }
        val yawSteer = DoubleArray(n) {
            val vv = v[it]
            vv * tan(Math.toRadians(steer[it]) / ratio) / (VehicleParams.WHEELBASE_M * (1 + VehicleParams.UNDERSTEER_K * vv * vv)) * scale
        }
        val yawSteerDps = DoubleArray(n) { Math.toDegrees(yawSteer[it]) }
        val stepRw = if (Np.isFin(step)) step / ratio else 0.0
        val vn = Np.nanToNum(v)
        val stepRad = Math.toRadians(stepRw)
        val yawQuantum = DoubleArray(n) { Math.toDegrees(vn[it] * stepRad / VehicleParams.WHEELBASE_M) * scale }

        // navigation distances: dead-reckon between ~1 Hz updates (standstill deadband)
        val sM = Np.cumsum(DoubleArray(n) { vn[it] / hz })
        val vkn = Np.nanToNum(vKph)
        val sDb = Np.cumsum(DoubleArray(n) { (if (vkn[it] > 1.5) vn[it] else 0.0) / hz })
        val tbt = deadReckon(trip.d("tbt_dist_m"), sDb)
        val caution = deadReckon(trip.d("caution_dist_m"), sDb)

        // lateral acceleration
        val aLat = DoubleArray(n) { v[it] * yawSteer[it] }
        val aLatS = Np.movingAverageSame(Np.nanToNum(aLat), hz.toInt())
        for (i in 0 until n) if (!Np.isFin(aLat[i])) aLatS[i] = Double.NaN
        val aLatG = DoubleArray(n) { aLatS[it] / G }
        val aLatNavG = DoubleArray(n) { v[it] * Math.toRadians(yawNav[it]) / G }
        val latOk = BooleanArray(n) {
            val d = abs(aLatG[it] - aLatNavG[it])
            !(Np.isFin(d) && d > 0.12)
        }
        return Derived(
            n = n, speedNativeHz = speedHz, vKph = vKph, vMs = v, vSmoothKph = vs, aLongG = aLongG, yawNavDps = yawNav,
            steerUnit = unit, steerUnitSource = unitSrc, steerStepDeg = step, steerTooCoarse = tooCoarse,
            steerSign = sign, steerSignSource = signSrc, ratioScale = scale, ratioScaleSource = cal.source,
            deltaDeg = deltaDeg, yawSteerDps = yawSteerDps, yawQuantumDps = yawQuantum, tbtDistM = tbt, cautionDistM = caution,
            aLatG = aLatG, aLatNavG = aLatNavG, latQualityOk = latOk, sM = sM,
        )
    }
}
