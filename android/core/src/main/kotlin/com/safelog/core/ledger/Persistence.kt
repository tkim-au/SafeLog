package com.safelog.core.ledger

import com.safelog.core.engine.DriverProfile
import com.safelog.core.engine.VehicleMemory
import com.safelog.core.json.Json
import java.time.LocalDate
import java.time.YearMonth

/** Where the car keeps its JSON documents (the app gives a files-dir implementation; tests use memory). */
interface KeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
    /** Keys that start with [prefix] (e.g. "raw/"). */
    fun list(prefix: String): List<String>
}

class MemoryStore : KeyValueStore {
    val map = LinkedHashMap<String, String>()
    override fun read(key: String) = map[key]
    override fun write(key: String, value: String) { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
    override fun list(prefix: String) = map.keys.filter { it.startsWith(prefix) }
}

/** App-wide settings (front-end AppSettings). Kept per car. */
data class SettingsState(
    // Quiet | Sometimes | Off. "Sometimes" (chime + one praise line at a signal wait, twice a trip at most) is what the
    // submitted plan describes for 연수 모드, so it is the default; 평소 모드 is silent whatever is chosen here.
    val signal: String = "Sometimes",
    val insuranceShare: Boolean = false,
    val dataContribution: Boolean = false,
    val insuranceInviteSeen: Boolean = false,
)

class DeviceState(
    var firstRunDone: Boolean = false,
    var recordingConsent: Boolean = false,
    var settings: SettingsState = SettingsState(),
    val driverIds: MutableList<String> = ArrayList(),
    var currentDriverId: String = "",
    var selectedMissionIds: List<String> = emptyList(),
    var vehicle: VehicleMemory = VehicleMemory(),
    var serverUrl: String? = null,
)

object Codec {
    private fun counts(m: Map<String, IntArray>) = m.mapValues { listOf(it.value[0], it.value[1]) }
    @Suppress("UNCHECKED_CAST")
    private fun countsFrom(x: Any?): Map<String, IntArray> =
        ((x as? Map<String, Any?>) ?: emptyMap()).mapValues { (it.value as List<Any?>).let { l -> intArrayOf((l[0] as Number).toInt(), (l[1] as Number).toInt()) } }

    fun scene(s: SceneView): Map<String, Any?> = linkedMapOf<String, Any?>("id" to s.id, "time" to s.time, "place" to s.place, "what" to s.what, "good" to s.good, "compare" to s.compare)
        .also { if (s.disputed) it["disputed"] = true }      // written only when set: the trip facts sent to the server keep their shape
    @Suppress("UNCHECKED_CAST")
    fun sceneFrom(x: Any?): SceneView { val m = x as Map<String, Any?>
        return SceneView(m["id"] as String, m["time"] as String, m["place"] as String, m["what"] as String, m["good"] as Boolean, m["compare"] as String?, m["disputed"] as? Boolean ?: false) }

    fun driver(d: DriverState): String = Json.write(linkedMapOf(
        "id" to d.id, "name" to d.name, "mode" to d.mode, "first_trip_ms" to d.firstTripMs, "xp_total" to d.xpTotal,
        "atlas_name" to d.atlasName, "voice" to d.voice,
        "entries" to d.entries.map { linkedMapOf("id" to it.id, "ms" to it.ms, "kind" to it.kind, "points" to it.points, "ref" to it.ref) },
        "trips" to d.trips.map { t -> linkedMapOf(
            "id" to t.id, "start_ms" to t.startMs, "end_ms" to t.endMs, "km" to t.km, "safe_km" to t.safeKm, "blocked" to t.blocked,
            "counts" to counts(t.counts), "missions" to t.missionIds, "cleared" to t.clearedIds, "scenes" to t.scenes.map { scene(it) },
            "summary" to t.summary, "points" to t.points, "xp" to t.xp, "attended" to t.attended, "score" to t.score, "mode" to t.mode) },
        "profile" to d.profile.toMap(), "mission_clears" to d.missionClears,
        "insurance" to d.insurance?.let { linkedMapOf("safe_km" to it.safeKm, "goal_km" to it.goalKm, "renewal" to it.renewal.toString(), "max_discount" to it.maxDiscountPercent) },
        "quiz" to d.quiz?.let { linkedMapOf("date" to it.date.toString(), "id" to it.quizId, "picked" to it.picked) },
        "recommended" to d.recommended?.let { linkedMapOf("date" to it.first.toString(), "id" to it.second) },
        "exchanges" to d.exchanges.mapValues { it.value.name }, "last_tags" to d.lastTripTags.toList(),
        "cleared_before_ms" to d.clearedBeforeMs,
        "mode_notice" to d.modeNotice, "training_streak" to d.trainingStreak, "graduated" to d.graduated, "avatar" to d.avatar,
        "tour" to linkedMapOf("vin" to d.tour.vin, "done" to d.tour.done.toList(), "skipped" to d.tour.skipped.toList(), "offer" to d.tour.offer,
            "started_ms" to d.tour.startedMs, "finished_ms" to d.tour.finishedMs, "ever_skipped" to d.tour.everSkipped.toList()),
        "tour_rewarded" to d.tourRewarded,
        "experience" to d.experience, "car_familiar" to d.carFamiliar,
    ))

