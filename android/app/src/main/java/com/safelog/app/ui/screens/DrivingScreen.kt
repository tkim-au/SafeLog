package com.safelog.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safelog.app.model.DrivePhase
import com.safelog.app.model.Practice
import com.safelog.app.ui.components.AtlasDriving
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

/**
 * 주행 중 아틀라스 표시 스위치. 시연 전용이다.
 * Lifestyle 앱의 주행 중 화면 허용 여부가 확인되지 않았다. 허용되지 않으면 false로 바꾼다.
 */
object DriveAtlasPolicy {
    const val ENABLED = true
}

/** 미션 카드 바탕: 파랑 12%를 화면 바탕에 섞은 불투명한 면(반투명 blur는 CPU를 써서 쓰지 않는다). */
fun missionTint(accent: Color, ground: Color) = accent.copy(alpha = 0.12f).compositeOver(ground)

/**
 * 지금 하는 미션. 옅은 파랑 면에 파란 글씨로, 보기만 하는 정보임을 드러낸다.
 * 진한 면은 홈의 코치 패널처럼 누르는 곳에만 쓰고, 운전 중에는 시선을 덜 끌게 한다.
 */
@Composable
private fun MissionCard(practice: Practice) {
    val colors = SafeLogTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(missionTint(colors.accent, colors.background))
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("지금 미션", style = SafeLogType.label, color = colors.accent)
        Text(practice.title, style = SafeLogType.title, color = colors.accent, textAlign = TextAlign.Center)
    }
}

/**
 * 주행 상태. 운전석에 앉은 아틀라스가 있다가, 잘한 순간과 신호 대기에 운전 동작을 한 번 한다.
 * 평소에는 정지 이미지라 그리기 비용이 없고, 움직임은 반복하지 않는다(주행 중 반복 움직임 금지).
 * 지금 하는 미션은 옅은 파란 카드로 보여 준다. 글자는 미션 이름 하나라 한눈에 읽힌다.
 * 숫자와 목록은 보이지 않는다. 신호 대기 중에만 짧은 글 한 줄이 나온다.
 */
@Composable
fun DrivingScreen(
    phase: DrivePhase,
    praiseCount: Int,
    idleLine: String?,
    recording: Boolean,
    modifier: Modifier = Modifier,
    missions: List<Practice> = emptyList(),
    atlasEnabled: Boolean = DriveAtlasPolicy.ENABLED,
) {
    val colors = SafeLogTheme.colors
    // 움직일 때마다 1씩 오른다. 처음 화면에 들어올 때는 움직이지 않는다.
    var wave by remember { mutableIntStateOf(0) }
    var seenPraise by remember { mutableIntStateOf(praiseCount) }
    LaunchedEffect(praiseCount) {
        if (praiseCount != seenPraise) {
            seenPraise = praiseCount
            wave++
        }
    }
    LaunchedEffect(idleLine) { if (idleLine != null) wave++ }

    // 아틀라스 크기는 창에 맞춰 크게 잡고, 글이 나타나고 사라져도 바뀌지 않는다(주행 중 화면이 출렁이지 않게).
    // 넓은 가로 창은 글 왼쪽(운전석 쪽)·아틀라스 오른쪽, 그 밖에는 위아래로 놓는다.
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background).padding(32.dp), contentAlignment = Alignment.Center) {
        val showAtlas = atlasEnabled && recording
        val w = maxWidth
        val wide = w > maxHeight * 1.3f
        val atlas = if (wide) minOf(maxHeight * 0.8f, maxWidth * 0.45f) else minOf(w * 0.85f, maxHeight * 0.55f)
        val texts: @Composable (Dp) -> Unit = { width ->
            Column(
                Modifier.width(width),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val line = if (atlasEnabled && phase == DrivePhase.Idling && idleLine != null) idleLine else null
                line?.let { Text(it, style = SafeLogType.bubble, color = colors.ink, textAlign = TextAlign.Center) }
                if (recording && missions.isNotEmpty()) {
                    missions.forEach { MissionCard(it) }
                } else if (line == null) {
                    Text(if (recording) "기록 중" else "기록 꺼짐", style = SafeLogType.label, color = colors.inkMuted)
                }
            }
        }
        if (wide && showAtlas) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                texts(minOf(640.dp, w - atlas - 48.dp))
                Spacer(Modifier.width(48.dp))
                AtlasDriving(wave, Modifier.size(atlas))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (showAtlas) {
                    AtlasDriving(wave, Modifier.size(atlas))
                    Spacer(Modifier.height(40.dp))
                }
                texts(minOf(640.dp, w))
            }
        }
    }
}
