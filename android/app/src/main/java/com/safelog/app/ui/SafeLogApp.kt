package com.safelog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.safelog.app.ui.components.DemoButton
import com.safelog.app.ui.components.DemoPanel
import com.safelog.app.ui.components.FloatingDemoButton
import com.safelog.app.ui.components.LocalDemoButton
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safelog.app.data.SafeLogRepository
import com.safelog.app.ui.components.Motion
import com.safelog.app.ui.components.PushContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import com.safelog.app.ui.components.LocalTabSlide
import com.safelog.app.ui.components.LocalTabSwipeLock
import com.safelog.app.ui.components.TabSlide
import com.safelog.app.ui.components.TabSwipeLock
import com.safelog.app.ui.components.tabSwipe
import com.safelog.app.model.DrivePhase
import com.safelog.app.ui.screens.DrivingScreen
import com.safelog.app.ui.screens.FirstRunScreen
import com.safelog.app.ui.screens.HomeScreen
import com.safelog.app.ui.screens.PlaceholderScreen
import com.safelog.app.ui.screens.QuizScreen
import com.safelog.app.ui.screens.RecordsScreen
import com.safelog.app.ui.screens.SettingsScreen
import com.safelog.app.ui.screens.SignUpScreen
import com.safelog.app.ui.screens.ShopScreen
import com.safelog.app.ui.screens.AtlasScreen
import com.safelog.app.ui.screens.CarTourScreen
import com.safelog.app.ui.screens.DriverPickerScreen
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.model.DriveMode
import com.safelog.app.ui.components.ButtonTone
import com.safelog.app.ui.screens.AppTopBar
import com.safelog.app.ui.screens.SafeLogPopup

/** 위쪽 탭과 하위 화면. 기록·교환소·캐릭터는 탭([menu]), 나머지는 탭 위로 들어가는 하위 화면이다. */
enum class Destination(val label: String) {
    Records("기록"), // S40 내 기록과 S41 운전 일지
    Shop("교환소"),
    Atlas("캐릭터"), // 첫 설정에서 "캐릭터 이름"을 받는 그 캐릭터
    Settings("설정"), // 위쪽 줄 톱니에서 간다
    Drivers("운전자"), // 위쪽 줄 얼굴이나 설정에서 간다
    CarTour("내 차 익히기"), // 홈 권유나 설정에서 간다(기획서 부가 A1)
    SignUp("가입"); // 설정 → 운전자 칸 "계정"에서 간다

    companion object {
        /** 위쪽 탭. "오늘"(홈) 다음에 이 순서로 놓인다. */
        val menu = listOf(Records, Shop, Atlas)
    }
}

/**
 * 기어가 화면을 정한다. 주행·정차면 무엇을 보고 있었든 주행 상태로 바꾸고 나머지 화면은 구성하지 않는다.
 * 보던 화면은 저장해 두어 주차하면 돌아온다.
 */
