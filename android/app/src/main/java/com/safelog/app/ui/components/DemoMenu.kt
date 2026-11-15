package com.safelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.safelog.app.ui.theme.SafeLogTheme

/**
 * 디버그 빌드의 "시연" 단추. 위쪽 줄(탭 화면의 [com.safelog.app.ui.screens.AppTopBar], 하위 화면의
 * [com.safelog.app.ui.screens.DepthHeader]) 오른쪽에 놓인다. null이면(릴리스·스냅샷) 아무것도 그리지 않는다.
 */
val LocalDemoButton = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** 시연 단추. 펼쳐 둔 동안은 파랗게 보인다. */
@Composable
fun DemoButton(open: Boolean, onClick: () -> Unit) {
    SafeLogButton("시연", onClick, primary = open, size = ButtonSize.Small)
}

/**
 * 시연 단추 아래로 펼치는 판. 판 밖을 누르면 닫힌다. 가림막은 그리지 않아 뒤 화면이 그대로 보인다.
 * 위쪽 줄(높이 80) 바로 아래 오른쪽에 붙는다.
 */
@Composable
fun BoxScope.DemoPanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = SafeLogTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 112.dp, end = 24.dp, start = 24.dp)
            .widthIn(max = 720.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.divider, shape)
            // 판 안의 빈 곳을 눌러도 닫히지 않게 여기서 받는다.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(16.dp),
    ) { content() }
}

/** 주행 화면처럼 위쪽 줄이 없는 화면에서 시연 단추를 오른쪽 위에 띄운다. */
@Composable
fun BoxScope.FloatingDemoButton() {
    LocalDemoButton.current?.let { button ->
        Box(Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 24.dp)) { button() }
    }
}
