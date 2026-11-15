package com.safelog.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.safelog.app.R

/**
 * Pleos 공식 토큰 값. 출처: Figma "Pleos Connect display guide"의 Foundation > Colors.
 * accent, reward, coach만 세이프로그 고유 색이다(아틀라스의 파랑과 빛).
 */
@Immutable
data class SafeLogColors(
    val background: Color, // background_primary
    val surface: Color, // surface_basic
    val surfaceHigh: Color, // surface_high
    val ink: Color, // text_dark_primary
    val inkSecondary: Color, // text_dark_secondary
    val inkMuted: Color, // text_dark_tertiary
    val inkDisabled: Color, // text_dark_quaternary
    val divider: Color, // divider_secondary
    val tabBackground: Color, // controller_tab_background
    val tabSelected: Color, // button_switch_enabled
    val buttonBasic: Color, // button_basic_enabled
    val buttonBasicPressed: Color, // button_basic_pressed
    val buttonFilled: Color, // button_filled_enabled
    val buttonFilledPressed: Color, // button_filled_pressed
    val buttonFilledDisabled: Color, // button_filled_disabled
    val onButtonFilled: Color, // text_light_primary
    val accent: Color,
    val onAccent: Color,
    val reward: Color,
    val positive: Color, // informative_positive. 지금 쓰지 않음(예전 B안의 강조색)
    val onAccentPositive: Color,
    val coach: Color,
    val onCoach: Color,
    val switchOn: Color, // 스위치 켜짐. 공식 초록 대신 주 버튼과 같은 파랑(강조색을 하나로)
    val primary: Color, // 앞으로 가는 버튼(교환하기·동의하기). 아틀라스 파랑
    val primaryPressed: Color,
    val onPrimary: Color,
    val destructive: Color, // 되돌릴 수 없는 삭제 버튼
    val destructivePressed: Color,
    val popup: Color, // ivi_system_popup 바탕. 다크에서는 가림막 위에서 구분되게 한 단계 밝다
)

private val Ink = Color(0xFF131417)
private val Paper = Color(0xFFFFFFFF)

val LightColors = SafeLogColors(
    background = Color(0xFFF7F8FA),
    surface = Paper,
    surfaceHigh = Color(0xFFEDEEF2),
    ink = Ink,
    inkSecondary = Ink.copy(alpha = 0.84f),
    inkMuted = Ink.copy(alpha = 0.64f),
    inkDisabled = Ink.copy(alpha = 0.32f),
    divider = Ink.copy(alpha = 0.10f),
    tabBackground = Ink.copy(alpha = 0.05f),
    tabSelected = Paper,
    buttonBasic = Ink.copy(alpha = 0.05f),
    buttonBasicPressed = Ink.copy(alpha = 0.10f),
    buttonFilled = Color(0xFF44464E),
    buttonFilledPressed = Color(0xFF313236),
    buttonFilledDisabled = Ink.copy(alpha = 0.05f),
    onButtonFilled = Paper,
    accent = Color(0xFF3D5FB8),
    onAccent = Paper,
    reward = Color(0xFF8A5A00),
    positive = Color(0xFF005CE6), // 공식 #0064FF는 회색 카드에서 대비 4.5:1이 안 돼 어둡게 했다
    onAccentPositive = Paper,
    coach = Color(0xFF1B4FA8),
    onCoach = Paper,
    switchOn = Color(0xFF3D5FB8),
    primary = Color(0xFF3D5FB8), // 흰 글자 대비 5.9:1
    primaryPressed = Color(0xFF2F4A93),
    onPrimary = Paper,
    destructive = Color(0xFFD93A3A), // 흰 글자 대비 4.5:1
    destructivePressed = Color(0xFFB82E2E),
    popup = Paper,
)