@Composable
fun SafeLogApp(
    repository: SafeLogRepository,
    // 디버그 빌드의 시연 버튼들. 오른쪽 위 "시연" 단추를 누르면 펼쳐진다. [close]는 판을 닫는다. null이면 단추도 없다.
    demoBar: (@Composable (close: () -> Unit) -> Unit)? = null,
    // 앱을 연 뒤 아직 운전자를 묻지 않았으면 true. 운전자가 둘 이상일 때 "누가 운전하나요?"부터 보인다(기획서 4장 3번).
    // 한 번 보였으면 [onDriverAsked]로 알린다. 주행을 마치고 주차 화면으로 돌아올 때는 다시 묻지 않는다.
    askDriver: Boolean = false,
    onDriverAsked: () -> Unit = {},
) {
    val phase by repository.phase.collectAsStateWithLifecycle()
    val consent by repository.recordingConsent.collectAsStateWithLifecycle()
    var demoOpen by remember { mutableStateOf(false) }
    val demoButton = remember { @Composable { DemoButton(demoOpen) { demoOpen = !demoOpen } } }
    CompositionLocalProvider(LocalDemoButton provides if (demoBar != null) demoButton else null) {
    Box(Modifier.fillMaxSize().background(SafeLogTheme.colors.background)) {
            // P ↔ D: 주차 화면과 주행 화면은 짧게 교차 페이드한다.
            Crossfade(phase == DrivePhase.Parked, animationSpec = tween(Motion.FADE), label = "phase") { parked ->
            if (parked) {
                ParkedContent(repository, askDriver, onDriverAsked)
            } else {
                val praise by repository.praiseCount.collectAsStateWithLifecycle()
                val idleLine by repository.idleLine.collectAsStateWithLifecycle()
                val briefing by repository.briefing.collectAsStateWithLifecycle()
                val selected by repository.selectedPracticeIds.collectAsStateWithLifecycle()
                DrivingScreen(
                    phase,
                    praise,
                    idleLine,
                    recording = consent,
                    missions = briefing.practices.filter { it.id in selected },
                )
                // 주행 화면에는 위쪽 줄이 없어 시연 단추를 따로 띄운다.
                Box(Modifier.fillMaxSize()) { FloatingDemoButton() }
            }
            }
        if (demoBar != null && demoOpen) DemoPanel(onDismiss = { demoOpen = false }) { demoBar { demoOpen = false } }
    }
    }
}

