package com.safelog.app.ui.screens

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safelog.app.model.InsuranceProgress
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.ProgressBar
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

/*
 * 보험 할인. 고객이 가장 좋아하는 혜택이라 홈·도착·기록·교환소에 같은 카드로 나온다.
 * 원칙: 점수는 보여 주지 않는다(오르내리면 기분이 상한다). 금액은 말하지 않는다(보험사가 갱신 때 정한다).
 * 대신 늘어나기만 하는 안전 거리를 "평소처럼 몇 번 더 달리면 되는지"로 바꿔 말한다.
 */

/**
 * 보험 할인 진행 카드. [gainedKm]가 있으면(도착 후) [reveal]이 true가 되는 순간
 * 오늘 달린 만큼 막대가 차오르고 남은 횟수가 줄어든다.
 */
@Composable
fun InsuranceCard(insurance: InsuranceProgress, gainedKm: Int = 0, reveal: Boolean = true) {
    val colors = SafeLogTheme.colors
    val km by animateIntAsState(if (reveal) insurance.safeKm else insurance.safeKm - gainedKm, tween(600), label = "insuranceKm")
    val shown = insurance.copy(safeKm = km)
    ListCard(title = "보험 할인", trailing = { Chip("갱신까지 ${insurance.monthsToRenewal}개월", colors.inkMuted) }) {
        Column(
            Modifier.padding(start = CARD_PADDING, end = CARD_PADDING, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
                Text(if (shown.remainingKm > 0) "할인 조건까지" else "할인 조건", style = SafeLogType.label, color = colors.inkMuted)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.remainingKm > 0) {
                        Text("${shown.tripsLeft}번", style = SafeLogType.numberMedium, color = colors.accent, maxLines = 1)
                        Text("더 달리면 돼요", style = SafeLogType.body, color = colors.ink, modifier = Modifier.padding(bottom = 10.dp))
                    } else {
                        Text("채웠어요", style = SafeLogType.numberMedium, color = colors.accent, maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressBar(shown.progress, colors.accent, Modifier.weight(1f))
                if (gainedKm > 0 && reveal) Chip("오늘 +${gainedKm}km", colors.accent)
            }
            Text(
                (if (shown.remainingKm > 0) "${shown.remainingKm}km 남음 · " else "") + "보험사 기준 최대 ${insurance.maxDiscountPercent}%",
                style = SafeLogType.label,
                color = colors.inkMuted,
            )
        }
    }
}

/**
 * 보험 공유에 동의하지 않은 사람에게 한 번만 보이는 안내. 닫으면 다시 나오지 않는다.
 */
@Composable
fun InsuranceInviteCard(onEnable: () -> Unit, onDismiss: () -> Unit) {
    val colors = SafeLogTheme.colors
    ListCard(title = "보험 할인도 받아 볼까요") {
        Column(
            Modifier.padding(start = CARD_PADDING, end = CARD_PADDING, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("안전하게 달린 거리를 갱신 때 보험사에 보내요", style = SafeLogType.body, color = colors.inkSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SafeLogButton("닫기", onDismiss, Modifier.weight(1f), size = ButtonSize.Small)
                SafeLogButton("켜기", onEnable, Modifier.weight(1f), primary = true, size = ButtonSize.Small)
            }
        }
    }
}
