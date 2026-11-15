package com.safelog.core.ledger

import com.safelog.core.engine.DriverProfile
import com.safelog.core.engine.Scoring
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * The facts of one finished trip that the reward rules need. This is what the car sends to the server
 * (POST /drives/{id}/finish); no raw driving data leaves the car.
 */
data class TripFacts(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val km: Double,
    /** reward gate: >20 % speeding, school-zone overspeed, FCA, or >= 2 severe events (internal only) */
    val blocked: Boolean,
    /** any risk moment (hard event, speeding): such a trip adds no safe km */
    val riskMoment: Boolean,
    /** released habits only: habit -> [successes, opportunities] (abstains excluded) */
    val counts: Map<String, IntArray>,
    /** tier XP from the engine (successes x tier XP x context, capped 10 per habit), before mission XP */
    val tierXp: Int,
    /** missions chosen before driving: id -> recommended */
    val missions: List<Pair<String, Boolean>>,
    val rain: Boolean = false,
    val night: Boolean = false,
    val scenes: List<SceneView> = emptyList(),
    val summary: String = "",
    /** quiz topics related to this drive (rain, turn, lane, school, railroad, long) */
    val tags: Set<String> = emptySet(),
    /** driver chosen ("운전자 미확인" trips earn nothing and add no mileage) */
    val identified: Boolean = true,
    /** weakest mission habit in the driver's profile BEFORE this trip (for "다음 미션"), computed on the car */
    val weakestHabit: String? = null,
    /** trip score 0..100 (100 minus risk deductions, [TripScore]). Internal only: used for the 연수 모드 switch */
    val score: Int = 100,
    /**
     * Summary indicators for the data-contribution programme (submitted plan, sections 5 and 6-3): one entry per
     * judged situation (what it was, how the driver handled it, the measured lead / speed, the context). No raw
     * signals. They leave the car only when the driver turned 데이터 기여 on; they are NOT part of [Codec.facts].
     */
    val metrics: List<Map<String, Any?>> = emptyList(),
)

class LedgerEntry(val id: String, val ms: Long, val kind: String, val points: Int, val ref: String = "")

class TripLog(
    val id: String, val startMs: Long, val endMs: Long, val km: Double, val safeKm: Double, val blocked: Boolean,
    val counts: Map<String, IntArray>, val missionIds: List<String>, val clearedIds: List<String>,
    var scenes: List<SceneView>, val summary: String, val points: Int, val xp: Int, val attended: Boolean,
    /** internal trip score (-1: driver not identified), and the mode the trip was driven in */
    val score: Int = -1, val mode: String = "Training",
) {
    val date: LocalDate get() = Rules.today(startMs)
}

class InsuranceState(var safeKm: Double, val goalKm: Int, val renewal: YearMonth, val maxDiscountPercent: Int)

class QuizState(val date: LocalDate, val quizId: String, var picked: Int?)

/** Everything kept for one driver (on the car; the server keeps the ledger and summaries). */
class DriverState(
    val id: String,
    var name: String,
    var mode: String = "Training",
    var firstTripMs: Long? = null,
    var xpTotal: Int = 0,
    var atlasName: String = "아틀라스",
    var voice: String = "basic",
    val entries: MutableList<LedgerEntry> = ArrayList(),
    val trips: MutableList<TripLog> = ArrayList(),
    var profile: DriverProfile = DriverProfile(),
    val missionClears: MutableMap<String, Int> = LinkedHashMap(),
    var insurance: InsuranceState? = null,
    var quiz: QuizState? = null,
    var recommended: Pair<LocalDate, String>? = null,
    val exchanges: MutableMap<String, ExchangeResult> = LinkedHashMap(),
    var lastTripTags: Set<String> = emptySet(),
    /** trips removed by "기록 지우기" still count for points / grade / insurance (only the journal is cleared) */
    var clearedBeforeMs: Long = 0,
    /** set by the ENGINE after a trip: "TrainingOffer" (it suggests 연수 모드, the driver decides) or "TrainingOff"
     *  (graduated, back to 평소 모드). The parked home shows it once. */
    var modeNotice: String? = null,
    /** consecutive training trips whose chosen missions all passed */
    var trainingStreak: Int = 0,
    /** the first graduation from training mode was rewarded */
    var graduated: Boolean = false,
    /** "atlas" | "letter:<0..5>" | "photo:<path on this device>" (photos stay on the car) */
    var avatar: String = "atlas",
    var tour: TourState = TourState(),
    var tourRewarded: Boolean = false,
    /** profile step answer, one of [Experience.ALL]; null = not asked yet (drivers from before the step existed) */
    var experience: String? = null,
    /** profile step answer: has driven this car before; null = not asked yet */
    var carFamiliar: Boolean? = null,
) {
    val balance: Int get() = entries.sumOf { it.points }
    val level get() = Rules.levelFor(xpTotal)

    fun earnedInMonth(ym: YearMonth): Int = entries.filter { it.points > 0 && YearMonth.from(Rules.today(it.ms)) == ym }.sumOf { it.points }

    fun safeMileageKm(nowMs: Long): Double {
        val from = Rules.today(nowMs).minusMonths(12)
        return trips.filter { !it.date.isBefore(from) }.sumOf { it.safeKm }
    }
}

