package com.safelog.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.safelog.app.data.FakeSafeLogRepository
import com.safelog.app.data.SafeLogRepository
import com.safelog.app.model.Account
import com.safelog.app.model.AtlasStage
import com.safelog.app.model.formatPhone
import com.safelog.app.model.isPhoneNumber
import com.safelog.app.model.MileageGrade
import com.safelog.app.model.Practice
import com.safelog.app.model.ShopCategory
import com.safelog.app.model.ShopItem
import com.safelog.app.model.atlasStage
import com.safelog.app.model.cost
import com.safelog.app.model.reachedInsuranceGoal
import com.safelog.app.model.PracticeKind
import com.safelog.app.model.togglePractice
import com.safelog.app.ui.components.keepWords
import com.safelog.app.ui.screens.missionTint
import com.safelog.app.ui.theme.DarkColors
import com.safelog.app.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val practices = listOf(
        Practice("a", "깜빡이1", PracticeKind.Signal),
        Practice("b", "깜빡이2", PracticeKind.Signal),
        Practice("c", "정지", PracticeKind.Brake),
        Practice("d", "속도", PracticeKind.Speed),
    )

    @Test
    fun togglePracticeRules() {
        assertEquals(listOf("a"), togglePractice(emptyList(), practices, "a", 1))
        assertEquals(emptyList<String>(), togglePractice(listOf("a"), practices, "a", 1))
        assertEquals(listOf("c"), togglePractice(listOf("a"), practices, "c", 1))
        // 같은 종류는 교체된다
        assertEquals(listOf("c", "b"), togglePractice(listOf("a", "c"), practices, "b", 2))
        // 개수가 차면 가장 먼저 고른 것이 빠진다
        assertEquals(listOf("c", "d"), togglePractice(listOf("a", "c"), practices, "d", 2))
    }

    @Test
    fun keepWordsBreaksOnlyAtSpaces() {
        assertEquals("멈\u2060췄 어\u2060요", keepWords("멈췄 어요"))
        assertEquals("", keepWords(""))
    }

    private fun contrast(fg: Color, ground: Color): Float {
        val a = fg.compositeOver(ground)
        val hi = maxOf(a.luminance(), ground.luminance())
        val lo = minOf(a.luminance(), ground.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** 공식 기준: 명도 대비 4.5:1 이상 */
    @Test
    fun colorsMeetContrast() {
        listOf(LightColors, DarkColors).forEach { c ->
            listOf(c.background, c.surface, c.surfaceHigh).forEach { ground ->
                listOf(c.ink, c.inkMuted, c.accent, c.reward, c.positive).forEach { fg ->
                    assertTrue("$fg on $ground = ${contrast(fg, ground)}", contrast(fg, ground) >= 4.5f)
                }
            }
            assertTrue(contrast(c.onAccent, c.accent) >= 4.5f)
            assertTrue(contrast(c.onCoach, c.coach) >= 4.5f)
            // 주행 화면 미션 카드: 옅은 파랑 면 위 파란 글씨
            assertTrue("mission", contrast(c.accent, missionTint(c.accent, c.background)) >= 4.5f)
            assertTrue(contrast(c.onButtonFilled, c.buttonFilled) >= 4.5f)
            assertTrue(contrast(c.onButtonFilled, c.buttonFilledPressed) >= 4.5f)
        }
    }

    @Test
    fun shopCostAndExchange() {
        val item = ShopItem("x", "x", "x", ShopCategory.Life, 1643, "x")
        assertEquals(1643, item.cost(MileageGrade.Basic))
        assertEquals(1560, item.cost(MileageGrade.Silver)) // 홈의 "커피 쿠폰까지 320P"와 맞물린다
        val repo = FakeSafeLogRepository()
        val coffee = repo.shopItems.value.first { it.id == "coffee" }
        assertEquals(repo.briefing.value.pointsBalance + repo.briefing.value.nextReward.remainingPoints, coffee.cost(repo.records.value.grade))
        assertTrue(!repo.exchange(coffee))
        assertTrue(repo.exchange(repo.shopItems.value.first { it.id == "mart10" }))
        assertEquals(1240 - 760, repo.briefing.value.pointsBalance)
    }

    @Test
    fun atlasStagesAndEquip() {
        assertEquals(AtlasStage.Baby, atlasStage(4))
        assertEquals(AtlasStage.Child, atlasStage(5))
        assertEquals(AtlasStage.Grown, atlasStage(11))
        val repo = FakeSafeLogRepository()
        repo.selectVoice("calm")
        assertEquals("calm", repo.atlas.value.voice)
    }

    @Test
    fun calendarDayLabel() {
        assertEquals("11월 4일 수요일", com.safelog.app.ui.screens.dayLabel(java.time.LocalDate.of(2026, 11, 4)))
    }

    @Test
    fun sampleProfileStepAndAddDriver() {
        // 샘플 화면도 실제와 같은 규칙: 경력이 시작 모드를, 이 차가 처음인지가 내 차 익히기 권유를 정한다.
        val repo = FakeSafeLogRepository(firstRunDone = false, recordingConsent = false)
        assertEquals(false, repo.profileDone.value)
        repo.finishFirstRun(consent = true, atlasName = null)
        repo.setDriverProfile(com.safelog.app.model.ProfileAnswer("정훈", com.safelog.app.model.DrivingExperience.Experienced, carFamiliar = true))
        assertEquals(true, repo.profileDone.value)
        assertEquals(com.safelog.app.model.DriveMode.Normal, repo.briefing.value.mode)
        assertEquals("정훈", repo.briefing.value.driverName)
        assertEquals(emptyList<String>(), repo.selectedPracticeIds.value)
        assertEquals(false, repo.carTour.value.offer)

        val before = repo.drivers.value.size
        repo.addDriver(com.safelog.app.model.ProfileAnswer(null, com.safelog.app.model.DrivingExperience.Returning, carFamiliar = false))
        assertEquals(before + 1, repo.drivers.value.size)
        assertEquals(repo.drivers.value.last().id, repo.driverId.value)
        assertEquals("운전자 ${before + 1}", repo.briefing.value.driverName)
        assertEquals(com.safelog.app.model.DriveMode.Training, repo.briefing.value.mode)
        assertEquals(true, repo.carTour.value.offer)
        while (repo.drivers.value.size < com.safelog.app.model.MAX_DRIVERS) repo.addDriver(com.safelog.app.model.ProfileAnswer(null, com.safelog.app.model.DrivingExperience.Novice, true))
        repo.addDriver(com.safelog.app.model.ProfileAnswer("넘침", com.safelog.app.model.DrivingExperience.Novice, true))
        assertEquals(com.safelog.app.model.MAX_DRIVERS, repo.drivers.value.size)
    }

    @Test
    fun quizAnswersOnce() {
        val repo = FakeSafeLogRepository()
        val before = repo.briefing.value.pointsBalance
        repo.answerQuiz(1) // 정답
        repo.answerQuiz(0) // 두 번째 답은 무시
        assertEquals(1, repo.quiz.value?.picked)
        assertEquals(before + 5, repo.briefing.value.pointsBalance)
        val wrong = FakeSafeLogRepository().apply { answerQuiz(0) }
        assertEquals(before + 2, wrong.briefing.value.pointsBalance)
    }

    @Test
    fun nextRewardProgress() {
        val r = com.safelog.app.model.NextReward("커피 쿠폰", 320, 1560)
        assertEquals(1240f / 1560, r.progress, 0.001f)
        assertEquals(1f, r.progressAt(-10), 0f)
        // 도착 뒤 남은 양은 출발 전보다 받은 포인트만큼 적다
        val brief = FakeSafeLogRepository.SAMPLE_BRIEFING.nextReward.remainingPoints
        val arrival = FakeSafeLogRepository.SAMPLE_ARRIVAL
        assertEquals(brief - arrival.points, arrival.nextReward.remainingPoints)
    }

    @Test
    fun driverSwitchAndClear() {
        val repo = FakeSafeLogRepository()
        repo.selectDriver("jihoon")
        assertEquals("지훈", repo.briefing.value.driverName)
        assertEquals(6, repo.atlas.value.level)
        val before = repo.briefing.value.pointsBalance
        repo.clearRecords()
        assertTrue(repo.records.value.journal.isEmpty())
        assertEquals(before, repo.briefing.value.pointsBalance) // 포인트는 남는다
    }

    @Test
    fun trainingModeSwitch() {
        val repo = FakeSafeLogRepository()
        repo.selectDriver("jihoon")
        repo.setTrainingMode(true) // 직접 켜면 알림 없이 추천 미션을 골라 둔다
        assertEquals(com.safelog.app.model.DriveMode.Training, repo.briefing.value.mode)
        assertEquals(com.safelog.app.model.DriveMode.Training, repo.drivers.value.first { it.id == "jihoon" }.mode)
        assertEquals(repo.briefing.value.practices.filter { it.recommended }.map { it.id }.take(1), repo.selectedPracticeIds.value)
        assertEquals(null, repo.modeNotice.value)
        repo.demoModeChange() // 판정 쪽이 끄면 알림
        assertEquals(com.safelog.app.model.ModeNotice.TrainingOff, repo.modeNotice.value)
        repo.dismissModeNotice()
        assertEquals(null, repo.modeNotice.value)
        repo.demoModeChange() // 평소 모드에서는 권하기만 한다. 모드는 그대로다
        assertEquals(com.safelog.app.model.ModeNotice.TrainingOffer, repo.modeNotice.value)
        assertEquals(com.safelog.app.model.DriveMode.Normal, repo.briefing.value.mode)
        // 오판정 의견과 다시 해 보기(샘플 화면)
        val scene = repo.records.value.journal.first().scenes.first()
        repo.disputeScene(scene.id)
        assertEquals(true, repo.records.value.journal.first().scenes.first().disputed)
        repo.demoCarStep()
        val done = repo.carTour.value.required.first { it.done }
        repo.retryCarStep(done.id)
        assertEquals(false, repo.carTour.value.required.first { it.id == done.id }.done)
    }

    /** 잔액이 바뀌면 다음 보상까지 남은 양도 같은 식으로 바뀐다(교환·퀴즈·도착) */
    @Test
    fun balanceKeepsNextRewardInSync() {
        val repo = FakeSafeLogRepository()
        fun check() = with(repo.briefing.value) { assertEquals((nextReward.price - pointsBalance).coerceAtLeast(0), nextReward.remainingPoints) }
        check()
        repo.exchange(FakeSafeLogRepository.SAMPLE_SHOP.first { it.id == "store5" }); check()
        repo.answerQuiz(1); check()
        val before = repo.briefing.value.pointsBalance
        repo.demoDrive(); repo.demoPark(); check()
        assertEquals(before + repo.arrival.value!!.points, repo.briefing.value.pointsBalance)
        assertEquals(repo.briefing.value.nextReward, repo.arrival.value!!.nextReward)
    }

    /** 미션 없이 도착하면 미션 포인트 없이 기본 적립만 */
    @Test
    fun arrivalWithoutMissionHasNoMissionPoints() {
        val repo = FakeSafeLogRepository()
        repo.selectPractices(emptyList())
        repo.demoDrive(); repo.demoPark()
        val a = repo.arrival.value!!
        assertEquals(null, a.practiceResult)
        assertEquals(FakeSafeLogRepository.SAMPLE_ARRIVAL.points - FakeSafeLogRepository.SAMPLE_ARRIVAL.practicePoints, a.points)
    }

    /** 연수 모드는 시작·운전자 바꾸기·도착 닫기 뒤에도 추천 미션을 골라 둔다. 평소 모드는 비운다 */
    @Test
    fun trainingPreselects() {
        val repo = FakeSafeLogRepository() // 민수는 연수 모드
        val rec = repo.briefing.value.practices.filter { it.recommended }.map { it.id }.take(1)
        assertEquals(rec, repo.selectedPracticeIds.value)
        repo.selectDriver("jihoon")
        assertTrue(repo.selectedPracticeIds.value.isEmpty())
        repo.selectDriver("minsu")
        assertEquals(rec, repo.selectedPracticeIds.value)
        repo.demoDrive(); repo.demoPark(); repo.dismissArrival()
        assertEquals(rec, repo.selectedPracticeIds.value)
        assertTrue(FakeSafeLogRepository(recordingConsent = false).selectedPracticeIds.value.isEmpty())
    }

    /** 내 차 익히기: 차가 감지한 순서대로 체크, 꼭 익힐 것을 다 마치면 경험치 한 번, 주행 중에는 진행하지 않음 */
    @Test
    fun carTourProgress() {
        val repo = FakeSafeLogRepository()
        repo.demoNewCar()
        assertTrue(repo.carTour.value.offer)
        repo.dismissCarTourOffer()
        assertTrue(!repo.carTour.value.offer)
        val xp = repo.atlas.value.xp
        val required = repo.carTour.value.required.size
        repeat(required - 1) { repo.demoCarStep() }
        assertEquals(required - 1, repo.carTour.value.doneCount)
        assertEquals(xp, repo.atlas.value.xp)
        repo.demoDrive(); repo.demoCarStep() // 주행 중에는 멈춘다
        assertEquals(required - 1, repo.carTour.value.doneCount)
        repo.demoPark(); repo.demoCarStep()
        assertTrue(repo.carTour.value.finished)
        assertEquals(xp + repo.carTour.value.rewardXp, repo.atlas.value.xp)
        repo.demoCarStep() // 편의 기능은 경험치를 더 주지 않는다
        assertEquals(xp + repo.carTour.value.rewardXp, repo.atlas.value.xp)
        assertEquals(1, repo.carTour.value.extras.count { it.done })
    }

    /** 건너뛴 단계는 다음으로 넘어가지만 완료로 세지 않는다. 나중에 감지되면 채워진다 */
    @Test
    fun carTourSkip() {
        val repo = FakeSafeLogRepository()
        val first = repo.carTour.value.next!!.id
        repo.skipCarStep(first)
        assertTrue(repo.carTour.value.next!!.id != first)
        assertEquals(0, repo.carTour.value.doneCount)
        repeat(repo.carTour.value.required.size - 1) { repo.demoCarStep() }
        assertTrue(!repo.carTour.value.finished) // 건너뛴 하나가 남아 배지는 아직
        repo.carTour.value = repo.carTour.value.update(first) { it.copy(done = true) } // 차가 나중에 감지
        assertTrue(repo.carTour.value.finished)
    }

    @Test
    fun avatarChangesOnlyThatDriver() {
        val repo = FakeSafeLogRepository()
        repo.setAvatar("minsu", com.safelog.app.model.Avatar.Letter(3))
        assertEquals(com.safelog.app.model.Avatar.Letter(3), repo.drivers.value.first { it.id == "minsu" }.avatar)
        assertEquals(FakeSafeLogRepository.SAMPLE_DRIVERS.first { it.id == "seoyeon" }.avatar, repo.drivers.value.first { it.id == "seoyeon" }.avatar)
    }

    @Test
    fun insuranceOnlyCountsUp() {
        val p = FakeSafeLogRepository.SAMPLE_INSURANCE
        assertEquals(180, p.remainingKm)
        assertEquals(0, p.copy(safeKm = 1200).remainingKm) // 넘쳐도 음수가 아니다
        assertEquals(1f, p.copy(safeKm = 1200).progress, 0f)
        assertEquals("약 1,240원어치", com.safelog.app.model.wonValue(1240))
    }

    @Test
    fun insuranceTripsAndGoal() {
        val p = FakeSafeLogRepository.SAMPLE_INSURANCE // 820/1000km, 한 번 30km
        assertEquals(6, p.tripsLeft) // 180km → 올림 6번
        assertEquals(1, p.copy(safeKm = 999).tripsLeft)
        assertEquals(0, p.copy(safeKm = 1000).tripsLeft)
        assertTrue(!FakeSafeLogRepository.SAMPLE_ARRIVAL.reachedInsuranceGoal())
        assertTrue(FakeSafeLogRepository.SAMPLE_ARRIVAL_GOAL.reachedInsuranceGoal())
        // 이미 채운 뒤의 주행은 다시 축하하지 않는다
        val after = FakeSafeLogRepository.SAMPLE_ARRIVAL_GOAL.copy(insurance = p.copy(safeKm = 1100))
        assertTrue(!after.reachedInsuranceGoal())
    }

    @Test
    fun arrivalPointsMatchMission() {
        // 홈에서 보여 준 미션 보상과 도착 때 받은 미션 포인트가 같아야 한다.
        val practice = FakeSafeLogRepository.SAMPLE_BRIEFING.practices.first { it.recommended }
        val arrival = FakeSafeLogRepository.SAMPLE_ARRIVAL
        assertEquals(practice.reward, arrival.practicePoints)
        assertTrue(arrival.points >= arrival.practicePoints) // points는 미션 포인트를 포함한 합계
    }

    @Test
    fun phoneFormat() {
        assertEquals("010", formatPhone("010"))
        assertEquals("010-1234", formatPhone("0101234"))
        assertEquals("010-1234-5", formatPhone("01012345")) // 치는 동안 줄표가 옮겨 다니지 않는다
        assertEquals("010-1234-5678", formatPhone("01012345678"))
        assertEquals("011-123-4567", formatPhone("0111234567"))
        assertEquals("010-****-5678", Account("민수", "01012345678").maskedPhone)
        assertTrue(isPhoneNumber("01012345678"))
        assertTrue(isPhoneNumber("0111234567"))
        assertTrue(!isPhoneNumber("0101234567")) // 010은 11자리
        assertTrue(!isPhoneNumber("02123456789"))
    }

    @Test
    fun demoSignUp() {
        val repo = FakeSafeLogRepository()
        assertTrue(!repo.signUp("민수", "01012345678", SafeLogRepository.DEMO_WRONG_CODE))
        assertTrue(!repo.signUp("민수", "01012345678", "123"))
        assertTrue(repo.accounts.value.isEmpty())
        assertTrue(repo.signUp("민수", "01012345678", "123456"))
        assertEquals(Account("민수", "01012345678"), repo.accounts.value[repo.driverId.value])
        // 계정은 운전자마다 따로다
        repo.selectDriver("jihoon")
        assertEquals(null, repo.accounts.value[repo.driverId.value])
    }
}
