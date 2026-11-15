package com.safelog.app.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safelog.app.model.Account
import com.safelog.app.model.formatPhone
import com.safelog.app.model.isPhoneNumber
import com.safelog.app.ui.components.AtlasImage
import com.safelog.app.ui.components.AtlasMood
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.PopCheck
import com.safelog.app.ui.components.PushContent
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.SurfaceCard
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType
import kotlinx.coroutines.delay

/**
 * 계정 가입. 설정 → 운전자 칸의 "계정"에서 들어오고, 지금 운전자의 계정을 만든다.
 * 주차 중에만 보인다(기어를 넣으면 주행 화면으로 바뀐다).
 * 1단계: 이름·휴대폰 번호, 필수 약관 둘 → 인증번호 받기
 * 2단계: 문자로 받은 6자리를 3분 안에 → 가입하기. 틀리거나 시간이 지나면 칸 아래 한 줄로 알린다.
 * 3단계: 가입했어요 → 확인(설정으로 돌아감)
 * 차 화면이라 치는 양을 줄인다. 이름은 운전자 이름으로 채워 두고, 번호 칸은 숫자 자판이며 자판의 다음·완료로 넘어간다.
 * ponytail: 약관 본문 화면은 약관 문서가 정해지면 행에 "보기"로 붙인다.
 */
@Composable
fun SignUpScreen(
    driverName: String,
    onRequestCode: (phone: String) -> Unit,
    onSignUp: (name: String, phone: String, code: String) -> Boolean,
    onBack: () -> Unit,
    // 화면 이미지 시험용
    initialStep: Int = 0,
    initialPhone: String = "",
    initialAgreed: Boolean = false,
    initialCode: String = "",
    initialWrong: Boolean = false,
) {
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    var name by rememberSaveable { mutableStateOf(driverName) }
    var phone by rememberSaveable { mutableStateOf(initialPhone) } // 숫자만
    var terms by rememberSaveable { mutableStateOf(initialAgreed) }
    var privacy by rememberSaveable { mutableStateOf(initialAgreed) }
    var code by rememberSaveable { mutableStateOf(initialCode) }
    var wrong by rememberSaveable { mutableStateOf(initialWrong) }
    var sends by rememberSaveable { mutableIntStateOf(if (initialStep > 0) 1 else 0) }
    var sentAt by rememberSaveable { mutableLongStateOf(if (initialStep > 0) SystemClock.elapsedRealtime() else 0L) }
    val keyboard = LocalSoftwareKeyboardController.current

    val send: () -> Unit = {
        onRequestCode(phone)
        sends++
        sentAt = SystemClock.elapsedRealtime()
        code = ""
        wrong = false
        step = 1
    }
    val submit: () -> Unit = {
        if (onSignUp(name.trim(), phone, code)) {
            keyboard?.hide()
            step = 2
        } else {
            wrong = true
        }
    }
    // 인증번호 단계에서 뒤로 가면 번호를 고칠 수 있게 1단계로. 다 했으면 설정으로.
    val back: () -> Unit = { if (step == 1) step = 0 else onBack() }
    BackHandler(onBack = back)

    val colors = SafeLogTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val inset = screenInset(maxWidth >= WIDE_BREAKPOINT)
        Column(Modifier.fillMaxSize().padding(start = inset, end = inset, top = inset)) {
            DepthHeader("가입", back)
            PushContent(step, forward = { from, to -> to > from }, Modifier.weight(1f), label = "signUp") { shown ->
                when (shown) {
                    0 -> InfoStep(
                        name = name,
                        onName = { if (it.length <= MAX_NAME_LENGTH) name = it },
                        phone = phone,
                        onPhone = { phone = it.filter(Char::isDigit).take(11) },
                        terms = terms,
                        onTerms = { terms = it },
                        privacy = privacy,
                        onPrivacy = { privacy = it },
                        onSend = send,
                        inset = inset,
                    )
                    1 -> CodeStep(
                        phone = phone,
                        resent = sends > 1,
                        sentAt = sentAt,
                        code = code,
                        onCode = { v -> code = v.filter(Char::isDigit).take(CODE_LENGTH); wrong = false },
                        wrong = wrong,
                        onResend = send,
                        onSubmit = submit,
                        inset = inset,
                    )
                    else -> DoneStep(Account(name.trim(), phone), onBack, inset)
                }
            }
        }
    }
}