val DarkColors = SafeLogColors(
    background = Color(0xFF131417), // background_secondary
    surface = Color(0xFF1D1E21), // basic_50
    surfaceHigh = Color(0xFF313236),
    ink = Paper,
    inkSecondary = Paper.copy(alpha = 0.84f),
    inkMuted = Paper.copy(alpha = 0.64f),
    inkDisabled = Paper.copy(alpha = 0.32f),
    divider = Paper.copy(alpha = 0.10f),
    tabBackground = Paper.copy(alpha = 0.05f),
    tabSelected = Color(0xFF44464E),
    buttonBasic = Paper.copy(alpha = 0.05f),
    buttonBasicPressed = Paper.copy(alpha = 0.10f),
    buttonFilled = Color(0xFF44464E),
    buttonFilledPressed = Color(0xFF686A72),
    buttonFilledDisabled = Paper.copy(alpha = 0.05f),
    onButtonFilled = Paper,
    accent = Color(0xFF9DB5EE),
    onAccent = Ink,
    reward = Color(0xFFF5C76B),
    positive = Color(0xFF4D9DFF), // 공식 #0082FF는 어두운 면에서 대비 4.5:1이 안 돼 밝혔다
    onAccentPositive = Ink,
    coach = Color(0xFF1B4FA8),
    onCoach = Paper,
    // 다크의 강조색(#9DB5EE)은 칠하면 파스텔처럼 흐려서, 버튼·스위치만 더 진한 파랑에 흰 글자를 쓴다.
    switchOn = Color(0xFF4A6FD1),
    primary = Color(0xFF4A6FD1), // 흰 글자 대비 4.7:1
    primaryPressed = Color(0xFF3D5FB8),
    onPrimary = Paper,
    destructive = Color(0xFFDC3E42), // 흰 글자 대비 4.4:1(큰 굵은 글자)
    destructivePressed = Color(0xFFC93434),
    popup = Color(0xFF313236),
)

@OptIn(ExperimentalTextApi::class)
private fun asta(weight: FontWeight) = Font(
    R.font.asta_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private val AstaSans = FontFamily(
    asta(FontWeight.Normal),
    asta(FontWeight.Bold),
    asta(FontWeight.ExtraBold),
)

private fun style(weight: FontWeight, size: Int, line: Int) =
    TextStyle(fontFamily = AstaSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

/** Pleos 공식 글자 표. 출처: Figma Foundation > Typography. */
object SafeLogType {
    // 큰 숫자는 자간을 좁히고 폭을 고정한다. 공식 Feature/Speed도 큰 숫자의 자간을 좁힌다.
    val number = style(FontWeight.ExtraBold, 60, 80).copy(fontFeatureSettings = "tnum", letterSpacing = (-0.02).em) // Headline/Large
    val numberMedium = style(FontWeight.ExtraBold, 48, 64).copy(fontFeatureSettings = "tnum", letterSpacing = (-0.02).em) // Headline/Small
    val numberCompact = style(FontWeight.ExtraBold, 40, 52).copy(fontFeatureSettings = "tnum", letterSpacing = (-0.02).em) // Title/Large
    val title = style(FontWeight.ExtraBold, 36, 44) // Title/Medium
    val button = style(FontWeight.Bold, 30, 38) // Body/Large/Strong, 버튼 Medium
    val buttonLarge = style(FontWeight.Bold, 32, 40) // Title/Small/Strong, 버튼 Large
    val bodyStrong = style(FontWeight.Bold, 26, 34) // Body/Small/Strong, 버튼 Small
    val body = style(FontWeight.Normal, 26, 34) // Body/Small/Normal
    val bodyLarge = style(FontWeight.Bold, 30, 38) // Body/Large/Strong, B안 제목
    val label = style(FontWeight.Normal, 24, 28) // Label/Medium
    val labelSmall = style(FontWeight.Normal, 20, 24) // Label/Small
    val bubble = style(FontWeight.Bold, 30, 48) // Feature/Bubble
}

private val LocalColors = staticCompositionLocalOf { LightColors }

/** 화면 일부의 색을 바꾼다. B안 탭은 바탕과 카드 색을 맞바꿔 쓴다. */
@Composable
fun ProvideSafeLogColors(colors: SafeLogColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColors provides colors, content = content)
}

object SafeLogTheme {
    val colors: SafeLogColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
}

@Composable
fun SafeLogTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(primary = colors.accent, background = colors.background, surface = colors.surface)
    } else {
        lightColorScheme(primary = colors.accent, background = colors.background, surface = colors.surface)
    }
    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