data class Settlement(val arrival: ArrivalView, val log: TripLog, val pointsBreakdown: Map<String, Int>)

/**
 * 운전 경력, asked once in the profile step (submitted plan, sections 3-1 and 4). A new or returning driver starts
 * in 연수 모드, an experienced one in 평소 모드; either can switch by hand afterwards.
 */
object Experience {
    const val NOVICE = "Novice"
    const val RETURNING = "Returning"
    const val EXPERIENCED = "Experienced"
    val ALL = listOf(NOVICE, RETURNING, EXPERIENCED)
    fun startMode(experience: String) = if (experience == EXPERIENCED) "Normal" else "Training"
}

/**
 * The reward ledger. Pure and deterministic given `nowMs`, so the server (Python) and the car (Kotlin) produce the
 * same numbers for the same facts (golden cases in backend/contract/ledger_cases.json).
 */
object Ledger {
    const val INSURANCE_GOAL_KM = 1000          // 보험사 조건 거리 [가정]
    const val INSURANCE_MAX_DISCOUNT = 10       // 보험사 공시 최대 할인율 [가정]
    val missionHabits get() = MissionCatalog.ALL.map { it.habit }.toSet()

    // ---------------------------------------------------------------------------------------------------- settle
    fun settle(d: DriverState, f: TripFacts, insuranceShare: Boolean, shop: List<ShopEntry>, nowMs: Long = f.endMs): Settlement {
        val date = Rules.today(f.startMs)
        val ym = YearMonth.from(date)
        val breakdown = LinkedHashMap<String, Int>()
        if (d.firstTripMs == null && f.identified) d.firstTripMs = f.startMs
        fun earn(kind: String, amount: Int) {
            if (amount <= 0) return
            val got = Rules.capped(d.earnedInMonth(ym), amount)
            if (got > 0) {
                d.entries.add(LedgerEntry("${f.id}:$kind", f.endMs, kind, got, f.id))
                breakdown[kind] = (breakdown[kind] ?: 0) + got
            }
        }
        // missions
        val missionResults = f.missions.mapNotNull { (id, rec) -> MissionCatalog.byId(id)?.let { m ->
            val c = f.counts[m.habit] ?: IntArray(2)
            Triple(m, rec, c)
        } }
        val cleared = missionResults.filter { (m, _, c) -> m.passed(c[0], c[1]) }
        val earns = f.identified && !f.blocked
        var attended = false
        if (earns) {
            // ① attendance: first safe trip of the day
            val alreadyToday = d.trips.any { it.attended && it.date == date }
            if (!alreadyToday) {
                attended = true
                val firstMonth = ChronoUnit.DAYS.between(Rules.today(d.firstTripMs!!), date) < Rules.FIRST_MONTH_DAYS
                earn("attendance", if (firstMonth) Rules.ATTEND_FIRST_MONTH else Rules.ATTEND_LATER)
                // weekly stack: the 3rd attended day of the week
                val wk = Rules.weekStart(date)
                val daysBefore = d.trips.filter { it.attended && Rules.weekStart(it.date) == wk }.map { it.date }.toSet().size
                if (daysBefore + 1 == Rules.WEEK_DAYS_FOR_ATTENDANCE) {
                    earn("week", Rules.WEEK_BONUS)
                    val streak = weekStreak(d, date, includeCurrent = true, extraDay = date)
                    if (streak == 4) earn("streak4", Rules.STREAK4_BONUS)
                    if (streak >= 8 && streak % 4 == 0) earn("streak8", Rules.STREAK8_BONUS)
                }
            }
            // ② missions (+ stamps every 5 clears of the same mission)
            for ((m, rec, _) in cleared) {
                earn("mission:${m.id}", if (rec) m.points * 2 else m.points)
                val n = (d.missionClears[m.id] ?: 0) + 1
                d.missionClears[m.id] = n
                if (n % Rules.STAMP_EVERY == 0) earn("stamp:${m.id}", Rules.STAMP_BONUS)
            }
        }
        // ③ 연수 모드 (submitted plan, section 3-2): a weak score only SUGGESTS it and the driver turns it on;
        //    three missions passed in a row with a good average graduate back to 평소 모드
        val modeBefore = d.mode
        var graduation = false
        if (f.identified) {
            val avg = Rules.recentScore(d.trips.filter { it.score >= 0 }.map { it.score } + f.score)
            if (d.mode != "Training") {
                if (avg < Rules.TRAINING_ON_AVG || f.score < Rules.TRAINING_ON_TRIP) d.modeNotice = "TrainingOffer"
            } else {
                if (f.blocked) d.trainingStreak = 0
                else if (missionResults.isNotEmpty()) d.trainingStreak = if (cleared.size == missionResults.size) d.trainingStreak + 1 else 0
                if (d.trainingStreak >= Rules.TRAINING_OFF_STREAK && avg >= Rules.TRAINING_OFF_AVG) {
                    d.mode = "Normal"; d.modeNotice = "TrainingOff"; d.trainingStreak = 0
                    if (earns && !d.graduated) {
                        d.graduated = true; graduation = true
                        earn("graduation", Rules.GRADUATION_POINTS)
                    }
                }
            }
        }
        val points = breakdown.values.sum()
        val missionXp = cleared.sumOf { (_, rec, _) -> if (rec) Rules.MISSION_XP * 2 else Rules.MISSION_XP }
        val xp = if (earns) f.tierXp + missionXp + (if (graduation) Rules.GRADUATION_XP else 0) else 0
        d.xpTotal += xp
        val safeKm = if (f.identified && !f.blocked && !f.riskMoment) f.km else 0.0
        if (insuranceShare && f.identified) {
            val ins = d.insurance ?: newInsurance(nowMs).also { d.insurance = it }
            ins.safeKm += safeKm
        }
        val log = TripLog(f.id, f.startMs, f.endMs, f.km, safeKm, f.blocked, f.counts, f.missions.map { it.first },
            cleared.map { it.first.id }, f.scenes, f.summary, points, xp, attended, if (f.identified) f.score else -1, modeBefore)
        d.trips.add(log)
        d.lastTripTags = f.tags
        // the Beta profile is updated by the caller with the engine judgments (DriverProfile.update)

        // arrival (all values AFTER this trip)
        val good = goodHabits(f.counts)
        val first = missionResults.firstOrNull()
        val practicePoints = cleared.sumOf { (m, rec, _) -> breakdown["mission:${m.id}"] ?: 0 }
        val arrival = ArrivalView(
            topHabit = good.firstOrNull(),
            otherHabits = good.drop(1).take(3),
            points = points,
            xp = xp,
            xpToNextLevel = Rules.xpToNextLevel(d.xpTotal),
            nextReward = nextReward(d, shop, nowMs),
            practiceResult = first?.let { (m, _, c) -> GoodHabitView(m.title, c[0], c[1]) },
            nextChallenge = missionResults.firstOrNull { it !in cleared }?.first?.title
                ?: f.weakestHabit?.let { MissionCatalog.byHabit(it)?.title },
            practiceCleared = first != null && cleared.any { it.first.id == first.first.id },
            practicePoints = practicePoints,
            noRewardReason = when {
                !f.identified -> "운전자를 고르면 다음부터 쌓여요"
                f.blocked -> "다음 주행에서 다시 모아 봐요"
                else -> null
            },
            insurance = if (insuranceShare) insuranceView(d, nowMs) else null,
            safeKmToday = safeKm.roundToInt(),
        )
        return Settlement(arrival, log, breakdown)
    }

