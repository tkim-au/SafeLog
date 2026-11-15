package com.safelog.core.ledger

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Reward rules shared by the on-device ledger and the server (backend/server/safelog_server/rules.py).
 * Both implementations are checked against the same golden cases (backend/contract/ledger_cases.json).
 * Values marked [가정] are the team's initial assumptions from plan v0.4 F8 and the front-end spec section 13.
 */
object Rules {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    const val MONTHLY_CAP = 3000                 // F8: 월 3,000P [가정]
    const val ATTEND_FIRST_MONTH = 10            // 운전한 날 +10P (첫 30일)
    const val ATTEND_LATER = 3                   // 두 달째부터 +3P
    const val FIRST_MONTH_DAYS = 30
    const val WEEK_DAYS_FOR_ATTENDANCE = 3       // 주 3회 = 그 주 출석
    const val WEEK_BONUS = 30                    // 주 +30P
    const val STREAK4_BONUS = 100                // 4주 연속 +100P
    const val STREAK8_BONUS = 200                // 8주 연속 +200P
    const val STAMP_EVERY = 5                    // 같은 미션 5번 통과마다
    const val STAMP_BONUS = 50                   // +50P
    const val QUIZ_RIGHT = 5
    const val QUIZ_WRONG = 2
    const val MISSION_XP = 10                    // 미션 통과 경험치 (추천은 2배)
    // 연수 모드 (plan F6). The trip score is internal: never in any screen value (front-end spec 13).
    const val TRAINING_ON_AVG = 70               // 최근 5회 가중 평균 < 70 -> 연수 모드를 권한다 (켜는 것은 운전자)
    const val TRAINING_ON_TRIP = 60              // 이번 주행 < 60 -> 연수 모드를 권한다
    const val TRAINING_OFF_AVG = 75              // 연수 미션 3회 연속 통과 + 평균 >= 75 -> 평소 모드
    const val TRAINING_OFF_STREAK = 3
    const val SCORE_WINDOW = 5
    const val GRADUATION_XP = 200                // 연수 졸업 +200 XP
    const val GRADUATION_POINTS = 300            // 연수 졸업 +300P (첫 졸업 한 번만)
    const val TOUR_XP = 50                       // (부가) 내 차 익히기 완료 +50 XP (운전자마다 한 번)

    fun today(nowMs: Long): LocalDate = Instant.ofEpochMilli(nowMs).atZone(ZONE).toLocalDate()
    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 교환 차감액: price x (100 - 등급 할인%) / 100, 정수 나눗셈 (front-end ShopItem.cost). */
    fun cost(price: Int, grade: Grade) = price * (100 - grade.discountPercent) / 100

    /** 안전 마일리지 등급 (최근 12개월) */
    enum class Grade(val label: String, val discountPercent: Int, val fromKm: Int) {
        Basic("기본", 0, 0), Silver("실버", 5, 1000), Gold("골드", 10, 5000), Platinum("플래티넘", 15, 15000);
        companion object { fun of(km: Double) = entries.last { km >= it.fromKm } }
    }

    /**
     * Atlas level curve (F8): levels 1-4 every 100 XP, level 5 at 500, level 10 at 1,500 (200 per level between),
     * then 300 per level. Returns the cumulative XP needed to REACH [level].
     */
    fun xpForLevel(level: Int): Int = when {
        level <= 1 -> 0
        level <= 4 -> (level - 1) * 100
        level <= 10 -> 500 + (level - 5) * 200
        else -> 1500 + (level - 10) * 300
    }

    fun levelFor(totalXp: Int): Int {
        var l = 1
        while (xpForLevel(l + 1) <= totalXp) l++
        return l
    }

    /** (xp inside the current level, size of the current level) -> front-end AtlasProfile.xp / xpToNext */
    fun levelProgress(totalXp: Int): Pair<Int, Int> {
        val l = levelFor(totalXp)
        return (totalXp - xpForLevel(l)) to (xpForLevel(l + 1) - xpForLevel(l))
    }

    fun xpToNextLevel(totalXp: Int): Int = xpForLevel(levelFor(totalXp) + 1) - totalXp

    /**
     * One mission per drive (submitted plan, sections 3 and 4: "미션은 한 번에 하나만"). Plan v0.4 F6 let a driver
     * pick 2 after 10 clears and 3 after 25; that is no longer offered. The server briefing uses the same value.
     */
    const val MAX_MISSIONS = 1

    /** Drivers who can share one car's app (the picker shows them in one row). */
    const val MAX_DRIVERS = 5

    /** Raw trip recordings stay on the car this long, then they are deleted (consent screen: "원본은 차 안에 90일"). */
    const val RAW_KEEP_DAYS = 90L

    /** Cuts an earning so the calendar month stays under the cap. */
    fun capped(earnedThisMonth: Int, amount: Int): Int = maxOf(0, minOf(amount, MONTHLY_CAP - earnedThisMonth))