    @Suppress("UNCHECKED_CAST")
    fun driverFrom(text: String): DriverState {
        val m = Json.obj(text)
        fun L(x: Any?) = (x as Number).toLong()
        val d = DriverState(
            id = m["id"] as String, name = m["name"] as String, mode = m["mode"] as String,
            firstTripMs = (m["first_trip_ms"] as? Number)?.toLong(), xpTotal = (m["xp_total"] as Number).toInt(),
            atlasName = m["atlas_name"] as String, voice = m["voice"] as String,
        )
        (m["entries"] as List<Any?>).forEach { e -> e as Map<String, Any?>
            d.entries.add(LedgerEntry(e["id"] as String, L(e["ms"]), e["kind"] as String, (e["points"] as Number).toInt(), e["ref"] as String)) }
        (m["trips"] as List<Any?>).forEach { x -> x as Map<String, Any?>
            d.trips.add(TripLog(x["id"] as String, L(x["start_ms"]), L(x["end_ms"]), (x["km"] as Number).toDouble(), (x["safe_km"] as Number).toDouble(),
                x["blocked"] as Boolean, countsFrom(x["counts"]), (x["missions"] as List<Any?>).map { it as String },
                (x["cleared"] as List<Any?>).map { it as String }, (x["scenes"] as List<Any?>).map { sceneFrom(it) },
                x["summary"] as String, (x["points"] as Number).toInt(), (x["xp"] as Number).toInt(), x["attended"] as Boolean,
                (x["score"] as? Number)?.toInt() ?: -1, x["mode"] as? String ?: "Training")) }
        d.profile = DriverProfile.from(m["profile"] as Map<String, Any?>)
        (m["mission_clears"] as Map<String, Any?>).forEach { (k, v) -> d.missionClears[k] = (v as Number).toInt() }
        (m["insurance"] as? Map<String, Any?>)?.let { d.insurance = InsuranceState((it["safe_km"] as Number).toDouble(), (it["goal_km"] as Number).toInt(), YearMonth.parse(it["renewal"] as String), (it["max_discount"] as Number).toInt()) }
        (m["quiz"] as? Map<String, Any?>)?.let { d.quiz = QuizState(LocalDate.parse(it["date"] as String), it["id"] as String, (it["picked"] as? Number)?.toInt()) }
        (m["recommended"] as? Map<String, Any?>)?.let { d.recommended = LocalDate.parse(it["date"] as String) to it["id"] as String }
        ((m["exchanges"] as? Map<String, Any?>) ?: emptyMap()).forEach { (k, v) -> d.exchanges[k] = ExchangeResult.valueOf(v as String) }
        d.lastTripTags = ((m["last_tags"] as? List<Any?>) ?: emptyList()).map { it as String }.toSet()
        d.clearedBeforeMs = (m["cleared_before_ms"] as? Number)?.toLong() ?: 0
        d.modeNotice = m["mode_notice"] as? String
        d.trainingStreak = (m["training_streak"] as? Number)?.toInt() ?: 0
        d.graduated = m["graduated"] as? Boolean ?: false
        d.avatar = m["avatar"] as? String ?: "atlas"
        (m["tour"] as? Map<String, Any?>)?.let { t ->
            d.tour = TourState(t["vin"] as String?, (t["done"] as List<Any?>).map { it as String }.toMutableSet(),
                (t["skipped"] as List<Any?>).map { it as String }.toMutableSet(), t["offer"] as Boolean,
                (t["started_ms"] as? Number)?.toLong(), (t["finished_ms"] as? Number)?.toLong(),
                ((t["ever_skipped"] as? List<Any?>) ?: emptyList()).map { it as String }.toMutableSet())
        }
        d.tourRewarded = m["tour_rewarded"] as? Boolean ?: false
        d.experience = m["experience"] as? String
        d.carFamiliar = m["car_familiar"] as? Boolean
        return d
    }

