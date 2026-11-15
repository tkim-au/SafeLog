package com.safelog.core.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Port of scoring_engine/scoring.py: tiers, XP, the Beta habit profile and the post-trip "good points".
 * Rewards are paid on successes, never on distance, and are blocked on risky trips.
 */
object Scoring {
    val TIER = mapOf(
        "smooth_stop" to "A", "speed_limit" to "A", "school_zone" to "A", "following_margin" to "A", "rain_speed" to "A",
        "turn_signal_turn" to "B", "lane_change_signal" to "B", "seat_belts" to "B", "railroad_stop" to "B", "headlights" to "B",
        "smooth_turn" to "B", "lane_keeping" to "B", "rest_breaks" to "B",
        "smooth_launch" to "C", "anticipatory_braking" to "C", "smooth_lane_change" to "C", "speed_bump" to "C",
        "caution_slowdown" to "C", "steady_cruise" to "C", "blinker_cancel" to "C",
        "right_turn_pause" to "X", "hazard_warning" to "X", "fca_reaction" to "X",
    )
    val TIER_WEIGHT = mapOf("A" to 3.0, "B" to 2.0, "C" to 1.0, "X" to 0.0)
    val TIER_XP = mapOf("A" to 3, "B" to 2, "C" to 1, "X" to 5)
    const val POP_PRIOR = 0.7
    const val PRIOR_STRENGTH = 4.0
    const val HALF_LIFE_TRIPS = 15
    const val MASTERY_TARGET = 0.80
    const val MIN_OPPS_TO_REPORT = 3
    const val PER_TRIP_CAP = 10
    val CONTEXT_MULT = linkedMapOf("rain" to 1.5, "night" to 1.25)

    val KO_LABEL = mapOf(
        "smooth_stop" to "부드러운 정지", "smooth_launch" to "부드러운 출발", "anticipatory_braking" to "미리 감속",
        "turn_signal_turn" to "회전 전 깜빡이", "smooth_turn" to "부드러운 회전", "right_turn_pause" to "우회전 전 멈춤",
        "lane_change_signal" to "차선 변경 3초 전 깜빡이", "smooth_lane_change" to "부드러운 차선 변경",
        "school_zone" to "스쿨존 30km/h", "speed_bump" to "과속방지턱 서행", "railroad_stop" to "철길 앞 일시정지",
        "caution_slowdown" to "주의 구간 감속", "hazard_warning" to "비상등으로 뒤차 배려", "fca_reaction" to "위기 대응",
        "speed_limit" to "제한속도 지키기", "rain_speed" to "빗길 20% 감속", "headlights" to "전조등 켜기",
        "following_margin" to "여유 있는 차간거리", "lane_keeping" to "차로 안정 유지", "seat_belts" to "모두 안전벨트",
        "rest_breaks" to "2시간마다 휴식", "steady_cruise" to "일정한 속도 유지", "blinker_cancel" to "깜빡이 정리",
    )

    fun tier(h: String) = TIER[h] ?: "C"
    fun weight(h: String) = TIER_WEIGHT[tier(h)] ?: 1.0
    fun label(h: String) = KO_LABEL[h] ?: h

    data class GoodPoint(val habit: String, val label: String, val text: String, val success: Int, val chances: Int)

    data class PracticeResult(val success: Int, val opportunities: Int, val passed: Boolean)

    data class TripRewards(
        val goodPoints: List<GoodPoint>,
        val allGoodPoints: List<GoodPoint>,
        val xp: Int,
        val rewardBlocked: Boolean,
        val practice: Map<String, PracticeResult>,
        /** habit -> [successes, opportunities] for released habits (abstains excluded). */
        val counts: Map<String, IntArray>,
    )

