package com.safelog.app.ui.screens

import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import com.safelog.app.ui.components.EaseOut
import com.safelog.app.ui.components.Motion
import com.safelog.app.ui.components.tabContentSlide
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.safelog.app.model.NextReward
import com.safelog.app.model.DriveMode
import com.safelog.app.model.ModeNotice
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.safelog.app.model.ArrivalSummary
import com.safelog.app.model.Attendance
import com.safelog.app.model.Briefing
import com.safelog.app.model.Driver
import com.safelog.app.model.GoodHabit
import com.safelog.app.model.Practice
import com.safelog.app.model.togglePractice
import com.safelog.app.model.wonValue
import com.safelog.app.model.reachedInsuranceGoal
import com.safelog.app.ui.components.AtlasImage
import androidx.compose.runtime.mutableStateOf
import com.safelog.app.ui.Destination
import com.safelog.app.ui.components.AtlasFill
import com.safelog.app.ui.components.AtlasMood
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.ButtonTone
import com.safelog.app.ui.components.CardShape
import com.safelog.app.ui.components.PopCheck
import com.safelog.app.ui.components.ProgressBar
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.SafeLogSpring
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 홈. 세이프로그의 목적은 "아무도 칭찬해 주지 않던 잘한 운전을 알아주고 보상하는 것"이다.
 * 그래서 가장 크게 보이는 것은 지도나 점수가 아니라 아틀라스와 잘한 횟수다.
 *
 * 화면은 질문 하나에 답한다.
 * - 출발 전(S10): 오늘 무슨 미션을 할까?
 * - 도착 후(S30): 오늘 무엇을 잘했고, 무엇을 얻었을까?
 *
 * 가로 창: 주인공 패널(창 높이) · 미션 목록 · 포인트 패널(창 높이) 세 칸. 세로 창: 위에서 아래로.
 * 출발 버튼은 없다. 기어를 D로 넣으면 시작하고, 고르지 않았으면 기록만 한다.
 */
