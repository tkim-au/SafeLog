package com.safelog.core

import com.safelog.core.contract.Trip
import com.safelog.core.engine.EngineConfig
import com.safelog.core.engine.Outcome
import com.safelog.core.engine.SafeLogEngine
import com.safelog.core.gate.GateFeatures
import com.safelog.core.gate.IncompatibleModel
import com.safelog.core.gate.TcnGate
import com.safelog.core.json.Json
import com.safelog.core.json.list
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The Kotlin TCN must give the probabilities PyTorch gives for the same bundle and judgments
 * (fixture: backend/tools/make_gate_fixture.py — random weights, the real model comes from the PC later).
 */
class GateParityTest {
    private fun res(name: String) = javaClass.classLoader.getResourceAsStream(name) ?: error("missing $name")

    @Suppress("UNCHECKED_CAST")
    private fun checkParity(bundle: String, expected: String) {
        val gate = TcnGate.load(res(bundle).bufferedReader().readText())
        val exp = Json.obj(res(expected).bufferedReader().readText())
        val trip = Trip.readCsv(res("parity/${exp["trip"]}.csv.gz"))
        val eng = SafeLogEngine(EngineConfig.bundled())
        val r = eng.runTrip(trip)
        val gated = r.judgments.filter { it.habit in GateFeatures.GATE_HABITS && (it.outcome == Outcome.SUCCESS || it.held) }
        val ej = exp.list("judgments").map { it as Map<String, Any?> }
        assertEquals(ej.size, gated.size)
        var maxErr = 0.0
        for ((e, j) in ej.zip(gated)) {
            assertEquals(e["habit"], j.habit)
            assertEquals((e["i_ref"] as Number).toInt(), j.iRef)
            val p = gate.probability(GateFeatures.tensor(trip, r.derived!!, j))
            maxErr = maxOf(maxErr, abs(p - (e["p"] as Number).toDouble()))
        }
        println("$bundle: ${ej.size} gated judgments, max |p_kotlin - p_torch| = $maxErr")
        assertTrue("max |p_kotlin - p_torch| = $maxErr", maxErr < 2e-4)
    }

    @Test
    fun probabilitiesMatchPyTorch() = checkParity("gate/random_gate_v3.json", "gate/random_gate_expected.json")

    /**
     * The PC's trained model, once exported with `export_gate.py --fixture <trip>` (writes model_gate_v3.json and
     * model_gate_expected.json next to the random fixture). Skipped while the model slot is empty.
     */
    @Test
    fun trainedModelMatchesPyTorch() {
        if (javaClass.classLoader.getResource("gate/model_gate_v3.json") == null) {
            println("SKIP trainedModelMatchesPyTorch: no exported model yet (gate slot empty)")
            return
        }
        checkParity("gate/model_gate_v3.json", "gate/model_gate_expected.json")
    }

    @Test
    fun gateOnlyVetoes() {
        val gate = TcnGate.load(res("gate/random_gate_v3.json").bufferedReader().readText())
        val trip = Trip.readCsv(res("parity/commute_city.csv.gz"))
        val rules = SafeLogEngine(EngineConfig.bundled()).runTrip(trip).judgments
        val gated = SafeLogEngine(EngineConfig.bundled(), gate).runTrip(trip).judgments
        assertEquals(rules.size, gated.size)
        for ((a, b) in rules.zip(gated)) {
            // a gated SUCCESS can only become ABSTAIN; MISS / ABSTAIN never change
            if (a.outcome != Outcome.SUCCESS) assertEquals(a.outcome, b.outcome)
            else assertTrue(b.outcome == Outcome.SUCCESS || (b.outcome == Outcome.ABSTAIN && b.reason == "gate_veto"))
        }
    }

    @Test
    fun refusesOtherDataContract() {
        val text = res("gate/random_gate_v3.json").bufferedReader().readText().replace("\"data_version\": 3", "\"data_version\": 2")
        try {
            TcnGate.load(text)
            throw AssertionError("v2 bundle must be refused")
        } catch (e: IncompatibleModel) {
            assertTrue(e.message!!.contains("v2"))
        }
    }
}
