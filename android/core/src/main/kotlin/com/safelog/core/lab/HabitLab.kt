package com.safelog.core.lab

import com.safelog.core.contract.Frame
import com.safelog.core.contract.Trip
import com.safelog.core.drive.DrivePhase
import com.safelog.core.drive.MissionDetector
import com.safelog.core.engine.EngineConfig
import com.safelog.core.engine.Outcome
import com.safelog.core.engine.SafeLogEngine
import com.safelog.core.engine.TripResult
import com.safelog.core.gate.PraiseGate
import kotlin.math.abs

/**
 * 습관 시험실 (developer only, debug builds). Shows, for every habit the engine knows, whether the car / emulator
 * delivers the signals it needs, what the in-drive detectors saw, and what the full engine says about the trip so far.
 * Misses and reasons are shown here on purpose: this screen is for developers, never for drivers.
 */
data class LabHabit(
    val code: String,            // scenario catalogue id (docs/scenario_catalogue.csv)
    val habit: String,           // engine habit key
    val title: String,
    val needs: List<String>,     // signal keys (LabSignals.ALL)
    val howTo: String,           // how to make it happen with the Pleos Virtual Console / navigation
    val live: Boolean = false,   // has an in-drive (chime) detector
    val blocked: String? = null, // why this can never show on a third-party Pleos app today
)

object LabCatalog {
    val CATEGORIES = linkedMapOf(
        "A" to "출발·정지", "B" to "속도", "C" to "신호", "D" to "조향", "E" to "차간·위기",
        "F" to "교차로·특수 지점", "G" to "탑승 준비", "H" to "휴식", "I" to "등화", "J" to "정속",
    )

    private const val NAV = "내비 경로 안내(모의 주행) 중에만 생겨요"
    val HABITS = listOf(
        LabHabit("A01", "smooth_stop", "부드러운 정지", listOf("speed", "cruise"),
            "D · 40km/h 이상 → 브레이크를 살살(0.25g 미만) 밟아 0까지. 크루즈 Not ready", live = true),
        LabHabit("A02", "smooth_launch", "부드러운 출발", listOf("speed", "cruise"),
            "정지 → 15초 안에 20km/h 이상, 가속 0.3g 미만으로 천천히", live = true),
        LabHabit("A03", "anticipatory_braking", "미리 감속", listOf("speed", "brake", "cruise"),
            "40km/h 이상 → 페달 떼고 몇 초 타력 → 브레이크로 정지"),
        LabHabit("B01", "speed_limit", "제한속도 지키기", listOf("speed", "speed_limit", "cruise"),
            "$NAV. 60초 동안 제한속도 아래, 평균은 제한의 60% 이상", live = true),
        LabHabit("B02", "school_zone", "스쿨존 30km/h", listOf("speed", "school_zone"),
            "$NAV. 어린이보호구역 안에서 30km/h 아래", live = true),
        LabHabit("B03", "rain_speed", "빗길 20% 감속", listOf("speed", "speed_limit", "wiper", "cruise"),
            "와이퍼 Low + $NAV. 60초 동안 제한속도의 80% 아래", live = true),
        LabHabit("B06", "speed_bump", "과속방지턱 서행", listOf("speed", "caution"), "$NAV. 방지턱 앞 30km/h 아래"),
        LabHabit("B07", "caution_slowdown", "주의 구간 감속", listOf("speed", "caution", "speed_limit"),
            "$NAV. 사고다발·안개 등 주의 지점 앞에서 10% 이상 감속"),
        LabHabit("C01", "turn_signal_turn", "회전 전 깜빡이", listOf("turn_signal", "tbt"),
            "$NAV. 회전 지점 30m 전(고속도로 100m)부터 깜빡이", live = true),
        LabHabit("C02", "lane_change_signal", "차선 변경 3초 전 깜빡이", listOf("speed", "steer", "turn_signal"),
            "40km/h 이상 · 깜빡이 켜고 4초 → 핸들 S자(한쪽 2초, 반대쪽 2초) → 깜빡이 끄기", live = true),
        LabHabit("C03", "blinker_cancel", "깜빡이 정리", listOf("turn_signal", "steer"), "회전·차선 변경 뒤 몇 초 안에 깜빡이 끄기"),
        LabHabit("C04", "hazard_warning", "비상등으로 뒤차 배려", listOf("speed", "hazard", "speed_limit"),
            "고속도로 70km/h 이상에서 6초 안에 30km/h 감속 + 비상등",
            blocked = "앱이 비상등 상태를 받지 못해요 (경고등은 PRIVATE_CAR_SAFETY 권한)"),
        LabHabit("D01", "smooth_turn", "부드러운 회전", listOf("speed", "steer", "tbt"), "$NAV. 교차로 회전을 천천히, 횡가속 낮게"),
        LabHabit("D02", "smooth_lane_change", "부드러운 차선 변경", listOf("speed", "steer", "cruise"),
            "40km/h 이상 · 핸들 S자를 4초 이상에 걸쳐 완만하게"),
        LabHabit("D03", "lane_keeping", "차로 안정 유지", listOf("telltale", "odometer"), "10km마다 차로 이탈 경고 없이",
            blocked = "경고등(LKA)을 읽을 권한이 없어요 (PRIVATE_CAR_SAFETY)"),
        LabHabit("E01", "following_margin", "여유 있는 차간거리", listOf("telltale", "odometer"), "10km마다 전방 충돌 경고 없이",
            blocked = "경고등(FCA)을 읽을 권한이 없어요 (PRIVATE_CAR_SAFETY)"),
        LabHabit("E02", "fca_reaction", "위기 대응", listOf("telltale", "brake"), "전방 충돌 경고 1초 안에 브레이크",
            blocked = "경고등(FCA)을 읽을 권한이 없어요 (PRIVATE_CAR_SAFETY)"),
        LabHabit("F01", "right_turn_pause", "우회전 전 멈춤", listOf("speed", "tbt"), "$NAV. 우회전 40m 전에 5km/h 아래로 멈춤"),
        LabHabit("F02", "railroad_stop", "철길 앞 일시정지", listOf("speed", "caution"), "$NAV. 철길 건널목 앞에서 정지"),
        LabHabit("G01", "seat_belts", "모두 안전벨트", listOf("seats", "belts", "speed"), "좌석 착좌(Occupied) + 벨트 착용 상태로 10km/h 넘기기"),
        LabHabit("H01", "rest_breaks", "2시간마다 휴식", listOf("speed", "gear"), "100분 넘게 주행 후 휴식 (P로 쉬면 주행이 나뉘어 판단 안 됨: 버그 #16)"),
        LabHabit("I01", "headlights", "전조등 켜기", listOf("low_beam", "wiper", "speed"), "와이퍼 Low + 외부등 Low beam으로 60초 이상 주행"),
        LabHabit("J01", "steady_cruise", "일정한 속도 유지", listOf("speed", "speed_limit", "cruise"),
            "$NAV(고속도로). 60km/h 이상 60초 동안 속도 편차 3km/h 이내"),
    )