@Composable
fun HomeScreen(
    briefing: Briefing,
    arrival: ArrivalSummary?,
    selectedIds: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    recordingConsent: Boolean,
    onRequestConsent: () -> Unit,
    onOpenQuiz: () -> Unit,
    onCloseArrival: () -> Unit,
    topBar: @Composable () -> Unit,
    insuranceShare: Boolean = false,
    insurance: com.safelog.app.model.InsuranceProgress? = null,
    insuranceInviteSeen: Boolean = true,
    onEnableInsurance: () -> Unit = {},
    onDismissInsuranceInvite: () -> Unit = {},
    modeNotice: ModeNotice? = null,
    onDismissModeNotice: () -> Unit = {},
    onAcceptTraining: () -> Unit = {},
    carTourOffer: Boolean = false,
    onStartCarTour: () -> Unit = {},
    onDismissCarTourOffer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val toggle: (String) -> Unit = { id ->
        onSelectionChange(togglePractice(selectedIds, briefing.practices, id, briefing.maxSelectable))
    }
    // 파란 패널은 할 일이 하나뿐인 도착 후(다음 미션 + 퀴즈)에만 쓴다. 출발 전 고르기는 미션 목록 한 곳에서 한다.
    val coach: @Composable () -> Unit = { arrival?.let { ArrivalCoach(it, onOpenQuiz) } }
    // 도착 연출 단계: 0 시작, 1 미션 체크(0.4초), 2 포인트 반영(1.1초), 3 보험 막대 차오름(1.8초).
    // 미션을 클리어하지 못했으면 1·2를 건너뛴다. 화면을 돌려도 다시 재생하지 않는다.
    var clearStep by rememberSaveable(arrival) { mutableIntStateOf(0) }
    LaunchedEffect(arrival) {
        if (arrival != null && clearStep == 0) {
            if (arrival.practiceCleared) {
                delay(400)
                clearStep = 1
                delay(700)
                clearStep = 2
            }
            delay(700)
            clearStep = 3
        }
    }
    // 이번 주행으로 보험 할인 조건을 막 채웠으면, 보험 막대가 다 찬 뒤 한 번 축하한다.
    var celebrated by rememberSaveable(arrival) { mutableStateOf(false) }
    val arrivalInsurance = arrival?.insurance?.takeIf { insuranceShare }
    // 지금 상태 한 줄. 위쪽 줄이 탭이 되면서 제목 아래에서 미션을 고르는 카드 안으로 옮겼다.
    val status = when {
        !recordingConsent -> "기록을 켜면 미션을 고를 수 있어요"
        selectedIds.isEmpty() -> "출발하면 알아서 기록해요"
        else -> "출발하면 미션이 시작돼요"
    }
    val details: @Composable ColumnScope.(Boolean) -> Unit = { wide ->
        if (arrival != null) {
            if (wide) MissionResultCard(arrival, clearStep)
            ArrivalResults(arrival)
        } else {
            // 한 질문은 한 목록: 미션 전부를 한 카드에, 추천을 맨 위에. 체크 행으로 고른다(박스 없음).
            val pickTitle = if (briefing.maxSelectable == 1) "하나를 골라요" else "${briefing.maxSelectable}개까지 골라요"
            ListCard(title = pickTitle, subtitle = status) {
                briefing.practices.sortedByDescending { it.recommended }.forEach {
                    PracticeRow(it, it.id in selectedIds, recordingConsent) { toggle(it.id) }
                }
            }
        }
    }
    // 보험 할인 진행. 출발 전과 도착 후 모두 같은 자리(가로 창은 오른쪽 칸 포인트·출석 아래, 세로 창은 맨 아래).
    // 도착 후에는 미션 → 포인트 → 보험 순서로 차오른다. 오늘 달린 거리만큼 막대가 늘고 남은 횟수가 준다.
    // 보험 공유에 동의하지 않았으면 안내 카드를 한 번만 보이고, 닫으면 이 자리는 비운다(동의를 조르지 않는다).
    val insuranceBlock: @Composable ColumnScope.() -> Unit = {
        when {
            arrival != null -> arrivalInsurance?.let { InsuranceCard(it, gainedKm = arrival.safeKmToday, reveal = clearStep >= 3) }
            insuranceShare -> insurance?.let { InsuranceCard(it) }
            else -> AnimatedVisibility(
                recordingConsent && !insuranceInviteSeen,
                enter = fadeIn(tween(Motion.FADE)),
                exit = fadeOut(tween(Motion.FADE)) + shrinkVertically(tween(Motion.POPUP_IN, easing = EaseOut)),
            ) { InsuranceInviteCard(onEnableInsurance, onDismissInsuranceInvite) }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_BREAKPOINT
        val inset = screenInset(wide)
        if (wide) {
            Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
                topBar()
                Row(Modifier.weight(1f).tabContentSlide(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                    HeroCard(briefing, arrival, wide = true, onCloseArrival, coach, Modifier.weight(1.1f).fillMaxHeight())
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(STACK_GAP),
                    ) { details(true) }
                    // 오른쪽 칸: 받는 것(포인트 → 보험). 카드는 위에 모으고, 칸 높이에 맞춰 포인트 그림 크기를 고른다.
                    // 출석은 "이번 주" 기록이라 주인공 카드 아래로 옮겼다.
                    RewardFit(Modifier.weight(1f).fillMaxHeight()) { layout, artMax ->
                        Column(verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
                            PointsCard(briefing, arrival, clearStep, Modifier.fillMaxWidth(), layout, artMax)
                            insuranceBlock()
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
                topBar()
                Box(Modifier.weight(1f).tabContentSlide()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(STACK_GAP),
                    ) {
                        HeroCard(briefing, arrival, wide = false, onCloseArrival, coach, Modifier.fillMaxWidth())
                        // 출발 전에는 미션 고르기가 먼저, 도착 후에는 미션 결과 → 포인트 순서로 연출을 따라 내려간다.
                        if (arrival == null) details(false) else MissionResultCard(arrival, clearStep)
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(STACK_GAP)) {
                            PointsCard(briefing, arrival, clearStep, Modifier.weight(1f).fillMaxHeight(), RewardLayout.None)
                            AttendanceCard(briefing.attendance, Modifier.weight(1f).fillMaxHeight())
                        }
                        if (arrival != null) details(false)
                        insuranceBlock()
                    }
                    ScrollEdge(Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        // 주행 뒤 판정 쪽의 알림은 주차 홈에서 한 번만. 연수 모드는 권하기만 하고 운전자가 "켜기"를 눌러야 켜진다
        // (왜 권하는지, 점수는 말하지 않는다). 졸업해 평소 모드로 돌아왔을 때는 축하한다.
        if (arrival == null && modeNotice == ModeNotice.TrainingOffer) {
            SafeLogPopup(
                title = "연수 모드로 연습해 볼까요",
                caption = DriveMode.Training.label,
                body = "추천 미션을 하나씩 골라 드려요",
                confirmText = "켜기",
                dismissText = "나중에",
                onConfirm = onAcceptTraining,
                onDismiss = onDismissModeNotice,
                image = { AtlasImage(AtlasMood.Cheer, 160.dp) },
            )
        } else if (arrival == null && modeNotice != null) {
            SafeLogPopup(
                title = "평소 모드로 돌아왔어요",
                confirmText = "좋아요",
                onConfirm = onDismissModeNotice,
                onDismiss = onDismissModeNotice,
                image = { AtlasImage(AtlasMood.Joy, 160.dp) },
            )
        }
        // 처음 타는 차·새 운전자면 주차 홈에서 한 번 권한다. 모드 알림이 먼저 뜨면 그다음에.
        if (arrival == null && modeNotice == null && carTourOffer) {
            SafeLogPopup(
                title = "처음 타는 차예요",
                body = "조작을 직접 해 보며 익혀요",
                confirmText = "해 보기",
                dismissText = "나중에",
                onConfirm = onStartCarTour,
                onDismiss = onDismissCarTourOffer,
                image = { AtlasImage(AtlasMood.Cheer, 160.dp) },
            )
        }
        if (arrival != null && arrival.reachedInsuranceGoal() && insuranceShare && clearStep >= 3 && !celebrated) {
            SafeLogPopup(
                title = "보험 할인 조건을 채웠어요",
                body = "갱신 때 보험사에 안전 운전 요약을 보내요",
                confirmText = "좋아요",
                onConfirm = { celebrated = true },
                onDismiss = { celebrated = true },
                image = { AtlasImage(AtlasMood.Joy, 160.dp) },
            )
        }
    }
}

/** 구분선 대신 쓰는 스크롤 끝 표시. 내용이 메뉴 아래로 이어진다는 것을 알려 준다. */
@Composable
private fun ScrollEdge(modifier: Modifier) {
    val ground = SafeLogTheme.colors.background
    Box(modifier.fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(Color.Transparent, ground))))
}

/** 주인공 카드: 아틀라스와 잘한 횟수, 그 아래 코치 패널 */
@Composable
private fun HeroCard(
    briefing: Briefing,
    arrival: ArrivalSummary?,
    wide: Boolean,
    onCloseArrival: () -> Unit,
    coach: @Composable () -> Unit,
    modifier: Modifier,
) {
    val colors = SafeLogTheme.colors
    val habit = if (arrival != null) arrival.topHabit else briefing.weekMissions
    val mood = if (arrival != null) AtlasMood.Joy else AtlasMood.Cheer
    Column(modifier.clip(CardShape).background(colors.surface)) {
        // 세로 창은 스크롤 안이라 높이를 나눠 가질 수 없다. 가로 창에서만 남는 높이를 차지한다.
        Box(if (wide) Modifier.weight(1f) else Modifier) {
            if (wide) {
                Column(Modifier.fillMaxSize().padding(CARD_PADDING)) {
                    // 이미지 원본 높이(480px)를 넘겨 흐려지지 않게 상한을 둔다.
                    Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = CARD_PADDING), contentAlignment = Alignment.Center) {
                        AtlasFill(mood, Modifier.fillMaxWidth().heightIn(max = 360.dp))
                    }
                    Achievement(habit, missions = arrival == null)
                    // 이번 주 출석. 달성한 미션과 같은 "이번 주" 기록이라 카드를 따로 두지 않는다.
                    Spacer(Modifier.height(CARD_PADDING))
                    AttendanceBlock(briefing.attendance)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(CARD_PADDING),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CARD_PADDING),
                ) {
                    AtlasFill(mood, Modifier.size(112.dp))
                    // 도착 후에는 오른쪽 위 닫기(X)와 글이 겹치지 않게 비켜 둔다.
                    Box(Modifier.weight(1f).padding(end = if (arrival != null) 40.dp else 0.dp)) { Achievement(habit, missions = arrival == null) }
                }
            }
            if (arrival != null) {
                IconAction(Icons.Filled.Close, "닫기", colors.inkMuted, Modifier.align(Alignment.TopEnd).padding(8.dp), onClick = onCloseArrival)
            }
        }
        coach()
    }
}

