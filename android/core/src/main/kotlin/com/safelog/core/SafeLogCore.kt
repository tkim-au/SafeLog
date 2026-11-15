package com.safelog.core

import com.safelog.core.contract.Frame
import com.safelog.core.contract.Trip
import com.safelog.core.contract.toCsv
import com.safelog.core.drive.DrivePhase
import com.safelog.core.drive.MissionDetector
import com.safelog.core.drive.PhaseMachine
import com.safelog.core.drive.PlaceMark
import com.safelog.core.drive.SignalGate
import com.safelog.core.drive.TripAssembler
import com.safelog.core.engine.EngineConfig
import com.safelog.core.engine.Outcome
import com.safelog.core.engine.SafeLogEngine
import com.safelog.core.engine.TripResult
import com.safelog.core.gate.PraiseGate
import com.safelog.core.ledger.ArrivalView
import com.safelog.core.ledger.AtlasView
import com.safelog.core.ledger.BriefingView
import com.safelog.core.ledger.CarTour
import com.safelog.core.ledger.CarTourView
import com.safelog.core.ledger.Codec
import com.safelog.core.ledger.DeviceState
import com.safelog.core.ledger.DriverState
import com.safelog.core.ledger.DriverView
import com.safelog.core.ledger.ExchangeResult
import com.safelog.core.ledger.Experience
import com.safelog.core.ledger.KeyValueStore
import com.safelog.core.ledger.Ledger
import com.safelog.core.ledger.MissionCatalog
import com.safelog.core.ledger.QuizView
import com.safelog.core.ledger.RecordsView
import com.safelog.core.ledger.SettingsState
import com.safelog.core.ledger.ShopCatalog
import com.safelog.core.ledger.ShopEntry
import com.safelog.core.ledger.TourState
import com.safelog.core.ledger.TripFacts
import com.safelog.core.ledger.Rules
import com.safelog.core.json.Json
import com.safelog.core.sync.ServerApi

/** Everything the screens read, in one consistent piece (published after every change). */
data class CoreSnapshot(
    val phase: DrivePhase,
    val briefing: BriefingView,
    val selectedMissionIds: List<String>,
    val arrival: ArrivalView?,
    val praiseCount: Int,
    val idleLine: String?,
    val firstRunDone: Boolean,
    val recordingConsent: Boolean,
    val records: RecordsView,
    val shop: List<ShopEntry>,
    val atlas: AtlasView,
    val settings: SettingsState,
    val drivers: List<DriverView>,
    val driverId: String,
    val quiz: QuizView?,
    val engineInfo: String,
    /** "TrainingOn" / "TrainingOff" only when the ENGINE switched the mode (shown once on the parked home) */
    val modeNotice: String?,
    val carTour: CarTourView,
    /** the current driver answered the profile step (driving experience, this car) */
    val profileDone: Boolean,
)

/** A finished recording waiting for the parked-phase engine run. */
class RecordedTrip(val trip: Trip, val startMs: Long, val missions: List<Pair<String, Boolean>>, val places: List<PlaceMark>, val driverId: String)

/**
 * What happened on one frame, for the app's sound / speech side. [recorded]: the car was just parked; run
 * [SafeLogCore.analyze] (heavy, any thread) and then [SafeLogCore.settle] (light, with the core lock).
 */
data class FrameEvents(val chime: Boolean = false, val speak: String? = null, val recorded: RecordedTrip? = null)

/**
 * The car-side backend of SafeLog: engine + ledger + drive runtime behind one object. Not thread-safe: the app
 * calls it from a single worker thread (LiveSafeLogRepository) and publishes [snapshot] to the screens.
 */
