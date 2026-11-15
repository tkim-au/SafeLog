package com.safelog.core.drive

import com.safelog.core.contract.DataContract
import com.safelog.core.contract.Frame
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.tan

/**
 * In-drive part (port of scoring_engine/streaming.py ChimeDetector, extended to every mission). O(1) per frame,
 * no arrays, no ML: well inside the Pleos budget (< 5 % CPU while driving). It only decides WHEN a mission
 * succeeded so the praise chime can play; the counts the driver sees after parking come from the full engine.
 */
class MissionDetector(
    val habit: String,
    private val hz: Double = DataContract.BASE_HZ,
    private val guardG: Double = 0.02,
    steerSign: Int = -1,
    steerUnit: String = "road_wheel",
) {
    var successes = 0; private set
    var chances = 0; private set
    private val G = DataContract.G
    private val vHist = ArrayDeque<Double>()
    private var state = "idle"
    private var peak = 0.0
    private var fcaAge = 1e9
    // turn signal
    private var sigOnDist: Double? = null
    private var prevSig = 0
    private var prevTd: Double? = null
    private var tdEst: Double? = null
    private var tdRawPrev: Double? = null
    // launch
    private var launchFrames = 0
    // zones / windows
    private var zoneFrames = 0
    private var zoneMax = 0.0
    private var zoneCam = false
    private var winFrames = 0
    private var winOk = 0
    private var winValid = true
    private val lc = LaneChangeOnline(hz, steerSign, if (steerUnit == "steering_wheel") 13.5 else 1.0)

    /** Returns true when a success happened on this frame. */
    fun step(f: Frame): Boolean {
        val v = f.speedKph
        fcaAge = if (f.ttFca == 1) 0.0 else fcaAge + 1
        if (!v.isNaN()) { vHist.addLast(v); if (vHist.size > hz.toInt() + 1) vHist.removeFirst() }
        val aG = if (vHist.size == hz.toInt() + 1) (vHist.last - vHist.first) / 3.6 / ((vHist.size - 1) / hz) / G else 0.0
        val ok = when (habit) {
            "smooth_stop" -> stop(v, aG, f)
            "smooth_launch" -> launch(v, aG, f)
            "turn_signal_turn" -> turnSignal(v, f)
            "lane_change_signal" -> lc.step(f).let { r -> if (r != 0) chances++; r == 1 }
            "school_zone" -> school(v, f)
            "speed_limit" -> window(v, f, 1.0f)
            "rain_speed" -> if (f.wiperActive == 1) window(v, f, 0.8f) else { resetWindow(); false }
            else -> false
        }
        if (ok) successes++
        return ok
    }

    private fun stop(v: Double, aG: Double, f: Frame): Boolean {
        if (state == "idle" && !v.isNaN() && v >= 32 && aG < -0.03) { state = "decel"; peak = 0.0 }
        else if (state == "decel") {
            peak = maxOf(peak, -aG)
            if (!v.isNaN() && v < 1.0) {
                state = "idle"; chances++
                return peak < 0.30 - guardG && fcaAge > 3 * hz && f.cruiseActive != 1
            } else if (aG > 0.05) state = "idle"
        }
        return false
    }

    private fun launch(v: Double, aG: Double, f: Frame): Boolean {
        if (!v.isNaN() && v < 1.0) { state = "stopped"; peak = 0.0; launchFrames = 0; return false }
        if (state == "stopped" && !v.isNaN()) {
            launchFrames++
            peak = maxOf(peak, aG)
            if (v >= 22) {
                state = "idle"; chances++
                return peak < 0.30 - guardG && f.cruiseActive == 0
            }
            if (launchFrames > 15 * hz) state = "idle"
        }
        return false
    }

    private fun turnSignal(v: Double, f: Frame): Boolean {
        val tdRaw = f.tbtDistM
        // NaviHelper refreshes the distance ~1 Hz: dead-reckon between refreshes
        if (tdRaw.isNaN()) tdEst = null
        else if (tdRawPrev == null || tdRaw != tdRawPrev || tdEst == null) tdEst = tdRaw
        else if (!v.isNaN() && v > 1.5) tdEst = tdEst!! - v / 3.6 / hz
        tdRawPrev = if (tdRaw.isNaN()) null else tdRaw
        val td = tdEst ?: Double.NaN
        val want = when (f.tbtType) { 1 -> 1; 2 -> 2; else -> 0 }
        var out = false
        if (!td.isNaN() && want != 0) {
            val sig = f.turnSignal
            if (sig == want && prevSig != want) sigOnDist = td
            if (sig != want && prevSig == want && td > 5) sigOnDist = null
            val p = prevTd
            if (p != null && p > 0 && 0 >= td) {
                val need = if (f.roadClass == 2) 100 else 30
                chances++
                out = sigOnDist != null && sigOnDist!! >= need + 5
                sigOnDist = null
            }
        }
        prevTd = if (td.isNaN()) null else td
        prevSig = f.turnSignal
        return out
    }

    private fun school(v: Double, f: Frame): Boolean {
        if (f.schoolZone == 1) {
            zoneFrames++
            if (!v.isNaN()) zoneMax = maxOf(zoneMax, v) else zoneCam = true
            if (f.cameraOverspeed == 1) zoneCam = true
            return false
        }
        var out = false
        if (zoneFrames >= 3 * hz) { chances++; out = zoneMax <= 30 - 1.3 && !zoneCam }
        zoneFrames = 0; zoneMax = 0.0; zoneCam = false
        return out
    }

    private fun resetWindow() { winFrames = 0; winOk = 0; winValid = true }

    private fun window(v: Double, f: Frame, factor: Float): Boolean {
        val lim = f.speedLimitKph
        if (lim.isNaN() || v.isNaN() || f.cruiseActive != 0 || f.schoolZone == 1) winValid = false
        else {
            val l = (factor * lim.toFloat()).toDouble()
            if (v <= l - (1.0 + 0.01 * l)) winOk++
        }
        winFrames++
        if (winFrames >= 60 * hz) {
            val good = winValid && winOk >= 0.95 * winFrames
            if (winValid) chances++
            resetWindow()
            return good
        }
        return false
    }
}

