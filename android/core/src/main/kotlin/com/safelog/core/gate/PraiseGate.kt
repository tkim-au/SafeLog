package com.safelog.core.gate

import com.safelog.core.contract.DataContract
import com.safelog.core.contract.Trip
import com.safelog.core.engine.Derived
import com.safelog.core.engine.Judgment
import com.safelog.core.engine.Outcome
import com.safelog.core.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Dual-key praise: a rule SUCCESS is shown only if the learned gate also agrees. The gate can only VETO
 * (SUCCESS -> ABSTAIN); it never creates praise or a miss, so every praise is still explained by a sourced rule.
 */
interface PraiseGate {
    val description: String
    fun apply(trip: Trip, d: Derived, judgments: List<Judgment>): List<Judgment>
}

class IncompatibleModel(msg: String) : Exception(msg)

/**
 * The TCN ensemble trained on the PC (`model_training/tcn.py`, `ensemble.py`), loaded from the bundle written by
 * `backend/tools/export_gate.py`. BatchNorm is folded into the convolutions at export time.
 */
class TcnGate private constructor(
    private val members: List<Member>,
    private val temperature: Double,
    private val thresholds: Map<String, Double>,
    val meta: Map<String, Any?>,
) : PraiseGate {

    override val description: String
        get() = "TCN gate (${members.size} members, T=${"%.3f".format(temperature)}, trained ${meta["trained_at"] ?: "?"})"

    override fun apply(trip: Trip, d: Derived, judgments: List<Judgment>): List<Judgment> {
        for (j in judgments) {
            if (j.habit !in GateFeatures.GATE_HABITS || j.outcome != Outcome.SUCCESS) continue
            val th = thresholds[j.habit] ?: 0.0
            if (th <= 0) continue
            val p = probability(GateFeatures.tensor(trip, d, j))
            j.meta["gate_p"] = p
            if (p < th) {
                j.outcome = Outcome.ABSTAIN; j.reason = "gate_veto"; j.level = ""
            }
        }
        return judgments
    }

    /** ensemble_prob + calibrated_prob: mean of member sigmoids, then temperature on the logit of the mean. */
    fun probability(t: GateFeatures.Tensor): Double {
        var p = 0.0
        for (m in members) p += sigmoid(m.logit(t))
        p /= members.size
        p = p.coerceIn(1e-6, 1 - 1e-6)
        return sigmoid(ln(p / (1 - p)) / temperature)
    }

    private class Conv(val cout: Int, val cin: Int, val k: Int, val dil: Int, val w: FloatArray, val b: FloatArray) {
        fun run(x: Array<DoubleArray>): Array<DoubleArray> {
            val T = x[0].size
            val pad = (k / 2) * dil
            return Array(cout) { o ->
                val out = DoubleArray(T) { b[o].toDouble() }
                for (c in 0 until cin) {
                    val xc = x[c]
                    for (q in 0 until k) {
                        val wv = w[(o * cin + c) * k + q].toDouble()
                        if (wv == 0.0) continue
                        val off = q * dil - pad
                        val lo = maxOf(0, -off)
                        val hi = minOf(T, T - off)
                        for (t in lo until hi) out[t] += wv * xc[t + off]
                    }
                }
                out
            }
        }
    }

    private class Block(val conv: Conv, val res: Conv?) {
        fun run(x: Array<DoubleArray>): Array<DoubleArray> {
            val h = conv.run(x)
            val r = res?.run(x) ?: x
            for (o in h.indices) for (t in h[o].indices) h[o][t] = maxOf(0.0, h[o][t]) + r[o][t]
            return h
        }
    }

    private class Member(val blocks: List<Block>, val out: Conv, val fc1W: FloatArray, val fc1B: FloatArray, val fc2W: FloatArray, val fc2B: FloatArray, val width: Int) {
        fun logit(t: GateFeatures.Tensor): Double {
            var h = Array(t.x.size) { c -> DoubleArray(t.x[c].size) { t.x[c][it].toDouble() } }
            for (b in blocks) h = b.run(h)
            h = out.run(h)
            val feat = DoubleArray(2 * width + t.s.size)
            for (c in 0 until width) {
                var s = 0.0; var mx = Double.NEGATIVE_INFINITY
                for (v in h[c]) { s += v; if (v > mx) mx = v }
                feat[c] = s / h[c].size
                feat[width + c] = mx
            }
            for (k in t.s.indices) feat[2 * width + k] = t.s[k].toDouble()
            val hid = fc1B.size
            val z = DoubleArray(hid) { o ->
                var s = fc1B[o].toDouble()
                for (k in feat.indices) s += fc1W[o * feat.size + k] * feat[k]
                maxOf(0.0, s)
            }
            var y = fc2B[0].toDouble()
            for (k in 0 until hid) y += fc2W[k] * z[k]
            return y
        }
    }

    companion object {
        private fun sigmoid(x: Double) = 1 / (1 + exp(-x))

        private fun floats(b64: Any?): FloatArray {
            val bytes = Base64.getDecoder().decode(b64 as String)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { bb.getFloat() }
        }

        @Suppress("UNCHECKED_CAST")
        fun load(json: String): TcnGate {
            val m = Json.obj(json)
            if (m["format"] != "safelog-gate") throw IncompatibleModel("not a SafeLog gate bundle")
            val dv = (m["data_version"] as? Number)?.toInt() ?: 1
            if (dv != DataContract.DATA_VERSION)
                throw IncompatibleModel("gate trained on data contract v$dv; this app is v${DataContract.DATA_VERSION}. Retrain on the PC.")
            val spec = m["feature_spec"] as Map<String, Any?>
            val ok = (spec["pre"] as Number).toInt() == GateFeatures.PRE && (spec["post"] as Number).toInt() == GateFeatures.POST &&
                spec["channels"] == GateFeatures.CHANNELS && spec["gate_habits"] == GateFeatures.GATE_HABITS &&
                (spec["static"] as Number).toInt() == GateFeatures.S
            if (!ok) throw IncompatibleModel("gate feature spec differs from this app's (model_training/features.py changed?)")
            val members = (m["members"] as List<Any?>).map { mm ->
                mm as Map<String, Any?>
                val width = (mm["width"] as Number).toInt()
                val blocks = (mm["blocks"] as List<Any?>).map { bb ->
                    bb as Map<String, Any?>
                    val cin = (bb["cin"] as Number).toInt(); val cout = (bb["cout"] as Number).toInt()
                    val dil = (bb["dilation"] as Number).toInt()
                    val conv = Conv(cout, cin, 5, dil, floats(bb["conv_w"]), floats(bb["conv_b"]))
                    val res = if (bb["res_w"] != null) Conv(cout, cin, 1, 1, floats(bb["res_w"]), floats(bb["res_b"])) else null
                    Block(conv, res)
                }
                Member(blocks, Conv(width, width, 1, 1, floats(mm["out_w"]), floats(mm["out_b"])),
                    floats(mm["fc1_w"]), floats(mm["fc1_b"]), floats(mm["fc2_w"]), floats(mm["fc2_b"]), width)
            }
            val th = (m["thresholds"] as Map<String, Any?>).mapValues { (it.value as Number).toDouble() }
            return TcnGate(members, (m["temperature"] as Number).toDouble(), th, (m["meta"] as? Map<String, Any?>) ?: emptyMap())
        }
    }
}