class SafeLogCore(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    val shop: List<ShopEntry> = ShopCatalog.DEFAULT,
    defaultDrivers: List<Pair<String, String>> = listOf("driver-1" to "운전자"),
) {
    val device: DeviceState = store.read("device.json")?.let { Codec.deviceFrom(it) } ?: DeviceState()
    private val drivers = LinkedHashMap<String, DriverState>()
    private val cfg: EngineConfig = EngineConfig.bundled().copyWith(device.vehicle)
    val engine = SafeLogEngine(cfg)
    var gate: PraiseGate?
        get() = engine.gate
        set(v) { engine.gate = v }

    // drive state
    private val phaseMachine = PhaseMachine()
    var phase = DrivePhase.Parked; private set
    private val frames = ArrayList<Frame>()
    private val places = ArrayList<PlaceMark>()
    private var tripStartMs = 0L
    private var tripMissions: List<Pair<String, Boolean>> = emptyList()
    private var detectors: List<MissionDetector> = emptyList()
    private var signalGate = SignalGate()
    private var lastFrame: Frame? = null
    var praiseCount = 0; private set
    var idleLine: String? = null; private set
    private var idleLinesThisTrip = 0
    private var idleFrames = 0
    /** Mission successes of this trip already praised by a signal-wait line (each success is praised once). */
    private var praisedSuccesses = 0
    var arrival: ArrivalView? = null; private set

    /**
     * What this car can report (signal names used by S05 내 차 익히기), from the vehicle source. null = unknown
     * (replay / emulator without the SDK): every step is shown.
     */
    var vehicleCaps: Set<String>? = null
    private var vinHash: String? = null
    /** Bumped whenever the tour changes on its own (car signals), so the app knows when to republish. */
    var tourVersion = 0; private set

    init {
        if (device.driverIds.isEmpty()) {
            for ((id, name) in defaultDrivers) { device.driverIds.add(id); drivers[id] = DriverState(id, name) }
            device.currentDriverId = defaultDrivers.first().first
            saveAll()
        }
        for (id in device.driverIds) drivers[id] = store.read("driver/$id.json")?.let { Codec.driverFrom(it) } ?: DriverState(id, id)
        if (device.currentDriverId !in drivers) device.currentDriverId = device.driverIds.first()
        device.selectedMissionIds = device.selectedMissionIds.takeLast(Rules.MAX_MISSIONS)      // saved before the one-mission rule
        preselectIfTraining()
    }

    val driver: DriverState get() = drivers.getValue(device.currentDriverId)
    fun driverState(id: String) = drivers[id]

    private fun save(d: DriverState = driver) = store.write("driver/${d.id}.json", Codec.driver(d))
    private fun saveDevice() { device.vehicle = cfg.vehicle; store.write("device.json", Codec.device(device)) }
    private fun saveAll() { drivers.values.forEach { save(it) }; saveDevice() }

    // ------------------------------------------------------------------------------------------- snapshot
    fun snapshot(): CoreSnapshot {
        val now = clock()
        val d = driver
        val raining = lastFrame?.wiperActive == 1
        val night = lastFrame?.night == 1
        return CoreSnapshot(
            phase = phase,
            briefing = Ledger.briefing(d, shop, now, raining, night),
            selectedMissionIds = device.selectedMissionIds,
            arrival = arrival,
            praiseCount = praiseCount,
            idleLine = idleLine,
            firstRunDone = device.firstRunDone,
            recordingConsent = device.recordingConsent,
            records = Ledger.records(d, now, device.settings.insuranceShare),
            shop = shop,
            atlas = Ledger.atlas(d),
            settings = device.settings,
            drivers = device.driverIds.map { id -> drivers.getValue(id).let { DriverView(it.id, it.name, it.mode, it.level, it.avatar) } },
            driverId = d.id,
            quiz = Ledger.quiz(d, now),
            engineInfo = "data contract v${cfg.dataVersion} · " + (engine.gate?.description ?: "rules only (gate empty)"),
            modeNotice = d.modeNotice,
            carTour = CarTour.view(d.tour, vehicleCaps),
            profileDone = d.experience != null,
        )
    }

    // ------------------------------------------------------------------------------------------- screen actions
    /** The latest pick wins when more than [Rules.MAX_MISSIONS] are asked for (same as the screen's toggle). */
    fun selectMissions(ids: List<String>) {
        device.selectedMissionIds = ids.filter { MissionCatalog.byId(it) != null }.takeLast(Rules.MAX_MISSIONS)
        saveDevice()
    }
    fun dismissArrival() { arrival = null; device.selectedMissionIds = emptyList(); preselectIfTraining(); saveDevice() }
    fun finishFirstRun(consent: Boolean, atlasName: String?) {
        device.firstRunDone = true
        if (consent) device.recordingConsent = true
        atlasName?.takeIf { it.isNotBlank() }?.let { driver.atlasName = it.trim(); save() }
        preselectIfTraining()
        saveDevice()
    }

    /**
     * 연수 모드 (front-end spec 10-7-1): before every departure the recommended mission is put in the selection
     * (the driver can change it). Normal mode leaves the selection empty. Never overrides a selection.
     */
    private fun preselectIfTraining() {
        val d = driver
        if (d.mode != "Training" || !device.recordingConsent || device.selectedMissionIds.isNotEmpty() || phase != DrivePhase.Parked) return
        val b = Ledger.briefing(d, shop, clock(), lastFrame?.wiperActive == 1, lastFrame?.night == 1)
        device.selectedMissionIds = b.missions.filter { it.recommended }.map { it.id }.take(1)
    }

    /** The driver turns 연수 모드 on / off (parked, plan F6). No notice: that is only for the engine's own switch. */
    fun setTrainingMode(on: Boolean) {
        if (phase != DrivePhase.Parked) return
        val d = driver
        d.mode = if (on) "Training" else "Normal"
        d.trainingStreak = 0
        if (on) preselectIfTraining()
        save(); saveDevice()
        enqueue("mode", mapOf("mode" to d.mode))
    }

    fun dismissModeNotice() { driver.modeNotice = null; save() }

    /** Face of a driver. A photo path stays on this device (it is not uploaded). */
    fun setAvatar(driverId: String, avatar: String) { drivers[driverId]?.let { it.avatar = avatar; save(it) } }
    fun exchange(itemId: String, requestId: String): ExchangeResult {
        val item = shop.firstOrNull { it.id == itemId } ?: return ExchangeResult.SOLD_OUT
        return Ledger.exchange(driver, item, requestId, clock()).also {
            save()
            if (it == ExchangeResult.OK) enqueue("exchange", mapOf("requestId" to requestId, "itemId" to itemId))
        }
    }
    fun selectVoice(id: String) { driver.voice = id; save() }
    fun updateSettings(s: SettingsState) {
        if (s.insuranceShare && driver.insurance == null) driver.insurance = Ledger.newInsurance(clock())
        device.settings = s
        save(); saveDevice()
    }
    fun withdrawConsent() {
        device.recordingConsent = false
        device.selectedMissionIds = emptyList()
        device.settings = device.settings.copy(insuranceShare = false, dataContribution = false)
        saveDevice()
        enqueue("consent", mapOf("recording" to false, "insurance" to false, "data" to false))
    }
    fun selectDriver(id: String) {
        if (id !in drivers || phase != DrivePhase.Parked) return
        device.currentDriverId = id
        device.selectedMissionIds = emptyList()
        arrival = null
        vinHash?.let { applyVin(driver, it) }
        preselectIfTraining()
        save(); saveDevice()
    }
    /**
     * Profile step of the current driver (parked only). The driving experience decides the starting mode and whether
     * this car is new to the driver decides the 내 차 익히기 offer (submitted plan, sections 3-1 and 4). A blank name
     * keeps the current one.
     */
    fun setDriverProfile(name: String?, experience: String, carFamiliar: Boolean) {
        if (phase != DrivePhase.Parked || experience !in Experience.ALL) return
        val d = driver
        applyProfile(d, name, experience, carFamiliar)
        device.selectedMissionIds = emptyList()             // the pre-selected mission belonged to the old mode
        preselectIfTraining()
        save(d); saveDevice()
        enqueue("mode", mapOf("mode" to d.mode))
    }

    private fun applyProfile(d: DriverState, name: String?, experience: String, carFamiliar: Boolean) {
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { d.name = it }
        d.experience = experience
        d.carFamiliar = carFamiliar
        d.mode = Experience.startMode(experience)
        d.trainingStreak = 0
        d.tour.offer = !carFamiliar && !CarTour.view(d.tour, vehicleCaps).finished
    }

    /**
     * Another driver of this car (parked only): own ledger, missions, Atlas and tour. They become the current driver.
     * Returns the new id, or null when it was refused (driving, [Rules.MAX_DRIVERS] reached, unknown experience).
     */
    fun addDriver(name: String, experience: String, carFamiliar: Boolean): String? {
        if (phase != DrivePhase.Parked || experience !in Experience.ALL || device.driverIds.size >= Rules.MAX_DRIVERS) return null
        val n = device.driverIds.size + 1
        val id = "driver-$n-${clock() % 100000}"
        val d = DriverState(id, name.trim().ifEmpty { "운전자 $n" })
        vinHash?.let { d.tour.vin = it }
        applyProfile(d, null, experience, carFamiliar)
        drivers[id] = d
        device.driverIds.add(id)
        save(d)
        selectDriver(id)
        return id
    }

    // ------------------------------------------------------------------------------------------- S05 내 차 익히기
    /** The car's identity (hash of getVINInfo()). Another car than last time: the tour starts over and is offered. */
    fun onVehicleId(hash: String) {
        vinHash = hash
        if (applyVin(driver, hash)) { save(); tourVersion++ }
    }

    private fun applyVin(d: DriverState, hash: String): Boolean = when (d.tour.vin) {
        hash -> false
        null -> { d.tour.vin = hash; true }
        else -> { d.tour = TourState(vin = hash, offer = true); true }
    }

    fun dismissCarTourOffer() { driver.tour.offer = false; save() }

    /** The driver opened 내 차 익히기: the practice time is measured from the first opening. */
    fun openCarTour() { val t = driver.tour; if (t.startedMs == null) { t.startedMs = clock(); save() } }

    /** Skipped steps do not count as done; if the car notices the control later, the step still turns done. */
    fun skipCarStep(id: String) {
        if (CarTour.stepOf(id) == null) return
        driver.tour.skipped.add(id); driver.tour.everSkipped.add(id)
        save()
    }

    /** "다시 해 보기" (parked): a finished step is open again and has to be done on the car once more. No second reward. */
    fun retryCarStep(id: String) {
        if (phase != DrivePhase.Parked) return
        if (CarTour.retry(driver.tour, id)) { tourVersion++; save() }
    }

    /** A cabin reading outside data contract v3 (hazard, high beam, parking brake, HVAC, seat position ...). Parked only. */
    fun onCabin(signal: String, value: Any?) { if (phase == DrivePhase.Parked) tourObserve(listOf(signal to value)) }

    private fun tourObserve(readings: List<Pair<String, Any?>>) {
        val d = driver
        var changed = false
        for ((sig, v) in readings) if (CarTour.observe(d.tour, sig, v).isNotEmpty()) changed = true
        if (!changed) return
        tourVersion++
        // all required steps done -> +50 XP and the "출발 준비 완료" badge, once per driver
        if (!d.tourRewarded && CarTour.view(d.tour, vehicleCaps).finished) {
            d.tourRewarded = true
            d.xpTotal += Rules.TOUR_XP
            d.tour.finishedMs = clock()
            // how long the practice took and how many steps were skipped: only for drivers who turned 데이터 기여 on
            enqueue("tour", if (device.settings.dataContribution) CarTour.metrics(d.tour, vehicleCaps) else emptyMap())
        }
        save()
    }

    /** Demo bar: the car "notices" the step the screen asks for now (fed through the same detection). */
    fun demoTourStep() {
        if (phase != DrivePhase.Parked) return
        val next = CarTour.view(driver.tour, vehicleCaps).next ?: return
        tourObserve(CarTour.demoSignals(next.id))
    }

    /** Demo bar: another car (new VIN). */
    fun demoNewCar() { onVehicleId("demo-${clock()}") }

    /**
     * Demo bar: what the engine does after a trip. In 평소 모드 it suggests 연수 모드 (the driver decides on the home
     * screen); in 연수 모드 it graduates the driver back to 평소 모드.
     */
    fun demoModeFlip() {
        val d = driver
        if (d.mode != "Training") d.modeNotice = "TrainingOffer"
        else {
            d.mode = "Normal"; d.modeNotice = "TrainingOff"
            enqueue("mode", mapOf("mode" to d.mode))
        }
        save(); saveDevice()
    }

    /** "기록 지우기": the journal goes, and so do the raw recordings of this driver's trips. Points stay. */
    fun clearRecords() {
        val d = driver
        for (t in d.trips) rawKeys(t.id).forEach(store::delete)
        Ledger.clearRecords(d, clock()); save()
    }

    // ------------------------------------------------------------------------------------------- 오판정 의견
    /**
     * The driver says a journal scene was judged wrong (submitted plan, section 3-3). The mark stays with the scene
     * and is sent to the server once; points are not changed by it.
     */
    fun disputeScene(sceneId: String) {
        val d = driver
        val trip = d.trips.firstOrNull { t -> t.scenes.any { it.id == sceneId } } ?: return
        val scene = trip.scenes.first { it.id == sceneId }
        if (scene.disputed) return
        trip.scenes = trip.scenes.map { if (it.id == sceneId) it.copy(disputed = true) else it }
        save(d)
        enqueue("dispute", mapOf("tripId" to trip.id, "sceneId" to sceneId, "what" to scene.what, "good" to scene.good))
    }

    // ------------------------------------------------------------------------------------------- raw recordings
    private fun rawKeys(tripId: String) = listOf("raw/$tripId.csv")

    /** Raw signals stay on the car for [Rules.RAW_KEEP_DAYS] days and never leave it. Trip ids carry the start time. */
    private fun pruneRaw(nowMs: Long) {
        val oldest = nowMs - Rules.RAW_KEEP_DAYS * 86_400_000L
        for (key in store.list("raw/")) {
            val start = key.removePrefix("raw/trip-").substringBefore('.').toLongOrNull() ?: continue
            if (start < oldest) store.delete(key)
        }
    }

    /** Demo only (debug builds): points to show the exchange flow without weeks of driving. Marked "demo" in the ledger. */
    fun demoCredit(points: Int) { driver.entries.add(com.safelog.core.ledger.LedgerEntry("demo:${clock()}", clock(), "demo", points)); save() }

    fun setServerUrl(url: String?) { device.serverUrl = url?.takeIf { it.isNotBlank() }; saveDevice() }
    fun answerQuiz(choice: Int): Int {
        val got = Ledger.answerQuiz(driver, choice, clock())
        save()
        driver.quiz?.let { enqueue("quiz", mapOf("quizId" to it.quizId, "choice" to choice, "ms" to clock())) }
        return got
    }

    // ------------------------------------------------------------------------------------------- driving
    /** A place name from navigation; shown on journal scenes. */
    fun onPlace(name: String) {
        if (phase != DrivePhase.Parked && name.isNotBlank() && places.lastOrNull()?.name != name) places.add(PlaceMark(frames.size / 10.0, name))
    }

    /** Feed one 10 Hz frame (time base is set here: frame.t is ignored and replaced by the recording time). */
    fun onFrame(fIn: Frame): FrameEvents {
        val before = phase
        val now = phaseMachine.step(fIn)
        phase = now
        lastFrame = fIn
        if (before == DrivePhase.Parked && now != DrivePhase.Parked) startTrip()
        if (now == DrivePhase.Parked) {
            idleLine = null
            if (before != DrivePhase.Parked) return FrameEvents(recorded = endTrip())
            tourObserve(CarTour.signalsOf(fIn))
            return FrameEvents()
        }
        if (!device.recordingConsent) return FrameEvents()
        val f = fIn.copy(t = frames.size / 10.0)
        frames.add(f)
        var success = false
        for (det in detectors) if (det.step(f)) success = true
        // 평소 모드 records without any sound or screen change (submitted plan, section 3): no chime, no spoken line
        val training = driver.mode == "Training"
        val sound = training && device.settings.signal != "Off"
        val chime = signalGate.step(f, success, sound)
        if (chime) praiseCount++
        // one short praise while waiting at a light ("가끔" only, never in rain / at night, at most twice a trip)
        var speak: String? = null
        if (now == DrivePhase.Idling) {
            idleFrames++
            val calm = training && device.settings.signal == "Sometimes" && f.wiperActive != 1 && f.night != 1
            if (calm && idleLine == null && idleLinesThisTrip < 2 && idleFrames == 1) {
                idleLine = idleLineText()
                if (idleLine != null) { idleLinesThisTrip++; speak = idleLine }
            }
        } else {
            idleFrames = 0
            idleLine = null
        }
        return FrameEvents(chime = chime, speak = speak)
    }

    /**
     * The signal-wait line is praise only (submitted plan, section 3): said when the chosen mission succeeded since
     * the last line, with no count. No success yet, no mission, or nothing new: nothing is said.
     */
    private fun idleLineText(): String? {
        val det = detectors.firstOrNull() ?: return null
        val m = tripMissions.firstOrNull()?.let { MissionCatalog.byId(it.first) } ?: return null
        if (det.successes <= praisedSuccesses) return null
        praisedSuccesses = det.successes
        return m.praise
    }

    private fun startTrip() {
        frames.clear(); places.clear()
        tripStartMs = clock()
        arrival = null
        idleLinesThisTrip = 0; idleFrames = 0; praisedSuccesses = 0
        signalGate = SignalGate()                       // trip time restarts at 0 for every trip
        val b = Ledger.briefing(driver, shop, tripStartMs, lastFrame?.wiperActive == 1, lastFrame?.night == 1)
        tripMissions = device.selectedMissionIds.map { id -> id to (b.missions.firstOrNull { it.id == id }?.recommended ?: false) }
        detectors = tripMissions.mapNotNull { MissionCatalog.byId(it.first) }.map {
            MissionDetector(it.habit, steerSign = cfg.vehicle.steerSign, steerUnit = cfg.vehicle.steerUnitLearned ?: "road_wheel")
        }
    }

    private fun endTrip(): RecordedTrip? {
        if (!device.recordingConsent || frames.isEmpty()) { frames.clear(); return null }
        val rec = RecordedTrip(Trip.fromFrames(frames), tripStartMs, tripMissions, places.toList(), driver.id)
        if (keepLastRecording) lastRecording = rec.trip
        frames.clear()
        return rec
    }

    // ------------------------------------------------------------------------------------------- developer lab
    /** Debug builds only (습관 시험실): keep the last finished recording so it can be judged again after parking. */
    var keepLastRecording = false
    var lastRecording: Trip? = null; private set

    /** Debug builds only: the frames recorded so far in this trip (a copy), else the last finished recording. */
    fun labRecording(): Trip? = if (frames.isNotEmpty()) Trip.fromFrames(frames) else lastRecording

    /** Parked phase, heavy part: the full engine (rules + gate) on the recorded trip. No state is changed except
     *  the per-car calibration memory. */
    fun analyze(rec: RecordedTrip): TripResult = engine.runTrip(rec.trip, rec.missions.mapNotNull { MissionCatalog.byId(it.first)?.habit })

    /** Parked phase, light part: settle the trip in the driver's ledger, update the profile, make the arrival. */
    fun settle(rec: RecordedTrip, result: TripResult): TripFacts? {
        if (result.skipped != null) return null
        val d = drivers[rec.driverId] ?: driver
        val hold = cfg.notReleased
        val facts = TripAssembler.facts("trip-${rec.startMs}", rec.trip, result, rec.startMs, rec.missions, rec.places, d.mode == "Training", hold)
            .copy(weakestHabit = d.profile.weakestHabit(Ledger.missionHabits))
        val st = Ledger.settle(d, facts, device.settings.insuranceShare, shop, facts.endMs)
        d.profile.update(result.judgments.filter { it.habit !in hold && it.outcome != Outcome.ABSTAIN })
        if (d.id == driver.id) arrival = st.arrival
        // the summary goes to the server; the indicators for the data-contribution programme only when the driver opted in
        enqueue("finish", linkedMapOf("insuranceShare" to device.settings.insuranceShare, "facts" to Codec.facts(facts),
            "metrics" to if (device.settings.dataContribution) facts.metrics else emptyList()), d)
        // the raw signals stay on the car (consent screen: 90 days) and are never uploaded
        store.write(rawKeys(facts.id).first(), rec.trip.toCsv())
        pruneRaw(clock())
        save(d); saveDevice()
        lastFacts[facts.id] = facts
        return facts
    }

    /** Both steps at once (tests, demo seeding). */
    fun judge(trip: Trip, startMs: Long, missions: List<Pair<String, Boolean>>, placeMarks: List<PlaceMark> = emptyList()): TripFacts? {
        val rec = RecordedTrip(trip, startMs, missions, placeMarks, driver.id)
        return settle(rec, analyze(rec))
    }

    val lastFacts = LinkedHashMap<String, TripFacts>()

    // ------------------------------------------------------------------------------------------- server sync
    /**
     * Local-first: the car's ledger answers the screens at once (works offline, e.g. on the emulator for judges);
     * when a server is configured every change is queued and sent in order. The server applies the same rules
     * (golden cases) and is idempotent, so a retried upload never double-counts.
     */
    @Suppress("UNCHECKED_CAST")
    private fun outbox(): MutableList<Map<String, Any?>> =
        (store.read("outbox.json")?.let { Json.parse(it) as List<Any?> } ?: emptyList()).map { it as Map<String, Any?> }.toMutableList()

    private fun enqueue(kind: String, payload: Map<String, Any?>, d: DriverState = driver) {
        if (device.serverUrl == null) return
        // the consent screen is where the driver agrees to the summary going to the server: without that consent only
        // the consent flag itself is sent (so a withdrawal reaches the server)
        if (!device.recordingConsent && kind != "consent") return
        val ob = outbox()
        ob.add(linkedMapOf("kind" to kind, "driverId" to d.id, "driverName" to d.name, "mode" to d.mode, "payload" to payload))
        store.write("outbox.json", Json.write(ob))
    }

    data class SyncReport(val sent: Int, val left: Int, val dropped: Int, val serverBalance: Int?, val error: String?)

    @Suppress("UNCHECKED_CAST")
    fun sync(apiIn: ServerApi? = null): SyncReport {
        val api = apiIn ?: device.serverUrl?.let { ServerApi(it) } ?: return SyncReport(0, 0, 0, null, "no server")
        val ob = outbox()
        var sent = 0; var dropped = 0; var balance: Int? = null; var err: String? = null
        val known = HashSet<String>()
        while (ob.isNotEmpty()) {
            val op = ob.first()
            val did = op["driverId"] as String
            val p = op["payload"] as Map<String, Any?>
            try {
                if (known.add(did)) api.ensureDriver(did, op["driverName"] as String, op["mode"] as String)
                val r = when (op["kind"]) {
                    "finish" -> api.finish(did, p["insuranceShare"] as Boolean, p["facts"] as Map<String, Any?>,
                        (p["metrics"] as? List<Any?>) ?: emptyList())
                    "dispute" -> api.dispute(did, p["tripId"] as String, p["sceneId"] as String, p["what"] as String, p["good"] as Boolean)
                    "exchange" -> api.exchange(p["requestId"] as String, did, p["itemId"] as String)
                    "quiz" -> api.answerQuiz(did, p["quizId"] as String, (p["choice"] as Number).toInt(), (p["ms"] as Number).toLong())
                    "consent" -> api.consent(did, p["recording"] as Boolean, p["insurance"] as Boolean, p["data"] as Boolean)
                    "mode" -> api.setMode(did, p["mode"] as String)
                    "tour" -> api.tourComplete(did, p)
                    else -> emptyMap()
                }
                if (did == driver.id) (r["pointsBalance"] as? Number)?.let { balance = it.toInt() }
                sent++
            } catch (e: ServerApi.HttpError) {
                if (e.code in 400..499) dropped++ else { err = e.message; break }       // 4xx: the server refuses this op for good
            } catch (e: Exception) {
                err = e.toString(); break                                              // offline: keep the rest for later
            }
            ob.removeAt(0)
            store.write("outbox.json", Json.write(ob))
        }
        return SyncReport(sent, ob.size, dropped, balance, err)
    }
}
