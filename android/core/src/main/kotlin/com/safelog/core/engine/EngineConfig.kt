package com.safelog.core.engine

import com.safelog.core.contract.DataContract
import com.safelog.core.json.Json

/**
 * Engine thresholds. Loaded from `safelog/engine_config.json`, which `backend/tools/export_engine_config.py`
 * writes from the PC's `scoring_engine/config/thresholds.yaml`, so both engines always share the same numbers.
 */
class EngineConfig(private val root: Map<String, Any?>) {
    /** Per-car calibration memory (steering sign, unit and ratio scale learned on earlier trips). */
    val vehicle = VehicleMemory.from(section("vehicle"))
    val dataVersion: Int = (root["data_version"] as? Number)?.toInt() ?: DataContract.DATA_VERSION

    /** Ground-truth mode (simulator only): guards are off. The app never sets it. */
    var truth = false

    @Suppress("UNCHECKED_CAST")
    fun section(name: String): Map<String, Any?> = root[name] as? Map<String, Any?> ?: emptyMap()

    fun num(sec: String, key: String, def: Double = Double.NaN): Double = (section(sec)[key] as? Number)?.toDouble() ?: def
    fun bool(sec: String, key: String, def: Boolean = false): Boolean = section(sec)[key] as? Boolean ?: def

    @Suppress("UNCHECKED_CAST")
    fun nums(sec: String, key: String): List<Double> = (section(sec)[key] as? List<Any?>)?.map { (it as Number).toDouble() } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    val notReleased: Set<String> get() = ((section("release")["not_released"] as? List<Any?>) ?: emptyList()).map { it.toString() }.toSet()

    fun copyWith(memory: VehicleMemory): EngineConfig = EngineConfig(root).also { it.vehicle.set(memory) }

    companion object {
        fun load(json: String): EngineConfig {
            val m = Json.obj(json)
            val v = (m["data_version"] as? Number)?.toInt() ?: DataContract.DATA_VERSION
            require(v == DataContract.DATA_VERSION) { "engine_config.json is for data contract v$v, this engine is v${DataContract.DATA_VERSION}" }
            return EngineConfig(m)
        }

        /** The thresholds bundled with this build (resource exported from thresholds.yaml). */
        fun bundled(): EngineConfig {
            val s = EngineConfig::class.java.classLoader.getResourceAsStream("safelog/engine_config.json")
                ?: error("safelog/engine_config.json missing from the core module resources")
            return load(s.bufferedReader(Charsets.UTF_8).readText())
        }
    }
}

/** Mirrors cfg["vehicle"] in Python (SafeLogEngine._remember_calibration). Persisted by the app per car. */
class VehicleMemory(
    var steerSign: Int = -1,
    var steerSignLearned: Boolean = false,
    var steerUnit: String = "auto",
    var steerUnitLearned: String? = null,
    var ratioScale: Double? = null,
) {
    fun set(o: VehicleMemory) {
        steerSign = o.steerSign; steerSignLearned = o.steerSignLearned; steerUnit = o.steerUnit
        steerUnitLearned = o.steerUnitLearned; ratioScale = o.ratioScale
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "steer_sign" to steerSign, "steer_sign_learned" to steerSignLearned, "steer_unit" to steerUnit,
        "steer_unit_learned" to steerUnitLearned, "ratio_scale" to ratioScale,
    )

    companion object {
        fun from(m: Map<String, Any?>) = VehicleMemory(
            steerSign = (m["steer_sign"] as? Number)?.toInt() ?: 0,
            steerSignLearned = m["steer_sign_learned"] as? Boolean ?: false,
            steerUnit = m["steer_unit"] as? String ?: "auto",
            steerUnitLearned = m["steer_unit_learned"] as? String,
            ratioScale = (m["ratio_scale"] as? Number)?.toDouble(),
        )
    }
}