@Composable
private fun Achievement(habit: GoodHabit?, missions: Boolean) = Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
    val colors = SafeLogTheme.colors
    if (habit == null) {
        Text("이번 운전", style = SafeLogType.label, color = colors.inkMuted)
        Text("무사히 도착했어요", style = SafeLogType.title, color = colors.ink)
        return@Column
    }
    Text(habit.label, style = SafeLogType.label, color = colors.inkMuted)
    // 잘한 횟수만 보인다. "몇 번 중"은 놓친 횟수를 드러내서 쓰지 않는다.
    Text(if (missions) "${habit.success}개" else "${habit.success}번", style = SafeLogType.number, color = colors.accent, maxLines = 1)
}

/** 코치 패널: 지금 할 일 하나. 주인공 카드와 한 몸이다. 높이는 목록 행과 같은 100. */
@Composable
private fun CoachPanel(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp).background(SafeLogTheme.colors.coach).padding(horizontal = CARD_PADDING, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun ArrivalCoach(summary: ArrivalSummary, onOpenQuiz: () -> Unit) {
    val colors = SafeLogTheme.colors
    CoachPanel {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
            summary.nextChallenge?.let {
                Text("다음 미션", style = SafeLogType.label, color = colors.onCoach.copy(alpha = 0.84f))
                Text(it, style = SafeLogType.bodyStrong, color = colors.onCoach)
            }
        }
        Box(
            Modifier
                .defaultMinSize(minWidth = ButtonSize.Small.minWidth, minHeight = ButtonSize.Small.height)
                .pressable(onClick = onOpenQuiz)
                .clip(RoundedCornerShape(ButtonSize.Small.radius))
                .background(colors.onCoach)
                .padding(horizontal = ButtonSize.Small.paddingH),
            contentAlignment = Alignment.Center,
        ) {
            Text("퀴즈 풀기", style = SafeLogType.bodyStrong, color = colors.coach)
        }
    }
}

