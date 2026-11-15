package com.safelog.app.ui.screens

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.Alignment
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.safelog.app.model.CarStep
import com.safelog.app.model.CarTour
import com.safelog.app.model.Telltale
import com.safelog.app.model.TelltaleLevel
import com.safelog.app.ui.components.AtlasImage
import com.safelog.app.ui.components.AtlasMood
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.Motion
import com.safelog.app.ui.components.PopCheck
import com.safelog.app.ui.components.ProgressBar
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

/**
 * S05 내 차 익히기. 게임의 첫 튜토리얼처럼 화면이 시킨 조작을 직접 해 보면 차가 감지해 체크된다.
 * 행은 눌리지 않는다. 버튼 위치도 알려 주지 않는다. 직접 찾아본 위치가 가장 오래 기억된다.
 * 패널: 출발 준비 진행과 지금 해 볼 조작 하나(감지가 안 되면 건너뛰기).
 * 목록: 출발 전 꼭(실제 출발 순서) · 편의 기능(상황별 묶음) · 내 차 경고등(지금 켜진 것 맨 위, 색으로 위험도).
 * 주차 중에만 보인다. 기어를 바꾸면 주행 화면으로 바뀌고 진행은 그대로 남는다.
 */
@Composable
fun CarTourScreen(
    tour: CarTour,
    onSkip: (String) -> Unit,
    onBack: () -> Unit,
    initialCelebrate: Boolean = false,
    // 끝낸 단계의 "다시"를 누르면 그 단계가 다시 열리고, 차가 그 조작을 한 번 더 감지해야 체크된다(기획서 4장 3번).
    onRetry: (String) -> Unit = {},
) {
    val colors = SafeLogTheme.colors
    val next = tour.next
    // 이 화면에서 꼭 익힐 것을 다 마친 순간 한 번 축하한다.
    // 지금 할 조작이 늘 목록 가운데에 오게 한다. 처음 열 때는 바로, 하나 끝날 때마다 부드럽게 다음 줄로 내려간다.
    val centering = rememberCenteringScroll()
    val currentRow = remember { RowRef() }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(next?.id) {
        withFrameNanos { } // 새 줄이 자리를 잡은 뒤에
        currentRow.coords?.let { centering.center(it, animate = !first) }
        first = false
    }
    var celebrate by rememberSaveable { mutableStateOf(initialCelebrate) }
    var wasFinished by remember { mutableStateOf(tour.finished) }
    // 배지와 경험치는 처음 다 마쳤을 때 한 번뿐이다. 다시 해 보기로 한 번 더 마쳤을 때는 축하하지 않는다.
    var everFinished by rememberSaveable { mutableStateOf(tour.finished) }
    LaunchedEffect(tour.finished) {
        if (tour.finished && !wasFinished && !everFinished) celebrate = true
        if (tour.finished) everFinished = true
        wasFinished = tour.finished
    }
    Box {
        SubScreen(
            header = { DepthHeader("내 차 익히기", onBack) },
            centering = centering,
            panel = {
                Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
                    Box(Modifier.padding(bottom = 16.dp)) { AtlasImage(if (tour.finished) AtlasMood.Joy else AtlasMood.Cheer, 160.dp) }
                    Text("출발 준비", style = SafeLogType.label, color = colors.inkMuted)
                    Text("${tour.doneCount}/${tour.required.size}", style = SafeLogType.number, color = colors.accent, maxLines = 1)
                    ProgressBar(tour.doneCount / tour.required.size.coerceAtLeast(1).toFloat(), colors.accent)
                }
                // 꼭 익힐 것을 다 했으면 끝났다고 말하고 홈으로 돌아갈 길을 준다. 편의 기능은 남아 있어도 선택이다.
                // 아니면 지금 해 볼 것 하나만 크게(바뀔 때 짧게 교차 페이드).
                Crossfade(if (tour.finished) "done" else next?.id, animationSpec = tween(Motion.FADE), label = "nextStep") { key ->
                    val step = (tour.required + tour.extras).firstOrNull { it.id == key }
                    Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
                        when {
                            key == "done" -> {
                                Text("출발 준비 완료", style = SafeLogType.title, color = colors.ink)
                                Text("편의 기능은 필요할 때 익혀요", style = SafeLogType.label, color = colors.inkMuted)
                                SafeLogButton("완료", onBack, Modifier.fillMaxWidth().padding(top = 16.dp), primary = true)
                            }
                            step != null -> {
                                Text("지금 해 볼 것", style = SafeLogType.label, color = colors.inkMuted)
                                Text(step.title, style = SafeLogType.title, color = colors.ink)
                                Text("어디 있는지 찾아보세요", style = SafeLogType.label, color = colors.inkMuted)
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Chip("출발 준비 완료 배지", colors.accent)
                                    Chip("+${tour.rewardXp} 경험치", colors.accent)
                                }
                                // 센서나 차종 때문에 감지가 안 되면 막히지 않게. 건너뛴 것은 완료로 세지 않는다.
                                SafeLogButton("건너뛰기", { onSkip(step.id) }, Modifier.padding(top = 8.dp), size = ButtonSize.Small)
                            }
                            // 건너뛴 것만 남았다. 차가 나중에 감지하면 채워진다.
                            else -> Text("건너뛴 것만 남았어요", style = SafeLogType.title, color = colors.ink)
                        }
                    }
                }
            },
            content = {
                ListCard(title = "출발 전 꼭", trailing = { Chip("${tour.doneCount}/${tour.required.size}", colors.accent) }) {
                    tour.required.forEach { StepRow(it, it.id == next?.id, currentRow, onRetry) }
                }
                if (tour.extraGroups.isNotEmpty()) {
                    ListCard(title = "편의 기능", trailing = { Chip("${tour.extras.count { it.done }}/${tour.extras.size}", colors.inkMuted) }) {
                        tour.extraGroups.forEach { group ->
                            GroupLabel(group.title)
                            group.steps.forEach { StepRow(it, it.id == next?.id, currentRow, onRetry) }
                        }
                    }
                }
                if (tour.telltales.isNotEmpty()) {
                    ListCard(title = "내 차 경고등") {
                        // 지금 계기판에 켜진 것이 있으면 맨 위에. 처음 탄 차의 노란불을 그 자리에서 알 수 있다.
                        val lit = tour.telltales.filter { it.on }
                        if (lit.isNotEmpty()) {
                            GroupLabel("지금 켜져 있어요")
                            lit.forEach { TelltaleRow(it) }
                        }
                        TelltaleLevel.entries.forEach { level ->
                            val items = tour.telltales.filter { it.level == level && !it.on }
                            if (items.isNotEmpty()) {
                                GroupLabel(level.label)
                                items.forEach { TelltaleRow(it) }
                            }
                        }
                    }
                }
            },
        )
        if (celebrate) {
            SafeLogPopup(
                title = "출발 준비 완료",
                caption = "배지를 받았어요",
                body = "경험치 +${tour.rewardXp}",
                // 다 했으니 앞으로 가는 버튼은 홈으로. 편의 기능이 남았으면 더 익히기를 고를 수 있다.
                confirmText = "홈으로",
                dismissText = if (tour.extras.any { !it.done }) "더 익히기" else null,
                onConfirm = { celebrate = false; onBack() },
                onDismiss = { celebrate = false },
                image = { AtlasImage(AtlasMood.Joy, 160.dp) },
            )
        }
    }
}

