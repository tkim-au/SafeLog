package com.safelog.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.safelog.app.model.DriveMode
import com.safelog.app.model.Driver
import com.safelog.app.ui.Destination
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.LocalDemoButton
import com.safelog.app.ui.components.Motion
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

/** 위쪽 탭이 고르는 화면. null은 "오늘"(홈)이다. */
val TABS: List<Destination?> = listOf(null) + Destination.menu

/**
 * 탭 화면(오늘·기록·교환소·캐릭터)의 맨 위 줄. A안 화면에 B안의 이동 방식(위쪽 줄 탭)을 합쳤다.
 * 예전 홈 오른쪽 아래 아이콘 메뉴 자리를 비워 홈 카드가 숨 쉴 칸을 얻는다.
 *
 * - 가로 창: 한 줄(공식 ivi_title Main 높이 80). 앱 표시 · 탭 · 오른쪽에 기록 켜기, 연수 모드, 운전자 얼굴, 설정.
 * - 세로 창: 두 줄. 앱 표시와 오른쪽 아이콘, 그 아래 탭.
 *
 * 하위 화면(설정·운전자·내 차 익히기·퀴즈·상품 정보)은 이 줄 대신 뒤로 가기 제목 줄([DepthHeader])을 쓴다.
 * 각 화면이 자기 맨 위에 이 줄을 그린다. 그래야 화면의 팝업 가림막이 이 줄까지 덮는다.
 */
@Composable
fun AppTopBar(
    tab: Destination?,
    onSelectTab: (Destination?) -> Unit,
    mode: DriveMode,
    recordingConsent: Boolean,
    driver: Driver?,
    onRequestConsent: () -> Unit,
    onOpen: (Destination) -> Unit,
    onOpenMode: () -> Unit,
) {
    val demoButton = LocalDemoButton.current
    val actions: @Composable RowScope.() -> Unit = {
        // 디버그 빌드의 시연 단추. 설정 톱니는 릴리스와 같은 자리(맨 오른쪽)에 둔다.
        demoButton?.invoke()
        // 동의 전에는 기록이 꺼져 있다는 것과 켜는 곳을 어느 탭에서든 같은 자리에 둔다.
        if (!recordingConsent) SafeLogButton("기록 켜기", onRequestConsent, primary = true, size = ButtonSize.Small)
        // 연수 모드는 기본과 다를 때만 보이는 운전자 상태라 얼굴 옆에 붙인다. 평소 모드는 아무것도 띄우지 않는다.
        AnimatedVisibility(
            // 기록을 끈 동안은 미션도 없어 연수 모드가 의미 없다.
            mode == DriveMode.Training && recordingConsent,
            enter = fadeIn(tween(Motion.FADE)) + scaleIn(tween(Motion.FADE), initialScale = 0.9f),
            exit = fadeOut(tween(Motion.FADE)) + scaleOut(tween(Motion.FADE), targetScale = 0.9f),
        ) {
            Box(Modifier.height(64.dp).pressable(onClick = onOpenMode), contentAlignment = Alignment.Center) {
                Chip(DriveMode.Training.label, SafeLogTheme.colors.accent)
            }
        }
        driver?.let {
            // 넷플릭스처럼 오른쪽 위 얼굴을 누르면 운전자를 바꾼다.
            Box(Modifier.size(64.dp).pressable { onOpen(Destination.Drivers) }, contentAlignment = Alignment.Center) {
                DriverAvatar(it, 48.dp)
            }
        }
        IconAction(Icons.Filled.Settings, "설정", SafeLogTheme.colors.inkSecondary) { onOpen(Destination.Settings) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= WIDE_BREAKPOINT) {
            Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppMark()
                Spacer(Modifier.width(24.dp))
                Tabs(tab, onSelectTab)
                Spacer(Modifier.weight(1f))
                actions()
            }
        } else {
            Column {
                Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppMark()
                    Spacer(Modifier.weight(1f))
                    actions()
                }
                Tabs(tab, onSelectTab)
            }
        }
    }
}

/**
 * 앱 이름(공식 Page Navigation의 왼쪽). 이름만 둔다.
 * 예전에는 앞에 캐릭터 얼굴을 붙였는데, 오른쪽 위 운전자 얼굴(기본이 캐릭터)과 겹쳐 뺐다.
 */
@Composable
private fun AppMark() {
    Text("세이프로그", style = SafeLogType.bodyLarge, color = SafeLogTheme.colors.ink, maxLines = 1)
}

/** 공식 Line 탭 모양. 고른 탭은 파란 글자와 글자 폭만큼의 4dp 밑줄(색만으로 말하지 않게). */
@Composable
private fun Tabs(tab: Destination?, onSelect: (Destination?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TABS.forEach { item ->
            TabItem(item?.label ?: "오늘", tab == item) { onSelect(item) }
        }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SafeLogTheme.colors
    Box(
        Modifier.height(80.dp).clip(RoundedCornerShape(16.dp)).pressSelect(selected, role = Role.Tab, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(label, style = SafeLogType.bodyStrong, color = if (selected) colors.accent else colors.inkMuted, maxLines = 1)
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (selected) colors.accent else Color.Transparent),
            )
        }
    }
}
