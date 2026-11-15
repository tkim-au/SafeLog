package com.safelog.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.safelog.app.ui.theme.SafeLogTheme

/*
 * 움직임 규칙. 차 화면이라 짧고 덜 튀게, 모든 화면이 같은 값을 쓴다.
 * - 화면 들어가기·나오기(푸시): 300ms. 새 화면은 오른쪽 끝에서 밀려 들어오고 이전 화면은 1/4만 왼쪽으로 물러난다(iOS 내비게이션).
 * - 팝업: 들어올 때 250ms(가림막 페이드 + 카드 0.94→1), 나갈 때 150ms. 닫기가 열기보다 빠르다.
 * - 같은 화면 안 내용 바뀜(날짜·문구·교환소 분야): 180ms 교차 페이드.
 * - 위쪽 탭 바뀜: 위쪽 줄은 제자리에서 180ms 교차 페이드, 줄 아래 내용은 탭 순서 방향으로 80dp 밀리며 240ms(TabSwipe.kt).
 *   탭은 오늘 → 기록 → 교환소 → 캐릭터 순서로 옆에 놓인 것처럼 움직인다. 화면을 옆으로 밀어 넘길 때와 방향이 같다.
 * - 누름: 0.97 스프링(Parts.pressScale). 크게 튀는 스프링은 쓰지 않는다.
 */
object Motion {
    const val PUSH = 300
    const val POPUP_IN = 250
    const val POPUP_OUT = 150
    const val FADE = 180
    const val TAB = 240
}

/** 빠르게 시작해 부드럽게 멈춘다(강조 감속). 들어오는 것에 쓴다. */
val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 화면 푸시. [forward]면 깊이 들어가고, 아니면 뒤로 나온다(나가는 화면이 위에서 오른쪽으로 빠진다). */
fun push(forward: Boolean): ContentTransform {
    val spec = tween<androidx.compose.ui.unit.IntOffset>(Motion.PUSH, easing = EaseOut)
    return if (forward) {
        slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(Motion.PUSH), 0.6f)
    } else {
        (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(Motion.PUSH), 0.6f) togetherWith slideOutHorizontally(spec) { it })
            .apply { targetContentZIndex = -1f }
    }
}

/**
 * 화면 단위 전환. 두 화면이 겹쳐 움직이므로 각 화면에 바탕을 깐다(투명한 화면 뒤로 다른 화면이 비치지 않게).
 * [forward]는 (이전, 다음)을 받아 깊이 들어가는지 알려 준다.
 */
@Composable
fun <S> PushContent(
    target: S,
    forward: (initial: S, target: S) -> Boolean,
    modifier: Modifier = Modifier,
    label: String = "push",
    content: @Composable (S) -> Unit,
) {
    val background = SafeLogTheme.colors.background
    AnimatedContent(target, modifier, transitionSpec = { push(forward(initialState, targetState)) }, label = label) { state ->
        Box(Modifier.fillMaxSize().background(background)) { content(state) }
    }
}
