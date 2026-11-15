package com.safelog.core.engine

import com.safelog.core.contract.DataContract
import com.safelog.core.contract.Trip
import kotlin.math.abs
import kotlin.math.sin

/**
 * Port of scoring_engine/habits.py: detectors (opportunities) and judges (SUCCESS / MISS / ABSTAIN).
 * MISS is internal only; the driver only ever sees successes. ABSTAIN removes the opportunity entirely.
 */
object Outcome { const val SUCCESS = 1; const val MISS = 0; const val ABSTAIN = -1 }

class Candidate(val habit: String, val iRef: Int, val i0: Int, val i1: Int, val meta: MutableMap<String, Any?> = linkedMapOf())

class Judgment(
    val habit: String,
    val iRef: Int,
    val tRef: Double,
    var outcome: Int,
    var level: String = "",
    val value: Double = Double.NaN,
    val threshold: Double = Double.NaN,
    val margin: Double = 0.0,
    var reason: String = "",
    val i0: Int = 0,
    val i1: Int = 0,
    val context: Map<String, Int> = emptyMap(),
    val meta: MutableMap<String, Any?> = linkedMapOf(),
) {
    val held get() = meta["held"] == true
    override fun toString() = "$habit@$iRef o=$outcome v=$value r=$reason"
}

internal class HabitCtx(val trip: Trip, val d: Derived, val cfg: EngineConfig) {
    val n = trip.n
    val t = trip.d("t")
    val cruise = trip.i("cruise_active")
    val brake = trip.i("brake_on")
    val sig = trip.i("turn_signal")
    val fca = trip.i("tt_fca")
    val road = trip.i("road_class")
    val truth get() = cfg.truth

    fun g(key: String) = cfg.num("general", key)
    fun c(sec: String, key: String) = cfg.num(sec, key)

    fun ctx(iIn: Int): Map<String, Int> {
        val i = iIn.coerceIn(0, n - 1)
        val night = trip.i("night")[i]
        return linkedMapOf(
            "rain" to if (trip.i("wiper_active")[i] == 1) 1 else 0,
            "night" to if (night == 1 || (trip.i("low_beam")[i] == 1 && night == -1)) 1 else 0,
            "congestion" to trip.i("congestion")[i],
            "road_class" to road[i],
        )
    }

    fun mk(habit: String, cand: Candidate, outcome: Int, value: Double = Double.NaN, thr: Double = Double.NaN,
           margin: Double = 0.0, reason: String = "", level: String = "") = Judgment(
        habit = habit, iRef = cand.iRef, tRef = t[minOf(cand.iRef, n - 1)], outcome = outcome, level = level,
        value = value, threshold = thr, margin = margin.coerceIn(0.0, 1.0).let { if (margin.isNaN()) Double.NaN else it },
        reason = reason, i0 = cand.i0, i1 = cand.i1, context = ctx(cand.iRef), meta = LinkedHashMap(cand.meta),
    )

    fun allFinite(a: DoubleArray, i0: Int, i1: Int): Boolean {
        for (i in maxOf(0, i0) until minOf(a.size, i1)) if (!Np.isFin(a[i])) return false
        return true
    }

    fun anyEq(a: IntArray, i0: Int, i1: Int, v: Int): Boolean {
        for (i in maxOf(0, i0) until minOf(a.size, i1)) if (a[i] == v) return true
        return false
    }

    fun anyLt0(a: IntArray, i0: Int, i1: Int): Boolean {
        for (i in maxOf(0, i0) until minOf(a.size, i1)) if (a[i] < 0) return true
        return false
    }

    fun fracEq(a: IntArray, i0: Int, i1: Int, v: Int): Double {
        val lo = maxOf(0, i0); val hi = minOf(a.size, i1)
        if (hi <= lo) return Double.NaN
        var k = 0
        for (i in lo until hi) if (a[i] == v) k++
        return k.toDouble() / (hi - lo)
    }

    /** Return a reason string if the opportunity must be abstained, else "". */
    fun abstainChecks(i0In: Int, i1In: Int, need: Set<String> = setOf("v"), forcedLookback: Boolean = true, adas: Boolean = true): String {
        val i0 = maxOf(0, i0In); val i1 = minOf(n, i1In)
        if ("v" in need && !allFinite(d.vKph, i0, i1)) return "speed_missing"
        if ("a" in need && !allFinite(d.aLongG, i0, i1)) return "accel_missing"
        if ("steer" in need && !allFinite(trip.d("steer_deg"), i0, i1)) return "steering_missing"
        if ("steer" in need && d.steerTooCoarse && !truth) return "steering_resolution_too_coarse"
        if ("steer" in need && d.steerUnitSource == "official_doc" && !truth) return "steering_unit_unverified"
        if ("brake" in need) { for (i in i0 until i1) if (brake[i] < 0) return "brake_missing" }
        if ("lat" in need) { for (i in i0 until i1) if (!d.latQualityOk[i]) return "lateral_estimates_disagree" }
        if (adas && cfg.bool("general", "adas_exclusion") && anyEq(cruise, i0, i1, 1)) return "adas_engaged"
        if (adas && cfg.bool("general", "adas_exclusion") && "adas_unknown_ok" !in need && anyLt0(cruise, i0, i1)) return "adas_state_unknown"
        if (forcedLookback) {
            val lb = (g("external_forcing_lookback_s") * DataContract.BASE_HZ).toInt()
            if (anyEq(fca, maxOf(0, i0 - lb), i1, 1)) return "external_forcing_fca"
        }
        return ""
    }

    fun kinGuard(): Double = if (truth) 0.0 else if (d.speedNativeHz < 2) g("guard_kin_g_lowrate") else g("guard_kin_g")
    fun speedGuard(lim: Double): Double = g("guard_speed_kph") + g("guard_speed_frac") * lim
    fun latGuard(thr: Double): Double {
        if (truth) return 0.0
        val unc = if (d.ratioScaleSource == "heading" || d.ratioScaleSource == "memory") 0.0 else g("steering_ratio_uncertainty")
        return kinGuard() + unc * thr
    }