    /** Good points for the arrival screen: habits with at least one success, tier weight x successes, >= 3 chances. */
    fun goodHabits(counts: Map<String, IntArray>): List<GoodHabitView> =
        counts.entries.filter { (h, c) -> c[0] > 0 && (Scoring.tier(h) == "X" || c[1] >= Scoring.MIN_OPPS_TO_REPORT) }
            .sortedBy { (h, c) -> -Scoring.weight(h) * c[0] }
            .map { (h, c) -> GoodHabitView(Scoring.label(h), c[0], if (Scoring.tier(h) == "X") c[0] else c[1]) }

    fun newInsurance(nowMs: Long): InsuranceState =
        InsuranceState(0.0, INSURANCE_GOAL_KM, YearMonth.from(Rules.today(nowMs)).plusMonths(12), INSURANCE_MAX_DISCOUNT)

    fun insuranceView(d: DriverState, nowMs: Long): InsuranceView? {
        val ins = d.insurance ?: return null
        val now = YearMonth.from(Rules.today(nowMs))
        val recent = d.trips.takeLast(10).filter { it.km > 0.5 }
        val avg = if (recent.isEmpty()) 30 else maxOf(1, (recent.sumOf { it.km } / recent.size).roundToInt())
        return InsuranceView(
            safeKm = ins.safeKm.toInt(), goalKm = ins.goalKm, renewal = "${ins.renewal.year}년 ${ins.renewal.monthValue}월",
            monthsToRenewal = maxOf(0, ChronoUnit.MONTHS.between(now, ins.renewal).toInt()), avgTripKm = avg,
            maxDiscountPercent = ins.maxDiscountPercent,
        )
    }

