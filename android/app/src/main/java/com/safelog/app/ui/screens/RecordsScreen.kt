package com.safelog.app.ui.screens

import androidx.compose.animation.Crossfade
import com.safelog.app.ui.components.Motion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.safelog.app.ui.components.EaseOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.safelog.app.model.DriveRecords
import com.safelog.app.model.HabitTrend
import com.safelog.app.model.Scene
import com.safelog.app.ui.components.CardShape
import com.safelog.app.ui.components.ProgressBar
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * S40 내 기록 + S41 운전 일지. 달력에서 운전한 날(점)을 누르면 그날의 장면이 나온다. 지도는 쓰지 않는다.
 * 패널: 달력(위)과 이번 주 요약(바닥). 목록: 고른 날의 주행과 이번 주 항목별.
 */
@Composable
fun RecordsScreen(
    records: DriveRecords,
    header: @Composable () -> Unit,
    today: LocalDate = LocalDate.now(),
    insuranceShare: Boolean = false,
    // 오판정 의견: 장면 줄의 "아니에요"를 누르고 한 번 더 확인하면 그 장면 id로 부른다.
    onDispute: (String) -> Unit = {},
    initialDisputeId: String? = null, // 화면 이미지 시험용
) {
    var disputeId by rememberSaveable { mutableStateOf(initialDisputeId) }
    Box {
    val byDate = remember(records.journal) { records.journal.groupBy { it.date } }
    val latest = byDate.keys.maxOrNull() ?: today
    val earliest = byDate.keys.minOrNull() ?: latest
    var selected by rememberSaveable { mutableStateOf(latest) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(latest)) }
    SubScreen(
        header = header,
        panel = {
            Calendar(
                month = month,
                selected = selected,
                today = today,
                drives = byDate.mapValues { it.value.size },
                canPrev = month > YearMonth.from(earliest),
                canNext = month < YearMonth.from(latest),
                onMonth = { month = it },
                onSelect = { selected = it },
            )
            WeekSummary(records)
        },
        content = {
            // 고른 날 하나가 카드 하나. 그날 여러 번 달렸으면 카드 안에서 주행마다 작은 제목으로 나눈다.
            // 다른 날을 누르면 그날 카드가 짧게 교차 페이드하고, 높이 차이는 부드럽게 늘고 줄어든다.
            Crossfade(selected, Modifier.animateContentSize(tween(Motion.FADE)), animationSpec = tween(Motion.FADE), label = "day") { day ->
                val logs = byDate[day].orEmpty()
                // 오늘 달린 기록의 이름은 "오늘의 운전"(기획서 3장). 지난 날은 날짜가 제목이다.
                val isToday = day == today
                ListCard(title = if (isToday) "오늘의 운전" else dayLabel(day), subtitle = dayLabel(day).takeIf { isToday }) {
                    if (logs.isEmpty()) ListRow("운전 기록이 없어요", enabled = false)
                    logs.forEach { log ->
                        Text(log.summary, style = SafeLogType.label, color = SafeLogTheme.colors.inkMuted, modifier = Modifier.padding(start = CARD_PADDING, end = CARD_PADDING, top = 16.dp))
                        log.scenes.forEach { scene -> SceneRow(scene) { disputeId = scene.id } }
                    }
                }
            }
            records.insurance?.takeIf { insuranceShare }?.let { InsuranceCard(it) }
            if (records.habits.isNotEmpty()) {
                ListCard(title = "이번 주 항목별") { records.habits.forEach { HabitRow(it) } }
            }
        },
    )
    disputeId?.let { id ->
        SafeLogPopup(
            title = "판정이 실제와 달랐나요",
            body = "의견을 남기면 판정을 고치는 데 써요",
            confirmText = "의견 남기기",
            dismissText = "취소",
            onConfirm = { onDispute(id); disputeId = null },
            onDismiss = { disputeId = null },
        )
    }
    }
}

fun dayLabel(date: LocalDate) =
    "${date.monthValue}월 ${date.dayOfMonth}일 ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}"