    fun kinJudge(habit: String, cand: Candidate, value: Double, okThr: Double, exThr: Double, guard: Double): Judgment {
        if (!Np.isFin(value)) return mk(habit, cand, Outcome.ABSTAIN, reason = "kinematics_missing")
        if (okThr - guard <= value && value < okThr) return mk(habit, cand, Outcome.ABSTAIN, value, okThr, reason = "inside_guard_band")
        if (value < okThr) {
            val lvl = if (value < exThr) "excellent" else "success"
            return mk(habit, cand, Outcome.SUCCESS, value, okThr, (okThr - value) / okThr, level = lvl)
        }
        return mk(habit, cand, Outcome.MISS, value, okThr, 0.0, reason = "above_threshold")
    }
}

object Habits {
    private const val HZ = DataContract.BASE_HZ

    // ------------------------------------------------------------------------------------------ stops / launches
    internal fun detectStops(h: HabitCtx): List<Candidate> {
        val vv = Np.nanToNum(h.d.vKph, -1.0)
        val sm = Np.nanToNum(h.d.vSmoothKph, -1.0)
        val stopKph = h.c("smooth_stop", "stop_speed_kph")
        val below = BooleanArray(h.n) { vv[it] >= 0 && vv[it] < stopKph }
        val out = ArrayList<Candidate>()
        for (r in Np.runs(below)) {
            val s = r[0]
            if (s == 0 || vv[s - 1] < 0) continue
            var i = s - 1
            val lim = maxOf(0, s - (60 * HZ).toInt())
            while (i > lim && sm[i - 1] >= sm[i] - 0.5 && sm[i - 1] >= 0) i--
            val i0 = maxOf(0, i - (2 * HZ).toInt())
            val need = h.c("smooth_stop", "min_entry_speed_kph") + (if (h.truth) 0.0 else 2.0)
            val entry = maxOf(vv[i], sm[i])
            if (entry >= need) out.add(Candidate("smooth_stop", s, i0, s + 1, linkedMapOf("entry_kph" to entry)))
        }
        return out
    }

    internal fun judgeStop(h: HabitCtx, c: Candidate): Judgment {
        val unknownScc = h.anyLt0(h.cruise, c.i0, c.i1)
        val r = h.abstainChecks(c.i0, c.i1, if (unknownScc) setOf("v", "a", "adas_unknown_ok") else setOf("v", "a"))
        if (r.isNotEmpty()) return h.mk("smooth_stop", c, Outcome.ABSTAIN, reason = r)
        if (unknownScc && !h.anyEq(h.brake, maxOf(c.i0, c.i1 - (4 * HZ).toInt()), c.i1, 1))
            return h.mk("smooth_stop", c, Outcome.ABSTAIN, reason = "adas_state_unknown_no_pedal")
        if (c.meta["forced"] == true) return h.mk("smooth_stop", c, Outcome.ABSTAIN, reason = "external_forcing")
        val peak = -Np.nanMin(h.d.aLongG, c.i0, c.i1)
        return h.kinJudge("smooth_stop", c, peak, h.c("smooth_stop", "success_max_decel_g"), h.c("smooth_stop", "excellent_max_decel_g"), h.kinGuard())
    }

    internal fun detectLaunches(h: HabitCtx): List<Candidate> {
        val vv = Np.nanToNum(h.d.vKph, -1.0)
        val w = (h.c("smooth_launch", "window_s") * HZ).toInt()
        val startMax = h.c("smooth_launch", "start_max_speed_kph")
        val endKph = h.c("smooth_launch", "end_speed_kph") + (if (h.truth) 0.0 else 2.0)
        val out = ArrayList<Candidate>()
        for (r in Np.runs(BooleanArray(h.n) { vv[it] >= 0 && vv[it] < startMax })) {
            val i0 = r[1] - 1
            val to = minOf(h.n, i0 + w)
            var hit = -1
            for (k in i0 until to) if (vv[k] >= endKph) { hit = k; break }
            if (hit >= 0 && (i0 until hit).all { vv[it] >= 0 }) out.add(Candidate("smooth_launch", i0, i0, i0 + w))
        }
        return out
    }

    internal fun judgeLaunch(h: HabitCtx, c: Candidate): Judgment {
        val r = h.abstainChecks(c.i0, c.i1, setOf("v", "a"))
        if (r.isNotEmpty()) return h.mk("smooth_launch", c, Outcome.ABSTAIN, reason = r)
        if (c.meta["forced"] == true) return h.mk("smooth_launch", c, Outcome.ABSTAIN, reason = "external_forcing")
        val peak = Np.nanMax(h.d.aLongG, c.i0, c.i1)
        return h.kinJudge("smooth_launch", c, peak, h.c("smooth_launch", "success_max_accel_g"), h.c("smooth_launch", "excellent_max_accel_g"), h.kinGuard())
    }

    internal fun judgeAnticipation(h: HabitCtx, c: Candidate): Judgment {
        val hab = "anticipatory_braking"
        val r = h.abstainChecks(c.i0, c.i1, setOf("v", "a", "brake"))
        if (r.isNotEmpty()) return h.mk(hab, c, Outcome.ABSTAIN, reason = r)
        if (c.meta["forced"] == true) return h.mk(hab, c, Outcome.ABSTAIN, reason = "external_forcing")
        val lo = maxOf(0, c.i0); val hi = minOf(h.n, c.i1)
        var fb = -1
        for (k in lo until hi) if (h.brake[k] == 1) { fb = k - lo; break }
        if (fb < 0) return h.mk(hab, c, Outcome.ABSTAIN, reason = "no_brake_application")
        var coast = 0
        var j = fb - 1
        while (j >= 0 && h.brake[lo + j] == 0 && h.d.aLongG[lo + j] < -0.02) { coast++; j-- }
        val coastS = coast / HZ
        val peak = -Np.nanMin(h.d.aLongG, lo, hi)
        val minCoast = h.c(hab, "min_coast_before_brake_s")
        val truthKin = c.meta["truth_kin"] == true
        val guardS = if (truthKin) 0.0 else 0.5
        if (coastS >= minCoast + guardS && peak <= h.c(hab, "max_peak_decel_g") - h.kinGuard() * (if (truthKin) 0 else 1))
            return h.mk(hab, c, Outcome.SUCCESS, coastS, minCoast, minOf(1.0, coastS / (2 * minCoast)), level = "success")
        return h.mk(hab, c, Outcome.MISS, coastS, minCoast, reason = "late_or_hard")
    }

