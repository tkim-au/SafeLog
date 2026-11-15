package com.safelog.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

val CardShape = RoundedCornerShape(24.dp)

/** 기본 스프링. 감쇠 1.0(튕김 없음), 응답 0.3초. 움직이는 중에 다시 눌러도 현재 값에서 이어진다. */
val SafeLogSpring = spring<Float>(dampingRatio = 1f, stiffness = 438f)

/** 누르는 동안 0.97로 줄어든다. 재구성 없이 그리기 단계에서만 움직인다. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, SafeLogSpring, label = "pressScale")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 한글 단어 안에서 줄이 바뀌지 않게 글자 사이에 WORD JOINER를 넣는다. 공백에서만 줄을 바꾼다. */
fun keepWords(text: String): String = buildString(text.length * 2) {
    text.forEachIndexed { i, c ->
        if (i > 0 && !c.isWhitespace() && !text[i - 1].isWhitespace()) append('\u2060')
        append(c)
    }
}

/** 앱 전체에서 쓰는 글자. "멈췄어/요"처럼 끊기는 줄바꿈을 막는다. */
@Composable
fun Text(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val display = remember(text) { keepWords(text) }
    androidx.compose.material3.Text(
        text = display,
        style = style,
        color = color,
        modifier = modifier,
        textAlign = textAlign,
        maxLines = maxLines,
        minLines = minLines,
    )
}

/** 선 없이 면 색으로만 구분한다. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(SafeLogTheme.colors.surface)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** 공식 ivi_basic_button 크기. 높이, 좌우 여백, 모서리, 최소 폭 순서다. */
enum class ButtonSize(val height: Dp, val paddingH: Dp, val radius: Dp, val minWidth: Dp) {
    Large(100.dp, 40.dp, 32.dp, 140.dp),
    Medium(80.dp, 32.dp, 24.dp, 120.dp),
    Small(64.dp, 24.dp, 16.dp, 100.dp),
}

/**
 * 버튼의 역할. 토스처럼 화면마다 앞으로 가는 행동 하나만 파랗게 칠한다.
 * - [Primary] 앞으로 가는 행동(교환하기·동의하기·켜기·좋아요). 아틀라스 파랑.
 * - [Destructive] 되돌릴 수 없는 삭제(기록 지우기). 빨강. 권하는 행동처럼 보이면 안 된다.
 * - [Neutral] 멈추는 행동(운전 기록 끄기·연수 모드 끄기). 짙은 회색(공식 button_filled).
 * - [Basic] 나머지(닫기·취소·들어보기). 옅은 면(공식 button_basic).
 */
enum class ButtonTone { Basic, Primary, Neutral, Destructive }

/**
 * 공식 ivi_basic_button의 크기·모서리에 역할별 색([ButtonTone])을 입힌다. [primary]는 [ButtonTone.Primary]의 줄임.
 * 못 누를 때는 테두리 없이 옅은 면과 흐린 글자만 남긴다. 눌린 색과 크기가 바로 바뀌어 터치 반응 0.25초 기준을 지킨다.
 */
@Composable
fun SafeLogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Medium,
    tone: ButtonTone = if (primary) ButtonTone.Primary else ButtonTone.Basic,
) {
    val colors = SafeLogTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(size.radius)
    val background = when {
        !enabled -> colors.buttonBasic
        tone == ButtonTone.Primary -> if (pressed) colors.primaryPressed else colors.primary
        tone == ButtonTone.Destructive -> if (pressed) colors.destructivePressed else colors.destructive
        tone == ButtonTone.Neutral -> if (pressed) colors.buttonFilledPressed else colors.buttonFilled
        pressed -> colors.buttonBasicPressed
        else -> colors.buttonBasic
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = size.minWidth, minHeight = size.height)
            .pressScale(interaction)
            .clip(shape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = size.paddingH),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = when (size) {
                ButtonSize.Large -> SafeLogType.buttonLarge
                ButtonSize.Medium -> SafeLogType.button
                ButtonSize.Small -> SafeLogType.bodyStrong
            },
            color = when {
                !enabled -> colors.inkDisabled
                tone == ButtonTone.Primary -> colors.onPrimary
                tone == ButtonTone.Destructive -> colors.onPrimary
                tone == ButtonTone.Neutral -> colors.onButtonFilled
                else -> colors.ink
            },
        )
    }
}

@Composable
fun ProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp) // 공식 slider_track 두께
            .clip(RoundedCornerShape(4.dp))
            .background(SafeLogTheme.colors.surfaceHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(color),
        )
    }
}