    // ---------------------------------------------------------------------------------------------------- briefing
    fun grade(d: DriverState, nowMs: Long) = Rules.Grade.of(d.safeMileageKm(nowMs))

    fun nextReward(d: DriverState, shop: List<ShopEntry>, nowMs: Long): NextRewardView {
        val g = grade(d, nowMs)
        val bal = d.balance
        val items = shop.filter { it.category != "Atlas" }.map { it to Rules.cost(it.price, g) }.sortedBy { it.second }
        val target = items.firstOrNull { it.second > bal } ?: items.lastOrNull() ?: return NextRewardView("교환소", 0, 1, "", null)
        return NextRewardView(target.first.name, maxOf(0, target.second - bal), target.second, target.first.art, target.first.image)
    }

    fun recommendedMission(d: DriverState, nowMs: Long, raining: Boolean): Mission {
        val date = Rules.today(nowMs)
        d.recommended?.let { (day, id) -> if (day == date) MissionCatalog.byId(id)?.let { return it } }
        val offered = MissionCatalog.ALL.filter { !it.rainOnly || raining }
        val weak = d.profile.weakestHabit(offered.map { it.habit }.toSet())
        val m = offered.firstOrNull { it.habit == weak } ?: offered[(date.toEpochDay() % offered.size).toInt()]
        d.recommended = date to m.id
        return m
    }

    fun briefing(d: DriverState, shop: List<ShopEntry>, nowMs: Long, raining: Boolean, night: Boolean): BriefingView {
        val rec = recommendedMission(d, nowMs, raining)
        val offered = MissionCatalog.ALL.filter { !it.rainOnly || raining }
        val today = Rules.today(nowMs)
        val wk = Rules.weekStart(today)
        val weekTrips = d.trips.filter { Rules.weekStart(it.date) == wk }
        val tries = weekTrips.sumOf { it.missionIds.size }
        val clears = weekTrips.sumOf { it.clearedIds.size }
        val attendedDays = weekTrips.filter { it.attended }.map { it.date }.toSet()
        val lastMiss = d.trips.lastOrNull()?.scenes?.firstOrNull { !it.good }
        return BriefingView(
            driverName = d.name, mode = d.mode,
            missions = offered.map { MissionView(it.id, it.title, it.kind, it.id == rec.id, it.points) },
            maxSelectable = Rules.MAX_MISSIONS,
            caution = lastMiss?.let { "지난번 ${it.place}" },
            tip = QuizBank.tipOf(today),
            pointsBalance = d.balance,
            nextReward = nextReward(d, shop, nowMs),
            weekMissions = GoodHabitView("이번 주 달성한 미션", clears, tries),
            attendance = AttendanceView((0 until 7).map { wk.plusDays(it.toLong()) in attendedDays }, weekStreak(d, today, includeCurrent = false)),
        )
    }

    /**
     * Consecutive weeks with >= 3 attended days, ending with the current week if it already counts
     * (or the previous week otherwise). [extraDay] counts a day being settled right now.
     */
    fun weekStreak(d: DriverState, today: LocalDate, includeCurrent: Boolean, extraDay: LocalDate? = null): Int {
        val days = d.trips.filter { it.attended }.map { it.date }.toMutableSet()
        extraDay?.let { days.add(it) }
        fun full(wk: LocalDate) = days.count { Rules.weekStart(it) == wk } >= Rules.WEEK_DAYS_FOR_ATTENDANCE
        var wk = Rules.weekStart(today)
        if (!full(wk)) { if (includeCurrent) return 0; wk = wk.minusWeeks(1) }
        var n = 0
        while (full(wk)) { n++; wk = wk.minusWeeks(1) }
        return n
    }