    // ------------------------------------------------------------------------------------------ turns
    internal fun detectTurns(h: HabitCtx): List<Candidate> {
        val out = ArrayList<Candidate>()
        val td = h.d.tbtDistM
        val tt = h.trip.i("tbt_type")
        val n = h.n
        val ok = BooleanArray(n) { Np.isFin(td[it]) && (tt[it] == 1 || tt[it] == 2 || tt[it] == 3) && td[it] < 2000 }
        for (r in Np.runs(ok)) {
            val s0 = r[0]; val e0 = r[1]
            val bounds = ArrayList<Int>()
            bounds.add(s0)
            for (k in s0 until e0 - 1) if (td[k + 1] - td[k] > 20) bounds.add(k + 1)
            bounds.add(e0)
            for (b in 0 until bounds.size - 1) {
                val a = bounds[b]; val e = bounds[b + 1]
                var segMin = Double.POSITIVE_INFINITY
                for (k in a until e) segMin = minOf(segMin, td[k])
                if (segMin > 5) continue
                val closeThr = maxOf(segMin, 0.0) + 3.0
                val close = (a until e).filter { td[it] <= closeThr }.map { it - a }
                val vv = DoubleArray(e - a) { val x = h.d.vKph[a + it]; if (x.isNaN()) 0.0 else x }
                val stopped = close.filter { vv[it] < 3 }
                val k: Int = if (stopped.isNotEmpty()) {
                    val last = stopped.last()
                    var after = -1
                    for (q in last until vv.size) if (vv[q] >= 3) { after = q - last; break }
                    last + (if (after >= 0) after else 0)
                } else {
                    var hit = -1
                    for (q in a until e) if (td[q] <= 0.0) { hit = q - a; break }
                    if (hit >= 0) hit else { var am = 0; for (q in a until e) if (td[q] < td[a + am]) am = q - a; am }
                }
                val iRef = a + minOf(k, e - a - 1)
                out.add(Candidate("turn", iRef, a, minOf(n, iRef + (8 * HZ).toInt()), linkedMapOf("dir" to tt[iRef], "source" to "tbt")))
            }
        }
        // heading-based turns without TBT (judge abstains)
        val yaw = Np.nanToNum(h.d.yawSteerDps)
        val win = (10 * HZ).toInt()
        val head = Np.cumsum(yaw).let { cs -> DoubleArray(n) { cs[it] / HZ } }
        val tbtIdx = out.map { it.iRef }
        val vn = Np.nanToNum(h.d.vKph)
        var i = win
        while (i < n) {
            val dh = head[i] - head[i - win]
            if (abs(dh) > 60 && Np.mean(vn.copyOfRange(i - win, i)) < 40) {
                if (tbtIdx.all { abs(i - it) > 15 * HZ }) {
                    var pk = i - win
                    for (q in i - win until i) if (abs(yaw[q]) > abs(yaw[pk])) pk = q
                    out.add(Candidate("turn", pk, i - win, i, linkedMapOf("dir" to (if (dh > 0) 1 else 2), "source" to "heading")))
                }
                i += win
            }
            i += 5
        }
        return out
    }

    internal fun judgeTurnSignal(h: HabitCtx, c: Candidate): Judgment {
        val hab = "turn_signal_turn"
        if (c.meta["source"] != "tbt" && c.meta["truth"] != true) return h.mk(hab, c, Outcome.ABSTAIN, reason = "no_navigation_reference")
        val dir = c.meta["dir"] as Int
        val want = if (dir == 1) 1 else if (dir == 2) 2 else 1
        val td = h.d.tbtDistM
        val expressway = h.road[maxOf(0, c.i0)] == 2
        val need = if (expressway) h.c(hab, "expressway_min_lead_m") else h.c(hab, "urban_min_lead_m")
        var j = c.iRef
        if (h.sig[j] != want) {
            val from = maxOf(0, j - (2 * HZ).toInt())
            var back = -1
            for (q in from..j) if (h.sig[q] == want) back = q
            if (back < 0) return h.mk(hab, c, Outcome.MISS, 0.0, need, reason = "no_signal")
            j = back
        }
        while (j > 0 && h.sig[j - 1] == want) j--
        val lead = if (Np.isFin(td[j])) td[j] else Double.NaN
        if (!Np.isFin(lead)) return h.mk(hab, c, Outcome.ABSTAIN, reason = "distance_missing")
        val guard = if (c.meta["truth"] == true) 0.0 else h.g("guard_lead_m")
        if (need <= lead && lead < need + guard) return h.mk(hab, c, Outcome.ABSTAIN, lead, need, reason = "inside_guard_band")
        if (lead >= need) return h.mk(hab, c, Outcome.SUCCESS, lead, need, minOf(1.0, (lead - need) / need + 0.2),
            level = if (lead >= 1.5 * need) "excellent" else "success")
        return h.mk(hab, c, Outcome.MISS, lead, need, reason = "late_signal")
    }

    internal fun judgeSmoothTurn(h: HabitCtx, c: Candidate): Judgment {
        val i0 = c.iRef - (6 * HZ).toInt(); val i1 = c.iRef + (8 * HZ).toInt()
        val r = h.abstainChecks(i0, i1, setOf("v", "steer", "adas_unknown_ok"))
        if (r.isNotEmpty()) return h.mk("smooth_turn", c, Outcome.ABSTAIN, reason = r)
        val peak = Np.nanMaxAbs(h.d.aLatG, maxOf(0, i0), i1)
        val thr = h.c("smooth_turn", "success_max_lat_g")
        return h.kinJudge("smooth_turn", c, peak, thr, h.c("smooth_turn", "excellent_max_lat_g"), h.latGuard(thr))
    }

