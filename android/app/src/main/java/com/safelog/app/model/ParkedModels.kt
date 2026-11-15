package com.safelog.app.model

import androidx.annotation.DrawableRes
import java.time.LocalDate

/** 안전 마일리지 등급(기획서 F8 ④). 교환소에서 덜 차감하는 비율이다. */
enum class MileageGrade(val label: String, val discountPercent: Int) {
    Basic("기본", 0), Silver("실버", 5), Gold("골드", 10), Platinum("플래티넘", 15),
}

/** S40 내 기록 + S41 운전 일지 */
data class DriveRecords(
    val weekDrives: Int,
    val goodMoments: Int,
    val safeMileageKm: Int,
    val grade: MileageGrade,
    val habits: List<HabitTrend>,
    val journal: List<DriveLog>,
    val insurance: InsuranceProgress? = null,
)

/**
 * 보험 할인 진행. 점수는 보여 주지 않는다(오르내리면 기분이 상한다). 대신 늘어나기만 하는
 * 이번 보험 기간의 안전 거리(위험 순간 없이 달린 거리)로 조건까지 남은 양을 보여 준다.
 * 할인 여부와 금액은 보험사가 갱신 때 정한다. 조건 거리는 보험사 기준 [가정].
 */
data class InsuranceProgress(
    val safeKm: Int,
    val goalKm: Int,
    val renewal: String,
    val monthsToRenewal: Int,
    /** 이 운전자의 평소 한 번 주행 거리. 남은 거리를 "몇 번 더"로 바꿔 말할 때 쓴다. */
    val avgTripKm: Int,
    /** 보험사가 공시한 안전운전 특약 최대 할인율 [가정]. 금액이 아니라 보험사 기준만 옮긴다. */
    val maxDiscountPercent: Int,
) {
    val remainingKm get() = (goalKm - safeKm).coerceAtLeast(0)
    val progress get() = (safeKm.toFloat() / goalKm).coerceIn(0f, 1f)

    /** 평소처럼 몇 번 더 달리면 조건을 채우는지. 올림이라 "0번"은 조건을 채웠을 때뿐이다. */
    val tripsLeft get() = if (avgTripKm <= 0) 0 else (remainingKm + avgTripKm - 1) / avgTripKm
}

/** 항목별 지킨 비율과 지난주 대비 변화(%p) */
data class HabitTrend(val label: String, val percent: Int, val change: Int)

/** 주행 한 번의 일지. 하루에 여러 번 달리면 같은 날짜로 여러 개다. */
data class DriveLog(val date: LocalDate, val summary: String, val scenes: List<Scene>)

/**
 * 한 주행의 주요 장면. [good]이 false면 다음 미션으로 권하는 장면이다.
 * [disputed]: 운전자가 "아니에요"로 판정이 실제와 달랐다는 의견을 남겼다(기획서 3장 3번의 오판정 의견).
 */
data class Scene(
    val id: String,
    val time: String,
    val place: String,
    val what: String,
    val good: Boolean,
    val compare: String? = null,
    val disputed: Boolean = false,
)

enum class ShopCategory(val label: String) { Car("차"), Life("생활"), Culture("문화·게임"), Atlas("캐릭터") }

/**
 * 교환 상품. 실제 상품 사진은 제휴처 목록에서 받는다. 그 전까지는 [art](큰 글자 쿠폰 그림)나 [image]로 그린다.
 */
data class ShopItem(
    val id: String,
    val name: String,
    val brand: String,
    val category: ShopCategory,
    val price: Int,
    val art: String,
    @DrawableRes val image: Int? = null,
)

/** 등급 할인을 뺀 실제 차감 포인트. 1P 미만은 버린다. */
fun ShopItem.cost(grade: MileageGrade) = price * (100 - grade.discountPercent) / 100

/** S55. 레벨 5와 10에서 모습이 자란다(기획서 F8). */
data class AtlasProfile(
    val name: String,
    val level: Int,
    val xp: Int,
    val xpToNext: Int,
    val outfits: List<Unlockable>,
    val voices: List<Unlockable>,
    val voice: String,
)

