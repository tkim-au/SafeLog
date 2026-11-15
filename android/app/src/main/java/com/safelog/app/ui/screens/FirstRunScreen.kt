package com.safelog.app.ui.screens

import androidx.activity.compose.BackHandler
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
import com.safelog.app.ui.components.PushContent
import com.safelog.app.ui.components.Text
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
import com.safelog.app.ui.components.AtlasImage
import com.safelog.app.ui.components.AtlasMood
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.SurfaceCard
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

/**
 * S00 첫 설정. 처음 한 번 나오지만 닫기로 바로 나갈 수 있다.
 * 1단계: 운전 기록 동의. 동의 버튼을 눌렀을 때만 동의로 본다. 닫기와 뒤로 가기는 동의가 아니다.
 * 2단계: 운전자 프로필([DriverProfileStep]): 이름, 운전 경력, 이 차를 타 봤는지. [askProfile]이 false면(이미 답한 운전자가
 *        기록을 다시 켤 때) 건너뛴다.
 * 3단계: 보험 할인(보험 공유) 동의. 기록 동의와 따로 묻고, "나중에"로 건너뛸 수 있다.
 */
@Composable
fun FirstRunScreen(
    onClose: () -> Unit,
    onAgree: (atlasName: String?, profile: ProfileAnswer?, insuranceShare: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initialStep: Int = STEP_CONSENT, // 화면 이미지 시험용
    askProfile: Boolean = true,
    initialExperience: DrivingExperience? = null, // 화면 이미지 시험용
    initialCarFamiliar: Boolean? = null, // 화면 이미지 시험용
) {
    var name by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(initialStep) }
    var driverName by rememberSaveable { mutableStateOf("") }
    var experience by rememberSaveable { mutableStateOf(initialExperience) }
    var carFamiliar by rememberSaveable { mutableStateOf(initialCarFamiliar) }
    BackHandler {
        when (step) {
            STEP_INSURANCE -> step = if (askProfile) STEP_PROFILE else STEP_CONSENT
            STEP_PROFILE -> step = STEP_CONSENT
            else -> onClose()
        }
    }
    val finish = { insuranceShare: Boolean ->
        val e = experience
        val f = carFamiliar
        val profile = if (askProfile && e != null && f != null) ProfileAnswer(driverName.trim().ifEmpty { null }, e, f) else null
        onAgree(name.trim().ifEmpty { null }, profile, insuranceShare)
    }
    PushContent(step, forward = { from, to -> to > from }, label = "firstRun") { shownStep ->
        when (shownStep) {
            STEP_PROFILE -> DriverProfileStep(
                title = "어떤 운전자인지 알려 주세요",
                name = driverName,
                onName = { driverName = it },
                experience = experience,
                onExperience = { experience = it },
                carFamiliar = carFamiliar,
                onCarFamiliar = { carFamiliar = it },
                backText = "뒤로",
                onBack = { step = STEP_CONSENT },
                nextText = "다음",
                onNext = { step = STEP_INSURANCE },
                modifier = modifier,
            )
            STEP_INSURANCE -> InsuranceStep(onLater = { finish(false) }, onAgree = { finish(true) }, modifier = modifier)
            else -> ConsentStep(name, { name = it }, onClose, { step = if (askProfile) STEP_PROFILE else STEP_INSURANCE }, modifier)
        }
    }
}

/** 첫 설정의 단계. 화면 이미지 시험에서 [FirstRunScreen]의 initialStep으로 쓴다. */
const val STEP_CONSENT = 0
const val STEP_PROFILE = 1
const val STEP_INSURANCE = 2

@Composable
private fun ConsentStep(name: String, onName: (String) -> Unit, onClose: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    val colors = SafeLogTheme.colors
    // 흐름 화면 규칙: 가운데 한 칸, 폭 최대 840, 가장자리 24(세로 창 16), 카드 사이 12.
    BoxWithConstraints(modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().padding(screenInset(maxWidth >= WIDE_BREAKPOINT)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(STACK_GAP),
    ) {
        ScreenBody(0.dp, Modifier.weight(1f).widthIn(max = FLOW_MAX_WIDTH), spacing = STACK_GAP) {
            AtlasImage(AtlasMood.Default, 144.dp)
            Text("운전을 기록할까요", style = SafeLogType.title, color = colors.ink)
            ListCard {
                ListRow("브레이크·깜빡이·핸들·내비·위치", sub = "읽는 것")
                // 원본 주행 신호는 차 밖으로 나가지 않는다. 서버에는 잘한 횟수와 포인트 같은 요약만 간다(이 동의가 그 동의다).
                ListRow("원본은 차 안에 90일, 요약만 서버에", sub = "보관")
            }
            SurfaceCard(Modifier.fillMaxWidth()) {
                Text("캐릭터 이름 (선택)", style = SafeLogType.label, color = colors.inkMuted)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceHigh, RoundedCornerShape(16.dp))
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { if (it.length <= MAX_NAME_LENGTH) onName(it) },
                        singleLine = true,
                        textStyle = SafeLogType.body.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (name.isEmpty()) {
                        Text("아틀라스", style = SafeLogType.body, color = colors.inkMuted)
                    }
                }
            }
        }
        Row(Modifier.widthIn(max = FLOW_MAX_WIDTH), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SafeLogButton("닫기", onClose, Modifier.weight(1f), size = ButtonSize.Large)
            SafeLogButton(
                "동의하기",
                onNext,
                Modifier.weight(2f),
                primary = true,
                size = ButtonSize.Large,
            )
        }
    }
    }
}

private const val MAX_NAME_LENGTH = 10

/** 3단계: 보험 할인. 무엇을 누구에게 언제 보내는지만 보여 준다. */
@Composable
private fun InsuranceStep(onLater: () -> Unit, onAgree: () -> Unit, modifier: Modifier) {
    val colors = SafeLogTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(screenInset(maxWidth >= WIDE_BREAKPOINT)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(STACK_GAP),
        ) {
            ScreenBody(0.dp, Modifier.weight(1f).widthIn(max = FLOW_MAX_WIDTH), spacing = STACK_GAP) {
                AtlasImage(AtlasMood.Joy, 144.dp)
                Text("보험 할인도 받아 볼까요", style = SafeLogType.title, color = colors.ink)
                ListCard {
                    ListRow("안전하게 달린 거리 요약", sub = "보내는 것")
                    ListRow("가입한 보험사, 갱신 때", sub = "받는 곳")
                    ListRow("설정에서 언제든 끌 수 있어요", sub = "끄기")
                }
            }
            Row(Modifier.widthIn(max = FLOW_MAX_WIDTH), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SafeLogButton("나중에", onLater, Modifier.weight(1f), size = ButtonSize.Large)
                SafeLogButton("보험 할인 받기", onAgree, Modifier.weight(2f), primary = true, size = ButtonSize.Large)
            }
        }
    }
}
