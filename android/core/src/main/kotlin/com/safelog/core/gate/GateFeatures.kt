package com.safelog.core.gate

import com.safelog.core.contract.Trip
import com.safelog.core.engine.Derived
import com.safelog.core.engine.Judgment

/**
 * Port of model_training/features.py: one judgment -> (17 x 240 window, 14 static values).
 * Everything comes from the OBSERVED trip (what the car app sees). Must stay identical to the Python file;
 * the gate bundle carries this spec and the loader refuses a model built for another one.
 */
object GateFeatures {
    val GATE_HABITS = listOf(
        "smooth_stop", "smooth_launch", "anticipatory_braking", "smooth_turn",
        "turn_signal_turn", "lane_change_signal", "smooth_lane_change",
    )
    const val PRE = 180
    const val POST = 60
    const val T = PRE + POST
    val CHANNELS = listOf(
        "v", "v_mask", "a_long", "brake", "steer", "yaw", "sig_l", "sig_r", "sig_h", "tbt", "tbt_mask",
        "limit_ratio", "fca", "lka", "cruise", "wiper", "school",
    )
    val C = CHANNELS.size
    val S = GATE_HABITS.size + 7

    class Tensor(val x: Array<FloatArray>, val s: FloatArray)

    private fun win(x: (Int) -> Double, n: Int, i: Int, fill: Float = 0f): FloatArray {
        val out = FloatArray(T) { fill }
        val a = i - PRE
        val b = i + POST
        val sa = maxOf(0, a)
        val sb = minOf(n, b)
        for (k in sa until sb) out[k - a] = x(k).toFloat()
        return out
    }

    private fun clip(x: Double, lo: Double, hi: Double) = if (x < lo) lo else if (x > hi) hi else x
    private fun nz(x: Double, nan: Double = 0.0) = if (x.isNaN()) nan else x

    fun tensor(trip: Trip, d: Derived, j: Judgment): Tensor {
        val i = j.iRef
        val n = trip.n
        val v = d.vKph
        val lim = trip.d("speed_limit_kph")
        val sig = trip.i("turn_signal")
        val tbt = d.tbtDistM
        val brake = trip.i("brake_on")
        val fca = trip.i("tt_fca"); val lka = trip.i("tt_lka")
        val cruise = trip.i("cruise_active"); val wiper = trip.i("wiper_active"); val school = trip.i("school_zone")
        fun fin(x: Double) = !x.isNaN() && !x.isInfinite()
        val x = arrayOf(
            win({ nz(v[it]) / 100 }, n, i),
            win({ if (fin(v[it])) 1.0 else 0.0 }, n, i),
            win({ clip(nz(d.aLongG[it]) * 3, -3.0, 3.0) }, n, i),
            win({ if (brake[it] == 1) 1.0 else 0.0 }, n, i),
            win({ clip(nz(d.deltaDeg[it]) / 6.0, -6.0, 6.0) }, n, i),
            win({ clip(nz(d.yawSteerDps[it]) / 20, -5.0, 5.0) }, n, i),
            win({ if (sig[it] == 1) 1.0 else 0.0 }, n, i),
            win({ if (sig[it] == 2) 1.0 else 0.0 }, n, i),
            win({ if (sig[it] == 3) 1.0 else 0.0 }, n, i),
            win({ clip(nz(tbt[it], 300.0) / 300, -0.1, 1.0) }, n, i, 1f),
            win({ if (fin(tbt[it])) 1.0 else 0.0 }, n, i),
            win({
                val ok = fin(v[it]) && fin(lim[it]) && lim[it] > 0
                clip(if (ok) nz(v[it]) / lim[it] else 0.0, 0.0, 2.0)
            }, n, i),
            win({ if (fca[it] == 1) 1.0 else 0.0 }, n, i),
            win({ if (lka[it] == 1) 1.0 else 0.0 }, n, i),
            win({ (if (cruise[it] == 1) 1.0 else 0.0) - (if (cruise[it] < 0) 0.5 else 0.0) }, n, i),
            win({ (if (wiper[it] == 1) 1.0 else 0.0) - (if (wiper[it] < 0) 0.5 else 0.0) }, n, i),
            win({ if (school[it] == 1) 1.0 else 0.0 }, n, i),
        )
        val s = FloatArray(S)
        s[GATE_HABITS.indexOf(j.habit)] = 1f
        val k = GATE_HABITS.size
        s[k] = (nz(j.value) / (kotlin.math.abs(nz(j.threshold)) + 1e-3)).toFloat()
        s[k + 1] = j.margin.toFloat()
        s[k + 2] = (minOf(d.speedNativeHz, 10.0) / 10).toFloat()
        s[k + 3] = (((j.meta["entry_kph"] as? Number)?.toDouble() ?: 0.0) / 100).toFloat()
        s[k + 4] = if (j.meta["source"] == "tbt") 1f else 0f
        s[k + 5] = if (d.steerSign != 0) 1f else 0f
        s[k + 6] = if ((0 until n).all { cruise[it] >= 0 }) 1f else 0f
        return Tensor(x, s)
    }
}
