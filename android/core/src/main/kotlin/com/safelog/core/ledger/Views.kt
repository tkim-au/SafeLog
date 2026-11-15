package com.safelog.core.ledger

/**
 * What the screens show. Field for field the same as the front-end models (android app model package);
 * the app's LiveSafeLogRepository only maps these 1:1. Strings are finished Korean (front-end spec section 9).
 */
data class MissionView(val id: String, val title: String, val kind: String, val recommended: Boolean, val points: Int)
data class GoodHabitView(val label: String, val success: Int, val chances: Int)
/** [image]: the shop item's picture key (same as [ShopEntry.image]), e.g. "reward_coffee" */
data class NextRewardView(val itemName: String, val remainingPoints: Int, val price: Int, val art: String, val image: String? = null)
data class AttendanceView(val daysThisWeek: List<Boolean>, val weekStreak: Int)
data class InsuranceView(val safeKm: Int, val goalKm: Int, val renewal: String, val monthsToRenewal: Int, val avgTripKm: Int, val maxDiscountPercent: Int)

data class BriefingView(
    val driverName: String, val mode: String, val missions: List<MissionView>, val maxSelectable: Int,
    val caution: String?, val tip: String, val pointsBalance: Int, val nextReward: NextRewardView,
    val weekMissions: GoodHabitView, val attendance: AttendanceView,
)

data class ArrivalView(
    val topHabit: GoodHabitView?, val otherHabits: List<GoodHabitView>, val points: Int, val xp: Int, val xpToNextLevel: Int,
    val nextReward: NextRewardView, val practiceResult: GoodHabitView?, val nextChallenge: String?, val practiceCleared: Boolean,
    val practicePoints: Int, val noRewardReason: String?, val insurance: InsuranceView?, val safeKmToday: Int,
)

/** [disputed]: the driver marked the judgment as wrong (오판정 의견). */
data class SceneView(val id: String, val time: String, val place: String, val what: String, val good: Boolean, val compare: String?, val disputed: Boolean = false)
data class DriveLogView(val date: java.time.LocalDate, val summary: String, val scenes: List<SceneView>)
data class HabitTrendView(val label: String, val percent: Int, val change: Int)
data class RecordsView(
    val weekDrives: Int, val goodMoments: Int, val safeMileageKm: Int, val grade: Rules.Grade, val habits: List<HabitTrendView>,
    val journal: List<DriveLogView>, val insurance: InsuranceView?,
)
data class UnlockableView(val id: String, val name: String, val unlockLevel: Int, val pitch: Float = 1f)
data class AtlasView(val name: String, val level: Int, val xp: Int, val xpToNext: Int, val outfits: List<UnlockableView>, val voices: List<UnlockableView>, val voice: String)
data class QuizView(val id: String, val question: String, val choices: List<String>, val answer: Int, val explanation: String, val picked: Int?)
/** [avatar]: "atlas" | "letter:<0..5>" | "photo:<path>" */
data class DriverView(val id: String, val name: String, val mode: String, val level: Int, val avatar: String = "atlas")

enum class ExchangeResult { OK, NOT_ENOUGH_POINTS, SOLD_OUT, NETWORK, MONTHLY_LIMIT }

object AtlasCatalog {
    val OUTFITS = listOf(
        UnlockableView("headband", "머리띠", 1), UnlockableView("cap", "모자", 2), UnlockableView("scarf", "목도리", 3),
        UnlockableView("glasses", "선글라스", 4), UnlockableView("suit", "정장 세트", 10), UnlockableView("badge", "골드 배지", 14),
    )
    val VOICES = listOf(
        UnlockableView("basic", "기본 목소리", 1), UnlockableView("calm", "차분한 목소리", 5, 0.85f),
        UnlockableView("cheer", "신나는 목소리", 15, 1.3f),
    )
}