@Composable
private fun ParkedContent(repository: SafeLogRepository, askDriver: Boolean, onDriverAsked: () -> Unit) {
    val firstRunDone by repository.firstRunDone.collectAsStateWithLifecycle()
    val profileDone by repository.profileDone.collectAsStateWithLifecycle()
    val briefing by repository.briefing.collectAsStateWithLifecycle()
    val consent by repository.recordingConsent.collectAsStateWithLifecycle()
    val drivers by repository.drivers.collectAsStateWithLifecycle()
    val driverId by repository.driverId.collectAsStateWithLifecycle()
    var consentOpen by rememberSaveable { mutableStateOf(false) }
    // 위쪽 탭. null은 "오늘"(홈). 하위 화면에서 돌아오면 보던 탭 그대로다.
    var tab by rememberSaveable { mutableStateOf<Destination?>(null) }
    // 탭 위로 들어간 하위 화면. 앱을 열었을 때 운전자가 둘 이상이면 운전자 고르기부터 보인다.
    val askNow = remember { askDriver && firstRunDone && drivers.size > 1 }
    var destination by rememberSaveable { mutableStateOf<Destination?>(if (askNow) Destination.Drivers else null) }
    LaunchedEffect(Unit) { if (askDriver) onDriverAsked() }
    var parent by rememberSaveable { mutableStateOf<Destination?>(null) } // 설정 → 운전자처럼 두 단계일 때
    var quizOpen by rememberSaveable { mutableStateOf(false) }
    var modeSheet by rememberSaveable { mutableStateOf(false) }
    val close = {
        quizOpen = false
        destination = parent
        parent = null
    }
    val open: (Destination) -> Unit = {
        if (it in Destination.menu) {
            // 기록·교환소·캐릭터는 들어가는 화면이 아니라 탭이다.
            quizOpen = false
            destination = null
            parent = null
            tab = it
        } else {
            parent = destination.takeIf { d -> d == Destination.Settings }
            destination = it
        }
    }
    // 시스템 뒤로 가기: 하위 화면 → 보던 탭, 다른 탭 → 오늘 → 앱 나가기
    BackHandler(enabled = quizOpen || destination != null || tab != null) {
        if (quizOpen || destination != null) close() else tab = null
    }
    // 탭은 오늘 → 기록 → 교환소 → 캐릭터 순서로 옆에 놓인 것처럼 넘긴다(위쪽 줄 누르기, 화면 밀기 모두).
    val tabIndex = TAB_ORDER.indexOf(tab)
    val tabDirection = remember { TabDirection(tabIndex) }
    tabDirection.follow(tabIndex)
    // 팝업이나 교환소 상품 화면이 떠 있으면 밀어서 넘기지 않는다.
    val swipeLock = remember { TabSwipeLock() }
    val current = destination
    // 화면마다 깊이(탭 0, 하위 화면·퀴즈 1, 설정 → 운전자 2)를 두고, 깊어지면 오른쪽에서 밀려 들어오고 얕아지면 오른쪽으로 빠진다.
    val base = when {
        quizOpen -> Screen("quiz", 1)
        current != null -> Screen(current.name, if (parent != null) 2 else 1)
        else -> Screen("tabs", 0)
    }
    // 동의 화면은 연 곳(탭·설정)보다 한 단계 깊다. 그래야 닫을 때 오른쪽으로 빠지며 원래 화면으로 돌아간다.
    val screen = if (!firstRunDone || consentOpen) Screen("consent", base.depth + 1) else base
    // 탭 화면마다 자기 맨 위에 그린다. 그래야 화면의 팝업 가림막이 이 줄까지 덮는다.
    val topBar: @Composable () -> Unit = {
        AppTopBar(
            tab = tab,
            onSelectTab = { tab = it },
            mode = briefing.mode,
            recordingConsent = consent,
            driver = drivers.firstOrNull { it.id == driverId },
            onRequestConsent = { consentOpen = true },
            onOpen = open,
            onOpenMode = { modeSheet = true },
        )
    }
    CompositionLocalProvider(LocalTabSwipeLock provides swipeLock) {
    Box(Modifier.fillMaxSize()) {
    PushContent(screen, forward = { from, to -> to.depth >= from.depth }, label = "screen") { target ->
    when (target.key) {
        // 닫기와 뒤로 가기는 동의가 아니다. 동의 없이도 앱은 볼 수 있다.
        "consent" -> {
            FirstRunScreen(
                onClose = {
                    repository.finishFirstRun(consent = false, atlasName = null)
                    consentOpen = false
                },
                onAgree = { name, profile, insurance ->
                    repository.finishFirstRun(consent = true, atlasName = name)
                    profile?.let(repository::setDriverProfile)
                    if (insurance) repository.updateSettings(repository.settings.value.copy(insuranceShare = true))
                    consentOpen = false
                },
                // 이미 답한 운전자가 기록을 다시 켤 때는 프로필 질문을 건너뛴다.
                askProfile = !profileDone,
            )
        }
        "quiz" -> {
            val quiz by repository.quiz.collectAsStateWithLifecycle()
            quiz?.let { QuizScreen(it, repository::answerQuiz, close) } ?: PlaceholderScreen("오늘의 퀴즈", close)
        }
        // 탭끼리는 화면을 통째로 밀지 않는다. 위쪽 줄은 제자리에서 교차 페이드하고, 줄 아래 내용만 탭 순서 방향으로 짧게 민다(TabSwipe.kt).
        // 화면을 옆으로 밀어도 탭이 넘어간다. 손가락이 왼쪽으로 가면 다음 탭.
        "tabs" -> AnimatedContent(
            tab,
            Modifier.fillMaxSize().tabSwipe(enabled = swipeLock.free) { step ->
                val next = tabIndex + step
                if (next in TAB_ORDER.indices) tab = TAB_ORDER[next]
            },
            transitionSpec = { fadeIn(tween(Motion.FADE)) togetherWith fadeOut(tween(Motion.FADE)) },
            label = "tab",
        ) { shown ->
            val slide = remember(this) { TabSlide(this) { tabDirection.value } }
            CompositionLocalProvider(LocalTabSlide provides slide) {
                if (shown == null) {
                    TodayTab(repository, topBar, onRequestConsent = { consentOpen = true }, onOpenQuiz = { quizOpen = true }, onOpen = open)
                } else {
                    TabScreen(shown, repository, topBar)
                }
            }
        }
        else -> DepthScreen(Destination.valueOf(target.key), repository, onRequestConsent = { consentOpen = true }, onOpen = open, onBack = close)
    }
    }
    // 동의 화면에는 위쪽 줄이 없어 시연 단추를 따로 띄운다(샘플 데이터는 이 화면으로 시작할 수 있다).
    if (screen.key == "consent") FloatingDemoButton()
    // 연수 모드 칩을 누르면: 무엇이 다른지 두 줄과 끄기. 왜 켜졌는지(점수)는 말하지 않는다. 칩은 모든 탭에 있어 여기서 띄운다.
    if (modeSheet) {
        SafeLogPopup(
            title = DriveMode.Training.label,
            body = "추천 미션을 골라 둬요\n도착하면 다음 미션을 한 줄로 알려 줘요",
            confirmText = "끄기",
            dismissText = "닫기",
            confirmTone = ButtonTone.Neutral,
            onConfirm = { repository.setTrainingMode(false); modeSheet = false },
            onDismiss = { modeSheet = false },
        )
    }
    }
    }
}

