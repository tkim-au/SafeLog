package com.safelog.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/*
 * 위쪽 탭(오늘·기록·교환소·캐릭터)을 화면을 옆으로 밀어서도 넘긴다. 위쪽 줄을 누르는 것이 기본이고 이것은 덧붙인 길이다.
 * - 손을 뗄 때 정한다. 가로로 96dp 넘게 밀었거나, 32dp 넘게 밀며 빠르게(초당 600dp 넘게) 튕겼을 때. 화면이 손가락을 따라오지는 않는다.
 * - 손가락이 왼쪽으로 가면 다음 탭, 오른쪽으로 가면 앞 탭. 양 끝(오늘, 캐릭터)에서 더 밀면 아무 일도 없다.
 * - 세로로 먼저 움직이면 목록 스크롤이라 끼어들지 않는다. 안쪽에서 가로로 끄는 부품(교환소 분야 줄)이 먼저 잡으면 양보한다.
 * - 넘기기로 정해지면 그 손가락의 누름은 취소된다(밀다가 카드가 눌리지 않게).
 * - 팝업이 떠 있거나 탭 안에서 들어간 화면(교환소 상품)이 열려 있으면 꺼진다([HoldTabSwipe]).
 * 위쪽 줄은 탭 화면마다 안에 그린다(팝업 가림막이 줄까지 덮게). 그래서 화면 전체를 밀지 않고 줄 아래 내용만 [tabContentSlide]로 민다.
 */

private val SWIPE_DISTANCE = 96.dp
private val FLING_DISTANCE = 32.dp
private val FLING_SPEED = 600.dp // 초당

/** 탭이 바뀔 때 줄 아래 내용이 밀리는 거리 */
private val TAB_SLIDE = 80.dp

/** 탭 넘기기를 막고 있는 것의 수. 0이면 넘길 수 있다. */
class TabSwipeLock {
    var holds by mutableIntStateOf(0)
        private set
    val free: Boolean get() = holds == 0

    internal fun hold() { holds++ }
    internal fun release() { holds-- }
}

val LocalTabSwipeLock = staticCompositionLocalOf<TabSwipeLock?> { null }

/** 이것이 화면에 있는 동안 탭 넘기기를 막는다. 팝업([com.safelog.app.ui.screens.SafeLogPopup])과 탭 안 하위 화면이 부른다. */
@Composable
fun HoldTabSwipe() {
    val lock = LocalTabSwipeLock.current ?: return
    DisposableEffect(lock) {
        lock.hold()
        onDispose { lock.release() }
    }
}

/** 탭 넘기기 몸짓. [onSwipe]는 1(손가락이 왼쪽으로, 다음 탭) 또는 -1(오른쪽으로, 앞 탭)을 받는다. */
@Composable
fun Modifier.tabSwipe(enabled: Boolean, onSwipe: (Int) -> Unit): Modifier {
    val on by rememberUpdatedState(enabled)
    val swipe by rememberUpdatedState(onSwipe)
    return pointerInput(Unit) {
        val far = SWIPE_DISTANCE.toPx()
        val flingFar = FLING_DISTANCE.toPx()
        val flingSpeed = FLING_SPEED.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!on) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var swiping = false
            while (true) {
                val event = awaitPointerEvent()
                // 두 손가락은 넘기기가 아니다.
                if (event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                // 안쪽 부품(세로 목록, 가로로 끄는 줄)이 먼저 잡았다.
                if (!swiping && change.isConsumed) return@awaitEachGesture
                val moved = change.position - down.position
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) {
                    if (swiping && on && abs(moved.x) > abs(moved.y)) {
                        val speed = tracker.calculateVelocity().x
                        val flung = abs(moved.x) >= flingFar && abs(speed) >= flingSpeed && sign(speed) == sign(moved.x)
                        if (abs(moved.x) >= far || flung) {
                            change.consume()
                            swipe(if (moved.x < 0) 1 else -1)
                        }
                    }
                    return@awaitEachGesture
                }
                if (!swiping) {
                    if (abs(moved.y) > slop) return@awaitEachGesture // 세로로 먼저 움직였다(스크롤)
                    if (abs(moved.x) > slop && abs(moved.x) > 2 * abs(moved.y)) swiping = true
                }
                // 넘기는 중에는 움직임을 먹어 안쪽 누름과 스크롤이 시작되지 않게 한다.
                if (swiping) change.consume()
            }
        }
    }
}

/** 탭 전환 하나. [direction]은 탭 순서에서 뒤 탭으로 가면 1(오른쪽에서 들어옴), 앞 탭이면 -1. */
class TabSlide(internal val scope: AnimatedVisibilityScope, internal val direction: () -> Int)

val LocalTabSlide = compositionLocalOf<TabSlide?> { null }

/**
 * 위쪽 줄 아래 내용에 붙인다. 탭이 바뀌면 탭 순서 방향으로 80dp 밀려 들어오고 나간다([Motion.TAB]).
 * 줄은 붙이지 않아 제자리에서 교차 페이드만 한다. 탭 밖(하위 화면, 스냅샷)에서는 아무 일도 안 한다.
 */
@Composable
fun Modifier.tabContentSlide(): Modifier {
    val slide = LocalTabSlide.current ?: return this
    val distance = with(LocalDensity.current) { TAB_SLIDE.roundToPx() }
    val (enter, exit) = remember(slide, distance) {
        val spec = tween<IntOffset>(Motion.TAB, easing = EaseOut)
        slideInHorizontally(spec) { slide.direction() * distance } to slideOutHorizontally(spec) { -slide.direction() * distance }
    }
    return with(slide.scope) { animateEnterExit(enter, exit) }
}