    // ---------------------------------------------------------------------------------------------------- records
    fun records(d: DriverState, nowMs: Long, insuranceShare: Boolean): RecordsView {
        val today = Rules.today(nowMs)
        val wk = Rules.weekStart(today)
        val visible = d.trips.filter { it.startMs >= d.clearedBeforeMs }
        val thisWeek = visible.filter { Rules.weekStart(it.date) == wk }
        val lastWeek = visible.filter { Rules.weekStart(it.date) == wk.minusWeeks(1) }
        fun agg(ts: List<TripLog>): Map<String, IntArray> {
            val m = LinkedHashMap<String, IntArray>()
            for (t in ts) for ((h, c) in t.counts) { val a = m.getOrPut(h) { IntArray(2) }; a[0] += c[0]; a[1] += c[1] }
            return m
        }
        val cur = agg(thisWeek)
        val prev = agg(lastWeek)
        val trends = cur.entries.filter { (h, c) -> c[1] > 0 && Scoring.tier(h) != "X" }
            .sortedWith(compareByDescending<Map.Entry<String, IntArray>> { Scoring.weight(it.key) }.thenByDescending { it.value[1] })
            .take(4)
            .map { (h, c) ->
                val pct = (100.0 * c[0] / c[1]).roundToInt()
                val p = prev[h]
                val change = if (p != null && p[1] > 0) pct - (100.0 * p[0] / p[1]).roundToInt() else 0
                HabitTrendView(Scoring.label(h), pct, change)
            }
        val from = today.minusDays(90)
        val journal = visible.filter { !it.date.isBefore(from) }
            .sortedWith(compareByDescending<TripLog> { it.date }.thenBy { it.startMs })
            .map { DriveLogView(it.date, it.summary, it.scenes) }
        val safe = d.safeMileageKm(nowMs)
        return RecordsView(
            weekDrives = thisWeek.size, goodMoments = cur.values.sumOf { it[0] }, safeMileageKm = safe.toInt(),
            grade = Rules.Grade.of(safe), habits = trends, journal = journal,
            insurance = if (insuranceShare) insuranceView(d, nowMs) else null,
        )
    }

    fun clearRecords(d: DriverState, nowMs: Long) { d.clearedBeforeMs = nowMs + 1 }

    // ---------------------------------------------------------------------------------------------------- atlas / quiz / shop
    fun atlas(d: DriverState): AtlasView {
        val (xp, span) = Rules.levelProgress(d.xpTotal)
        return AtlasView(d.atlasName, d.level, xp, span, AtlasCatalog.OUTFITS, AtlasCatalog.VOICES, d.voice)
    }

    fun quiz(d: DriverState, nowMs: Long): QuizView {
        val date = Rules.today(nowMs)
        val st = d.quiz?.takeIf { it.date == date } ?: QuizState(date, QuizBank.pick(date, d.lastTripTags).id, null).also { d.quiz = it }
        val q = QuizBank.ALL.first { it.id == st.quizId }
        return QuizView(q.id, q.question, q.choices, q.answer, q.explanation, st.picked)
    }

    /** One answer a day. Right +5P, wrong +2P (after reading the explanation). Returns points earned (0 if ignored). */
    fun answerQuiz(d: DriverState, choice: Int, nowMs: Long): Int {
        quiz(d, nowMs)
        val st = d.quiz!!
        if (st.picked != null) return 0
        st.picked = choice
        val q = QuizBank.ALL.first { it.id == st.quizId }
        val amount = if (choice == q.answer) Rules.QUIZ_RIGHT else Rules.QUIZ_WRONG
        val got = Rules.capped(d.earnedInMonth(YearMonth.from(Rules.today(nowMs))), amount)
        if (got > 0) d.entries.add(LedgerEntry("quiz:${st.date}", nowMs, "quiz", got, st.quizId))
        return got
    }

    /** Balance check, debit and coupon issue are one step; the same requestId never debits twice. */
    fun exchange(d: DriverState, item: ShopEntry, requestId: String, nowMs: Long): ExchangeResult {
        d.exchanges[requestId]?.let { return it }
        val cost = Rules.cost(item.price, grade(d, nowMs))
        val r = if (d.balance < cost) ExchangeResult.NOT_ENOUGH_POINTS else {
            d.entries.add(LedgerEntry("exchange:$requestId", nowMs, "exchange", -cost, item.id))
            ExchangeResult.OK
        }
        d.exchanges[requestId] = r
        return r
    }
}
