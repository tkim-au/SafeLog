package com.safelog.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * 고르는 순간 톡 튀며 점이 퍼지는 체크 표시(팝 버스트).
 * 원은 0.8에서 살짝 넘쳤다가 1로 돌아오고, 바깥으로 고리 하나와 점 8개가 0.45초 동안 퍼지며 사라진다.
 * 고를 때만 재생하고 해제할 때와 화면에 처음 나올 때는 재생하지 않는다.
 * 값은 graphicsLayer와 Canvas 안에서만 읽어 재구성 없이 그린다. 터치 칸은 공식 ivi_checkbox와 같은 64dp다.
 */
@Composable
fun PopCheck(selected: Boolean, fill: Color, tick: Color, empty: Color, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) } // 0이면 시작, 1이면 끝
    var initial by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (initial) {
            initial = false
            return@LaunchedEffect
        }
        if (selected) {
            launch {
                scale.snapTo(0.8f)
                scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
            }
            launch {
                burst.snapTo(0f)
                burst.animateTo(1f, tween(BURST_MS, easing = EaseOutStrongCurve))
            }
        } else {
            burst.snapTo(1f)
            scale.animateTo(1f, SafeLogSpring)
        }
    }
    Box(modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { drawPopBurst(burst.value, fill) }
        Box(
            Modifier
                .size(40.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(CircleShape)
                .background(if (selected) fill else empty),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, "선택됨", tint = tick)
        }
    }
}

/** 팝 버스트 한 장면. [progress] 0~1. 1이면 아무것도 그리지 않는다. 화면 이미지 시험에서도 쓴다. */
fun DrawScope.drawPopBurst(progress: Float, color: Color) {
    if (progress >= 1f) return
    val fade = 1f - progress
    val center = Offset(size.width / 2, size.height / 2)
    val base = 20.dp.toPx() // 체크 원 반지름
    // 고리: 원에서 바깥으로 커지며 옅어진다
    drawCircle(
        color = color.copy(alpha = 0.5f * fade),
        radius = base + 10.dp.toPx() * progress,
        center = center,
        style = Stroke(width = 3.dp.toPx() * fade),
    )
    // 점 8개: 바깥으로 날아가며 작아진다
    val distance = base + 4.dp.toPx() + 10.dp.toPx() * progress
    val dot = 3.dp.toPx() * fade
    repeat(DOTS) { i ->
        val angle = Math.toRadians(i * 360.0 / DOTS - 90.0)
        drawCircle(
            color = color.copy(alpha = fade),
            radius = dot,
            center = Offset(center.x + distance * cos(angle).toFloat(), center.y + distance * sin(angle).toFloat()),
        )
    }
}

private val EaseOutStrongCurve = androidx.compose.animation.core.CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private const val BURST_MS = 450
private const val DOTS = 8