    fun byHabit(h: String) = HABITS.firstOrNull { it.habit == h }
    val LIVE = HABITS.filter { it.live }.map { it.habit }
}

/** What each signal key means and when it counts as "readable" / "seen working". */
object LabSignals {
    val ALL = linkedMapOf(
        "speed" to "차속", "gear" to "기어", "brake" to "브레이크", "steer" to "조향각", "turn_signal" to "방향지시등",
        "cruise" to "크루즈 상태", "wiper" to "와이퍼", "low_beam" to "전조등", "night" to "야간", "seats" to "착좌",
        "belts" to "벨트", "odometer" to "주행거리", "telltale" to "경고등", "hazard" to "비상등",
        "nav" to "내비 안내", "tbt" to "회전 안내", "speed_limit" to "제한속도", "caution" to "주의 지점", "school_zone" to "스쿨존",
    )

    /** (readable, interesting) for one frame. */
    fun read(key: String, f: Frame): Pair<Boolean, Boolean> = when (key) {
        "speed" -> !f.speedKph.isNaN() to (!f.speedKph.isNaN() && f.speedKph > 1)
        "gear" -> (f.gear >= 0) to (f.gear == 3)
        "brake" -> (f.brakeOn >= 0) to (f.brakeOn == 1)
        "steer" -> !f.steerDeg.isNaN() to (!f.steerDeg.isNaN() && abs(f.steerDeg) > 5)
        "turn_signal" -> (f.turnSignal in 1..2) to (f.turnSignal in 1..2)       // API has no "unknown": seen = used
        "cruise" -> (f.cruiseActive >= 0) to (f.cruiseActive == 1)
        "wiper" -> (f.wiperActive >= 0) to (f.wiperActive == 1)
        "low_beam" -> (f.lowBeam >= 0) to (f.lowBeam == 1)
        "night" -> (f.night >= 0) to (f.night == 1)
        "seats" -> (f.seatsOccupied >= 0) to (f.seatsOccupied > 0)
        "belts" -> (f.beltsBuckled >= 0) to (f.beltsBuckled > 0)
        "odometer" -> !f.odoKm.isNaN() to !f.odoKm.isNaN()
        "telltale" -> (f.ttFca >= 0 || f.ttLka >= 0) to (f.ttFca == 1 || f.ttLka == 1)
        "hazard" -> (f.turnSignal == 3) to (f.turnSignal == 3)
        "nav" -> (f.navActive >= 0) to (f.navActive == 1)
        "tbt" -> !f.tbtDistM.isNaN() to (!f.tbtDistM.isNaN() && f.tbtType in 1..3)
        "speed_limit" -> !f.speedLimitKph.isNaN() to !f.speedLimitKph.isNaN()
        "caution" -> (f.cautionType > 0) to (f.cautionType > 0)
        "school_zone" -> (f.schoolZone == 1) to (f.schoolZone == 1)
        else -> false to false
    }
}