    fun tripRewards(judgments: List<Judgment>, risk: RiskSummary?, practice: List<String> = emptyList()): TripRewards {
        val by = LinkedHashMap<String, IntArray>()
        var xp = 0.0
        val capped = HashMap<String, Int>()
        for (j in judgments) {
            if (j.outcome == Outcome.ABSTAIN) continue
            val c = by.getOrPut(j.habit) { IntArray(2) }
            c[1] += 1
            if (j.outcome == Outcome.SUCCESS) {
                c[0] += 1
                if ((capped[j.habit] ?: 0) < PER_TRIP_CAP) {
                    var m = 1.0
                    for ((k, mult) in CONTEXT_MULT) if ((j.context[k] ?: 0) != 0) m *= mult
                    xp += TIER_XP.getValue(tier(j.habit)) * minOf(m, 2.0)
                    capped[j.habit] = (capped[j.habit] ?: 0) + 1
                }
            }
        }
        val lines = ArrayList<GoodPoint>()
        for ((h, c) in by.entries.sortedBy { -weight(it.key) * it.value[0] }) {
            val (s, n) = c[0] to c[1]
            if (s == 0) continue
            if (tier(h) == "X") lines.add(GoodPoint(h, label(h), "${label(h)} ${s}번", s, n))
            else if (n >= MIN_OPPS_TO_REPORT) lines.add(GoodPoint(h, label(h), "${label(h)} ${n}번 중 ${s}번", s, n))
        }
        val pr = LinkedHashMap<String, PracticeResult>()
        for (p in practice) {
            val c = by[p] ?: IntArray(2)
            val passed = c[1] >= MIN_OPPS_TO_REPORT && c[0].toDouble() / maxOf(c[1], 1) >= 0.8
            pr[p] = PracticeResult(c[0], c[1], passed)
            if (passed) xp += 10
        }
        val blocked = risk?.rewardBlocked == true
        return TripRewards(lines.take(2), lines, if (blocked) 0 else Math.rint(xp).toInt(), blocked, pr, by)
    }

    /** Driving-journal scenes: the successes kept with the widest margin, one per habit. */
    fun bestScenes(judgments: List<Judgment>, k: Int = 2): List<Judgment> {
        val seen = HashSet<String>()
        val out = ArrayList<Judgment>()
        val succ = judgments.filter { it.outcome == Outcome.SUCCESS && tier(it.habit) != "X" }
            .sortedBy { -(it.margin * weight(it.habit)) }
        for (j in succ) {
            if (seen.add(j.habit)) out.add(j)
            if (out.size == k) break
        }
        return out
    }
}

/** Beta-Binomial habit state with exponential forgetting (port of HabitState). */
class HabitState(
    var a: Double = Scoring.POP_PRIOR * Scoring.PRIOR_STRENGTH,
    var b: Double = (1 - Scoring.POP_PRIOR) * Scoring.PRIOR_STRENGTH,
    var nSuccess: Int = 0,
    var nOpps: Int = 0,
    var streak: Int = 0,
    var bestStreak: Int = 0,
) {
    val mean get() = a / (a + b)
    val lcb get() = BetaDist.ppf(0.10, a, b)

    fun toMap(): Map<String, Any?> = linkedMapOf("a" to a, "b" to b, "n_success" to nSuccess, "n_opps" to nOpps, "streak" to streak, "best_streak" to bestStreak)

    companion object {
        fun from(m: Map<String, Any?>) = HabitState(
            (m["a"] as Number).toDouble(), (m["b"] as Number).toDouble(), (m["n_success"] as Number).toInt(),
            (m["n_opps"] as Number).toInt(), (m["streak"] as? Number)?.toInt() ?: 0, (m["best_streak"] as? Number)?.toInt() ?: 0,
        )
    }
}