/** 레벨로 열리는 것. 목소리는 [pitch]로 미리 듣기 높낮이를 정한다. */
data class Unlockable(val id: String, val name: String, val unlockLevel: Int, val pitch: Float = 1f)

enum class AtlasStage(val label: String, val fromLevel: Int) { Baby("아기", 1), Child("어린이", 5), Grown("어른", 10) }

fun atlasStage(level: Int) = AtlasStage.entries.last { level >= it.fromLevel }

/** 주행 중 신호 단계(기획서 F5) */
enum class SignalLevel(val label: String, val detail: String) {
    Quiet("조용히", "성공할 때만 효과음"),
    Sometimes("가끔", "효과음 + 신호 대기 때 한 마디, 운전 한 번에 2번까지"),
    Off("끄기", "도착한 뒤에만 알려 줘요"),
}

/** 한 차에 둘 수 있는 운전자 수. 고르기 화면의 얼굴 칸이 한 줄에 들어가는 수다. 판정 쪽 Rules.MAX_DRIVERS와 같다. */
const val MAX_DRIVERS = 5

/**
 * 프로필 질문의 운전 경력(기획서 3장 1번, 4장). 처음이거나 오랜만이면 연수 모드로, 익숙하면 평소 모드로 시작한다.
 * 모드는 나중에 설정에서 직접 바꿀 수 있다.
 */
enum class DrivingExperience(val label: String) {
    Novice("처음이에요"), // 면허를 딴 지 얼마 안 됐다
    Returning("오랜만이에요"), // 한동안 운전을 쉬었다
    Experienced("익숙해요"), // 평소에 자주 운전한다
}

/**
 * 프로필 질문의 답. [name]이 null이면 이름을 그대로 둔다.
 * [carFamiliar]가 false(이 차가 처음)면 홈에서 내 차 익히기를 한 번 권한다.
 */
data class ProfileAnswer(val name: String?, val experience: DrivingExperience, val carFamiliar: Boolean)

/** 차 한 대를 나눠 쓰는 운전자. 아틀라스와 기록은 운전자마다 따로다. */
data class Driver(val id: String, val name: String, val mode: DriveMode, val level: Int, val avatar: Avatar = Avatar.Atlas)

/** 운전자 얼굴. 넷플릭스처럼 각자 고른다. 기본은 레벨만큼 자란 그 사람의 아틀라스. */
sealed interface Avatar {
    data object Atlas : Avatar

    /** 색 칸 위에 이름 첫 글자. [color]는 화면의 색 목록 순번(0~5). */
    data class Letter(val color: Int) : Avatar

    /** 휴대폰 사진에서 고른 얼굴. 앱 저장소에 줄여서 복사해 둔 파일 경로. */
    data class Photo(val path: String) : Avatar
}

/** 운전자 한 사람의 세이프로그 계정. 휴대폰 번호로 가입한다. [phone]은 숫자만(01012345678). */
data class Account(val name: String, val phone: String) {
    /** 화면에 보이는 번호. 가운데 자리를 가린다(010-****-5678). */
    val maskedPhone: String get() = formatPhone(phone).let { f ->
        val parts = f.split('-')
        if (parts.size == 3) "${parts[0]}-${"*".repeat(parts[1].length)}-${parts[2]}" else f
    }
}

/**
 * 숫자만 든 휴대폰 번호에 줄표를 넣는다. 3-4-4, 010이 아닌 10자리(011 등)만 3-3-4.
 * 입력 중인 짧은 번호도 앞에서부터 끊는다(010-1234-5). 그래서 치는 동안 줄표가 옮겨 다니지 않는다.
 */
fun formatPhone(digits: String): String {
    val mid = if (digits.length == 10 && !digits.startsWith("010")) 3 else 4
    return when {
        digits.length <= 3 -> digits
        digits.length <= 3 + mid -> "${digits.take(3)}-${digits.drop(3)}"
        else -> "${digits.take(3)}-${digits.substring(3, 3 + mid)}-${digits.drop(3 + mid)}"
    }
}

/** 휴대폰 번호로 받을 수 있는 모양인지. 010은 11자리, 011·016 같은 옛 번호는 10~11자리. */
fun isPhoneNumber(digits: String) =
    digits.all(Char::isDigit) && digits.startsWith("01") && digits.length in (if (digits.startsWith("010")) 11..11 else 10..11)