    internal fun judgeRightTurnPause(h: HabitCtx, c: Candidate): Judgment? {
        val hab = "right_turn_pause"
        if (c.meta["dir"] != 2 || h.road[c.iRef] == 2) return null
        if (c.meta["source"] != "tbt" && c.meta["truth"] != true) return h.mk(hab, c, Outcome.ABSTAIN, reason = "no_navigation_reference")
        val td = h.d.tbtDistM
        val w = h.cfg.nums(hab, "window_m")
        val near = (c.i0..c.iRef).filter { td[it] <= w[0] && td[it] >= w[1] }
        if (near.isEmpty() || !near.all { Np.isFin(h.d.vKph[it]) }) return h.mk(hab, c, Outcome.ABSTAIN, reason = "speed_missing")
        val vmin = near.minOf { h.d.vKph[it] }
        val mx = h.c(hab, "max_speed_kph")
        if (vmin <= mx) return h.mk(hab, c, Outcome.SUCCESS, vmin, mx, 1 - vmin / mx, level = "success")
        return h.mk(hab, c, Outcome.ABSTAIN, vmin, mx, reason = "success_only_habit")
    }

    // ------------------------------------------------------------------------------------------ lane changes
    internal fun detectLaneChanges(h: HabitCtx): List<Candidate> {
        val c = "lane_change_signal"
        val v = Np.nanToNum(h.d.vMs)
        var yaw = Np.nanToNum(h.d.yawSteerDps)
        val n = yaw.size
        val yq = h.d.yawQuantumDps
        var yqMax = 0.0
        for (x in yq) if (!x.isNaN() && x > yqMax) yqMax = x
        if (yqMax > 0.2) yaw = Np.movingAverageSame(yaw, HZ.toInt())
        val base = Np.medianFilterNearest(yaw, (15 * HZ).toInt() or 1)
        val dev = DoubleArray(n) { yaw[it] - base[it] }
        val above = BooleanArray(n) { abs(dev[it]) > maxOf(0.5, 1.5 * yq[it]) }
        val lobes = Np.runs(above, (0.5 * HZ).toInt()).map { r -> Triple(r[0], r[1], Np.pairwiseSum(dev, r[0], r[1]) / HZ) }
        val td = h.d.tbtDistM
        val tt = h.trip.i("tbt_type")
        val maxDur = h.c(c, "max_duration_s")
        val minOff = h.c(c, "min_lateral_offset_m")
        val minV = h.c(c, "min_speed_kph")
        val noTbt = h.c(c, "no_tbt_within_m")
        val out = ArrayList<Candidate>()
        var k = 0
        while (k < lobes.size - 1) {
            val (s1, e1, a1) = lobes[k]
            val (s2, e2, a2) = lobes[k + 1]
            val ok = Np.sign(a1) != Np.sign(a2) && (s2 - e1) / HZ <= 2.0 && abs(a1 / a2) in 0.25..4.0 &&
                abs(a1) <= 15 && (e2 - s1) / HZ <= maxDur
            if (!ok) { k++; continue }
            var j = s1
            while (j > 0 && s1 - j < (1.5 * HZ).toInt() && Np.sign(dev[j - 1]) == Np.sign(a1) && abs(dev[j - 1]) > 0.1) j--
            val end = minOf(n, e2 + (0.5 * HZ).toInt())
            var cum = 0.0
            val terms = DoubleArray(end - j)
            for (q in j until end) {
                cum += dev[q]
                val psi = Math.toRadians(cum / HZ)
                terms[q - j] = v[q] * sin(psi)
            }
            val y = Np.pairwiseSum(terms) / HZ
            val vm = Np.mean(v.copyOfRange(j, end)) * 3.6
            if (abs(y) < minOff || abs(y) > 5.5 || vm < minV) { k++; continue }
            val meta = linkedMapOf<String, Any?>("dir" to (if (a1 > 0) 1 else 2), "lat_offset_m" to abs(y), "duration_s" to (end - j) / HZ)
            val from = maxOf(0, j - 50)
            var nearTurn = false
            for (q in from until end) if (Np.isFin(td[q]) && td[q] < noTbt && (tt[q] == 1 || tt[q] == 2 || tt[q] == 3)) { nearTurn = true; break }
            meta["near_turn"] = nearTurn
            var maxBase = 0.0
            for (q in j until end) maxBase = maxOf(maxBase, abs(base[q]))
            meta["curving"] = maxBase > 1.5
            out.add(Candidate("lane_change", j, j, end, meta))
            k += 2
        }
        return out
    }

    internal fun judgeLaneChangeSignal(h: HabitCtx, c: Candidate): Judgment {
        val hab = "lane_change_signal"
        if (c.meta["near_turn"] == true || c.meta["curving"] == true) return h.mk(hab, c, Outcome.ABSTAIN, reason = "turn_or_curve_ambiguity")
        val r = h.abstainChecks(c.i0, c.i1, setOf("v", "steer"), adas = false)
        if (r.isNotEmpty()) return h.mk(hab, c, Outcome.ABSTAIN, reason = r)
        if (h.d.steerSign == 0 && c.meta["truth"] != true) return h.mk(hab, c, Outcome.ABSTAIN, reason = "steer_sign_unknown")
        val want = c.meta["dir"] as Int
        val from = maxOf(0, c.i0 - (15 * HZ).toInt())
        var first = -1
        for (q in from until minOf(h.n, c.i1)) if (h.sig[q] == want) { first = q; break }
        val minLead = h.c(hab, "min_lead_s")
        if (first < 0) return h.mk(hab, c, Outcome.MISS, Double.NaN, minLead, reason = "no_signal")
        val lead = (c.i0 - first) / HZ
        val guard = if (c.meta["truth"] == true) 0.0 else h.g("guard_lead_s")
        if (minLead <= lead && lead < minLead + guard) return h.mk(hab, c, Outcome.ABSTAIN, lead, minLead, reason = "inside_guard_band")
        if (lead >= minLead) return h.mk(hab, c, Outcome.SUCCESS, lead, minLead, minOf(1.0, lead / (2 * minLead)),
            level = if (lead >= 5) "excellent" else "success")
        return h.mk(hab, c, Outcome.MISS, lead, minLead, reason = "late_signal")
    }