/** 도착 후 "오늘 얻은 것"(기획서 3장의 이름): 다른 잘한 순간과 캐릭터 경험치 */
@Composable
private fun ArrivalResults(arrival: ArrivalSummary) {
    // 결과가 없는 도착(위험 주행 등)에는 제목도 두지 않는다.
    if (arrival.otherHabits.isEmpty() && arrival.xp == 0) return
    val colors = SafeLogTheme.colors
    ListCard(title = "오늘 얻은 것") {
        arrival.otherHabits.forEach { ListRow(it.label) { ValueText("${it.success}번") } }
        if (arrival.xp > 0) {
            ListRow("캐릭터 경험치", sub = "다음 레벨까지 ${arrival.xpToNextLevel}") { ValueText("+${arrival.xp}", colors.accent) }
        }
    }
}

/**
 * 오늘 고른 미션의 결과. 클리어했으면 체크가 팝 버스트로 채워지고 카드가 파랗게 물든 뒤,
 * 살짝 가라앉으며(sink) 포인트 칸의 숫자가 올라간다. 클리어하지 못했으면 연출 없이 다음을 권한다.
 */
@Composable
internal fun MissionResultCard(arrival: ArrivalSummary, clearStep: Int) {
    val practice = arrival.practiceResult ?: return
    val colors = SafeLogTheme.colors
    val cleared = arrival.practiceCleared
    val checked = cleared && clearStep >= 1
    val background by animateColorAsState(
        if (checked) colors.accent.copy(alpha = 0.12f).compositeOver(colors.surface) else colors.surface,
        tween(200),
        label = "missionCard",
    )
    val sink = remember { Animatable(0f) }
    LaunchedEffect(clearStep) {
        if (cleared && clearStep == 2) {
            sink.animateTo(1f, tween(120))
            sink.animateTo(0f, SafeLogSpring)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = sink.value * 8.dp.toPx()
                scaleX = 1f - sink.value * 0.02f
                scaleY = 1f - sink.value * 0.02f
            }
            .defaultMinSize(minHeight = 100.dp)
            .clip(CardShape)
            .background(background)
            .padding(horizontal = CARD_PADDING, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
            Text("오늘 미션", style = SafeLogType.label, color = if (checked) colors.accent else colors.inkMuted)
            Text(practice.label, style = SafeLogType.bodyStrong, color = colors.ink)
            Text(
                if (cleared) "${practice.success}번" else "다음에 다시 해 봐요",
                style = SafeLogType.label,
                color = colors.inkMuted,
            )
        }
        PopCheck(checked, colors.accent, colors.onAccent, colors.surfaceHigh)
    }
}