/** 카드 안 작은 묶음 제목. 운전 기록의 주행 제목과 같은 모양. */
@Composable
private fun GroupLabel(text: String) {
    Text(text, style = SafeLogType.label, color = SafeLogTheme.colors.inkMuted, modifier = Modifier.padding(start = CARD_PADDING, end = CARD_PADDING, top = 16.dp))
}

/** 지금 할 조작 줄의 위치. 바뀔 때마다 다시 그릴 필요가 없어 상태가 아닌 그릇에 담는다. */
private class RowRef { var coords: LayoutCoordinates? = null }

/**
 * 조작 한 줄. 누를 수 없고, 차가 감지하면 체크가 톡 튀며 채워진다.
 * 지금 할 줄은 파란 칸(토스의 진행 중 단계)으로 감싸고 제목을 굵은 파랑으로, 끝난 줄은 흐리게 물러난다.
 * 칸은 다음 줄로 넘어갈 때 짧게 교차 페이드한다.
 */
@Composable
private fun StepRow(step: CarStep, current: Boolean, currentRow: RowRef, onRetry: (String) -> Unit) {
    val colors = SafeLogTheme.colors
    val fill by animateColorAsState(if (current) accentTint() else colors.surface, tween(Motion.FADE), label = "stepFill")
    val titleColor by animateColorAsState(
        when {
            current -> colors.accent
            step.done -> colors.inkMuted
            else -> colors.ink
        },
        tween(Motion.FADE),
        label = "stepTitle",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(if (current) Modifier.onGloballyPositioned { currentRow.coords = it } else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .background(fill)
            .defaultMinSize(minHeight = 100.dp)
            .padding(horizontal = CARD_PADDING - 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
            Text(step.title, style = if (current) SafeLogType.bodyStrong else SafeLogType.body, color = titleColor)
            if (step.skipped && !step.done) Chip("건너뜀", colors.inkMuted)
        }
        // 끝낸 조작을 다시 연습하고 싶을 때. 누르면 체크가 풀리고 이 줄이 "지금 해 볼 것"이 된다.
        if (step.done) SafeLogButton("다시", { onRetry(step.id) }, size = ButtonSize.Small)
        // 파란 칸 위에서는 빈 원을 흰색으로 둬야 보인다.
        PopCheck(step.done, colors.accent, colors.onAccent, if (current) colors.surface else colors.surfaceHigh)
    }
}

/** 경고등 한 줄. 앞의 점이 계기판 색(빨강·노랑·파랑)이다. */
@Composable
private fun TelltaleRow(item: Telltale) {
    val dot = when (item.level) {
        TelltaleLevel.Red -> SafeLogTheme.colors.destructive
        TelltaleLevel.Yellow -> TELLTALE_YELLOW
        TelltaleLevel.Info -> SafeLogTheme.colors.accent
    }
    ListRow(item.name, sub = item.meaning, leading = { Box(Modifier.size(16.dp).clip(CircleShape).background(dot)) })
}

/** 계기판 노랑. 라이트·다크 모두에서 점으로 구분되는 진한 호박색. */
private val TELLTALE_YELLOW = Color(0xFFE5A100)
