package com.safelog.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.safelog.app.model.AtlasProfile
import com.safelog.app.model.AtlasStage
import com.safelog.app.model.Unlockable
import com.safelog.app.model.atlasStage
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.PopCheck
import com.safelog.app.ui.components.ProgressBar
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.components.rememberSoundPreview
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

/**
 * S55 캐릭터 탭. 경험치로 자라고, 레벨마다 꾸미기가 열린다(기획서 F8).
 * 패널: 지금 모습·레벨·경험치(위)와 성장 단계·다음에 열리는 것(바닥). 목록: 꾸미기와 목소리.
 */
@Composable
fun AtlasScreen(profile: AtlasProfile, onSelectVoice: (String) -> Unit, header: @Composable () -> Unit) {
    val colors = SafeLogTheme.colors
    val stage = atlasStage(profile.level)
    val next = (profile.outfits + profile.voices).filter { it.unlockLevel > profile.level }.minByOrNull { it.unlockLevel }
    val sound = rememberSoundPreview()
    SubScreen(
        header = header,
        panel = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    painterResource(stage.image),
                    contentDescription = profile.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(200.dp), // 가장 낮은 창(WF 800)에서도 패널 안에 다 들어가는 높이
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Text(profile.name, style = SafeLogType.title, color = colors.ink, modifier = Modifier.weight(1f))
                    Text("Lv.${profile.level}", style = SafeLogType.numberMedium, color = colors.accent, maxLines = 1)
                }
                Text("다음 레벨까지 ${profile.xpToNext - profile.xp}XP", style = SafeLogType.label, color = colors.inkMuted)
                ProgressBar(profile.xp / profile.xpToNext.toFloat(), colors.accent)
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CardTitle("성장")
                Row(Modifier.fillMaxWidth()) {
                    AtlasStage.entries.forEach { s -> StageCell(s, reached = profile.level >= s.fromLevel, current = s == stage, Modifier.weight(1f)) }
                }
                next?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("다음에 열려요", style = SafeLogType.label, color = colors.inkMuted, modifier = Modifier.weight(1f))
                        Text(it.name, style = SafeLogType.bodyStrong, color = colors.ink)
                        Chip("Lv.${it.unlockLevel}", colors.accent)
                    }
                }
            }
        },
        content = {
            // 입는 그림이 생기기 전까지는 고르지 못하게 한다. 골라도 모습이 그대로면 고장으로 보인다.
            ListCard(title = "꾸미기", trailing = { Chip("입은 모습은 곧 나와요", colors.inkMuted) }) {
                profile.outfits.forEach { item ->
                    val open = profile.level >= item.unlockLevel
                    ListRow(item.name, enabled = open) {
                        if (!open) ValueText("Lv.${item.unlockLevel}", colors.inkMuted)
                    }
                }
            }
            ListCard(title = "목소리") {
                profile.voices.forEach { item ->
                    VoiceRow(item, profile.level, selected = item.id == profile.voice, onSelect = { onSelectVoice(item.id) }) {
                        sound.say("안녕하세요, ${profile.name}예요", item.pitch)
                    }
                }
            }
        },
    )
}

@Composable
private fun StageCell(stage: AtlasStage, reached: Boolean, current: Boolean, modifier: Modifier) {
    val colors = SafeLogTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.height(80.dp), contentAlignment = Alignment.BottomCenter) {
            Image(
                painterResource(stage.image),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(if (current) 80.dp else 60.dp).alpha(if (reached) 1f else 0.32f),
            )
        }
        Text(stage.label, style = SafeLogType.bodyStrong, color = if (current) colors.accent else colors.inkMuted)
        Text("Lv.${stage.fromLevel}", style = SafeLogType.label, color = colors.inkMuted)
    }
}

/** 열린 목소리는 행을 눌러 고르고, 오른쪽에서 미리 듣는다. 잠긴 것은 열리는 레벨만. */
@Composable
private fun VoiceRow(item: Unlockable, level: Int, selected: Boolean, onSelect: () -> Unit, onPreview: () -> Unit) {
    val colors = SafeLogTheme.colors
    val open = level >= item.unlockLevel
    ListRow(item.name, Modifier.pressSelect(selected, enabled = open, role = Role.RadioButton, onClick = onSelect), enabled = open) {
        if (open) {
            SafeLogButton("들어보기", onPreview, size = ButtonSize.Small)
            PopCheck(selected, colors.accent, colors.onAccent, colors.surfaceHigh)
        } else {
            ValueText("Lv.${item.unlockLevel}", colors.inkMuted)
        }
    }
}