enum class SignalState { Missing, Readable, Working }

data class LabEvent(val t: Double, val habit: String, val success: Boolean)

data class LabJudgment(
    val habit: String, val t: Double, val outcome: Int, val reason: String, val value: Double, val threshold: Double,
    val held: Boolean, val gateP: Double?,
) {
    val label: String get() = when {
        held -> "성공(보류)"
        outcome == Outcome.SUCCESS -> "성공"
        outcome == Outcome.MISS -> "놓침"
        else -> "판단 보류"
    }
}

data class LabJudge(
    val atS: Double, val frames: Int, val skipped: String?, val judgments: List<LabJudgment>,
    val steerUnit: String?, val steerSource: String?, val speedHz: Double?, val km: Double?,
)

/** Per-habit verdict for the list: can it be shown on this car right now? */
data class LabHabitStatus(val habit: LabHabit, val missing: List<String>, val demonstrable: Boolean)

/** Immutable snapshot for the screen. */
data class LabView(
    val frames: Int, val driving: Boolean, val last: Frame?, val signals: Map<String, SignalState>,
    val statuses: List<LabHabitStatus>, val live: Map<String, Pair<Int, Int>>, val events: List<LabEvent>, val judge: LabJudge?,
)

class HabitLab {
    private val readable = HashSet<String>()
    private val working = HashSet<String>()
    private var detectors: List<MissionDetector> = emptyList()
    private var driving = false
    var frames = 0; private set
    var last: Frame? = null; private set
    val events = ArrayDeque<LabEvent>()
    var lastJudge: LabJudge? = null

    /** Start a fresh test (also called by itself when a trip starts). Signal history is kept unless [all]. */
    fun reset(all: Boolean = false, steerSign: Int = -1, steerUnit: String = "road_wheel") {
        detectors = LabCatalog.LIVE.map { MissionDetector(it, steerSign = steerSign, steerUnit = steerUnit) }
        frames = 0
        events.clear()
        if (all) { readable.clear(); working.clear(); lastJudge = null }
    }

    /** Feed every frame the core sees, with the phase the core decided. */
    fun onFrame(f: Frame, phase: DrivePhase, steerSign: Int = -1, steerUnit: String = "road_wheel") {
        last = f
        for (k in LabSignals.ALL.keys) {
            val (r, w) = LabSignals.read(k, f)
            if (r) readable.add(k)
            if (w) working.add(k)
        }
        val nowDriving = phase != DrivePhase.Parked
        if (nowDriving && !driving) reset(steerSign = steerSign, steerUnit = steerUnit)
        driving = nowDriving
        if (!nowDriving) return
        val t = frames / 10.0
        frames++
        for (d in detectors) {
            val before = d.chances
            val ok = d.step(f.copy(t = t))
            if (ok || d.chances > before) {
                events.addFirst(LabEvent(t, d.habit, ok))
                while (events.size > 60) events.removeLast()
            }
        }
    }

    fun signal(key: String): SignalState = when (key) {
        in working -> SignalState.Working
        in readable -> SignalState.Readable
        else -> SignalState.Missing
    }

    /** In-drive detector counts for this trip: habit -> (successes, chances). */
    fun liveCounts(): Map<String, Pair<Int, Int>> = detectors.associate { it.habit to (it.successes to it.chances) }

    fun statuses(): List<LabHabitStatus> = LabCatalog.HABITS.map { h ->
        val missing = h.needs.filter { k ->
            // signals that are events (blinker, hazard, caution, school zone) count once they have been seen
            signal(k) == SignalState.Missing
        }
        LabHabitStatus(h, missing, h.blocked == null && missing.isEmpty())
    }

    fun view() = LabView(frames, driving, last, LabSignals.ALL.keys.associateWith { signal(it) }, statuses(), liveCounts(),
        events.toList(), lastJudge)

    companion object {
        /** Full engine (rules + gate) on [trip]. Held successes are labelled "성공(보류)". */
        fun judge(trip: Trip, cfg: EngineConfig, gate: PraiseGate?): LabJudge = fromResult(trip, SafeLogEngine(cfg, gate).runTrip(trip))

        /** The same view of a result the app already computed (a finished trip). */
        fun fromResult(trip: Trip, r: TripResult): LabJudge {
            val t = trip.d("t")
            val d = r.derived
            return LabJudge(
                atS = if (trip.n > 0) t[trip.n - 1] else 0.0, frames = trip.n, skipped = r.skipped,
                judgments = r.judgments.map { j ->
                    LabJudgment(j.habit, t[minOf(j.iRef, trip.n - 1)], j.outcome, j.reason, j.value, j.threshold, j.held,
                        j.meta["gate_p"] as? Double)
                },
                steerUnit = d?.steerUnit, steerSource = d?.steerUnitSource, speedHz = d?.speedNativeHz,
                km = d?.km,
            )
        }
    }
}
