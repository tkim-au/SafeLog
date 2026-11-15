package com.safelog.core

import com.safelog.core.contract.Trip
import com.safelog.core.engine.EngineConfig
import com.safelog.core.engine.SafeLogEngine
import com.safelog.core.json.Json
import com.safelog.core.json.list
import com.safelog.core.json.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The Kotlin engine must give exactly the judgments of the Python reference engine (scoring_engine/ on the PC)
 * on the same recorded trips. Fixtures: backend/tools/export_trips.py --only parity.
 */
class ParityTest {
    private fun res(name: String) = javaClass.classLoader.getResourceAsStream(name) ?: error("missing test resource $name")

    private fun cases(): List<String> {
        val idx = res("parity/index.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        return idx
    }

    private fun close(a: Double?, b: Double?, tol: Double = 1e-7): Boolean {
        if (a == null || b == null) return (a == null || a.isNaN()) && (b == null || b.isNaN())
        if (a.isNaN() || b.isNaN()) return a.isNaN() && b.isNaN()
        return abs(a - b) <= tol * maxOf(1.0, abs(a), abs(b))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun judgmentsMatchPython() {
        val failures = ArrayList<String>()
        var total = 0
        for (name in cases()) {
            val trip = Trip.readCsv(res("parity/$name.csv.gz"))
            val exp = Json.obj(res("parity/$name.expected.json").bufferedReader().readText())
            val eng = SafeLogEngine(EngineConfig.bundled())
            val r = eng.runTrip(trip)
            val d = r.derived!!
            val ed = exp.obj("derived")
            fun chk(what: String, ok: Boolean) { if (!ok) failures.add("$name: $what") }
            chk("steer_unit ${d.steerUnit}/${ed["steer_unit"]}", d.steerUnit == ed["steer_unit"])
            chk("steer_unit_source ${d.steerUnitSource}/${ed["steer_unit_source"]}", d.steerUnitSource == ed["steer_unit_source"])
            chk("steer_sign ${d.steerSign}/${ed["steer_sign"]}", d.steerSign == (ed["steer_sign"] as Number).toInt())
            chk("steer_sign_source ${d.steerSignSource}/${ed["steer_sign_source"]}", d.steerSignSource == ed["steer_sign_source"])
            chk("ratio_scale ${d.ratioScale}/${ed["ratio_scale"]}", close(d.ratioScale, (ed["ratio_scale"] as Number).toDouble()))
            chk("ratio_scale_source", d.ratioScaleSource == ed["ratio_scale_source"])
            chk("speed_native_hz", close(d.speedNativeHz, (ed["speed_native_hz"] as Number).toDouble()))
            chk("km", close(d.km, (ed["km"] as Number).toDouble(), 1e-9))
            val ej = exp.list("judgments").map { it as Map<String, Any?> }
            total += ej.size
            if (ej.size != r.judgments.size) failures.add("$name: ${r.judgments.size} judgments, python ${ej.size}")
            for ((k, e) in ej.withIndex()) {
                val j = r.judgments.getOrNull(k) ?: break
                val tag = "$name #$k ${e["habit"]}@${e["i_ref"]}"
                val same = j.habit == e["habit"] && j.iRef == (e["i_ref"] as Number).toInt() && j.i0 == (e["i0"] as Number).toInt() &&
                    j.i1 == (e["i1"] as Number).toInt() && j.outcome == (e["outcome"] as Number).toInt() && j.level == e["level"] &&
                    j.reason == e["reason"] && j.held == e["held"]
                if (!same) { failures.add("$tag: kotlin ${j.habit}@${j.iRef}[${j.i0},${j.i1}) o=${j.outcome} ${j.level} ${j.reason} held=${j.held} | python o=${e["outcome"]} ${e["level"]} ${e["reason"]} [${e["i0"]},${e["i1"]})"); if (failures.size > 40) break; continue }
                if (!close(j.value, (e["value"] as? Number)?.toDouble(), 1e-6)) failures.add("$tag value ${j.value} vs ${e["value"]}")
                if (!close(j.margin, (e["margin"] as? Number)?.toDouble(), 1e-6)) failures.add("$tag margin ${j.margin} vs ${e["margin"]}")
                val ec = e.obj("context").mapValues { (it.value as Number).toInt() }
                if (ec != j.context) failures.add("$tag context ${j.context} vs $ec")
            }
            val er = exp.obj("risk")
            val rk = r.risk.toMap()
            for ((k, v) in er) {
                val kv = rk[k]
                val ok = when (v) { is Boolean -> v == kv; is Number -> close((kv as Number).toDouble(), v.toDouble(), 1e-9); else -> true }
                if (!ok) failures.add("$name risk $k $kv vs $v")
            }
            val ew = exp.obj("rewards")
            if ((ew["xp"] as Number).toInt() != r.rewards.xp) failures.add("$name xp ${r.rewards.xp} vs ${ew["xp"]}")
            if (ew["lines"] != r.rewards.allGoodPoints.map { it.text }) failures.add("$name lines ${r.rewards.allGoodPoints.map { it.text }} vs ${ew["lines"]}")
            val cal = exp.obj("calibration")
            val mem = eng.cfg.vehicle
            if ((cal["steer_sign"] as Number).toInt() != mem.steerSign) failures.add("$name memory steer_sign")
            if (!close((cal["ratio_scale"] as? Number)?.toDouble(), mem.ratioScale)) failures.add("$name memory ratio_scale ${mem.ratioScale} vs ${cal["ratio_scale"]}")
            if (failures.size > 40) break
        }
        assertTrue("parity failures (${failures.size}) over $total judgments:\n" + failures.take(40).joinToString("\n"), failures.isEmpty())
        assertTrue(total > 1000)
    }

    @Test
    fun bundledConfigIsContractV3() {
        assertEquals(3, EngineConfig.bundled().dataVersion)
    }
}
