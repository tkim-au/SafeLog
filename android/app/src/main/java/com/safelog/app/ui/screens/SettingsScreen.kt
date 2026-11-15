package com.safelog.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.safelog.app.ui.components.Motion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safelog.app.BuildConfig
import com.safelog.app.model.Account
import com.safelog.app.model.AppSettings
import com.safelog.app.model.CarTour
import com.safelog.app.model.Driver
import com.safelog.app.model.DriveMode
import com.safelog.app.model.SignalLevel
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.ButtonTone
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.rememberSoundPreview
import com.safelog.app.ui.theme.SafeLogTheme

/**
 * S90 설정. 흐름 화면이라 가로 창에서도 가운데 한 칸이다.
 * 되돌리기 어려운 것(기록 끄기, 기록 지우기)은 팝업으로 한 번 더 묻는다.
 * 보험 공유와 데이터 기여는 기록 동의가 있어야 켤 수 있다(기록이 없으면 보낼 것도 없다).
 * 아틀라스 숨기기는 홈이 글만으로 보이는 모양을 정한 뒤 넣는다.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    driver: Driver,
    recordingConsent: Boolean,
    onChange: (AppSettings) -> Unit,
    onOpenDrivers: () -> Unit,
    onRequestConsent: () -> Unit,
    onWithdrawConsent: () -> Unit,
    onClearRecords: () -> Unit,
    onBack: () -> Unit,
    onTrainingChange: (Boolean) -> Unit = {},
    carTour: CarTour? = null,
    onOpenCarTour: () -> Unit = {},
    account: Account? = null,
    onOpenSignUp: () -> Unit = {},
) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) } // "consent" | "clear"
    val sound = rememberSoundPreview()
    Box(Modifier.fillMaxSize()) {
        FlowScreen("설정", onBack) {
            ListCard(title = "운전자") {
                ListRow(driver.name, Modifier.pressable(onClick = onOpenDrivers), leading = { DriverAvatar(driver, 64.dp) }) {
                    Chevron()
                }
                // 계정은 운전자마다 따로다. 가입 전이면 가입 화면으로 가고, 가입했으면 번호만 보인다.
                if (account == null) {
                    ListRow("계정", Modifier.pressable(onClick = onOpenSignUp)) {
                        ValueText("가입하기", SafeLogTheme.colors.accent)
                        Chevron()
                    }
                } else {
                    ListRow("계정") { ValueText(account.maskedPhone) }
                }
                SwitchRow("연수 모드", driver.mode == DriveMode.Training && recordingConsent, onTrainingChange, sub = "추천 미션을 골라 둬요", enabled = recordingConsent)
                carTour?.let {
                    ListRow("내 차 익히기", Modifier.pressable(onClick = onOpenCarTour)) {
                        ValueText(if (it.finished) "완료" else "${it.doneCount}/${it.required.size}")
                        Chevron()
                    }
                }
            }
            ListCard(title = "주행 중 신호") {
                Box(Modifier.padding(horizontal = CARD_PADDING, vertical = 12.dp)) {
                    Segmented(SignalLevel.entries.map { it.label }, settings.signal.ordinal) {
                        onChange(settings.copy(signal = SignalLevel.entries[it]))
                    }
                }
                Crossfade(settings.signal, animationSpec = tween(Motion.FADE), label = "signal") { signal ->
                    ListRow(signal.detail) {
                        if (signal != SignalLevel.Off) SafeLogButton("들어보기", sound::chime, size = ButtonSize.Small)
                    }
                }
            }
            ListCard(title = "동의") {
                SwitchRow("운전 기록", recordingConsent, { if (it) onRequestConsent() else confirm = "consent" })
                SwitchRow(
                    "보험 공유",
                    settings.insuranceShare && recordingConsent,
                    { onChange(settings.copy(insuranceShare = it)) },
                    enabled = recordingConsent,
                )
                SwitchRow(
                    "데이터 기여",
                    settings.dataContribution && recordingConsent,
                    { onChange(settings.copy(dataContribution = it)) },
                    enabled = recordingConsent,
                )
            }
            ListCard(title = "기록") {
                ListRow("기록 지우기", Modifier.pressable { confirm = "clear" }, sub = "포인트는 남아요") { Chevron() }
            }
            ListCard(title = "앱 정보") {
                ListRow("버전") { ValueText(BuildConfig.VERSION_NAME) }
            }
        }
        when (confirm) {
            "consent" -> SafeLogPopup(
                title = "운전 기록을 끌까요",
                body = "끄는 동안은 미션과 포인트가 쌓이지 않아요",
                confirmText = "끄기",
                dismissText = "취소",
                confirmTone = ButtonTone.Neutral,
                onConfirm = {
                    onWithdrawConsent()
                    confirm = null
                },
                onDismiss = { confirm = null },
            )
            "clear" -> SafeLogPopup(
                title = "기록을 지울까요",
                body = "운전 일지와 항목별 기록이 지워져요. 포인트는 남아요",
                confirmText = "지우기",
                dismissText = "취소",
                confirmTone = ButtonTone.Destructive,
                onConfirm = {
                    onClearRecords()
                    confirm = null
                },
                onDismiss = { confirm = null },
            )
        }
    }
}
