package com.safelog.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.safelog.app.model.DrivingExperience
import com.safelog.app.model.ProfileAnswer
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.SurfaceCard
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

/**
 * 운전자 프로필 질문(기획서 3장 1번, 4장). 첫 설정의 한 단계이고, 운전자를 더할 때도 같은 화면이다.
 * 이름은 비워 둘 수 있다. 두 질문은 골라야 넘어간다: 운전 경력이 시작 모드를(처음·오랜만은 연수 모드, 익숙하면 평소 모드),
 * 이 차가 처음인지가 내 차 익히기 권유를 정한다. 고르면 무엇이 정해졌는지 카드 제목 옆 꼬리표로 바로 알린다.
 * 선택지는 설정의 주행 중 신호처럼 한 줄 묶음(Segmented)으로 둔다. 행으로 풀면 가장 낮은 창(높이 800)에서 둘째 질문이
 * 화면 아래로 밀려 보이지 않았다. 이 배치는 공식 창 크기 모두에서 스크롤 없이 한 화면에 들어간다.
 */
@Composable
fun DriverProfileStep(
    title: String,
    name: String,
    onName: (String) -> Unit,
    experience: DrivingExperience?,
    onExperience: (DrivingExperience) -> Unit,
    carFamiliar: Boolean?,
    onCarFamiliar: (Boolean) -> Unit,
    backText: String,
    onBack: () -> Unit,
    nextText: String,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    namePlaceholder: String = "운전자",
) {
    val colors = SafeLogTheme.colors
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background)) {
        Column(
            Modifier.fillMaxSize().padding(screenInset(maxWidth >= WIDE_BREAKPOINT)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(STACK_GAP),
        ) {
            ScreenBody(0.dp, Modifier.weight(1f).widthIn(max = FLOW_MAX_WIDTH), spacing = STACK_GAP) {
                Text(title, style = SafeLogType.title, color = colors.ink)
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Text("이름 (선택)", style = SafeLogType.label, color = colors.inkMuted)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceHigh, RoundedCornerShape(16.dp))
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                    ) {
                        BasicTextField(
                            value = name,
                            onValueChange = { if (it.length <= MAX_DRIVER_NAME_LENGTH) onName(it) },
                            singleLine = true,
                            textStyle = SafeLogType.body.copy(color = colors.ink),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (name.isEmpty()) Text(namePlaceholder, style = SafeLogType.body, color = colors.inkMuted)
                    }
                }
                ListCard(
                    title = "운전 경력",
                    trailing = experience?.let { e ->
                        { Chip(if (e == DrivingExperience.Experienced) "평소 모드로 시작해요" else "연수 모드로 시작해요", colors.accent) }
                    },
                ) {
                    Choices(DrivingExperience.entries.map { it.label }, experience?.ordinal ?: -1) { onExperience(DrivingExperience.entries[it]) }
                }
                ListCard(
                    title = "이 차",
                    trailing = if (carFamiliar == false) ({ Chip("내 차 익히기를 권해요", colors.accent) }) else null,
                ) {
                    Choices(listOf(CAR_NEW, CAR_FAMILIAR), when (carFamiliar) { null -> -1; false -> 0; true -> 1 }) { onCarFamiliar(it == 1) }
                }
            }
            Row(Modifier.widthIn(max = FLOW_MAX_WIDTH), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SafeLogButton(backText, onBack, Modifier.weight(1f), size = ButtonSize.Large)
                SafeLogButton(
                    nextText,
                    onNext,
                    Modifier.weight(2f),
                    primary = true,
                    enabled = experience != null && carFamiliar != null,
                    size = ButtonSize.Large,
                )
            }
        }
    }
}

private const val MAX_DRIVER_NAME_LENGTH = 10

private const val CAR_NEW = "처음 타요"
private const val CAR_FAMILIAR = "타 봤어요"

/** 한 질문의 선택지 묶음. [selected]가 -1이면 아직 고르지 않았다. */
@Composable
private fun Choices(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.padding(start = CARD_PADDING, end = CARD_PADDING, top = 4.dp, bottom = 16.dp)) {
        Segmented(options, selected, onSelect)
    }
}

/**
 * 운전자 추가. 프로필 질문에 답하면 그 사람이 지금 운전자가 된다(기록·미션·캐릭터는 운전자마다 따로).
 * 이름을 비우면 "운전자 [number]"가 된다.
 */
@Composable
fun AddDriverScreen(
    number: Int,
    onAdd: (ProfileAnswer) -> Unit,
    onBack: () -> Unit,
    initialExperience: DrivingExperience? = null, // 화면 이미지 시험용
    initialCarFamiliar: Boolean? = null, // 화면 이미지 시험용
) {
    var name by rememberSaveable { mutableStateOf("") }
    var experience by rememberSaveable { mutableStateOf(initialExperience) }
    var carFamiliar by rememberSaveable { mutableStateOf(initialCarFamiliar) }
    DriverProfileStep(
        title = "운전자 추가",
        name = name,
        onName = { name = it },
        experience = experience,
        onExperience = { experience = it },
        carFamiliar = carFamiliar,
        onCarFamiliar = { carFamiliar = it },
        backText = "취소",
        onBack = onBack,
        nextText = "추가하기",
        onNext = {
            val e = experience
            val f = carFamiliar
            if (e != null && f != null) onAdd(ProfileAnswer(name.trim().ifEmpty { null }, e, f))
        },
        namePlaceholder = "운전자 $number",
    )
}