/** S90. 보험 공유와 데이터 기여는 따로 동의한다(기획서 9장). */
data class AppSettings(
    // 기획서의 연수 모드(효과음 + 신호 대기 칭찬 한 마디)가 기본. 평소 모드는 무엇을 골라도 소리가 없다.
    val signal: SignalLevel = SignalLevel.Sometimes,
    val insuranceShare: Boolean = false,
    val dataContribution: Boolean = false,
    /** 홈의 보험 할인 안내를 닫았는지. 한 번 닫으면 다시 보이지 않는다(조르지 않는다). */
    val insuranceInviteSeen: Boolean = false,
)

/** S32 오늘의 퀴즈. 하루 1문제, 맞히면 +5P, 틀려도 해설을 보면 +2P(기획서 F8 ③). */
data class Quiz(
    val question: String,
    val choices: List<String>,
    val answer: Int,
    val explanation: String,
    val picked: Int? = null,
) {
    val reward get() = if (picked == answer) 5 else 2
}

/**
 * S05 내 차 익히기(기획서 부가 A1). 화면이 시킨 조작을 사용자가 해 보면 차가 감지해 [CarStep.done]이 된다.
 * 차종마다 없는 기능은 판정 쪽이 빼고 보낸다(checkXxxCapabilities). 앱은 차를 제어하지 않고 조회만 한다.
 * 주차 브레이크처럼 차 종류에 따라 다른 단계는 판정 쪽이 맞는 쪽 하나만 넣는다(전자식이면 "스위치 걸고 풀기").
 */
data class CarTour(
    /** 출발 전 꼭 익힐 조작. 실제 출발 순서대로. 다 하면 "출발 준비 완료" 배지와 [rewardXp]. */
    val required: List<CarStep>,
    /** 편의 기능을 상황별로 묶은 것(비 오는 날·추운 날·더운 날·그 밖에). 해도 되고 안 해도 된다. */
    val extraGroups: List<CarStepGroup>,
    /** 내 차에 있는 경고등의 뜻(조작 없음). 색으로 위험도를 나눈다. */
    val telltales: List<Telltale>,
    /** 처음 타는 차·새 운전자라 홈에서 한 번 권할지. "나중에"나 "해 보기"를 누르면 false. */
    val offer: Boolean = false,
    val rewardXp: Int = 50,
) {
    val extras get() = extraGroups.flatMap { it.steps }
    val doneCount get() = required.count { it.done }

    /** 꼭 익힐 것을 다 했는지. 건너뛴 단계는 한 것으로 세지 않는다. */
    val finished get() = required.isNotEmpty() && required.all { it.done }

    /** 지금 해 볼 조작. 꼭 익힐 것 먼저, 그다음 편의 기능. 건너뛴 것은 넘어간다. */
    val next get() = (required + extras).firstOrNull { !it.done && !it.skipped }

    /** 단계 하나를 바꾼다(차가 감지함·건너뜀 공용). */
    fun update(id: String, change: (CarStep) -> CarStep) = copy(
        required = required.map { if (it.id == id) change(it) else it },
        extraGroups = extraGroups.map { g -> g.copy(steps = g.steps.map { if (it.id == id) change(it) else it }) },
    )
}

data class CarStepGroup(val title: String, val steps: List<CarStep>)

/** [skipped]: 사용자가 건너뜀. 나중에 차가 감지하면 done이 된다. */
data class CarStep(val id: String, val title: String, val done: Boolean = false, val skipped: Boolean = false)

/** 경고등 색. 실제 계기판처럼 빨강은 바로 세우고, 노랑은 곧 점검하고, 나머지는 켜짐 알림이다. */
enum class TelltaleLevel(val label: String) { Red("바로 안전한 곳에 세워요"), Yellow("곧 점검해요"), Info("켜져 있다는 알림") }

/** [on]: 지금 계기판에 켜져 있음(Safety 경고등 상태). 켜져 있으면 목록 맨 위에 올린다. */
data class Telltale(val id: String, val name: String, val meaning: String, val level: TelltaleLevel, val on: Boolean = false)