    internal fun judgeSmoothLaneChange(h: HabitCtx, c: Candidate): Judgment {
        val hab = "smooth_lane_change"
        if (c.meta["near_turn"] == true || c.meta["curving"] == true) return h.mk(hab, c, Outcome.ABSTAIN, reason = "turn_or_curve_ambiguity")
        val r = h.abstainChecks(c.i0, c.i1, setOf("v", "steer"))
        if (r.isNotEmpty()) return h.mk(hab, c, Outcome.ABSTAIN, reason = r)
        val peak = Np.nanMaxAbs(h.d.aLatG, c.i0, c.i1)
        val mx = h.c(hab, "max_lat_accel_g")
        val guard = if (c.meta["truth"] == true) 0.0 else h.latGuard(mx)
        if (mx - guard <= peak && peak < mx) return h.mk(hab, c, Outcome.ABSTAIN, peak, mx, reason = "inside_guard_band")
        if (peak < mx) return h.mk(hab, c, Outcome.SUCCESS, peak, mx, (mx - peak) / mx, level = "success")
        return h.mk(hab, c, Outcome.MISS, peak, mx, reason = "abrupt")
    }

    internal fun judgeBlinkerCancel(h: HabitCtx, c: Candidate, endIdx: Int): Judgment? {
        val lim = (h.c("blinker_cancel", "max_linger_s") * HZ).toInt()
        val segEnd = minOf(h.n, endIdx + lim + (2 * HZ).toInt())
        if (segEnd - endIdx < lim) return null
        val want = (c.meta["dir"] as? Int) ?: 0
        if (h.anyEq(h.sig, maxOf(0, endIdx - (3 * HZ).toInt()), endIdx + 1, want)) {
            var allOn = true
            for (q in endIdx until endIdx + lim) if (h.sig[q] != want) { allOn = false; break }
            val ok = !allOn
            return h.mk("blinker_cancel", Candidate("blinker_cancel", endIdx, endIdx, endIdx + lim, LinkedHashMap(c.meta)),
                if (ok) Outcome.SUCCESS else Outcome.MISS, 0.0, lim / HZ, if (ok) 1.0 else 0.0, level = if (ok) "success" else "")
        }
        return null
    }

    // ------------------------------------------------------------------------------------------ zones & points
    internal fun detectSchool(h: HabitCtx): List<Candidate> {
        val sz = h.trip.i("school_zone")
        return Np.runs(BooleanArray(h.n) { sz[it] == 1 }, (3 * HZ).toInt()).map { Candidate("school_zone", it[0], it[0], it[1]) }
    }

    internal fun judgeSchool(h: HabitCtx, c: Candidate): Judgment {
        val hab = "school_zone"
        val r = h.abstainChecks(c.i0, c.i1, setOf("v"), forcedLookback = false, adas = false)
        if (r.isNotEmpty()) return h.mk(hab, c, Outcome.ABSTAIN, reason = r)
        var vmax = Double.NEGATIVE_INFINITY
        for (q in c.i0 until c.i1) vmax = maxOf(vmax, h.d.vKph[q])
        val cam = h.anyEq(h.trip.i("camera_overspeed"), c.i0, c.i1, 1)
        val mx = h.c(hab, "success_max_speed_kph")
        val g = if (h.truth) 0.0 else h.speedGuard(mx)
        if (mx - g < vmax && vmax <= mx && !cam) return h.mk(hab, c, Outcome.ABSTAIN, vmax, mx, reason = "inside_guard_band")
        if (vmax <= mx && !cam) return h.mk(hab, c, Outcome.SUCCESS, vmax, mx, (mx - vmax) / 10, level = "success")
        return h.mk(hab, c, Outcome.MISS, vmax, mx, reason = "over_30")
    }

    internal fun detectCautionPoints(h: HabitCtx): List<Candidate> {
        val cd = h.d.cautionDistM
        val ct = h.trip.i("caution_type")
        val types = setOf(2, 3) + h.cfg.nums("caution_slowdown", "types").map { it.toInt() }
        val n = h.n
        val out = ArrayList<Candidate>()
        for (i in 1 until n) {
            if (Np.isFin(cd[i]) && Np.isFin(cd[i - 1]) && cd[i - 1] > 0 && 0 >= cd[i] && ct[i] in types) {
                var j = i
                while (j > 0 && Np.isFin(cd[j - 1]) && cd[j - 1] >= cd[j] && cd[j - 1] < 600) j--
                var k = i
                while (k < n - 1 && Np.isFin(cd[k + 1]) && cd[k + 1] <= cd[k] && cd[k + 1] > -100) k++
                out.add(Candidate("caution", i, j, k + 1, linkedMapOf("ctype" to ct[i])))
            }
        }
        return out
    }

    internal fun judgeCaution(h: HabitCtx, c: Candidate): Judgment {
        val ct = c.meta["ctype"] as Int
        val cd = h.d.cautionDistM
        val v = h.d.vKph
        val idx = (c.i0 until c.i1).toList()
        val habName = when (ct) { 2 -> "speed_bump"; 3 -> "railroad_stop"; else -> "caution_slowdown" }
        if (!h.allFinite(v, c.i0, c.i1)) return h.mk(habName, c, Outcome.ABSTAIN, reason = "speed_missing")
        if (ct == 2) {
            val hab = "speed_bump"
            val sel = idx.filter { abs(cd[it]) <= h.c(hab, "check_window_m") }
            if (sel.isEmpty()) return h.mk(hab, c, Outcome.ABSTAIN, reason = "no_samples")
            val vmax = sel.maxOf { v[it] }
            val mx = h.c(hab, "max_speed_kph")
            val g = if (h.truth) 0.0 else h.speedGuard(mx)
            if (mx - g < vmax && vmax <= mx) return h.mk(hab, c, Outcome.ABSTAIN, vmax, mx, reason = "inside_guard_band")
            val ok = vmax <= mx
            return h.mk(hab, c, if (ok) Outcome.SUCCESS else Outcome.MISS, vmax, mx, if (ok) (mx - vmax) / mx else 0.0, level = if (ok) "success" else "")
        }
        if (ct == 3) {
            val hab = "railroad_stop"
            val sel = idx.filter { cd[it] <= h.c(hab, "stop_within_m") && cd[it] >= -2 }
            if (sel.isEmpty()) return h.mk(hab, c, Outcome.ABSTAIN, reason = "no_samples")
            val vmin = sel.minOf { v[it] }
            val stop = h.c(hab, "stop_speed_kph")
            val ok = vmin <= stop - (if (h.truth) 0.0 else 0.5)
            return h.mk(hab, c, if (ok) Outcome.SUCCESS else Outcome.MISS, vmin, stop, if (ok) 1.0 else 0.0, level = if (ok) "success" else "")
        }
        val hab = "caution_slowdown"
        val rw = h.cfg.nums(hab, "ref_window_m")
        val cw = h.cfg.nums(hab, "check_window_m")
        val ref = idx.filter { cd[it] <= rw[0] && cd[it] >= rw[1] }
        val chk = idx.filter { cd[it] <= cw[0] && cd[it] >= cw[1] }
        if (ref.size < 5 || chk.size < 3) return h.mk(hab, c, Outcome.ABSTAIN, reason = "no_samples")
        val vref = Np.mean(DoubleArray(ref.size) { v[ref[it]] })
        val vchk = Np.mean(DoubleArray(chk.size) { v[chk[it]] })
        val limArr = h.trip.d("speed_limit_kph")
        val lim = Np.nanMedianF32(DoubleArray(ref.size) { limArr[ref[it]] })
        if (Np.isFin(lim) && vref < (0.5f * lim.toFloat()).toDouble()) return h.mk(hab, c, Outcome.ABSTAIN, reason = "already_slow")
        val red = 1 - vchk / maxOf(vref, 1e-3)
        val minRed = h.c(hab, "min_speed_reduction_frac")
        val ok = red >= minRed
        return h.mk(hab, c, if (ok) Outcome.SUCCESS else Outcome.MISS, red, minRed, if (ok) minOf(1.0, red / 0.3) else 0.0, level = if (ok) "success" else "")
    }

