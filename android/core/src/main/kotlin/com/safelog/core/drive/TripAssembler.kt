package com.safelog.core.drive

import com.safelog.core.contract.Trip
import com.safelog.core.engine.Judgment
import com.safelog.core.engine.Outcome
import com.safelog.core.engine.Scoring
import com.safelog.core.engine.TripResult
import com.safelog.core.ledger.Rules
import com.safelog.core.ledger.SceneView
import com.safelog.core.ledger.TripFacts
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** A place name from navigation (NaviHelper CurrentLocationInfo / road name) at trip time [t] seconds. */
data class PlaceMark(val t: Double, val name: String)

/**
 * Engine result -> the facts the ledger and the journal need. Only RELEASED habits are counted (held habits and
 * abstains never reach the driver). Scene texts follow the front-end rules: plain words, no units like g,
 * never scolding (section 9 of the front-end spec).
 */
object TripAssembler {
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    fun whatOf(j: Judgment): String {
        val v = j.value
        fun i(x: Double) = x.roundToInt()
        return if (j.outcome == Outcome.SUCCESS) when (j.habit) {
            "turn_signal_turn" -> "회전 ${i(v)}m 전 깜빡이"
            "lane_change_signal" -> "차선 변경 ${"%.1f".format(v).removeSuffix(".0")}초 전 깜빡이"
            "smooth_launch" -> "부드럽게 출발"
            "smooth_turn" -> "부드럽게 회전"
            "smooth_lane_change" -> "부드럽게 차선 변경"
            "school_zone" -> "스쿨존 ${i(v)}km 이하로 통과"
            "speed_limit" -> "제한속도 지키며 1분"
            "rain_speed" -> "빗길 80% 속도로"
            "caution_slowdown" -> "주의 구간 미리 감속"
            "speed_bump" -> "과속방지턱 천천히"
            "railroad_stop" -> "철길 앞 일시정지"
            "headlights" -> "어두울 때 전조등"
            "seat_belts" -> "모두 안전벨트"
            "rest_breaks" -> "2시간 전에 쉬어 감"
            "blinker_cancel" -> "깜빡이 바로 정리"
            "right_turn_pause" -> "우회전 전 멈춤"
            else -> Scoring.label(j.habit)
        } else when (j.habit) {            // "다음 미션" scene (training mode only): framed as what to try, never as a fault
            "turn_signal_turn" -> "회전 30m 전에 켜 보기"
            "lane_change_signal" -> "3초 전에 깜빡이 켜 보기"
            "smooth_launch" -> "천천히 출발해 보기"
            "smooth_turn" -> "천천히 돌아 보기"
            "smooth_lane_change" -> "여유 있게 차선 바꾸기"
            "school_zone" -> "스쿨존 30km로 가 보기"
            "speed_limit" -> "제한속도 안에서 달려 보기"
            "rain_speed" -> "빗길에서 조금 더 천천히"
            "speed_bump" -> "방지턱 앞에서 천천히"
            "caution_slowdown" -> "주의 구간 미리 줄이기"
            "headlights" -> "어두워지면 전조등 켜기"
            "seat_belts" -> "모두 안전벨트 매기"
            else -> Scoring.label(j.habit)
        }
    }

    private fun placeFallback(j: Judgment, trip: Trip): String = when (j.habit) {
        "turn_signal_turn", "smooth_turn", "right_turn_pause", "blinker_cancel" -> "교차로"
        "school_zone" -> "어린이보호구역"
        "railroad_stop" -> "철길 건널목"
        "speed_bump" -> "과속방지턱 구간"
        "caution_slowdown" -> "주의 구간"
        "rest_breaks" -> "휴게소"
        else -> when (trip.i("road_class")[j.iRef.coerceIn(0, trip.n - 1)]) { 2 -> "고속도로"; 1 -> "국도"; else -> "시내 도로" }
    }

    fun placeAt(places: List<PlaceMark>, t: Double): String? = places.lastOrNull { it.t <= t }?.name

    /** Habits that are a state or a whole-window ratio make dull journal scenes; moments come first. */
    private val STATE_HABITS = setOf("seat_belts", "headlights", "speed_limit", "steady_cruise", "blinker_cancel")

    /** Journal scenes: up to 2 moments kept with the widest margin (one per habit), falling back to the engine's pick. */
    fun journalScenes(result: TripResult, hold: Set<String>, k: Int = 2): List<Judgment> {
        val succ = result.judgments.filter { it.outcome == Outcome.SUCCESS && Scoring.tier(it.habit) != "X" && it.habit !in hold }
        val moments = succ.filter { it.habit !in STATE_HABITS }.sortedBy { -(it.margin * Scoring.weight(it.habit)) }
        val out = ArrayList<Judgment>()
        val seen = HashSet<String>()
        for (j in moments + Scoring.bestScenes(succ, k)) { if (out.size < k && seen.add(j.habit)) out.add(j) }
        return out
    }

    fun summary(startMs: Long, durationS: Double, rain: Boolean): String {
        val h = Instant.ofEpochMilli(startMs).atZone(Rules.ZONE).hour
        val part = when (h) { in 5..9 -> "아침"; in 10..15 -> "낮"; in 16..19 -> "저녁"; else -> "밤" }
        val min = maxOf(1, (durationS / 60).roundToInt())
        val dur = if (min >= 60) "${min / 60}시간${if (min % 60 > 0) " ${min % 60}분" else ""}" else "${min}분"
        return listOfNotNull(part, if (rain) "비" else null, dur).joinToString(" · ")
    }