/** 월요일 시작. 홈의 출석 칸(월~일)과 같다. 운전한 날만 누를 수 있고, 점 개수는 그날 주행 수(최대 3). */
@Composable
private fun Calendar(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    drives: Map<LocalDate, Int>,
    canPrev: Boolean,
    canNext: Boolean,
    onMonth: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = SafeLogTheme.colors
    Column {
        // 이 달력과 날짜별 기록의 이름(기획서 3장).
        Text("운전 일지", style = SafeLogType.label, color = colors.inkMuted)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${month.year}년 ${month.monthValue}월", style = SafeLogType.bodyLarge, color = colors.ink, modifier = Modifier.weight(1f))
            IconAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "지난달", colors.ink, enabled = canPrev) { onMonth(month.minusMonths(1)) }
            IconAction(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달", colors.ink, enabled = canNext) { onMonth(month.plusMonths(1)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            "월화수목금토일".forEach {
                Text(it.toString(), style = SafeLogType.labelSmall, color = colors.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            }
        }
        // 달을 넘기면 넘긴 쪽에서 날짜가 살짝 밀려 들어온다.
        AnimatedContent(
            month,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(Motion.PUSH, easing = EaseOut)) { dir * it / 6 } + fadeIn(tween(Motion.FADE))) togetherWith
                    (slideOutHorizontally(tween(Motion.PUSH, easing = EaseOut)) { -dir * it / 6 } + fadeOut(tween(Motion.FADE)))
            },
            label = "month",
        ) { month ->
        Column {
        val lead = month.atDay(1).dayOfWeek.value - 1 // 월요일 0
        val cells = lead + month.lengthOfMonth()
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val day = week * 7 + col - lead + 1
                    Box(Modifier.weight(1f).height(64.dp), contentAlignment = Alignment.Center) {
                        if (day in 1..month.lengthOfMonth()) {
                            val date = month.atDay(day)
                            DayCell(date, date == selected, date == today, drives[date] ?: 0) { onSelect(date) }
                        }
                    }
                }
            }
        }
        }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, today: Boolean, drives: Int, onClick: () -> Unit) {
    val colors = SafeLogTheme.colors
    val fill by animateColorAsState(if (selected) colors.accent else Color.Transparent, tween(150), label = "day")
    Column(
        Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(fill)
            .then(if (today && !selected) Modifier.border(2.dp, colors.accent, CircleShape) else Modifier)
            .selectable(selected, enabled = drives > 0, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = if (drives > 0) SafeLogType.bodyStrong else SafeLogType.body,
            color = when {
                selected -> colors.onAccent
                drives > 0 -> colors.ink
                else -> colors.inkMuted
            },
        )
        // 주행 수만큼 점. 고른 날은 점도 흰색으로 남겨 개수를 잃지 않는다.
        Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(drives.coerceAtMost(3)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (selected) colors.onAccent else colors.accent))
            }
        }
    }
}

/** 패널 바닥의 이번 주 요약. 숫자 셋을 같은 크기로 나란히. */
@Composable
private fun WeekSummary(records: DriveRecords) {
    val colors = SafeLogTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardTitle("이번 주") { Chip("${records.grade.label} 등급", colors.accent) }
        Row(Modifier.fillMaxWidth()) {
            SummaryCell("잘한 순간", "${records.goodMoments}번", colors.accent, Modifier.weight(1f))
            SummaryCell("주행", "${records.weekDrives}번", colors.ink, Modifier.weight(1f))
            SummaryCell("안전 마일리지", "%,dkm".format(Locale.US, records.safeMileageKm), colors.ink, Modifier.weight(1.3f))
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, color: Color, modifier: Modifier) {
    val colors = SafeLogTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
        Text(label, style = SafeLogType.label, color = colors.inkMuted, maxLines = 1)
        Text(value, style = SafeLogType.bodyLarge, color = color, maxLines = 1)
    }
}

@Composable
private fun HabitRow(habit: HabitTrend) {
    val colors = SafeLogTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = CARD_PADDING, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(habit.label, style = SafeLogType.bodyStrong, color = colors.ink, modifier = Modifier.weight(1f))
            if (habit.change > 0) Chip("지난주보다 +${habit.change}%p", colors.accent)
            ValueText("${habit.percent}%")
        }
        ProgressBar(habit.percent / 100f, colors.accent)
    }
}

/** 장면 한 줄. 판정이 실제와 달랐으면 "아니에요"로 의견을 남긴다. 남긴 장면에는 꼬리표만 보인다. */
@Composable
private fun SceneRow(scene: Scene, onDispute: () -> Unit) {
    val colors = SafeLogTheme.colors
    ListRow(
        title = scene.what,
        sub = listOfNotNull(scene.place, scene.compare).joinToString(" · "),
        leading = { Text(scene.time, style = SafeLogType.label, color = colors.inkMuted, modifier = Modifier.width(72.dp)) },
    ) {
        if (!scene.good) Chip("다음 미션", colors.accent)
        if (scene.disputed) Chip("의견 남김", colors.inkMuted) else SafeLogButton("아니에요", onDispute, size = ButtonSize.Small)
    }
}