@Composable
private fun InfoStep(
    name: String,
    onName: (String) -> Unit,
    phone: String,
    onPhone: (String) -> Unit,
    terms: Boolean,
    onTerms: (Boolean) -> Unit,
    privacy: Boolean,
    onPrivacy: (Boolean) -> Unit,
    onSend: () -> Unit,
    inset: Dp,
) {
    val colors = SafeLogTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = name.isNotBlank() && isPhoneNumber(phone) && terms && privacy
    StepLayout(
        inset,
        buttons = {
            SafeLogButton("인증번호 받기", onSend, Modifier.fillMaxWidth(), primary = true, enabled = ready, size = ButtonSize.Large)
        },
    ) {
        Text("휴대폰 번호로 가입해요", style = SafeLogType.title, color = colors.ink)
        SurfaceCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Field("이름") {
                    InputBox(
                        value = name,
                        onValueChange = onName,
                        placeholder = "이름",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                Field("휴대폰 번호") {
                    InputBox(
                        value = phone,
                        onValueChange = onPhone,
                        placeholder = "010-0000-0000",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        // 차 자판은 창을 줄이지 않고 아래를 덮는다. 아직 못 넘어가면 완료로 자판만 닫아 약관 행을 보이게 한다.
                        // 포커스는 그대로 둔다. clearFocus를 하면 실제 Enter 키(adb·운전대 버튼)가 뒤로 버튼까지 눌렀다.
                        keyboardActions = KeyboardActions(onDone = { if (ready) onSend() else keyboard?.hide() }),
                        visualTransformation = PhoneTransformation,
                    )
                }
            }
        }
        ListCard(title = "약관 동의") {
            AgreeRow("서비스 이용약관", terms, onTerms)
            AgreeRow("개인정보 수집·이용", privacy, onPrivacy)
        }
    }
}

@Composable
private fun AgreeRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = SafeLogTheme.colors
    ListRow(title, Modifier.pressSelect(checked) { onChange(!checked) }, badge = { Chip("필수", colors.accent) }) {
        PopCheck(checked, colors.accent, colors.onAccent, colors.surfaceHigh)
    }
}

@Composable
private fun CodeStep(
    phone: String,
    resent: Boolean,
    sentAt: Long,
    code: String,
    onCode: (String) -> Unit,
    wrong: Boolean,
    onResend: () -> Unit,
    onSubmit: () -> Unit,
    inset: Dp,
) {
    val colors = SafeLogTheme.colors
    val left = rememberSecondsLeft(sentAt)
    val expired = left == 0
    val ready = code.length == CODE_LENGTH && !expired
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val inspection = LocalInspectionMode.current
    LaunchedEffect(Unit) { if (!inspection) focus.requestFocus() }
    StepLayout(
        inset,
        buttons = {
            // 반반으로 나눈다. 1:2면 가장 좁은 세로 창(628)에서 "다시 받기"가 두 줄이 된다.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SafeLogButton("다시 받기", onResend, Modifier.weight(1f), size = ButtonSize.Large)
                SafeLogButton("가입하기", onSubmit, Modifier.weight(1f), primary = true, enabled = ready, size = ButtonSize.Large)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
            Text("인증번호를 넣어 주세요", style = SafeLogType.title, color = colors.ink)
            Text(
                "${formatPhone(phone)}로 ${if (resent) "다시 " else ""}보냈어요",
                style = SafeLogType.body,
                color = colors.inkSecondary,
            )
        }
        SurfaceCard(Modifier.fillMaxWidth()) {
            Field("인증번호 6자리") {
                InputBox(
                    value = code,
                    onValueChange = onCode,
                    placeholder = "000000",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ready) onSubmit() else keyboard?.hide() }),
                    error = wrong || expired,
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Text("%d:%02d".format(left / 60, left % 60), style = SafeLogType.bodyStrong, color = if (expired) colors.destructive else colors.accent)
                }
                when {
                    expired -> ErrorLine("시간이 지났어요. 인증번호를 다시 받아 주세요")
                    wrong -> ErrorLine("인증번호가 맞지 않아요. 다시 확인해 주세요")
                }
            }
        }
    }
}