/** 화면 전환의 열쇠. [depth]로 들어가는지 나오는지 정한다. */
private data class Screen(val key: String, val depth: Int)

/** 위쪽 탭이 옆으로 놓인 순서. null은 "오늘"(홈). */
private val TAB_ORDER: List<Destination?> = listOf(null) + Destination.menu

/**
 * 탭 순서에서 어느 쪽으로 갔는지. 뒤 탭으로 갔으면 1, 앞 탭이면 -1.
 * 탭을 바꾸는 길(위쪽 줄, 화면 밀기, 뒤로 가기, 다른 화면의 바로 가기)이 여럿이라 바뀐 값을 보고 정한다.
 */
private class TabDirection(private var index: Int) {
    var value = 1
        private set

    fun follow(next: Int) {
        if (next == index) return
        value = if (next > index) 1 else -1
        index = next
    }
}

/** "오늘" 탭: 출발 전(S10)과 도착 후(S30) 홈 */
@Composable
private fun TodayTab(
    repository: SafeLogRepository,
    topBar: @Composable () -> Unit,
    onRequestConsent: () -> Unit,
    onOpenQuiz: () -> Unit,
    onOpen: (Destination) -> Unit,
) {
    val briefing by repository.briefing.collectAsStateWithLifecycle()
    val arrival by repository.arrival.collectAsStateWithLifecycle()
    val selected by repository.selectedPracticeIds.collectAsStateWithLifecycle()
    val consent by repository.recordingConsent.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle()
    // 도착 화면 ↔ 출발 전 화면(닫기)은 짧게 교차 페이드한다.
    Crossfade(arrival, animationSpec = tween(Motion.FADE), label = "arrival") { shownArrival ->
        HomeScreen(
            insuranceShare = settings.insuranceShare,
            insurance = repository.records.collectAsStateWithLifecycle().value.insurance,
            insuranceInviteSeen = settings.insuranceInviteSeen,
            onEnableInsurance = { repository.updateSettings(settings.copy(insuranceShare = true)) },
            onDismissInsuranceInvite = { repository.updateSettings(settings.copy(insuranceInviteSeen = true)) },
            modeNotice = repository.modeNotice.collectAsStateWithLifecycle().value,
            onDismissModeNotice = repository::dismissModeNotice,
            onAcceptTraining = {
                // 권유를 받아들임: 연수 모드를 켜고(추천 미션이 골라진다) 알림을 닫는다.
                repository.setTrainingMode(true)
                repository.dismissModeNotice()
            },
            carTourOffer = repository.carTour.collectAsStateWithLifecycle().value.offer,
            onStartCarTour = {
                repository.dismissCarTourOffer()
                onOpen(Destination.CarTour)
            },
            onDismissCarTourOffer = repository::dismissCarTourOffer,
            briefing = briefing,
            arrival = shownArrival,
            selectedIds = selected,
            onSelectionChange = repository::selectPractices,
            recordingConsent = consent,
            onRequestConsent = onRequestConsent,
            onOpenQuiz = onOpenQuiz,
            onCloseArrival = repository::dismissArrival,
            topBar = topBar,
        )
    }
}