/** Streaming version of the S-shaped yaw detector (needs only the last ~15 s). */
class LaneChangeOnline(private val hz: Double, private val sign: Int, private val ratio: Double) {
    private val yaw = ArrayDeque<Double>()
    private var sigSince: Pair<Int, Int>? = null
    private var t = 0
    private val lobes = ArrayDeque<Triple<Int, Int, Double>>()
    private var cur: DoubleArray? = null

    /** 1 = lane change with the blinker on >= 3.5 s before, -1 = lane change without, 0 = nothing. */
    fun step(f: Frame): Int {
        t += 1
        if (sign == 0) return 0
        val v = if (f.speedKph.isNaN()) 0.0 else f.speedKph / 3.6
        val delta = if (f.steerDeg.isNaN()) 0.0 else Math.toRadians(sign * f.steerDeg / ratio)
        val y = Math.toDegrees(v * tan(delta) / (2.895 * (1 + 0.0025 * v * v)))
        yaw.addLast(y); if (yaw.size > (15 * hz).toInt()) yaw.removeFirst()
        val base = yaw.sorted()[yaw.size / 2]
        val dev = y - base
        val sig = f.turnSignal
        if ((sig == 1 || sig == 2) && sigSince == null) sigSince = t to sig
        if (sig == 0 && sigSince != null && t - sigSince!!.first > 15 * hz) sigSince = null
        if (abs(dev) > 0.5) {
            if (cur == null) cur = doubleArrayOf(t.toDouble(), 0.0)
            cur!![1] += dev / hz
        } else if (cur != null) {
            if (t - cur!![0] >= 0.5 * hz) { lobes.addLast(Triple(cur!![0].toInt(), t, cur!![1])); if (lobes.size > 3) lobes.removeFirst() }
            cur = null
            if (lobes.size >= 2) {
                val l = lobes.toList()
                val (s1, e1, a1) = l[l.size - 2]
                val (s2, _, a2) = l[l.size - 1]
                if (Math.signum(a1) != Math.signum(a2) && (s2 - e1) <= 2 * hz && abs(a1 / a2) in 0.25..4.0 && v * 3.6 >= 40) {
                    val d = if (a1 > 0) 1 else 2
                    lobes.clear()
                    val ss = sigSince
                    if (ss != null && ss.second == d && (s1 - ss.first) / hz >= 3.5) { sigSince = null; return 1 }
                    return -1
                }
            }
        }
        return 0
    }
}

/**
 * When the chime may actually play: praise only, one short sound for each mission success (submitted plan,
 * section 3: "미션 달성 1건당 짧은 칭찬음을 한 번"), deferred while the driver is busy (steering hard, blinker on,
 * turn within 300 m, camera / caution point / school zone), dropped if still busy 20 s later, and silent for
 * 30 s after a forward-collision warning. [minGapS] only keeps two tones from running into each other; plan
 * v0.4 F5 had capped the chime at once per 5 minutes.
 */
class SignalGate(private val hz: Double = DataContract.BASE_HZ) {
    var minGapS = 3.0
    private var lastChimeT = -1e9
    private var pendingSince: Double? = null
    private var pendingBusyEnd: Double? = null
    private var fcaUntil = -1e9

    fun busy(f: Frame): Boolean =
        abs(if (f.steerDeg.isNaN()) 0.0 else f.steerDeg) > 3.0 || f.turnSignal != 0 ||
            (!f.tbtDistM.isNaN() && f.tbtDistM <= 300 && f.tbtType in 1..3) ||
            (!f.cautionDistM.isNaN() && f.cautionDistM in -50.0..300.0) || f.schoolZone == 1 || f.ttFca == 1

    /** Call every frame. [success] = a mission detector fired. Returns true when the chime should play now. */
    fun step(f: Frame, success: Boolean, enabled: Boolean): Boolean {
        if (f.ttFca == 1) { fcaUntil = f.t + 30; pendingSince = null }
        if (!enabled) { pendingSince = null; return false }
        if (success && pendingSince == null) pendingSince = f.t
        val p = pendingSince ?: return false
        if (f.t < fcaUntil) { pendingSince = null; return false }
        if (f.t - lastChimeT < minGapS) { pendingSince = null; return false }
        if (busy(f)) { pendingBusyEnd = null; if (f.t - p > 20) pendingSince = null; return false }
        if (pendingBusyEnd == null) pendingBusyEnd = f.t
        if (f.t - pendingBusyEnd!! > 3) { pendingSince = null; pendingBusyEnd = null; return false }
        lastChimeT = f.t; pendingSince = null; pendingBusyEnd = null
        return true
    }
}