@Composable
private fun DoneStep(account: Account, onDone: () -> Unit, inset: Dp) {
    val colors = SafeLogTheme.colors
    StepLayout(
        inset,
        buttons = { SafeLogButton("확인", onDone, Modifier.fillMaxWidth(), primary = true, size = ButtonSize.Large) },
    ) {
        AtlasImage(AtlasMood.Cheer, 144.dp)
        Text("가입했어요", style = SafeLogType.title, color = colors.ink)
        ListCard {
            ListRow("이름") { ValueText(account.name) }
            ListRow("휴대폰 번호") { ValueText(account.maskedPhone) }
        }
    }
}

/** 흐름 화면 규칙(가운데 한 칸, 폭 최대 840, 카드 사이 12)에 아래쪽 버튼 줄. 내용이 넘치면 본문만 스크롤한다. */
@Composable
private fun StepLayout(
    inset: Dp,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(bottom = inset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(STACK_GAP),
    ) {
        ScreenBody(0.dp, Modifier.weight(1f).widthIn(max = FLOW_MAX_WIDTH), spacing = STACK_GAP, content = content)
        Box(Modifier.widthIn(max = FLOW_MAX_WIDTH).fillMaxWidth()) { buttons() }
    }
}

/** 칸 위 이름표와 칸 */
@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
        Text(label, style = SafeLogType.label, color = SafeLogTheme.colors.inkMuted)
        content()
    }
}

/**
 * 입력칸. 공식 ivi_text_field Medium 높이 100. 첫 설정의 이름 칸과 같은 면(surfaceHigh, 모서리 16).
 * 틀렸을 때만 빨간 테두리. 색만으로 말하지 않게 칸 아래에 [ErrorLine]을 함께 둔다.
 */
@Composable
private fun InputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    error: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = SafeLogTheme.colors
    val shape = RoundedCornerShape(16.dp)
    // 칸 전체(높이 100)를 입력칸으로 만든다. 그래야 글줄 위아래 여백을 눌러도 자판이 뜬다.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = SafeLogType.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 100.dp)
                    .background(colors.surfaceHigh, shape)
                    .then(if (error) Modifier.border(2.dp, colors.destructive, shape) else Modifier)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = SafeLogType.body, color = colors.inkDisabled)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = SafeLogType.label, color = SafeLogTheme.colors.destructive)
}

/** 보낸 때부터 남은 초. 화면 이미지 시험에서는 멈춰 있다(3:00). */
@Composable
private fun rememberSecondsLeft(sentAt: Long): Int {
    val inspection = LocalInspectionMode.current
    val left by produceState(CODE_SECONDS, sentAt) {
        if (inspection) return@produceState
        while (true) {
            value = (CODE_SECONDS - (SystemClock.elapsedRealtime() - sentAt) / 1000).coerceAtLeast(0).toInt()
            if (value == 0) break
            delay(250)
        }
    }
    return left
}

/** 숫자만 든 번호를 010-1234-5678로 보여 준다. 커서 위치는 줄표를 건너뛰어 맞춘다. */
private val PhoneTransformation = VisualTransformation { text ->
    val digits = text.text
    val shown = formatPhone(digits)
    // shown 안에서 n번째 숫자가 있는 자리
    val digitAt = shown.indices.filter { shown[it] != '-' }
    TransformedText(
        AnnotatedString(shown),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int) =
                if (offset >= digits.length) shown.length else digitAt[offset]

            override fun transformedToOriginal(offset: Int) = digitAt.count { it < offset }
        },
    )
}

private const val MAX_NAME_LENGTH = 20
private const val CODE_LENGTH = 6
private const val CODE_SECONDS = 180