    /** 장기 점수 = 최근 5회 가중 평균 (plan F6): weights 1..5 from the oldest to the newest trip. */
    fun recentScore(scores: List<Int>): Double {
        val last = scores.takeLast(SCORE_WINDOW)
        if (last.isEmpty()) return 100.0
        var num = 0.0; var den = 0.0
        last.forEachIndexed { k, s -> num += (k + 1) * s; den += (k + 1) }
        return num / den
    }

    fun insuranceRemainingKm(safeKm: Int, goalKm: Int) = maxOf(goalKm - safeKm, 0)
    fun tripsLeft(remainingKm: Int, avgTripKm: Int) = if (avgTripKm <= 0) 0 else (remainingKm + avgTripKm - 1) / avgTripKm
}

/** A mission the driver can pick before driving (front-end `Practice`). Built only from RELEASED habits. */
data class Mission(
    val id: String,
    val habit: String,
    val title: String,
    val kind: String,           // Brake | Signal | Speed  (front-end PracticeKind)
    val minChances: Int,        // 통과 조건: 기회 수
    val minRate: Double,        // 통과 조건: 성공 비율
    val points: Int = 10,
    val rainOnly: Boolean = false,
    val praise: String = "$title, 잘했어요", // 신호 대기 한 마디: 칭찬만, 숫자 없이 (기획서 3장)
) {
    fun passed(success: Int, chances: Int) = chances >= minChances && success.toDouble() / maxOf(chances, 1) >= minRate
}