    fun facts(
        id: String, trip: Trip, result: TripResult, startMs: Long, missions: List<Pair<String, Boolean>>,
        places: List<PlaceMark>, training: Boolean, hold: Set<String>, identified: Boolean = true,
    ): TripFacts {
        val counts = LinkedHashMap<String, IntArray>()
        for (j in result.judgments) {
            // held habits are never shown, not even as a ratio (a held habit's misses would read as a bad habit)
            if (j.outcome == Outcome.ABSTAIN || j.habit in hold) continue
            val c = counts.getOrPut(j.habit) { IntArray(2) }
            c[1]++
            if (j.outcome == Outcome.SUCCESS) c[0]++
        }
        val dur = if (trip.n > 1) trip.t[trip.n - 1] - trip.t[0] else 0.0
        fun scene(j: Judgment, good: Boolean, k: Int) = SceneView(
            id = "$id-$k",
            time = Instant.ofEpochMilli(startMs + (j.tRef * 1000).toLong()).atZone(Rules.ZONE).format(HHMM),
            place = placeAt(places, j.tRef) ?: placeFallback(j, trip),
            what = whatOf(j), good = good, compare = null,
        )
        val scenes = ArrayList<SceneView>()
        journalScenes(result, hold).forEachIndexed { k, j -> scenes.add(scene(j, true, k + 1)) }
        if (training) {
            // one "다음 미션" scene: a released, non-bonus habit that did not work out, highest tier first
            val miss = result.judgments.filter { it.outcome == Outcome.MISS && Scoring.tier(it.habit) != "X" && it.habit !in hold }
                .maxByOrNull { Scoring.weight(it.habit) }
            if (miss != null) scenes.add(scene(miss, false, scenes.size + 1))
        }
        scenes.sortBy { it.time }
        val wiper = trip.i("wiper_active"); val night = trip.i("night")
        val rain = (0 until trip.n).count { wiper[it] == 1 } > trip.n / 4
        val dark = (0 until trip.n).count { night[it] == 1 } > trip.n / 4
        val tags = HashSet<String>()
        if (rain) tags.add("rain")
        if (dark) tags.add("night")
        if ((counts["turn_signal_turn"]?.get(1) ?: 0) > 0) tags.add("turn")
        if ((counts["lane_change_signal"]?.get(1) ?: 0) > 0) tags.add("lane")
        if (counts.containsKey("school_zone")) tags.add("school")
        if (counts.containsKey("railroad_stop")) tags.add("railroad")
        if (dur > 90 * 60) tags.add("long")
        val tierXp = Scoring.tripRewards(result.judgments.filter { it.habit !in hold }, result.risk, emptyList()).xp
        return TripFacts(
            id = id, startMs = startMs, endMs = startMs + (dur * 1000).toLong(), km = result.risk.km,
            blocked = result.risk.rewardBlocked, riskMoment = result.risk.anyRiskMoment, counts = counts,
            tierXp = tierXp, missions = missions, rain = rain, night = dark, scenes = scenes,
            summary = summary(startMs, dur, rain), tags = tags, identified = identified,
            score = TripScore.of(result.risk, counts),
            metrics = metrics(result, hold),
        )
    }

    private const val MAX_METRICS = 300

    /**
     * Summary indicators (data contribution): for every judged situation its type, whether the driver handled it,
     * the measured value (blinker lead in m or s, speed in a zone, peak g ...), when in the trip, and the context.
     * Released habits only, no raw signals, no place names.
     */
    fun metrics(result: TripResult, hold: Set<String>): List<Map<String, Any?>> =
        result.judgments.filter { it.outcome != Outcome.ABSTAIN && it.habit !in hold }.take(MAX_METRICS).map { j ->
            linkedMapOf(
                "habit" to j.habit, "success" to (j.outcome == Outcome.SUCCESS),
                "value" to j.value.takeIf { !it.isNaN() && !it.isInfinite() }?.let { Math.round(it * 100) / 100.0 },
                "tS" to Math.round(j.tRef),
                "rain" to (j.context["rain"] ?: 0), "night" to (j.context["night"] ?: 0),
                "congestion" to (j.context["congestion"] ?: 0), "roadClass" to (j.context["road_class"] ?: 0),
            )
        }
}

/**
 * 이번 주행 점수 (plan F6: 100에서 시작해 감점) [팀 기준 초기값]. It only drives the 연수 모드 switch and is
 * never put in a screen value. Forced events (cut-in, FCA-preceded braking) are not deducted.
 */
object TripScore {
    const val HARD = 8           // 급제동·급가속 (unforced), 10 s speeding
    const val SEVERE_EXTRA = 7   // on top of HARD for severe ones
    const val LATERAL = 4
    const val SCHOOL_ZONE = 20
    const val FCA = 10
    const val NO_SIGNAL = 3      // 깜빡이 없는 회전·차선 변경 (released habits only)

    fun of(r: com.safelog.core.engine.RiskSummary, counts: Map<String, IntArray>): Int {
        var s = 100
        s -= HARD * (r.hardBrake + r.hardAccel + r.speeding10s)
        s -= SEVERE_EXTRA * (r.severeBrake + r.severeAccel + r.severeSpeeding10s)
        s -= LATERAL * r.hardLateral
        s -= SCHOOL_ZONE * r.schoolZoneOverspeed
        s -= FCA * r.fcaWarnings
        for (h in listOf("turn_signal_turn", "lane_change_signal")) counts[h]?.let { s -= NO_SIGNAL * (it[1] - it[0]) }
        return s.coerceIn(0, 100)
    }
}