/** 기록·교환소·캐릭터 탭. 맨 위 줄은 [topBar]라 뒤로 가기가 없다. */
@Composable
private fun TabScreen(tab: Destination, repository: SafeLogRepository, topBar: @Composable () -> Unit) {
    val briefing by repository.briefing.collectAsStateWithLifecycle()
    val records by repository.records.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle()
    when (tab) {
        Destination.Records -> RecordsScreen(records, topBar, insuranceShare = settings.insuranceShare, onDispute = repository::disputeScene)
        Destination.Shop -> {
            val items by repository.shopItems.collectAsStateWithLifecycle()
            ShopScreen(
                balance = briefing.pointsBalance,
                nextReward = briefing.nextReward,
                grade = records.grade,
                items = items,
                onExchange = repository::exchange,
                header = topBar,
                insurance = records.insurance?.takeIf { settings.insuranceShare },
            )
        }
        Destination.Atlas -> {
            val atlas by repository.atlas.collectAsStateWithLifecycle()
            AtlasScreen(atlas, repository::selectVoice, topBar)
        }
        else -> Unit // 탭이 아니다
    }
}

/** 탭 위로 들어가는 하위 화면. 뒤로 가기 제목 줄이 있다. */
@Composable
private fun DepthScreen(
    destination: Destination,
    repository: SafeLogRepository,
    onRequestConsent: () -> Unit,
    onOpen: (Destination) -> Unit,
    onBack: () -> Unit,
) {
    val settings by repository.settings.collectAsStateWithLifecycle()
    when (destination) {
        Destination.Settings -> {
            val consent by repository.recordingConsent.collectAsStateWithLifecycle()
            val drivers by repository.drivers.collectAsStateWithLifecycle()
            val driverId by repository.driverId.collectAsStateWithLifecycle()
            SettingsScreen(
                settings = settings,
                driver = drivers.first { it.id == driverId },
                recordingConsent = consent,
                onChange = repository::updateSettings,
                onOpenDrivers = { onOpen(Destination.Drivers) },
                onRequestConsent = onRequestConsent,
                onWithdrawConsent = repository::withdrawConsent,
                onClearRecords = repository::clearRecords,
                onTrainingChange = repository::setTrainingMode,
                carTour = repository.carTour.collectAsStateWithLifecycle().value,
                onOpenCarTour = { onOpen(Destination.CarTour) },
                account = repository.accounts.collectAsStateWithLifecycle().value[driverId],
                onOpenSignUp = { onOpen(Destination.SignUp) },
                onBack = onBack,
            )
        }
        Destination.SignUp -> {
            val drivers by repository.drivers.collectAsStateWithLifecycle()
            val driverId by repository.driverId.collectAsStateWithLifecycle()
            SignUpScreen(
                driverName = drivers.first { it.id == driverId }.name,
                onRequestCode = repository::requestSignUpCode,
                onSignUp = repository::signUp,
                onBack = onBack,
            )
        }
        Destination.CarTour -> {
            val tour by repository.carTour.collectAsStateWithLifecycle()
            // 실습에 걸린 시간은 이 화면을 처음 연 때부터 잰다.
            LaunchedEffect(Unit) { repository.openCarTour() }
            CarTourScreen(tour, onSkip = repository::skipCarStep, onBack = onBack, onRetry = repository::retryCarStep)
        }
        Destination.Drivers -> {
            val drivers by repository.drivers.collectAsStateWithLifecycle()
            val driverId by repository.driverId.collectAsStateWithLifecycle()
            DriverPickerScreen(drivers, driverId, onSelect = {
                repository.selectDriver(it)
                onBack()
            }, onSetAvatar = repository::setAvatar, onBack = onBack, onAdd = {
                // 더한 사람이 지금 운전자가 된다. 이 차가 처음이면 돌아간 홈에서 내 차 익히기를 권한다.
                repository.addDriver(it)
                onBack()
            })
        }
        else -> Unit // 탭으로 연다
    }
}
