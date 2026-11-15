package com.safelog.app.live

import android.content.Context
import android.util.Log
import com.safelog.app.BuildConfig
import com.safelog.app.data.SafeLogRepository
import com.safelog.app.model.Account
import com.safelog.app.model.AppSettings
import com.safelog.app.model.Avatar
import com.safelog.app.model.ArrivalSummary
import com.safelog.app.model.AtlasProfile
import com.safelog.app.model.Briefing
import com.safelog.app.model.DrivePhase
import com.safelog.app.model.DriveRecords
import com.safelog.app.model.ProfileAnswer
import com.safelog.app.model.Driver
import com.safelog.app.model.Quiz
import com.safelog.app.model.ShopItem
import com.safelog.app.vehicle.DemoTrip
import com.safelog.app.vehicle.PleosVehicleSource
import com.safelog.app.vehicle.ReplaySource
import com.safelog.app.vehicle.TestSignalSource
import com.safelog.app.vehicle.VehicleSource
import com.safelog.core.CoreSnapshot
import com.safelog.core.FrameEvents
import com.safelog.core.RecordedTrip
import com.safelog.core.SafeLogCore
import com.safelog.core.contract.Frame
import com.safelog.core.contract.Trip
import com.safelog.core.drive.PlaceMark
import com.safelog.core.json.Json
import com.safelog.core.json.list
import com.safelog.core.lab.HabitLab
import com.safelog.core.lab.LabView
import com.safelog.core.ledger.ExchangeResult
import com.safelog.core.sync.ServerApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The real SafeLogRepository: car signals -> SafeLogCore (phase, live mission detectors, praise gate, recording)
 * -> engine (rules + optional learned gate) -> local ledger -> screens, with the server mirrored in the background.
 *
 * Threads: every core call holds [lock]. Frames arrive on the vehicle thread (10 Hz). The heavy engine pass after
 * parking runs on [engineExec] without the lock, then the light settle step takes the lock again. Sync runs on
 * [ioExec]. StateFlows are thread-safe, Compose collects them on the main thread.
 */
