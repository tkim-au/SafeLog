package com.safelog.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safelog.app.data.FakeSafeLogRepository
import com.safelog.app.live.LiveSafeLogRepository
import com.safelog.app.ui.SafeLogApp
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.screens.HabitLabScreen
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

class MainActivity : ComponentActivity() {
    private val app get() = application as SafeLogApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCarPermissions()
        app.live.startVehicle()
        DriveService.start(this)
        setContent {
            // debug only: the test console or the 시연 panel may switch to the sample data (DemoScreen)
            val sample by DemoScreen.sample.collectAsStateWithLifecycle()
            val useFake = BuildConfig.DEBUG && sample
            // debug only: 습관 시험실 (developer habit test page). Release builds have no 시연 panel, so it cannot open.
            var labOpen by rememberSaveable { mutableStateOf(false) }
            // 앱을 연 뒤 "누가 운전하나요?"를 이미 보였는지(운전자가 둘 이상일 때만 뜬다). 화면을 돌려도, 시험실을 다녀와도 한 번뿐이다.
            var driverAsked by rememberSaveable { mutableStateOf(false) }
            val repository = if (useFake) app.fake else app.live
            // debug only: the buttons behind the top-right "시연" button. Release builds pass null, so there is no button.
            val demoBar: @Composable (close: () -> Unit) -> Unit = { close ->
                if (useFake) FakeDemoBar(app.fake, close) { DemoScreen.sample.value = false }
                else LiveDemoBar(app.live, close, onOpenLab = { app.live.labRefresh(); labOpen = true }) { DemoScreen.sample.value = true }
            }
            SafeLogTheme {
                if (BuildConfig.DEBUG && labOpen) {
                    val view by app.live.labView.collectAsStateWithLifecycle()
                    val phase by app.live.phase.collectAsStateWithLifecycle()
                    val src by app.live.sourceInfo.collectAsStateWithLifecycle()
                    HabitLabScreen(view, phase.name, src, app.live::labJudge, app.live::labReset) { labOpen = false }
                } else SafeLogApp(
                    repository = repository,
                    demoBar = if (BuildConfig.DEBUG) demoBar else null,
                    askDriver = !driverAsked,
                    onDriverAsked = { driverAsked = true },
                )
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) app.live.startVehicle(restart = true)   // re-register with the new rights
    }

    /** Car data rights. Unknown names (e.g. Pleos permissions on a plain AAOS emulator) are simply not granted. */
    private fun requestCarPermissions() {
        val wanted = CAR_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    companion object {
        val CAR_PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.CAR_SEATS",
            "pleos.car.permission.CAR_WINDOWS",
            "pleos.car.permission.CAR_SAFETY",
            "pleos.car.permission.CAR_INFO",
            "pleos.car.permission.NAVI_ROUTE",
            "android.permission.POST_NOTIFICATIONS",
        )
    }
}

/**
 * Debug-only demo buttons for the emulator: replays a recorded trip through the real pipeline (vehicle frames ->
 * live praise -> engine -> ledger), at 10x or 30x speed. The Pleos simulator's own signals work too.
 * Shown in the panel under the top-right "시연" button. Buttons that change the screen also [close] the panel
 * so the result is in view; the speed, model and sync buttons keep it open to read the status line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveDemoBar(repo: LiveSafeLogRepository, close: () -> Unit, onOpenLab: () -> Unit, onUseFake: () -> Unit) {
    val running by repo.demoRunning.collectAsStateWithLifecycle()
    val engine by repo.engineInfo.collectAsStateWithLifecycle()
    val sync by repo.syncInfo.collectAsStateWithLifecycle()
    var speed by rememberSaveable { mutableIntStateOf(10) }
    var message by remember { mutableStateOf<String?>(null) }
    val trips = remember { repo.demoTrips() }
    val andClose: (() -> Unit) -> () -> Unit = { action -> { action(); close() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running == null) {
                trips.forEach { t -> SafeLogButton(t.title.substringBefore(" ·"), andClose { repo.startDemo(t.name, speed.toDouble()) }, size = ButtonSize.Small) }
            } else {
                SafeLogButton("도착 (P)", andClose(repo::stopDemo), primary = true, size = ButtonSize.Small)
            }
            SafeLogButton("×$speed", { speed = when (speed) { 1 -> 10; 10 -> 30; else -> 1 } }, size = ButtonSize.Small)
            // S05 내 차 익히기 / 연수 모드: the same paths the car's signals and the engine take
            SafeLogButton("조작", andClose(repo::demoCarStep), size = ButtonSize.Small)
            SafeLogButton("새 차", andClose(repo::demoNewCar), size = ButtonSize.Small)
            SafeLogButton("모드", andClose(repo::demoModeChange), size = ButtonSize.Small)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SafeLogButton("습관 시험실", andClose(onOpenLab), primary = true, size = ButtonSize.Small)
            SafeLogButton("기록 채우기", andClose(repo::seedHistory), size = ButtonSize.Small)
            SafeLogButton("+500P", andClose { repo.demoCredit(500) }, size = ButtonSize.Small)
            SafeLogButton("모델 불러오기", { message = repo.installGateFromDownloads() }, size = ButtonSize.Small)
            SafeLogButton("동기화", { Thread { repo.syncNow() }.start() }, size = ButtonSize.Small)
            SafeLogButton("샘플 화면", andClose(onUseFake), size = ButtonSize.Small)
        }
        Text(
            listOfNotNull(message, engine, sync).joinToString(" · "),
            style = SafeLogType.label,
            color = SafeLogTheme.colors.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The sample-data buttons (design review without the engine): the front end's own demo moments. Each one closes the panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FakeDemoBar(repository: FakeSafeLogRepository, close: () -> Unit, onUseLive: () -> Unit) {
    val andClose: (() -> Unit) -> () -> Unit = { action -> { action(); close() } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SafeLogButton("P", andClose(repository::demoPark), size = ButtonSize.Small)
        SafeLogButton("D", andClose(repository::demoDrive), size = ButtonSize.Small)
        SafeLogButton("신호 대기", andClose(repository::demoIdle), size = ButtonSize.Small)
        SafeLogButton("칭찬", andClose(repository::demoPraise), size = ButtonSize.Small)
        SafeLogButton("보험", andClose(repository::demoInsuranceGoal), size = ButtonSize.Small)
        SafeLogButton("모드", andClose(repository::demoModeChange), size = ButtonSize.Small)
        SafeLogButton("새 차", andClose(repository::demoNewCar), size = ButtonSize.Small)
        SafeLogButton("조작", andClose(repository::demoCarStep), size = ButtonSize.Small)
        SafeLogButton("실제 엔진", andClose(onUseLive), size = ButtonSize.Small)
    }
}
