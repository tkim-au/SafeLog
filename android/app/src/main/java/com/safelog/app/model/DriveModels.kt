package com.safelog.app.model

import androidx.annotation.DrawableRes

/** 기어·속도·브레이크로 판정 팀이 정하는 앱 상태. 화면은 이 값만 본다. */
enum class DrivePhase { Parked, Driving, Idling }

enum class DriveMode(val label: String) { Normal("평소 모드"), Training("연수 모드") }

/**
 * 주행 뒤 판정 쪽이 연수 모드에 대해 알리는 것. 주차 화면에서 한 번 보이고 닫으면 null로 돌린다.
 * [TrainingOffer]: 연수 모드를 권한다(기획서 3장 2번). 모드는 바뀌지 않았고, 운전자가 "켜기"를 눌러야 켜진다.
 * [TrainingOff]: 연수를 졸업해 평소 모드로 바뀌었다. 사용자가 직접 바꿀 때는 쓰지 않는다.
 */
enum class ModeNotice { TrainingOffer, TrainingOff }

/** 이번 주행으로 보험 할인 조건을 막 채웠는지. 채운 순간에만 축하한다. */
fun ArrivalSummary.reachedInsuranceGoal(): Boolean {
    val p = insurance ?: return false
    return p.remainingKm == 0 && p.safeKm - safeKmToday < p.goalKm
}

/** 1P의 원화 가치 [가정]. 기획서 F8 "월 3,000P(약 3,000원 가치)". 제휴가 정해지면 바꾼다. */
const val WON_PER_POINT = 1

/** 잔액 옆에 붙이는 원화 표시. 작은 적립(+10P)에는 쓰지 않는다(10원은 오히려 작아 보인다). */
fun wonValue(points: Int) = "약 %,d원어치".format(java.util.Locale.US, points * WON_PER_POINT)

/** 같은 종류의 연습은 함께 고를 수 없다(기획서 F6). */
enum class PracticeKind(val label: String) { Brake("브레이크"), Signal("깜빡이"), Speed("속도") }

data class Practice(
    val id: String,
    val title: String,
    val kind: PracticeKind,
    val recommended: Boolean = false,
    /** 클리어하면 받는 기본 포인트(기획서 F8 ②). 추천 미션은 2배. */
    val points: Int = 10,
) {
    val reward get() = if (recommended) points * 2 else points
}

/** S10 출발 브리핑 */
data class Briefing(
    val driverName: String,
    val mode: DriveMode,
    val practices: List<Practice>,
    val maxSelectable: Int,
    val caution: String?,
    val tip: String,
    val pointsBalance: Int,
    val nextReward: NextReward,
    /** 이번 주 미션 도전과 달성 횟수. 도착 전 주인공 카드에 보인다. */
    val weekMissions: GoodHabit,
    val attendance: Attendance,
)

/** 월요일부터 7일. 주 3회 운전하면 그 주 출석이다(기획서 F8). */
data class Attendance(val daysThisWeek: List<Boolean>, val weekStreak: Int)

data class GoodHabit(val label: String, val success: Int, val chances: Int)

/** 다음 보상. [price]는 등급 할인을 뺀 실제 차감 포인트다. */
/** [image]가 있으면 [art] 글자 대신 상품 사진을 쓴다. */
data class NextReward(val itemName: String, val remainingPoints: Int, val price: Int, val art: String = "", @DrawableRes val image: Int? = null) {
    val progress get() = progressAt(remainingPoints)

    fun progressAt(remaining: Int) = (1f - remaining.toFloat() / price).coerceIn(0f, 1f)
}

/** S30 오늘 얻은 것. 위험 주행이면 points·xp가 0이다(기획서 F7). */
data class ArrivalSummary(
    val topHabit: GoodHabit?,
    val otherHabits: List<GoodHabit>,
    val points: Int,
    val xp: Int,
    val xpToNextLevel: Int,
    val nextReward: NextReward,
    val practiceResult: GoodHabit?,
    val nextChallenge: String?,
    /** 오늘 고른 연습을 통과했는지. 통과 기준은 판정 팀이 정한다. */
    val practiceCleared: Boolean = false,
    /** 연습 통과로 받은 포인트. [points]에 이미 포함된 값이다. */
    val practicePoints: Int = 0,
    /** 포인트가 없는 이유(위험 주행 등). 꾸짖지 않는 한 줄이다. */
    val noRewardReason: String? = null,
    /** 보험 할인 진행(오늘 주행 반영 뒤). 보험 공유에 동의했을 때만 보인다. */
    val insurance: InsuranceProgress? = null,
    /** 오늘 위험 순간 없이 달린 거리 */
    val safeKmToday: Int = 0,
)

/**
 * 연습 칸을 눌렀을 때의 새 선택 목록.
 * 다시 누르면 해제, 같은 종류는 교체, 개수가 차면 가장 먼저 고른 것을 뺀다.
 */
fun togglePractice(
    selected: List<String>,
    practices: List<Practice>,
    id: String,
    max: Int,
): List<String> {
    if (id in selected) return selected - id
    val kind = practices.first { it.id == id }.kind
    val otherKinds = selected.filter { s -> practices.first { it.id == s }.kind != kind }
    return (otherKinds + id).takeLast(max)
}