    fun device(s: DeviceState): String = Json.write(linkedMapOf(
        "first_run_done" to s.firstRunDone, "recording_consent" to s.recordingConsent,
        "settings" to linkedMapOf("signal" to s.settings.signal, "insurance_share" to s.settings.insuranceShare,
            "data_contribution" to s.settings.dataContribution, "insurance_invite_seen" to s.settings.insuranceInviteSeen),
        "drivers" to s.driverIds, "current_driver" to s.currentDriverId, "selected_missions" to s.selectedMissionIds,
        "vehicle" to s.vehicle.toMap(), "server_url" to s.serverUrl,
    ))

    @Suppress("UNCHECKED_CAST")
    fun deviceFrom(text: String): DeviceState {
        val m = Json.obj(text)
        val st = m["settings"] as Map<String, Any?>
        return DeviceState(
            firstRunDone = m["first_run_done"] as Boolean, recordingConsent = m["recording_consent"] as Boolean,
            settings = SettingsState(st["signal"] as String, st["insurance_share"] as Boolean, st["data_contribution"] as Boolean, st["insurance_invite_seen"] as Boolean),
            driverIds = (m["drivers"] as List<Any?>).map { it as String }.toMutableList(),
            currentDriverId = m["current_driver"] as String,
            selectedMissionIds = (m["selected_missions"] as List<Any?>).map { it as String },
            vehicle = VehicleMemory.from(m["vehicle"] as Map<String, Any?>),
            serverUrl = m["server_url"] as String?,
        )
    }

    fun facts(f: TripFacts): Map<String, Any?> = linkedMapOf(
        "id" to f.id, "startMs" to f.startMs, "endMs" to f.endMs, "km" to f.km, "blocked" to f.blocked, "riskMoment" to f.riskMoment,
        "counts" to counts(f.counts), "tierXp" to f.tierXp, "missions" to f.missions.map { linkedMapOf("id" to it.first, "recommended" to it.second) },
        "rain" to f.rain, "night" to f.night, "scenes" to f.scenes.map { scene(it) }, "summary" to f.summary, "tags" to f.tags.sorted(),
        "identified" to f.identified, "weakestHabit" to f.weakestHabit, "score" to f.score,
    )

    @Suppress("UNCHECKED_CAST")
    fun factsFrom(m: Map<String, Any?>): TripFacts = TripFacts(
        id = m["id"] as String, startMs = (m["startMs"] as Number).toLong(), endMs = (m["endMs"] as Number).toLong(),
        km = (m["km"] as Number).toDouble(), blocked = m["blocked"] as Boolean, riskMoment = m["riskMoment"] as Boolean,
        counts = countsFrom(m["counts"]), tierXp = (m["tierXp"] as Number).toInt(),
        missions = (m["missions"] as List<Any?>).map { (it as Map<String, Any?>).let { x -> x["id"] as String to (x["recommended"] as Boolean) } },
        rain = m["rain"] as? Boolean ?: false, night = m["night"] as? Boolean ?: false,
        scenes = ((m["scenes"] as? List<Any?>) ?: emptyList()).map { sceneFrom(it) }, summary = m["summary"] as? String ?: "",
        tags = ((m["tags"] as? List<Any?>) ?: emptyList()).map { it as String }.toSet(), identified = m["identified"] as? Boolean ?: true,
        weakestHabit = m["weakestHabit"] as String?, score = (m["score"] as? Number)?.toInt() ?: 100,
    )
}