/**
 * 받은 것과 다음 보상까지 남은 양. 가로 창은 다음 보상 그림을 함께 둔다: 높이가 넉넉하면 4:3 큰 그림, 모자라면 썸네일 한 줄.
 * 도착 후에는 숫자가 올라가는 만큼 남은 양이 함께 줄어든다.
 */
@Composable
internal fun PointsCard(briefing: Briefing, arrival: ArrivalSummary?, clearStep: Int, modifier: Modifier, rewardLayout: RewardLayout = RewardLayout.None, artMax: Dp = Dp.Infinity) {
    val colors = SafeLogTheme.colors
    val reward = arrival?.nextReward ?: briefing.nextReward
    // 미션을 클리어했으면 미션 포인트를 뺀 값에서 시작해, 체크가 끝나면 받은 만큼 올라간다.
    val bonus = if (arrival?.practiceCleared == true) arrival.practicePoints else 0
    val shown by animateIntAsState(
        targetValue = when {
            arrival == null -> briefing.pointsBalance
            clearStep >= 2 -> arrival.points
            else -> arrival.points - bonus
        },
        animationSpec = tween(400),
        label = "points",
    )
    val pop = remember { Animatable(1f) }
    LaunchedEffect(clearStep) {
        if (clearStep == 2 && bonus > 0) {
            pop.animateTo(1.08f, tween(120))
            pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 400f))
        }
    }
    Column(
        modifier.clip(CardShape).background(colors.surface).padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("포인트", style = SafeLogType.label, color = colors.inkMuted)
            AnimatedVisibility(bonus > 0 && clearStep >= 2, enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.95f)) {
                Chip("미션 클리어 +${points(bonus)}", colors.accent)
            }
        }
        val value = when {
            arrival == null -> points(shown)
            arrival.points > 0 -> "+" + points(shown)
            else -> null // 위험 주행에는 보상이 없다(기획서 F7).
        }
        if (value != null) {
            Text(
                value,
                style = numberStyle(value, hero = false),
                // 잔액은 검정, 이번에 받은 "+10P"만 금색. 금색은 늘어난 것에만 쓴다.
                color = if (arrival == null) colors.ink else colors.reward,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
            // 잔액에만 원화를 함께 적는다. 도착 후 "+10P" 같은 작은 적립에는 쓰지 않는다.
            if (arrival == null) Text(wonValue(shown), style = SafeLogType.label, color = colors.inkMuted)
        } else {
            Text("다음에 받아요", style = SafeLogType.bodyLarge, color = colors.ink)
            arrival?.noRewardReason?.let { Text(it, style = SafeLogType.label, color = colors.inkMuted) }
        }
        val remaining = if (arrival == null) reward.remainingPoints else reward.remainingPoints + arrival.points - shown
        val progress: @Composable () -> Unit = {
            Text("${reward.itemName}까지 ${points(remaining)}", style = SafeLogType.label, color = colors.inkMuted)
            ProgressBar(reward.progressAt(remaining), colors.accent)
        }
        when (rewardLayout) {
            RewardLayout.None -> progress()
            RewardLayout.Large -> {
                // 늘 4:3. 높이가 조금 모자라면 같은 모양 그대로 작아져 가운데에 선다.
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    RewardArt(reward, Modifier.heightIn(max = artMax).aspectRatio(4f / 3f, matchHeightConstraintsFirst = artMax != Dp.Infinity))
                }
                progress()
            }
            // 쇼핑 앱 장바구니 행처럼 정사각 썸네일 옆에 이름과 막대.
            RewardLayout.Row -> Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RewardArt(reward, Modifier.size(80.dp), compact = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { progress() }
            }
        }
    }
}

/** 포인트 카드의 다음 보상 그림. 그림 칸은 어느 창에서나 같은 비율이고, 모자라면 크기 대신 배치를 바꾼다. */
internal enum class RewardLayout { None, Large, Row }