class LiveSafeLogRepository(
    private val context: Context,
    serverUrl: String? = null,
) : SafeLogRepository {
    private val lock = Any()
    val core = SafeLogCore(FileStore(File(context.filesDir, "safelog")))
    private val player = PraisePlayer(context)
    private val engineExec = Executors.newSingleThreadExecutor { r -> Thread(r, "safelog-engine").apply { priority = Thread.MIN_PRIORITY } }
    private val ioExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safelog-sync") }

    private var first = core.snapshot()
    override val phase = MutableStateFlow(Mapping.phase(first.phase))
    override val briefing = MutableStateFlow(Mapping.briefing(first.briefing))
    override val selectedPracticeIds = MutableStateFlow(first.selectedMissionIds)
    override val arrival = MutableStateFlow(Mapping.arrival(first.arrival))
    override val praiseCount = MutableStateFlow(first.praiseCount)
    override val idleLine = MutableStateFlow(first.idleLine)
    override val firstRunDone = MutableStateFlow(first.firstRunDone)
    override val recordingConsent = MutableStateFlow(first.recordingConsent)
    override val records = MutableStateFlow(Mapping.records(first.records))
    override val shopItems = MutableStateFlow(first.shop.map(Mapping::shop))
    override val atlas = MutableStateFlow(Mapping.atlas(first.atlas))
    override val settings = MutableStateFlow(Mapping.settings(first.settings))
    override val drivers = MutableStateFlow(first.drivers.map(Mapping::driver))
    override val driverId = MutableStateFlow(first.driverId)
    override val quiz = MutableStateFlow(Mapping.quiz(first.quiz))
    override val carTour = MutableStateFlow(Mapping.carTour(first.carTour))
    override val modeNotice = MutableStateFlow(Mapping.modeNotice(first.modeNotice))
    override val profileDone = MutableStateFlow(first.profileDone)

    // ---- extra state for the debug 시연 panel (not part of the screen contract)
    private val _engineInfo = MutableStateFlow(first.engineInfo)
    val engineInfo: StateFlow<String> = _engineInfo
    private val _sourceInfo = MutableStateFlow("")
    val sourceInfo: StateFlow<String> = _sourceInfo
    private val _demoRunning = MutableStateFlow<String?>(null)
    val demoRunning: StateFlow<String?> = _demoRunning
    private val _syncInfo = MutableStateFlow("서버 미설정")
    val syncInfo: StateFlow<String> = _syncInfo
    var gateSource = "모델 없음 (규칙만)"; private set

    // ---- 습관 시험실 (developer test page): debug builds only, never part of the driver's screens
    val lab: HabitLab? = if (com.safelog.app.BuildConfig.DEBUG) HabitLab() else null
    private val _labView = MutableStateFlow<LabView?>(null)
    val labView: StateFlow<LabView?> = _labView
    private var labFrames = 0

    private var source: VehicleSource? = null
    private var analyzing = 0
    private var lastTourVersion = 0
    private var framesSinceCaps = 0

    init {
        if (core.device.serverUrl == null && !serverUrl.isNullOrBlank()) core.setServerUrl(serverUrl)
        if (lab != null) core.keepLastRecording = true
        loadGate()
        ioExec.scheduleWithFixedDelay({ syncNow() }, 5, 60, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------------------------------------ publish
    private fun publish(s: CoreSnapshot = core.snapshot()) {
        phase.value = Mapping.phase(s.phase)
        briefing.value = Mapping.briefing(s.briefing)
        selectedPracticeIds.value = s.selectedMissionIds
        arrival.value = Mapping.arrival(s.arrival)
        praiseCount.value = s.praiseCount
        idleLine.value = s.idleLine
        firstRunDone.value = s.firstRunDone
        recordingConsent.value = s.recordingConsent
        records.value = Mapping.records(s.records)
        shopItems.value = s.shop.map(Mapping::shop)
        atlas.value = Mapping.atlas(s.atlas).also { a -> player.pitch = a.voices.firstOrNull { it.id == a.voice }?.pitch ?: 1f }
        settings.value = Mapping.settings(s.settings)
        drivers.value = s.drivers.map(Mapping::driver)
        driverId.value = s.driverId
        quiz.value = Mapping.quiz(s.quiz)
        carTour.value = Mapping.carTour(s.carTour)
        modeNotice.value = Mapping.modeNotice(s.modeNotice)
        profileDone.value = s.profileDone
        lastTourVersion = core.tourVersion
        _engineInfo.value = s.engineInfo + " · " + gateSource + " · 목소리: " + player.voiceEngine
    }

    private inline fun <T> act(block: () -> T): T = synchronized(lock) { block().also { publish() } }

    // ------------------------------------------------------------------------------------------------ vehicle
    /**
     * Start listening to the car (Pleos SDK + AAOS). Safe to call again (activity re-created, service started):
     * it does nothing while the car source runs or a demo replay is playing, unless [restart] (new permissions).
     */
    @Synchronized fun startVehicle(restart: Boolean = false) {
        if (_demoRunning.value != null) return
        if (unwrapped() is PleosVehicleSource && !restart) return
        switchSource(PleosVehicleSource(context))
    }

    /** The running source without the debug test-signal wrapper. */
    private fun unwrapped(): VehicleSource? = (source as? TestSignalSource)?.inner ?: source

    @Synchronized fun stopVehicle() { source?.stop(); source = null }

    /** What the car source can read right now (channel -> where it comes from), for the 시연 panel / logs. */
    fun vehicleStatus(): Map<String, String> = source?.status() ?: emptyMap()

    @Synchronized private fun switchSource(raw: VehicleSource) {
        source?.stop()
        // debug builds: signals the Pleos emulator cannot produce, set from adb (vehicle/TestSignals.kt). Never in release.
        val s = if (BuildConfig.DEBUG) TestSignalSource(raw) else raw
        source = s
        s.start(
            onFrame = ::onFrame,
            onPlace = { name -> synchronized(lock) { core.onPlace(name) } },
            onCabin = { sig, v -> synchronized(lock) { core.onCabin(sig, v); if (core.tourVersion != lastTourVersion) publish() } },
            onVehicleId = { hash -> synchronized(lock) { core.onVehicleId(hash); publish() } },
        )
        _sourceInfo.value = s.name
    }

    private fun onFrame(f: Frame) {
        val ev: FrameEvents
        val before: com.safelog.core.drive.DrivePhase
        synchronized(lock) {
            // what the car can report decides which 내 차 익히기 steps exist (checked every 5 s)
            if (++framesSinceCaps >= 50) {
                framesSinceCaps = 0
                val caps = source?.capabilities()
                if (caps != core.vehicleCaps) { core.vehicleCaps = caps; publish() }
            }
            before = core.phase
            ev = core.onFrame(f)
            lab?.let { l ->
                val veh = core.engine.cfg.vehicle
                l.onFrame(f, core.phase, veh.steerSign, veh.steerUnitLearned ?: "road_wheel")
                if (++labFrames >= 5 || core.phase != before) { labFrames = 0; _labView.value = l.view() }    // 2 Hz is enough to read
            }
            if (core.phase != before || core.tourVersion != lastTourVersion) publish()   // trip start / stop / light / tour step
            else {
                praiseCount.value = core.praiseCount
                idleLine.value = core.idleLine
            }
        }
        if (ev.chime) player.chime()
        ev.speak?.let(player::say)
        ev.recorded?.let(::finishTrip)
    }

    private fun finishTrip(rec: RecordedTrip) {
        synchronized(lock) { analyzing++ }
        engineExec.execute {
            try {
                val result = core.analyze(rec)                 // heavy: rules + gate over the whole trip, no lock
                lab?.let { l -> val j = HabitLab.fromResult(rec.trip, result); synchronized(lock) { l.lastJudge = j; _labView.value = l.view() } }
                act { core.settle(rec, result) }
            } catch (t: Throwable) {
                Log.e("SafeLog", "trip analysis failed", t)
            } finally {
                synchronized(lock) { analyzing-- }
                ioExec.execute { syncNow() }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ screens
    override fun selectPractices(ids: List<String>) = act { core.selectMissions(ids) }
    override fun dismissArrival() = act { core.dismissArrival() }
    override fun exchange(item: ShopItem): Boolean = act { core.exchange(item.id, UUID.randomUUID().toString()) == ExchangeResult.OK }
    override fun selectVoice(id: String) = act { core.selectVoice(id) }
    override fun updateSettings(settings: AppSettings) = act { core.updateSettings(Mapping.settings(settings)) }
    override fun withdrawConsent() = act { core.withdrawConsent() }
    override fun selectDriver(id: String) = act { core.selectDriver(id) }
    override fun clearRecords() = act { core.clearRecords() }
    override fun answerQuiz(choice: Int) { act { core.answerQuiz(choice) } }
    override fun finishFirstRun(consent: Boolean, atlasName: String?) = act { core.finishFirstRun(consent, atlasName) }
    override fun dismissCarTourOffer() = act { core.dismissCarTourOffer() }
    override fun skipCarStep(id: String) = act { core.skipCarStep(id) }
    override fun openCarTour() = act { core.openCarTour() }
    override fun retryCarStep(id: String) = act { core.retryCarStep(id) }
    override fun disputeScene(sceneId: String) = act { core.disputeScene(sceneId) }
    override fun dismissModeNotice() = act { core.dismissModeNotice() }
    override fun setTrainingMode(on: Boolean) = act { core.setTrainingMode(on) }
    override fun setAvatar(driverId: String, avatar: Avatar) = act { core.setAvatar(driverId, Mapping.avatar(avatar)) }
    override fun setDriverProfile(answer: ProfileAnswer) = act { core.setDriverProfile(answer.name, answer.experience.name, answer.carFamiliar) }
    override fun addDriver(answer: ProfileAnswer) { act { core.addDriver(answer.name.orEmpty(), answer.experience.name, answer.carFamiliar) } }

    // ponytail: 서버에 계정 API가 아직 없다. 가입은 이 메모리에만 남고 앱을 다시 켜면 사라진다.
    // 서버가 생기면 requestSignUpCode가 문자를 보내게 하고, signUp은 서버 확인 결과로 바꾼 뒤 core 저장소에 남긴다.
    override val accounts = MutableStateFlow(emptyMap<String, Account>())
    override fun requestSignUpCode(phone: String) = Unit
    override fun signUp(name: String, phone: String, code: String): Boolean {
        if (!SafeLogRepository.demoCodeOk(code)) return false
        accounts.value = accounts.value + (driverId.value to Account(name, phone))
        return true
    }

    // ------------------------------------------------------------------------------------------------ gate
    fun loadGate() {
        val l = GateLoader.load(context)
        synchronized(lock) { core.gate = l.gate; gateSource = l.source; publish() }
    }

    /** Debug: install a bundle pushed to /sdcard/Download/gate_v3.json (adb push) without rebuilding. */
    fun installGateFromDownloads(): String = try {
        val f = File("/sdcard/Download/${GateLoader.FILE}")
        GateLoader.install(context, f)
        loadGate()
        "모델을 불러왔어요"
    } catch (t: Throwable) {
        "불러오지 못했어요: ${t.message}"
    }

    // ------------------------------------------------------------------------------------------------ server
    fun syncNow() {
        val url = synchronized(lock) { core.device.serverUrl } ?: run { _syncInfo.value = "서버 미설정 (기기에만 저장)"; return }
        val api = ServerApi(url, timeoutMs = 3000)
        val up = runCatching { api.health() }.isSuccess          // probe without the lock (may wait on the network)
        if (!up) { _syncInfo.value = "서버 연결 안 됨 · 기기에 저장 중"; return }
        val r = synchronized(lock) { core.sync(api).also { publish() } }
        _syncInfo.value = if (r.error != null) "동기화 보류: ${r.error}" else "동기화 ${r.sent}건 · 서버 잔액 ${r.serverBalance ?: "-"}P"
    }

    fun setServer(url: String?) { act { core.setServerUrl(url) }; ioExec.execute { syncNow() } }
    val serverUrl: String? get() = synchronized(lock) { core.device.serverUrl }

    // ------------------------------------------------------------------------------------------------ demo (debug 시연 panel)
    fun demoTrips(): List<DemoTrip> = ReplaySource.list(context)

    /** Replays a recorded trip through the very same path as the car (emulator demo). The car source resumes after. */
    @Synchronized fun startDemo(name: String, speed: Double = 10.0) {
        _demoRunning.value = name
        try {
            switchSource(ReplaySource(context, name, speed) {
                _demoRunning.value = null
                android.os.Handler(android.os.Looper.getMainLooper()).post { startVehicle() }
            })
        } catch (e: Exception) {
            // a missing or broken demo file must not take the app down: say so in the 시연 panel and go back to the car
            android.util.Log.e("SafeLog", "demo $name could not start", e)
            _demoRunning.value = null
            _syncInfo.value = "시연 파일을 열 수 없어요: $name"
            source?.stop(); source = null
            startVehicle()
        }
    }

    /** Stops a replay mid-way: the car is put in P, so the trip ends and is judged like a real one. */
    @Synchronized fun stopDemo() {
        source?.stop(); source = null
        _demoRunning.value = null
        val park = Frame(t = 0.0, speedKph = 0.0, gear = 0, brakeOn = 1)
        repeat(3) { onFrame(park) }
        startVehicle()
    }

    /** Fills the journal with the three demo trips on the last few days (so records, trends and grade have content). */
    fun seedHistory() {
        engineExec.execute {
          try {
            val day = 86_400_000L
            val now = System.currentTimeMillis()
            val plan = listOf("commute_city" to 3, "lane_practice" to 2, "rainy_evening" to 1)
            for ((name, daysAgo) in plan) {
                val trip = ReplaySource.readTrip(context, name)
                val meta = Json.obj(context.assets.open("demo/$name.json").bufferedReader().readText())
                val places = meta.list("places").map { p -> (p as List<*>).let { PlaceMark((it[0] as Number).toDouble(), it[1] as String) } }
                val start = now - daysAgo * day - 2 * 3_600_000L
                val missions = if (name == "lane_practice") listOf("lane-signal" to true) else emptyList()
                synchronized(lock) { core.judge(trip, start, missions, places) }
            }
            act { core.dismissArrival() }
          } catch (e: Exception) {
            // runs on the engine thread: an exception here would end the whole app
            android.util.Log.e("SafeLog", "seedHistory failed", e)
            _syncInfo.value = "기록 채우기 실패: ${e.javaClass.simpleName}"
          }
        }
    }

    fun demoCredit(points: Int) = act { core.demoCredit(points) }

    /** Demo: the car notices the control 내 차 익히기 asks for now (through the real detection). */
    fun demoCarStep() = act { core.demoTourStep() }

    /** Demo: another car (new VIN) -> the tour starts over and is offered on the home screen. */
    fun demoNewCar() = act { core.demoNewCar() }

    /** Demo: the engine's own 연수 모드 switch with its notice. */
    fun demoModeChange() = act { core.demoModeFlip() }

    fun isAnalyzing() = synchronized(lock) { analyzing > 0 }

    // ------------------------------------------------------------------------------------------------ habit lab (debug)
    /** Judge the trip so far (or the last finished one) with the full engine, off the vehicle thread. */
    fun labJudge() {
        val l = lab ?: return
        engineExec.execute {
            try {
                val (trip, cfg, gate) = synchronized(lock) {
                    Triple(core.labRecording(), core.engine.cfg.copyWith(core.engine.cfg.vehicle), core.gate)
                }
                if (trip == null) return@execute
                val j = HabitLab.judge(trip, cfg, gate)
                synchronized(lock) { l.lastJudge = j; _labView.value = l.view() }
            } catch (t: Throwable) {
                Log.e("SafeLog", "lab judge failed", t)
            }
        }
    }

    /** Clear the lab's live counts, events, signal history and verdicts. */
    fun labReset() {
        val l = lab ?: return
        synchronized(lock) { val veh = core.engine.cfg.vehicle; l.reset(all = true, veh.steerSign, veh.steerUnitLearned ?: "road_wheel"); _labView.value = l.view() }
    }

    fun labRefresh() { val l = lab ?: return; synchronized(lock) { _labView.value = l.view() } }

    fun release() {
        stopVehicle()
        player.release()
        engineExec.shutdown(); ioExec.shutdown()
    }
}