object MissionCatalog {
    /**
     * smooth_stop ("브레이크 나눠 밟기" in the mock-up) is not offered: two-stage braking cannot be measured with a
     * pressed/not-pressed brake signal, and smooth stops are held back until the gate passes 97 % (doc section 9).
     */
    val ALL = listOf(
        // a 10-minute drive has only a few turns / lane changes: 2 chances are enough to judge the mission
        Mission("lane-signal", "lane_change_signal", "차선 바꾸기 3초 전 깜빡이", "Signal", 2, 0.8, praise = "차선 깜빡이 잘 켰어요"),
        Mission("turn-signal", "turn_signal_turn", "회전 30m 전 깜빡이", "Signal", 2, 0.8, praise = "회전 깜빡이 잘 켰어요"),
        Mission("soft-launch", "smooth_launch", "부드럽게 출발하기", "Brake", 3, 0.8, praise = "부드럽게 출발했어요"),
        Mission("school-zone", "school_zone", "스쿨존 시속 30km", "Speed", 1, 1.0, praise = "스쿨존 속도 잘 지켰어요"),
        Mission("speed-limit", "speed_limit", "제한속도 지키기", "Speed", 3, 0.8, praise = "제한속도 잘 지켰어요"),
        Mission("rain-slow", "rain_speed", "비 오는 날 20% 천천히", "Speed", 2, 0.8, rainOnly = true, praise = "빗길에서 천천히 잘 달렸어요"),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
    fun byHabit(h: String) = ALL.firstOrNull { it.habit == h }
}

data class ShopEntry(val id: String, val name: String, val brand: String, val category: String, val price: Int, val art: String, val image: String? = null)

object ShopCatalog {
    /** Mock exchange (F8: partners not decided). Same items and prices as the front-end sample. */
    val DEFAULT = listOf(
        ShopEntry("bluemembers", "블루멤버스 포인트 전환", "블루멤버스", "Car", 1000, "1,000P"),
        ShopEntry("bluehands", "무상 점검 쿠폰", "블루핸즈", "Car", 3000, "점검"),
        ShopEntry("epit", "충전 크레딧 5kWh", "E-pit", "Car", 1500, "5kWh"),
        ShopEntry("coffee", "아메리카노 쿠폰", "편의점 커피", "Life", 1643, "COFFEE", "reward_coffee"),
        ShopEntry("mart10", "마트 10% 할인권", "마트", "Life", 800, "10%"),
        ShopEntry("store5", "편의점 5% 할인권", "편의점", "Life", 400, "5%"),
        ShopEntry("culture", "문화상품권 5천원", "문화상품권", "Culture", 5000, "5,000원"),
        ShopEntry("coin", "게임 코인 100개", "게임", "Culture", 1200, "100"),
        ShopEntry("raincoat", "캐릭터 우비", "캐릭터", "Atlas", 600, "우비", "atlas_cheer"),
        ShopEntry("busan-voice", "부산 사투리 목소리", "캐릭터", "Atlas", 900, "목소리", "atlas_joy"),
    )
}

data class QuizItem(val id: String, val question: String, val choices: List<String>, val answer: Int, val explanation: String, val tags: Set<String>, val tip: String)

object QuizBank {
    /** 차 신호로 판정할 수 없는 기준을 퀴즈와 출발 전 팁으로 다룬다 (plan v0.4 3-2, F8 ③). */
    val ALL = listOf(
        QuizItem("q-roundabout", "회전교차로에 들어갈 때 누가 먼저 갈까요?", listOf("들어가려는 차", "이미 돌고 있는 차", "오른쪽에 있는 차"), 1,
            "들어가는 차가 양보해요. 나갈 때 오른쪽 깜빡이를 켜요.", setOf("turn"), "회전교차로는 들어가는 차가 양보해요"),
        QuizItem("q-rain", "비 오는 날 젖은 길에서는 제한속도보다 얼마나 줄일까요?", listOf("10%", "20%", "줄이지 않아도 돼요"), 1,
            "노면이 젖으면 20% 줄여요. 폭우·안개·눈길은 50%예요.", setOf("rain"), "비 오는 날은 제한속도의 80%로 달려요"),
        QuizItem("q-signal30", "회전할 때 깜빡이는 언제부터 켤까요?", listOf("회전 지점 10m 전", "회전 지점 30m 전", "돌기 시작할 때"), 1,
            "시내에서는 30m 전, 고속도로에서는 100m 전부터 켜요.", setOf("turn"), "회전 30m 전부터 깜빡이를 켜요"),
        QuizItem("q-lane3s", "차선을 바꿀 때 깜빡이는 몇 초 전에 켜면 좋을까요?", listOf("바꾸면서", "1초 전", "3초 전"), 2,
            "3초 전에 켜면 뒤차가 알아채고 속도를 맞출 시간이 생겨요.", setOf("lane"), "차선 바꾸기 3초 전에 깜빡이"),
        QuizItem("q-school", "어린이보호구역의 제한속도는?", listOf("시속 30km", "시속 40km", "시속 50km"), 0,
            "어린이보호구역은 시속 30km예요. 횡단보도 앞에서는 꼭 멈춰요.", setOf("school"), "스쿨존은 사람이 없어도 천천히"),
        QuizItem("q-rightturn", "빨간불일 때 우회전하려면?", listOf("천천히 바로 돈다", "멈췄다가 보행자를 보고 돈다", "경적을 울리고 돈다"), 1,
            "빨간불 우회전은 일단 멈춘 뒤 보행자가 없을 때 천천히 돌아요.", setOf("turn"), "우회전 전에는 한 번 멈춰요"),
        QuizItem("q-yellow", "노란불이 켜지면?", listOf("서둘러 지나간다", "멈출 수 있으면 멈춘다", "속도를 유지한다"), 1,
            "노란불은 '서두르라'가 아니라 '멈춤'이에요. 이미 교차로에 들어갔다면 빠져나가요.", setOf("general"), "노란불은 '멈춤'이에요"),
        QuizItem("q-railroad", "철길 건널목 앞에서는?", listOf("천천히 지나간다", "일시정지 후 안전을 확인한다", "경보가 없으면 그냥 간다"), 1,
            "철길 앞에서는 모든 차가 멈춰서 좌우를 확인해요.", setOf("railroad"), "철길 앞에서는 꼭 멈춰요"),
        QuizItem("q-belt", "안전벨트는 어느 자리에서 매야 할까요?", listOf("앞자리만", "모든 자리", "고속도로에서만"), 1,
            "2018년부터 모든 도로에서 모든 좌석이 안전벨트를 매야 해요.", setOf("general"), "출발 전 모두 안전벨트"),
        QuizItem("q-rest", "장거리 운전은 얼마마다 쉬면 좋을까요?", listOf("4시간", "2시간", "쉬지 않아도 돼요"), 1,
            "2시간마다 10분 이상 쉬면 졸음운전을 줄일 수 있어요.", setOf("long"), "2시간마다 쉬어 가요"),
        QuizItem("q-headway", "앞차와의 안전거리를 쉽게 재는 방법은?", listOf("앞차 번호판이 보이면 충분", "표지판을 지난 뒤 4초 세기", "1초 세기"), 1,
            "앞차가 지나간 표지판을 4초 세고 지나가면 안전거리예요.", setOf("general"), "앞차가 지나간 표지판에서 4초 세기"),
        QuizItem("q-lights", "비가 와서 어두울 때 전조등은?", listOf("켜지 않아도 된다", "켠다", "안개등만 켠다"), 1,
            "밤과 비·안개·눈이 올 때는 전조등을 켜요. 주간주행등만으로는 뒤가 어두워요.", setOf("rain", "night"), "비 오는 날은 전조등을 켜요"),
    )

    /** One question a day: related to today's driving first (rain -> rain, turns -> turn questions), otherwise rotate. */
    fun pick(date: java.time.LocalDate, tags: Set<String>): QuizItem {
        val related = ALL.filter { q -> q.tags.any { it in tags } }
        val pool = related.ifEmpty { ALL }
        return pool[(date.toEpochDay() % pool.size).toInt()]
    }

    fun tipOf(date: java.time.LocalDate): String = ALL[((date.toEpochDay() + 3) % ALL.size).toInt()].tip
}
