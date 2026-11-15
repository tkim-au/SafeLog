package com.safelog.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safelog.app.ui.components.PopCheck
import com.safelog.app.ui.components.drawPopBurst
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.resources.Density
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.safelog.app.data.FakeSafeLogRepository
import com.safelog.app.model.DrivePhase
import com.safelog.app.model.GoodHabit
import com.safelog.app.ui.Destination
import com.safelog.app.ui.screens.AppTopBar
import com.safelog.app.ui.SafeLogApp
import com.safelog.app.ui.screens.DrivingScreen
import com.safelog.app.ui.theme.SafeLogTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 화면을 이미지로 그려 확인한다. 창 크기는 Figma "Form factors & Display"의 공식 값이다.
 */
@RunWith(Parameterized::class)
class ScreensSnapshotTest(private val case: Case) {
    data class Case(val name: String, val config: DeviceConfig, val dark: Boolean) {
        override fun toString() = name
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = case.config,
        theme = "android:Theme.Material.Light.NoActionBar",
        showSystemUi = false,
    )

    private fun snap(name: String, content: @Composable () -> Unit) =
        paparazzi.snapshot(name) {
            // 스냅샷 환경에는 Activity가 없어 뒤로 가기 처리기를 직접 넣어 준다.
            val lifecycleOwner = LocalLifecycleOwner.current
            val backOwner = remember {
                object : OnBackPressedDispatcherOwner, LifecycleOwner by lifecycleOwner {
                    override val onBackPressedDispatcher = OnBackPressedDispatcher()
                }
            }
            // 검사 모드를 켜서 팝업처럼 나타나며 움직이는 것은 다 나타난 모습으로 찍는다.
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides backOwner,
                androidx.compose.ui.platform.LocalInspectionMode provides true,
            ) {
                SafeLogTheme(dark = case.dark, content = content)
            }
        }

    @Test
    fun firstRun() = snap("s00") { SafeLogApp(FakeSafeLogRepository(firstRunDone = false, recordingConsent = false)) }

    @Test
    fun briefingNoConsent() = snap("s10_no_consent") { SafeLogApp(FakeSafeLogRepository(recordingConsent = false)) }

    @Test
    fun briefing() = snap("s10") { SafeLogApp(FakeSafeLogRepository()) }

    @Test
    fun briefingSelected() = snap("s10_selected") {
        SafeLogApp(FakeSafeLogRepository().apply { selectPractices(listOf("lane-signal")) })
    }

    @Test
    fun arrival() = snap("s30") {
        SafeLogApp(FakeSafeLogRepository().apply { arrival.value = FakeSafeLogRepository.SAMPLE_ARRIVAL })
    }

    @Test
    fun arrivalRisky() = snap("s30_risky") {
        SafeLogApp(
            FakeSafeLogRepository().apply {
                arrival.value = FakeSafeLogRepository.SAMPLE_ARRIVAL.copy(
                    topHabit = null, otherHabits = emptyList(), points = 0, xp = 0, practiceResult = null,
                )
            },
        )
    }

    /** 팝 버스트 장면을 진행도 0.1·0.3·0.5·0.7·0.9로 나란히 그린다. 움직임을 이미지로 확인하는 용도다. */
    @Test
    fun popBurstFrames() = snap("pop_burst") {
        val accent = SafeLogTheme.colors.accent
        Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f).forEach { p ->
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(64.dp)) { drawPopBurst(p, accent) }
                    PopCheck(true, SafeLogTheme.colors.accent, SafeLogTheme.colors.onAccent, SafeLogTheme.colors.surfaceHigh)
                }
            }
        }
    }

    /** 미션 클리어 연출의 세 단계(시작, 체크, 포인트 반영)를 위에서 아래로 그린다. */
    @Test
    fun missionClearSteps() = snap("mission_clear") {
        val arrival = FakeSafeLogRepository.SAMPLE_ARRIVAL
        val briefing = FakeSafeLogRepository.SAMPLE_BRIEFING
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(0, 1, 2).forEach { step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(width = 400.dp, height = 160.dp)) { com.safelog.app.ui.screens.MissionResultCard(arrival, step) }
                    com.safelog.app.ui.screens.PointsCard(briefing, arrival, step, Modifier.size(width = 240.dp, height = 200.dp))
                }
            }
        }
    }

    /** 나쁜 경우의 데이터: 큰 숫자, 긴 글 */
    @Test
    fun arrivalWorstCase() = snap("s30_worst") {
        SafeLogApp(
            FakeSafeLogRepository().apply {
                arrival.value = FakeSafeLogRepository.SAMPLE_ARRIVAL.copy(
                    topHabit = GoodHabit("비 오는 날 신호 앞 부드러운 정지와 출발", success = 128, chances = 128),
                    otherHabits = listOf(GoodHabit("차선 변경 3초 전 깜빡이와 회전 전 깜빡이", 99, 100)),
                    points = 3000,
                    nextChallenge = "고속도로 합류할 때 100m 전에 깜빡이 켜고 천천히 들어가기",
                )
            },
        )
    }

    // 탭 화면. 시작 탭을 바꿀 수 없어 화면을 직접 그리고, 맨 위 줄은 그 탭을 고른 모습으로 넣는다.
    /** 탭 화면 맨 위 줄. 앱과 같은 값(샘플 운전자, 연수 모드)으로 [tab]을 고른 모습 */
    @Composable
    private fun tabBar(tab: Destination?) {
        val repo = remember { FakeSafeLogRepository() }
        AppTopBar(
            tab = tab, onSelectTab = {}, mode = repo.briefing.value.mode, recordingConsent = true,
            driver = repo.drivers.value.first { it.id == repo.driverId.value }, onRequestConsent = {}, onOpen = {}, onOpenMode = {},
        )
    }

    @Test
    fun records() = snap("s40_records") {
        com.safelog.app.ui.screens.RecordsScreen(FakeSafeLogRepository.SAMPLE_RECORDS, { tabBar(Destination.Records) }, today = java.time.LocalDate.of(2026, 11, 6))
    }

    /** 오판정 의견: 확인 팝업과, 의견을 남긴 장면의 꼬리표 */
    @Test
    fun recordsDispute() = snap("s40_records_dispute") {
        val r = FakeSafeLogRepository.SAMPLE_RECORDS
        val first = r.journal.maxBy { it.date }.scenes.first().id
        com.safelog.app.ui.screens.RecordsScreen(r, { tabBar(Destination.Records) }, today = java.time.LocalDate.of(2026, 11, 6), initialDisputeId = first)
    }

    @Test
    fun recordsDisputed() = snap("s40_records_disputed") {
        val repo = remember { FakeSafeLogRepository().apply { disputeScene(records.value.journal.maxBy { it.date }.scenes.first().id) } }
        com.safelog.app.ui.screens.RecordsScreen(repo.records.value, { tabBar(Destination.Records) }, today = java.time.LocalDate.of(2026, 11, 6))
    }

    @Test
    fun recordsInsurance() = snap("s40_records_insurance") {
        com.safelog.app.ui.screens.RecordsScreen(FakeSafeLogRepository.SAMPLE_RECORDS, { tabBar(Destination.Records) }, today = java.time.LocalDate.of(2026, 11, 6), insuranceShare = true)
    }

    @Test
    fun shop() = snap("s50_shop") { shopScreen(detail = null) }

    @Test
    fun shopDetail() = snap("s50_shop_detail") { shopScreen(detail = "mart10") }

    @Test
    fun shopDetailShort() = snap("s50_shop_detail_short") { shopScreen(detail = "coffee") }

    @Test
    fun shopExchanged() = snap("s50_shop_exchanged") { shopScreen(detail = "store5", exchanged = true) }

    @Composable
    private fun shopScreen(detail: String?, exchanged: Boolean = false) {
        val repo = remember { FakeSafeLogRepository() }
        val b = repo.briefing.value
        com.safelog.app.ui.screens.ShopScreen(
            b.pointsBalance, b.nextReward, repo.records.value.grade, repo.shopItems.value, { true }, { tabBar(Destination.Shop) },
            initialDetailId = detail, initialDone = exchanged,
        )
    }

    @Test
    fun quiz() = snap("s32_quiz") { com.safelog.app.ui.screens.QuizScreen(FakeSafeLogRepository.SAMPLE_QUIZ, {}, {}) }

    @Test
    fun quizWrong() = snap("s32_quiz_wrong") {
        com.safelog.app.ui.screens.QuizScreen(FakeSafeLogRepository.SAMPLE_QUIZ.copy(picked = 0), {}, {})
    }

    // 첫 주, 첫 주행 전: 기록·포인트가 없다. 실제 첫인상이다.
    private fun firstWeek() = FakeSafeLogRepository().apply {
        briefing.value = briefing.value.copy(
            pointsBalance = 0,
            nextReward = com.safelog.app.model.NextReward("편의점 5% 할인권", remainingPoints = 400, price = 400),
            weekMissions = GoodHabit("이번 주 달성한 미션", success = 0, chances = 0),
            attendance = com.safelog.app.model.Attendance(List(7) { false }, weekStreak = 0),
        )
        records.value = records.value.copy(
            weekDrives = 0, goodMoments = 0, safeMileageKm = 0, grade = com.safelog.app.model.MileageGrade.Basic,
            habits = emptyList(), journal = emptyList(),
        )
    }

    @Test
    fun firstWeekHome() = snap("first_week_home") { SafeLogApp(remember { firstWeek() }) }

    @Test
    fun firstWeekRecords() = snap("first_week_records") {
        com.safelog.app.ui.screens.RecordsScreen(firstWeek().records.value, { tabBar(Destination.Records) }, today = java.time.LocalDate.of(2026, 11, 2))
    }

    @Test
    fun firstWeekShop() = snap("first_week_shop") {
        val repo = remember { firstWeek() }
        val b = repo.briefing.value
        com.safelog.app.ui.screens.ShopScreen(b.pointsBalance, b.nextReward, repo.records.value.grade, repo.shopItems.value, { true }, { tabBar(Destination.Shop) })
    }

    @Test
    fun atlas() = snap("s55_atlas") {
        com.safelog.app.ui.screens.AtlasScreen(FakeSafeLogRepository.SAMPLE_ATLAS, {}, { tabBar(Destination.Atlas) })
    }

    @Test
    fun settings() = snap("s90_settings") { settingsScreen(consent = true) }

    @Test
    fun settingsNoConsent() = snap("s90_settings_no_consent") { settingsScreen(consent = false) }

    @Composable
    private fun settingsScreen(consent: Boolean) = com.safelog.app.ui.screens.SettingsScreen(
        com.safelog.app.model.AppSettings(insuranceShare = true), FakeSafeLogRepository.SAMPLE_DRIVERS.first(), consent,
        {}, {}, {}, {}, {}, {},
        carTour = FakeSafeLogRepository.SAMPLE_CAR_TOUR,
    )

    @Test
    fun settingsAccount() = snap("s90_settings_account") {
        com.safelog.app.ui.screens.SettingsScreen(
            com.safelog.app.model.AppSettings(insuranceShare = true), FakeSafeLogRepository.SAMPLE_DRIVERS.first(), true,
            {}, {}, {}, {}, {}, {},
            carTour = FakeSafeLogRepository.SAMPLE_CAR_TOUR,
            account = com.safelog.app.model.Account("민수", "01012345678"),
        )
    }

    @Test
    fun signUpEmpty() = snap("signup_empty") { signUp(step = 0, phone = "", agreed = false) }

    @Test
    fun signUp() = snap("signup") { signUp(step = 0) }

    @Test
    fun signUpCode() = snap("signup_code") { signUp(step = 1, code = "1234") }

    @Test
    fun signUpWrong() = snap("signup_wrong") { signUp(step = 1, code = "000000", wrong = true) }

    @Test
    fun signUpDone() = snap("signup_done") { signUp(step = 2) }

    @Composable
    private fun signUp(step: Int, phone: String = "01012345678", agreed: Boolean = true, code: String = "", wrong: Boolean = false) =
        com.safelog.app.ui.screens.SignUpScreen(
            "민수", {}, { _, _, _ -> true }, {},
            initialStep = step, initialPhone = phone, initialAgreed = agreed, initialCode = code, initialWrong = wrong,
        )

    @Test
    fun drivers() = snap("drivers") {
        com.safelog.app.ui.screens.DriverPickerScreen(FakeSafeLogRepository.SAMPLE_DRIVERS, "minsu", {}, { _, _ -> }, {}, onAdd = {})
    }

    /** 운전자가 다섯이면 추가 칸이 없다. */
    @Test
    fun driversFull() = snap("drivers_full") {
        val five = FakeSafeLogRepository.SAMPLE_DRIVERS + listOf(
            com.safelog.app.model.Driver("d4", "은영", com.safelog.app.model.DriveMode.Training, level = 1),
            com.safelog.app.model.Driver("d5", "정훈", com.safelog.app.model.DriveMode.Normal, level = 3, avatar = com.safelog.app.model.Avatar.Letter(4)),
        )
        com.safelog.app.ui.screens.DriverPickerScreen(five, "minsu", {}, { _, _ -> }, {}, onAdd = {})
    }

    @Test
    fun driverAdd() = snap("driver_add") {
        com.safelog.app.ui.screens.AddDriverScreen(number = 4, onAdd = {}, onBack = {})
    }

    @Test
    fun driverAddAnswered() = snap("driver_add_answered") {
        com.safelog.app.ui.screens.AddDriverScreen(
            number = 4, onAdd = {}, onBack = {},
            initialExperience = com.safelog.app.model.DrivingExperience.Returning, initialCarFamiliar = false,
        )
    }

    /** 앱을 열었을 때 운전자가 둘 이상이면 운전자 고르기부터 보인다. */
    @Test
    fun startAsksWhoDrives() = snap("start_who_drives") { SafeLogApp(FakeSafeLogRepository(), askDriver = true) }

    @Test
    fun driversEditing() = snap("drivers_editing") {
        com.safelog.app.ui.screens.DriverPickerScreen(FakeSafeLogRepository.SAMPLE_DRIVERS, "minsu", {}, { _, _ -> }, {}, initialEditing = true, onAdd = {})
    }

    /** 사진 고르기 창은 Activity가 있어야 열린다. 스냅샷에서는 빈 등록소를 준다. */
    @Test
    fun avatarPicker() = snap("avatar_picker") {
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) = Unit
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
            com.safelog.app.ui.screens.DriverPickerScreen(FakeSafeLogRepository.SAMPLE_DRIVERS, "minsu", {}, { _, _ -> }, {}, initialEditId = "jihoon")
        }
    }

    @Test
    fun popup() = snap("popup") {
        com.safelog.app.ui.screens.SafeLogPopup(
            title = "기록을 지울까요", body = "운전 일지와 항목별 기록이 지워져요. 포인트는 남아요",
            confirmText = "지우기", dismissText = "취소", onConfirm = {}, onDismiss = {},
            confirmTone = com.safelog.app.ui.components.ButtonTone.Destructive,
        )
    }

    // 보험 할인
    @Test
    fun firstRunInsurance() = snap("s00_insurance") {
        com.safelog.app.ui.screens.FirstRunScreen(onClose = {}, onAgree = { _, _, _ -> }, initialStep = com.safelog.app.ui.screens.STEP_INSURANCE)
    }

    @Test
    fun firstRunProfile() = snap("s00_profile") {
        com.safelog.app.ui.screens.FirstRunScreen(onClose = {}, onAgree = { _, _, _ -> }, initialStep = com.safelog.app.ui.screens.STEP_PROFILE)
    }

    @Test
    fun firstRunProfileAnswered() = snap("s00_profile_answered") {
        com.safelog.app.ui.screens.FirstRunScreen(
            onClose = {}, onAgree = { _, _, _ -> }, initialStep = com.safelog.app.ui.screens.STEP_PROFILE,
            initialExperience = com.safelog.app.model.DrivingExperience.Novice, initialCarFamiliar = false,
        )
    }

    @Test
    fun homeInsuranceInvite() = snap("s10_insurance_invite") {
        SafeLogApp(remember { FakeSafeLogRepository().apply { updateSettings(settings.value.copy(insuranceShare = false)) } })
    }

    /** S05 내 차 익히기: 처음, 세 단계 마친 뒤, 꼭 익힐 것을 다 마친 순간 */
    @Test
    fun carTour() = snap("s05_car_tour") {
        com.safelog.app.ui.screens.CarTourScreen(FakeSafeLogRepository.SAMPLE_CAR_TOUR, onSkip = {}, onBack = {})
    }

    @Test
    fun carTourProgress() = snap("s05_car_tour_progress") {
        val t = FakeSafeLogRepository.SAMPLE_CAR_TOUR
        com.safelog.app.ui.screens.CarTourScreen(t.copy(required = t.required.mapIndexed { i, s -> s.copy(done = i < 3, skipped = i == 4) }), onSkip = {}, onBack = {})
    }

    @Test
    fun carTourDone() = snap("s05_car_tour_done") {
        val t = FakeSafeLogRepository.SAMPLE_CAR_TOUR
        com.safelog.app.ui.screens.CarTourScreen(t.copy(required = t.required.map { it.copy(done = true) }), onSkip = {}, onBack = {}, initialCelebrate = true)
    }

    /** 처음 타는 차: 홈에서 한 번 권한다 */
    @Test
    fun homeCarTourOffer() = snap("s10_car_tour_offer") {
        SafeLogApp(remember { FakeSafeLogRepository().apply { demoNewCar() } })
    }

    /** 판정 쪽이 연수 모드를 켠 순간(평소 모드인 지훈) */
    /** 주행 뒤 연수 모드를 권하는 순간(켜기 / 나중에) */
    @Test
    fun modeOn() = snap("mode_on") {
        SafeLogApp(remember { FakeSafeLogRepository().apply { selectDriver("jihoon"); demoModeChange() } })
    }

    /** 연수 모드가 끝난 순간 */
    @Test
    fun modeOff() = snap("mode_off") {
        SafeLogApp(remember { FakeSafeLogRepository().apply { demoModeChange() } })
    }

    /** 도착 때 보험 카드: 차오르기 전 → 차오른 뒤 → 조건을 채움 */
    @Test
    fun insuranceSteps() = snap("insurance_steps") {
        val p = FakeSafeLogRepository.SAMPLE_INSURANCE
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp).size(width = 560.dp, height = 1100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.safelog.app.ui.screens.InsuranceCard(p, gainedKm = 28, reveal = false)
            com.safelog.app.ui.screens.InsuranceCard(p, gainedKm = 28, reveal = true)
            com.safelog.app.ui.screens.InsuranceCard(p.copy(safeKm = 1000), gainedKm = 28, reveal = true)
        }
    }

    @Test
    fun insuranceGoalPopup() = snap("insurance_goal_popup") {
        com.safelog.app.ui.screens.SafeLogPopup(
            title = "보험 할인 조건을 채웠어요", body = "갱신 때 보험사에 안전 운전 요약을 보내요",
            confirmText = "좋아요", onConfirm = {}, onDismiss = {},
            image = { com.safelog.app.ui.components.AtlasImage(com.safelog.app.ui.components.AtlasMood.Joy, 160.dp) },
        )
    }

    @Test
    fun shopInsurance() = snap("s50_shop_insurance") {
        val repo = remember { FakeSafeLogRepository() }
        val b = repo.briefing.value
        com.safelog.app.ui.screens.ShopScreen(
            b.pointsBalance, b.nextReward, repo.records.value.grade, repo.shopItems.value, { true }, { tabBar(Destination.Shop) },
            insurance = FakeSafeLogRepository.SAMPLE_INSURANCE,
        )
    }

    @Test
    fun driving() = snap("driving") { DrivingScreen(DrivePhase.Driving, 0, null, recording = true) }

    @Test
    fun drivingMission() = snap("driving_mission") {
        DrivingScreen(
            DrivePhase.Driving, 0, null, recording = true,
            missions = FakeSafeLogRepository.SAMPLE_BRIEFING.practices.take(1),
        )
    }

    @Test
    fun idlingMission() = snap("idling_mission") {
        DrivingScreen(
            DrivePhase.Idling, 0, "부드럽게 출발했어요", recording = true,
            missions = FakeSafeLogRepository.SAMPLE_BRIEFING.practices.take(1),
        )
    }

    @Test
    fun drivingNoConsent() = snap("driving_no_consent") { DrivingScreen(DrivePhase.Driving, 0, null, recording = false) }

    @Test
    fun idling() = snap("idling") {
        DrivingScreen(DrivePhase.Idling, 0, "부드럽게 출발했어요", recording = true)
    }

    companion object {
        /** 1dp = 1px로 그린다. 크기는 공식 앱 창 크기(dp)에서 왼쪽 시스템 영역을 뺀 값이다. */
        private fun window(w: Int, h: Int, dark: Boolean = false) = DeviceConfig.PIXEL_5.copy(
            screenWidth = w,
            screenHeight = h,
            density = Density.MEDIUM,
            xdpi = 160,
            ydpi = 160,
            orientation = if (w > h) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
            nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            Case("s17_land", window(1700, 1184), false),
            Case("s17_port", window(840, 1184), false),
            Case("s146_land", window(1360, 948), false),
            Case("s146_port", window(672, 948), false),
            Case("l129_land", window(1274, 855), false),
            Case("l129_port", window(628, 855), false),
            Case("wstd_land", window(1640, 810), false),
            Case("wstd_port", window(800, 810), false),
            Case("wext_land", window(1640, 1400), false),
            Case("wext_port", window(800, 1400), false),
            Case("wf_land", window(1256, 800), false),
            Case("wf_port", window(648, 800), false),
            Case("l129_port_dark", window(628, 855, dark = true), true),
        )
    }
}