    // ------------------------------------------------------------------------------------------ windows
    private fun windows(n: Int, w: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i <= n - w) { out.add(intArrayOf(i, minOf(n, i + w))); i += w }
        return out
    }

    internal fun judgeSpeedLimit(h: HabitCtx, c: Candidate): Judgment? {
        val hab = "speed_limit"
        val a = c.i0; val b = c.i1
        val lim = h.trip.d("speed_limit_kph")
        val v = h.d.vKph
        val len = b - a
        if ((a until b).count { Np.isFin(lim[it]) }.toDouble() / len < 0.9) return null
        if (h.anyEq(h.trip.i("school_zone"), a, b, 1)) return null
        if ((a until b).count { Np.isFin(v[it]) }.toDouble() / len < 0.95) return h.mk(hab, c, Outcome.ABSTAIN, reason = "speed_missing")
        if (h.fracEq(h.cruise, a, b, 1) > 0.5) return h.mk(hab, c, Outcome.ABSTAIN, reason = "adas_engaged")
        if (h.anyLt0(h.cruise, a, b)) return h.mk(hab, c, Outcome.ABSTAIN, reason = "adas_state_unknown")
        val m = (a until b).filter { Np.isFin(lim[it]) && Np.isFin(v[it]) }
        val vm = Np.mean(DoubleArray(m.size) { v[m[it]] })
        val lm = meanF32(DoubleArray(m.size) { lim[m[it]] })
        if (vm < (0.6f * lm.toFloat()).toDouble()) return null
        val tol = h.c(hab, "tolerance_kph")
        var ok = 0
        for (q in m) {
            val l = lim[q]
            val g = if (h.truth) 0.0 else h.speedGuard(l)
            if (v[q] <= (l + tol) - g) ok++
        }
        val frac = ok.toDouble() / m.size
        val minF = h.c(hab, "success_min_fraction")
        val pass = frac >= minF
        return h.mk(hab, c, if (pass) Outcome.SUCCESS else Outcome.MISS, frac, minF,
            if (pass) (frac - minF) / (1 - minF + 1e-9) else 0.0, level = if (pass) "success" else "")
    }

    /** np.mean of a float32 array (float32 pairwise accumulation). Limits are integers, so this is exact in practice. */
    private fun meanF32(a: DoubleArray): Double {
        if (a.isEmpty()) return Double.NaN
        return (Np.pairwiseSum(a).toFloat() / a.size.toFloat()).toDouble()
    }

    internal fun judgeRain(h: HabitCtx, c: Candidate): Judgment? {
        val hab = "rain_speed"
        val a = c.i0; val b = c.i1
        val lim = h.trip.d("speed_limit_kph")
        val v = h.d.vKph
        val len = b - a
        if ((a until b).count { Np.isFin(lim[it]) }.toDouble() / len < 0.9 || h.anyEq(h.trip.i("school_zone"), a, b, 1)) return null
        if ((a until b).count { Np.isFin(v[it]) }.toDouble() / len < 0.95) return h.mk(hab, c, Outcome.ABSTAIN, reason = "speed_missing")
        if (h.fracEq(h.cruise, a, b, 1) > 0.5) return h.mk(hab, c, Outcome.ABSTAIN, reason = "adas_engaged")
        if (h.anyLt0(h.cruise, a, b)) return h.mk(hab, c, Outcome.ABSTAIN, reason = "adas_state_unknown")
        val m = (a until b).filter { Np.isFin(lim[it]) && Np.isFin(v[it]) }
        val vm = Np.mean(DoubleArray(m.size) { v[m[it]] })
        val lm = meanF32(DoubleArray(m.size) { lim[m[it]] })
        if (vm < (0.5f * lm.toFloat()).toDouble()) return null
        val factor = h.c(hab, "factor").toFloat()
        var ok = 0
        for (q in m) {
            val p = (factor * lim[q].toFloat()).toDouble()            // numpy: python float * float32 array -> float32
            val g = if (h.truth) 0.0 else h.speedGuard(p)
            if (v[q] <= p - g) ok++
        }
        val frac = ok.toDouble() / m.size
        val minF = h.c(hab, "success_min_fraction")
        val pass = frac >= minF
        return h.mk(hab, c, if (pass) Outcome.SUCCESS else Outcome.MISS, frac, minF,
            if (pass) (frac - minF) / (1 - minF + 1e-9) else 0.0, level = if (pass) "success" else "")
    }

    internal fun detectLightWindows(h: HabitCtx): List<Candidate> {
        val night = h.trip.i("night"); val wiper = h.trip.i("wiper_active")
        return windows(h.n, (60 * HZ).toInt()).filter { (a, b) ->
            var k = 0
            for (q in a until b) {
                val need = night[q] == 1 || wiper[q] == 1
                val vq = h.d.vKph[q]
                if (need && (if (vq.isNaN()) 0.0 else vq) > 5) k++
            }
            k.toDouble() / (b - a) > 0.8
        }.map { (a, b) -> Candidate("headlights", (a + b) / 2, a, b) }
    }

    internal fun judgeLights(h: HabitCtx, c: Candidate): Judgment {
        val lb = h.trip.i("low_beam")
        if (h.anyLt0(lb, c.i0, c.i1)) return h.mk("headlights", c, Outcome.ABSTAIN, reason = "light_state_missing")
        val frac = h.fracEq(lb, c.i0, c.i1, 1)
        val ok = frac >= 0.95
        return h.mk("headlights", c, if (ok) Outcome.SUCCESS else Outcome.MISS, frac, 0.95, if (ok) 1.0 else 0.0, level = if (ok) "success" else "")
    }

    private fun blocksByKm(h: HabitCtx, km: Double): List<IntArray> {
        var s = h.d.sM
        val odo = h.trip.d("odo_km")
        if (odo.all { Np.isFin(it) } && odo.isNotEmpty()) {
            val steps = (1 until odo.size).map { odo[it] - odo[it - 1] }.filter { it > 0 }
            if (steps.isNotEmpty() && steps.max() <= 0.011) s = DoubleArray(odo.size) { (odo[it] - odo[0]) * 1000 }
        }
        val total = s[s.size - 1]
        val out = ArrayList<IntArray>()
        val blocks = Math.floor(total / (km * 1000)).toInt()
        for (k in 0 until blocks) out.add(intArrayOf(Np.searchLeft(s, k * km * 1000), Np.searchLeft(s, (k + 1) * km * 1000)))
        return out
    }

    internal fun judgeBlock(h: HabitCtx, c: Candidate): Judgment {
        val a = c.i0; val b = c.i1
        if (h.fracEq(h.cruise, a, b, 1) > 0.5) return h.mk(c.habit, c, Outcome.ABSTAIN, reason = "adas_engaged")
        if (h.anyLt0(h.cruise, a, b)) return h.mk(c.habit, c, Outcome.ABSTAIN, reason = "adas_state_unknown")
        val x = h.trip.i(if (c.habit == "following_margin") "tt_fca" else "tt_lka")
        if (h.anyLt0(x, a, b)) return h.mk(c.habit, c, Outcome.ABSTAIN, reason = "telltale_unavailable")
        val nOn = Np.runs(BooleanArray(maxOf(0, b - a)) { x[a + it] == 1 }).size
        val ok = nOn == 0
        return h.mk(c.habit, c, if (ok) Outcome.SUCCESS else Outcome.MISS, nOn.toDouble(), 0.0, if (ok) 1.0 else 0.0, level = if (ok) "success" else "")
    }

    internal fun judgeBelts(h: HabitCtx, c: Candidate): Judgment {
        val occ = h.trip.i("seats_occupied")[c.iRef]
        val buck = h.trip.i("belts_buckled")[c.iRef]
        if (occ < 1 || buck < 0) return h.mk("seat_belts", c, Outcome.ABSTAIN, reason = "seat_data_missing")
        val ok = buck >= occ
        return h.mk("seat_belts", c, if (ok) Outcome.SUCCESS else Outcome.MISS, buck.toDouble(), occ.toDouble(), if (ok) 1.0 else 0.0,
            level = if (ok) "success" else "", reason = if (ok) "" else "unbuckled_seat")
    }

    internal fun detectRest(h: HabitCtx): List<Candidate> {
        val gear = h.trip.i("gear")
        val moving = BooleanArray(h.n) { val v = h.d.vKph[it]; (if (v.isNaN()) 0.0 else v) > 3 || gear[it] == 3 }
        val parked = BooleanArray(h.n) { gear[it] == 0 }
        val minBreak = h.c("rest_breaks", "min_break_min")
        val stints = ArrayList<Triple<Int, Int, Boolean>>()
        var last = 0
        for (r in Np.runs(parked, (minBreak * 60 * HZ).toInt())) { stints.add(Triple(last, r[0], true)); last = r[1] }
        stints.add(Triple(last, h.n, false))
        val out = ArrayList<Candidate>()
        for ((a, b, brk) in stints) {
            var mv = 0
            for (q in a until b) if (moving[q]) mv++
            val dur = mv / HZ / 60
            if (dur >= 100 || (brk && dur >= 90)) out.add(Candidate("rest_breaks", b - 1, a, b, linkedMapOf("stint_min" to dur, "break" to brk)))
        }
        return out
    }

    internal fun judgeRest(h: HabitCtx, c: Candidate): Judgment? {
        val dur = c.meta["stint_min"] as Double
        val brk = c.meta["break"] as Boolean
        val mx = h.c("rest_breaks", "max_continuous_drive_min")
        val ok = brk && dur <= mx
        if (!brk && dur < mx) return null
        return h.mk("rest_breaks", c, if (ok) Outcome.SUCCESS else Outcome.MISS, dur, mx, if (ok) 1.0 else 0.0, level = if (ok) "success" else "")
    }

    internal fun judgeCruise(h: HabitCtx, c: Candidate): Judgment? {
        val hab = "steady_cruise"
        val a = c.i0; val b = c.i1
        val v = DoubleArray(b - a) { h.d.vKph[a + it] }
        if (!v.all { Np.isFin(it) }) return h.mk(hab, c, Outcome.ABSTAIN, reason = "speed_missing")
        if (v.min() < h.c(hab, "min_speed_kph") || (a until b).any { h.trip.i("congestion")[it] >= 1 }) return null
        if (h.anyEq(h.cruise, a, b, 1)) return h.mk(hab, c, Outcome.ABSTAIN, reason = "adas_engaged")
        if (h.anyLt0(h.cruise, a, b)) return h.mk(hab, c, Outcome.ABSTAIN, reason = "adas_state_unknown")
        if ((a until b).any { h.sig[it] > 0 } || h.anyEq(h.fca, a, b, 1)) return null
        val lim = h.trip.d("speed_limit_kph")
        if (!(a until b).all { Np.isFin(lim[it]) }) return null
        for (q in a until b) {
            val g = if (h.truth) 0.0 else h.speedGuard(lim[q])
            if (v[q - a] > lim[q] - g) return null
        }
        val sd = Np.std(v)
        val mx = h.c(hab, "max_speed_std_kph")
        val ok = sd <= mx
        return h.mk(hab, c, if (ok) Outcome.SUCCESS else Outcome.MISS, sd, mx, if (ok) (mx - sd) / mx else 0.0, level = if (ok) "success" else "")
    }

    internal fun detectHazard(h: HabitCtx): List<Candidate> {
        val v = h.d.vKph
        val w = (h.c("hazard_warning", "within_s") * HZ).toInt()
        val out = ArrayList<Candidate>()
        var i = w
        while (i < h.n) {
            if (h.road[i] == 2 && Np.isFin(v[i]) && Np.isFin(v[i - w])) {
                if (v[i - w] >= h.c("hazard_warning", "min_entry_speed_kph") && v[i - w] - v[i] >= h.c("hazard_warning", "min_drop_kph")) {
                    out.add(Candidate("hazard_warning", i, i - w, minOf(h.n, i + (5 * HZ).toInt())))
                    i += (30 * HZ).toInt()
                    continue
                }
            }
            i += 2
        }
        return out
    }

    internal fun judgeHazard(h: HabitCtx, c: Candidate): Judgment =
        if (h.anyEq(h.sig, c.i0, c.i1, 3)) h.mk("hazard_warning", c, Outcome.SUCCESS, 1.0, 1.0, 1.0, level = "success")
        else h.mk("hazard_warning", c, Outcome.ABSTAIN, reason = "success_only_habit")

    internal fun judgeFcaReaction(h: HabitCtx, c: Candidate): Judgment {
        val hi = minOf(h.n, c.i0 + (1.0 * HZ).toInt() + 1)
        if (h.anyLt0(h.brake, c.i0, hi)) return h.mk("fca_reaction", c, Outcome.ABSTAIN, reason = "brake_missing")
        if (h.anyEq(h.brake, c.i0, hi, 1)) {
            val m = Np.nanMaxAbs(h.d.aLatG, c.i0, c.i1)
            val lat = if (m.isNaN()) 0.0 else m
            if (lat < 0.5) return h.mk("fca_reaction", c, Outcome.SUCCESS, 1.0, 1.0, 1.0, level = "success")
        }
        return h.mk("fca_reaction", c, Outcome.ABSTAIN, reason = "success_only_habit")
    }

    // ------------------------------------------------------------------------------------------ registry
    fun runEventHabits(trip: Trip, d: Derived, cfg: EngineConfig): List<Judgment> {
        val h = HabitCtx(trip, d, cfg)
        val js = ArrayList<Judgment>()
        val antMin = cfg.num("anticipatory_braking", "min_entry_speed_kph") + (if (cfg.truth) 0.0 else 2.0)
        for (c in detectStops(h)) {
            js.add(judgeStop(h, c))
            if ((c.meta["entry_kph"] as Double) >= antMin)
                js.add(judgeAnticipation(h, Candidate("anticipatory_braking", c.iRef, c.i0, c.i1, LinkedHashMap(c.meta))))
        }
        for (c in detectLaunches(h)) js.add(judgeLaunch(h, c))
        for (c in detectTurns(h)) {
            js.add(judgeTurnSignal(h, c))
            js.add(judgeSmoothTurn(h, c))
            judgeRightTurnPause(h, c)?.let { js.add(it) }
            judgeBlinkerCancel(h, c, minOf(h.n - 1, c.iRef + (4 * HZ).toInt()))?.let { js.add(it) }
        }
        for (c in detectLaneChanges(h)) {
            js.add(judgeLaneChangeSignal(h, c))
            js.add(judgeSmoothLaneChange(h, c))
            judgeBlinkerCancel(h, c, minOf(h.n - 1, c.i1))?.let { js.add(it) }
        }
        for (c in detectSchool(h)) js.add(judgeSchool(h, c))
        for (c in detectCautionPoints(h)) js.add(judgeCaution(h, c))
        for (c in detectHazard(h)) js.add(judgeHazard(h, c))
        val fca = trip.i("tt_fca")
        for (r in Np.runs(BooleanArray(h.n) { fca[it] == 1 })) js.add(judgeFcaReaction(h, Candidate("fca_reaction", r[0], r[0], minOf(h.n, r[0] + (3 * HZ).toInt()))))
        return js
    }

    fun runWindowHabits(trip: Trip, d: Derived, cfg: EngineConfig): List<Judgment> {
        val h = HabitCtx(trip, d, cfg)
        val js = ArrayList<Judgment>()
        for ((a, b) in windows(h.n, (cfg.num("speed_limit", "window_s") * HZ).toInt())) judgeSpeedLimit(h, Candidate("speed_limit", (a + b) / 2, a, b))?.let { js.add(it) }
        val wiper = trip.i("wiper_active")
        for ((a, b) in windows(h.n, (cfg.num("rain_speed", "window_s") * HZ).toInt())) {
            if (h.fracEq(wiper, a, b, 1) >= 0.8) judgeRain(h, Candidate("rain_speed", (a + b) / 2, a, b))?.let { js.add(it) }
        }
        for (c in detectLightWindows(h)) js.add(judgeLights(h, c))
        for ((a, b) in blocksByKm(h, cfg.num("following_margin", "block_km"))) {
            js.add(judgeBlock(h, Candidate("following_margin", (a + b) / 2, a, b)))
            js.add(judgeBlock(h, Candidate("lane_keeping", (a + b) / 2, a, b)))
        }
        val vn = Np.nanToNum(d.vKph)
        val belt = (0 until h.n).firstOrNull { vn[it] > cfg.num("seat_belts", "check_at_speed_kph") }
        if (belt != null) js.add(judgeBelts(h, Candidate("seat_belts", belt, belt, belt + 1)))
        for (c in detectRest(h)) judgeRest(h, c)?.let { js.add(it) }
        val road = trip.i("road_class")
        for ((a, b) in windows(h.n, (cfg.num("steady_cruise", "window_s") * HZ).toInt())) {
            if ((a until b).all { road[it] == 2 }) judgeCruise(h, Candidate("steady_cruise", (a + b) / 2, a, b))?.let { js.add(it) }
        }
        return js
    }
}

private operator fun IntArray.component1() = this[0]
private operator fun IntArray.component2() = this[1]