class DriverProfile(val habits: MutableMap<String, HabitState> = linkedMapOf(), var trips: Int = 0) {
    fun update(judgments: List<Judgment>) {
        val lam = 0.5.pow(1.0 / Scoring.HALF_LIFE_TRIPS)
        val by = LinkedHashMap<String, MutableList<Int>>()
        for (j in judgments) if (j.outcome != Outcome.ABSTAIN) by.getOrPut(j.habit) { ArrayList() }.add(j.outcome)
        val pa = Scoring.POP_PRIOR * Scoring.PRIOR_STRENGTH
        val pb = (1 - Scoring.POP_PRIOR) * Scoring.PRIOR_STRENGTH
        for ((h, outs) in by) {
            val st = habits.getOrPut(h) { HabitState() }
            st.a = pa + lam * (st.a - pa)
            st.b = pb + lam * (st.b - pb)
            val s = outs.count { it == Outcome.SUCCESS }
            val f = if (Scoring.tier(h) == "X") 0 else outs.size - s
            st.a += s; st.b += f
            st.nSuccess += s; st.nOpps += outs.size
            for (o in outs) { st.streak = if (o == Outcome.SUCCESS) st.streak + 1 else 0; st.bestStreak = maxOf(st.bestStreak, st.streak) }
        }
        trips += 1
    }

    fun skillIndex(): Double {
        var num = 0.0; var den = 0.0
        for ((h, st) in habits) {
            val w = Scoring.weight(h)
            if (w == 0.0 || st.nOpps < 5) continue
            num += w * st.mean; den += w
        }
        return if (den > 0) 100 * num / den else Double.NaN
    }

    fun mastered(): List<String> = habits.filter { (h, st) -> Scoring.tier(h) != "X" && st.nOpps >= 5 && st.lcb >= Scoring.MASTERY_TARGET }.keys.toList()

    /** Weakest habit with enough data, framed positively (practice mode only). */
    fun weakestHabit(allowed: Set<String>? = null): String? =
        habits.filter { (h, st) -> Scoring.tier(h) != "X" && st.nOpps >= Scoring.MIN_OPPS_TO_REPORT && (allowed == null || h in allowed) }
            .minByOrNull { it.value.mean }?.key

    fun toMap(): Map<String, Any?> = linkedMapOf("trips" to trips, "habits" to habits.mapValues { it.value.toMap() })

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun from(m: Map<String, Any?>): DriverProfile = DriverProfile(
            ((m["habits"] as? Map<String, Any?>) ?: emptyMap()).mapValues { HabitState.from(it.value as Map<String, Any?>) }.toMutableMap(),
            (m["trips"] as? Number)?.toInt() ?: 0,
        )
    }
}

/** Regularised incomplete beta and its inverse (enough for the 10th-percentile mastery bound). */
object BetaDist {
    private fun lgamma(x: Double): Double {
        val g = 7.0
        val c = doubleArrayOf(0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7)
        if (x < 0.5) return ln(Math.PI / kotlin.math.sin(Math.PI * x)) - lgamma(1 - x)
        val xx = x - 1
        var a = c[0]
        val t = xx + g + 0.5
        for (i in 1 until 9) a += c[i] / (xx + i)
        return 0.5 * ln(2 * Math.PI) + (xx + 0.5) * ln(t) - t + ln(a)
    }

    private fun cf(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-300
        var c = 1.0
        var d = 1 - (a + b) * x / (a + 1)
        if (abs(d) < tiny) d = tiny
        d = 1 / d
        var h = d
        for (m in 1..300) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2))
            d = 1 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1 / d; h *= d * c
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1))
            d = 1 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1 / d
            val del = d * c
            h *= del
            if (abs(del - 1) < 1e-14) break
        }
        return h
    }

    fun cdf(x: Double, a: Double, b: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        val bt = exp(lgamma(a + b) - lgamma(a) - lgamma(b) + a * ln(x) + b * ln(1 - x))
        return if (x < (a + 1) / (a + b + 2)) bt * cf(x, a, b) / a else 1 - bt * cf(1 - x, b, a) / b
    }

    fun ppf(p: Double, a: Double, b: Double): Double {
        var lo = 0.0; var hi = 1.0
        repeat(100) {
            val mid = (lo + hi) / 2
            if (cdf(mid, a, b) < p) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }
}