/**
 * 오른쪽 열의 포인트·출석 카드를 주어진 높이에 맞춘다. 순서대로 시도한다:
 * ① 4:3 큰 그림이 카드 폭 그대로 들어가면 그대로 ② 조금 모자라면 4:3을 유지한 채 그림만 줄인다(높이 140 이상일 때)
 * ③ 그래도 모자라면 썸네일 한 줄. 남는 높이는 카드 아래 한 곳에만 생긴다.
 */
@Composable
private fun RewardFit(modifier: Modifier, content: @Composable (RewardLayout, Dp) -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = constraints.maxWidth, minHeight = 0, maxHeight = Constraints.Infinity)
        fun measure(key: String, layout: RewardLayout, artMax: Dp) = subcompose(key) { content(layout, artMax) }.map { it.measure(loose) }
        val full = measure("full", RewardLayout.Large, Dp.Infinity)
        val over = (full.maxOfOrNull { it.height } ?: 0) - constraints.maxHeight
        val fullArt = (constraints.maxWidth - CARD_PADDING.roundToPx() * 2) * 3f / 4f
        val chosen = when {
            over <= 0 -> full
            fullArt - over >= 140.dp.toPx() -> measure("shrunk", RewardLayout.Large, (fullArt - over).toDp())
            else -> measure("row", RewardLayout.Row, Dp.Infinity)
        }
        layout(constraints.maxWidth, constraints.maxHeight) { chosen.forEach { it.place(0, 0) } }
    }
}

/** 다음 보상 그림. 교환소 상품 그림과 같은 모양이다. */
@Composable
private fun RewardArt(reward: NextReward, modifier: Modifier, compact: Boolean = false) {
    Box(modifier.productStage(), contentAlignment = Alignment.Center) {
        if (reward.image != null) {
            ProductPhoto(reward.image, reward.itemName, Modifier.fillMaxSize())
        } else {
            Text(
                reward.art.ifEmpty { reward.itemName },
                style = if (compact) SafeLogType.labelSmall else SafeLogType.numberCompact,
                color = SafeLogTheme.colors.accent,
                maxLines = 1,
            )
        }
    }
}

private fun points(value: Int) = "%,dP".format(Locale.US, value)

/** 주 3회 운전하면 그 주 출석, +30P(기획서 F8). 첫 달의 주된 적립원이다. */
@Composable
private fun AttendanceCard(attendance: Attendance, modifier: Modifier) {
    Column(
        modifier.clip(CardShape).background(SafeLogTheme.colors.surface).padding(CARD_PADDING),
        verticalArrangement = Arrangement.Center,
    ) { AttendanceBlock(attendance) }
}

/** 출석 내용. 세로 창은 카드([AttendanceCard]), 가로 창은 주인공 카드 아래에 넣는다. */
@Composable
private fun AttendanceBlock(attendance: Attendance) {
    val colors = SafeLogTheme.colors
    val days = attendance.daysThisWeek.count { it }.coerceAtMost(3)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("출석", style = SafeLogType.label, color = colors.inkMuted)
            if (attendance.weekStreak > 0) Chip("${attendance.weekStreak}주 연속", colors.accent)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("이번 주 $days/3", style = SafeLogType.bodyStrong, color = colors.ink)
            Chip("+30P", colors.reward)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            "월화수목금토일".forEachIndexed { i, day ->
                DayDot(day.toString(), attendance.daysThisWeek.getOrElse(i) { false })
            }
        }
    }
}

@Composable
private fun DayDot(day: String, done: Boolean) {
    val colors = SafeLogTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (done) colors.accent else colors.surfaceHigh))
        Text(day, style = SafeLogType.labelSmall, color = if (done) colors.ink else colors.inkMuted)
    }
}

/** 행 전체를 눌러 고른다. */
@Composable
private fun PracticeRow(practice: Practice, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = SafeLogTheme.colors
    // 행 아래에는 이름과 겹치지 않는 정보, 클리어하면 받는 포인트를 둔다. 추천은 2배라 꼬리표를 붙인다.
    ListRow(
        practice.title,
        Modifier.pressSelect(selected, enabled, onClick = onToggle),
        sub = "+${practice.reward}P",
        enabled = enabled,
        badge = if (practice.recommended) ({ Chip("추천", colors.accent) }) else null,
    ) {
        PopCheck(selected, colors.accent, colors.onAccent, colors.surfaceHigh)
    }
}
