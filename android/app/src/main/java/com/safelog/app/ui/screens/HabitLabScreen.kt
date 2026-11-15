package com.safelog.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType
import com.safelog.core.contract.Frame
import com.safelog.core.lab.LabCatalog
import com.safelog.core.lab.LabHabitStatus
import com.safelog.core.lab.LabJudgment
import com.safelog.core.lab.LabSignals
import com.safelog.core.lab.LabView
import com.safelog.core.lab.SignalState

private val Green = Color(0xFF2E9E5B)
private val Blue = Color(0xFF3D7BE0)
private val Gray = Color(0xFF8A8F98)
private val Red = Color(0xFFD9534F)
private val Amber = Color(0xFFE0A030)

/**
 * 습관 시험실: developer-only test page (debug builds, opened from the debug 시연 panel). Lists every habit by category,
 * whether this car / the emulator delivers the signals it needs, the in-drive detector counts and the full engine's
 * verdict (with misses and reasons, which drivers never see). Drive with the Pleos Virtual Console while it is open.
 */
@Composable
fun HabitLabScreen(view: LabView?, phase: String, source: String, onJudge: () -> Unit, onReset: () -> Unit, onClose: () -> Unit) {
    val colors = SafeLogTheme.colors
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(colors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("습관 시험실", style = SafeLogType.title, color = colors.ink)
                Text("개발자 전용 · 디버그 빌드에서만 열려요 · 고객 화면에는 없어요", style = SafeLogType.labelSmall, color = Red)
            }
            val judge = view?.judge
            Text(
                "$phase · ${source.ifBlank { "-" }} · 기록 ${"%.0f".format((view?.frames ?: 0) / 10.0)}초" +
                    (judge?.let { " · 판정 ${"%.0f".format(it.atS)}초 시점" + (it.skipped?.let { s -> " ($s)" } ?: "") } ?: ""),
                style = SafeLogType.label, color = colors.inkSecondary,
            )
            SafeLogButton("지금 판정", onJudge, primary = true, size = ButtonSize.Small)
            SafeLogButton("초기화", onReset, size = ButtonSize.Small)
            SafeLogButton("닫기", onClose, size = ButtonSize.Small)
        }
        SignalStrip(view)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HabitList(view, selected, Modifier.weight(1.25f)) { selected = if (selected == it) null else it }
            Detail(view, selected, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SignalStrip(view: LabView?) {
    val f = view?.last
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, name) in LabSignals.ALL) {
            val st = view?.signals?.get(key) ?: SignalState.Missing
            val c = when (st) { SignalState.Working -> Green; SignalState.Readable -> Blue; SignalState.Missing -> Gray }
            Column(
                Modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(name, style = SafeLogType.labelSmall, color = c)
                Text(valueOf(key, f), style = SafeLogType.labelSmall, color = SafeLogTheme.colors.ink, maxLines = 1)
            }
        }
    }
}

private fun valueOf(key: String, f: Frame?): String {
    f ?: return "-"
    fun n(x: Double, d: Int = 0) = if (x.isNaN()) "없음" else "%.${d}f".format(x)
    fun i(x: Int, vararg names: String) = if (x < 0) "없음" else names.getOrElse(x) { x.toString() }
    return when (key) {
        "speed" -> n(f.speedKph, 1) + " km/h"
        "gear" -> i(f.gear, "P", "R", "N", "D")
        "brake" -> i(f.brakeOn, "뗌", "밟음")
        "steer" -> n(f.steerDeg, 1) + "°"
        "turn_signal" -> i(f.turnSignal, "꺼짐", "왼쪽", "오른쪽", "비상")
        "cruise" -> i(f.cruiseActive, "꺼짐", "작동")
        "wiper" -> i(f.wiperActive, "멈춤", "작동")
        "low_beam" -> i(f.lowBeam, "꺼짐", "켜짐")
        "night" -> i(f.night, "낮", "밤")
        "seats" -> i(f.seatsOccupied) + "명"
        "belts" -> i(f.beltsBuckled) + "명"
        "odometer" -> n(f.odoKm, 1) + " km"
        "telltale" -> if (f.ttFca < 0 && f.ttLka < 0) "없음" else "FCA ${f.ttFca} LKA ${f.ttLka}"
        "hazard" -> if (f.turnSignal == 3) "켜짐" else "-"
        "nav" -> i(f.navActive, "안내 없음", "안내 중")
        "tbt" -> if (f.tbtDistM.isNaN()) "없음" else "${"%.0f".format(f.tbtDistM)}m · ${i(f.tbtType, "-", "좌", "우", "U", "직진")}"
        "speed_limit" -> n(f.speedLimitKph) + " km/h"
        "caution" -> if (f.cautionType > 0) "종류 ${f.cautionType} · ${n(f.cautionDistM)}m" else "-"
        "school_zone" -> if (f.schoolZone == 1) "안" else "-"
        else -> "-"
    }
}

@Composable
private fun HabitList(view: LabView?, selected: String?, modifier: Modifier, onSelect: (String) -> Unit) {
    val colors = SafeLogTheme.colors
    val statuses = view?.statuses ?: emptyList()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((cat, catName) in LabCatalog.CATEGORIES) {
            val rows = statuses.filter { it.habit.code.startsWith(cat) }
            if (rows.isEmpty()) continue
            item(key = "cat-$cat") {
                Text("$cat  $catName", style = SafeLogType.bodyStrong, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
            }
            items(rows, key = { it.habit.habit }) { st -> HabitRow(st, view, st.habit.habit == selected) { onSelect(st.habit.habit) } }
        }
    }
}

@Composable
private fun HabitRow(st: LabHabitStatus, view: LabView?, selected: Boolean, onClick: () -> Unit) {
    val colors = SafeLogTheme.colors
    val h = st.habit
    val js = view?.judge?.judgments?.filter { it.habit == h.habit } ?: emptyList()
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) colors.surfaceHigh else colors.surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(h.code, style = SafeLogType.label, color = colors.inkMuted, modifier = Modifier.width(52.dp))
        Text(h.title, style = SafeLogType.label, color = colors.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val (txt, c) = when {
            h.blocked != null -> "차에서 못 읽음" to Red
            st.demonstrable -> "시연 가능" to Green
            else -> "필요: " + st.missing.joinToString(", ") { LabSignals.ALL[it] ?: it } to Amber
        }
        LabChip(txt, c)
        if (h.live) {
            val (s, n) = view?.live?.get(h.habit) ?: (0 to 0)
            LabChip("주행 중 $s/$n", Blue)
        }
        if (js.isNotEmpty()) {
            val counts = js.groupingBy { it.label }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }
            LabChip(counts, if (js.any { it.outcome == 1 || it.held }) Green else Gray)
        }
    }
}

@Composable
private fun LabChip(text: String, c: Color) {
    Box(Modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = SafeLogType.labelSmall, color = c, maxLines = 1)
    }
}

@Composable
private fun Detail(view: LabView?, habit: String?, modifier: Modifier) {
    val colors = SafeLogTheme.colors
    Column(
        modifier.fillMaxSize().background(colors.surface, RoundedCornerShape(16.dp)).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val h = habit?.let { LabCatalog.byHabit(it) }
        if (h == null) {
            Text("주행 중 감지 (최근)", style = SafeLogType.bodyStrong, color = colors.ink)
            val ev = view?.events ?: emptyList()
            if (ev.isEmpty()) Text("아직 없어요. 왼쪽에서 습관을 고르면 하는 법과 판정이 보여요.", style = SafeLogType.label, color = colors.inkMuted)
            for (e in ev.take(25)) {
                val name = LabCatalog.byHabit(e.habit)?.title ?: e.habit
                Text("${"%.1f".format(e.t)}초  $name  ${if (e.success) "성공 (칭찬음)" else "기회만"}", style = SafeLogType.label,
                    color = if (e.success) Green else colors.inkSecondary)
            }
            view?.judge?.let { j ->
                Text("엔진 정보: 조향 ${j.steerUnit ?: "-"} (${j.steerSource ?: "-"}) · 차속 ${j.speedHz?.let { "%.1f".format(it) } ?: "-"}Hz · ${j.km?.let { "%.2f".format(it) } ?: "-"}km",
                    style = SafeLogType.labelSmall, color = colors.inkMuted)
            }
        } else HabitDetail(view, h)
    }
}

@Composable
private fun HabitDetail(view: LabView?, h: com.safelog.core.lab.LabHabit) {
    val colors = SafeLogTheme.colors
    run {
        Text("${h.code} ${h.title}", style = SafeLogType.bodyLarge, color = colors.ink)
        Text("엔진 키: ${h.habit}", style = SafeLogType.labelSmall, color = colors.inkMuted)
        h.blocked?.let { Text(it, style = SafeLogType.label, color = Red) }
        Text("하는 법", style = SafeLogType.bodyStrong, color = colors.ink)
        Text(h.howTo, style = SafeLogType.label, color = colors.inkSecondary)
        Text("필요한 신호", style = SafeLogType.bodyStrong, color = colors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in h.needs) {
                val st = view?.signals?.get(k) ?: SignalState.Missing
                LabChip("${LabSignals.ALL[k] ?: k}: " + when (st) { SignalState.Working -> "작동 확인"; SignalState.Readable -> "읽힘"; SignalState.Missing -> "없음" },
                    when (st) { SignalState.Working -> Green; SignalState.Readable -> Blue; SignalState.Missing -> Gray })
            }
        }
        if (h.live) {
            val (s, n) = view?.live?.get(h.habit) ?: (0 to 0)
            Text("주행 중 감지기: 기회 ${n}번 · 성공 ${s}번", style = SafeLogType.bodyStrong, color = colors.ink)
            for (e in (view?.events ?: emptyList()).filter { it.habit == h.habit }.take(10)) {
                Text("${"%.1f".format(e.t)}초  ${if (e.success) "성공 (칭찬음)" else "기회만"}", style = SafeLogType.label,
                    color = if (e.success) Green else colors.inkSecondary)
            }
        }
        Text("엔진 판정 (규칙 + 학습 게이트)", style = SafeLogType.bodyStrong, color = colors.ink)
        val js = view?.judge?.judgments?.filter { it.habit == h.habit } ?: emptyList()
        if (view?.judge == null) Text("'지금 판정'을 누르면 지금까지의 주행을 판정해요. 주차하면 자동으로 판정돼요.", style = SafeLogType.label, color = colors.inkMuted)
        else if (js.isEmpty()) Text("이번 기록에는 기회가 없었어요.", style = SafeLogType.label, color = colors.inkMuted)
        for (j in js) JudgmentLine(j)
    }
}

@Composable
private fun JudgmentLine(j: LabJudgment) {
    val c = when { j.held || j.outcome == 1 -> Green; j.outcome == 0 -> Red; else -> Gray }
    val v = if (j.value.isNaN()) "" else " · 값 ${"%.3f".format(j.value)}" + if (j.threshold.isNaN()) "" else " / 기준 ${"%.3f".format(j.threshold)}"
    val p = j.gateP?.let { " · 게이트 ${"%.2f".format(it)}" } ?: ""
    Text("${"%.1f".format(j.t)}초  ${j.label}${if (j.reason.isNotEmpty()) " (${j.reason})" else ""}$v$p", style = SafeLogType.label, color = c)
}
