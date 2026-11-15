package com.safelog.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.abs

private const val TAG = "SafeLog/Pleos"

/**
 * The real car (or the Pleos Connect emulator): AAOS CarPropertyManager for speed / gear / night (mandatory AAOS
 * properties), Pleos Vehicle SDK 2.3.2 for brake, steering, turn signal, wipers, lights, cruise, seats, odometer,
 * and NaviHelper 3.0.5 for speed limit, turn-by-turn, caution points, cameras, heading and place names.
 * Every value is converted to data contract v3 (see android/core DataContract and data_spec/DATA_AUDIT.md).
 */
class PleosVehicleSource(private val context: Context) : VehicleSource {
    override val name = "Pleos 차량"
    private val asm = FrameAssembler()
    private val status = LinkedHashMap<String, String>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var cpmCallback: CarPropertyManager.CarPropertyEventCallback? = null
    private var vehicle: Any? = null
    private var navi: Any? = null
    private val unregister = ArrayList<() -> Unit>()
    private var t0 = 0L
    private var onFrame: ((com.safelog.core.contract.Frame) -> Unit)? = null
    private var onPlace: ((String) -> Unit)? = null
    private var onCabin: ((String, Any?) -> Unit)? = null
    private var onVehicleId: ((String) -> Unit)? = null
    private val caps = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile private var probedAtNs = 0L

    override fun status(): Map<String, String> = synchronized(status) { LinkedHashMap(status) }
    private fun mark(k: String, v: String) = synchronized(status) { status[k] = v }

    override fun start(
        onFrame: (com.safelog.core.contract.Frame) -> Unit, onPlace: (String) -> Unit,
        onCabin: (String, Any?) -> Unit, onVehicleId: (String) -> Unit,
    ) {
        this.onFrame = onFrame; this.onPlace = onPlace; this.onCabin = onCabin; this.onVehicleId = onVehicleId
        val th = HandlerThread("safelog-vehicle").also { it.start() }
        thread = th
        val h = Handler(th.looper)
        handler = h
        h.post { startAaos(); startVehicleSdk(); startNavi() }
        t0 = System.nanoTime()
        h.postDelayed(tick, 100)
        h.postDelayed(poll, 500)
    }

    override fun stop() {
        handler?.removeCallbacksAndMessages(null)
        unregister.forEach { runCatching { it() } }
        unregister.clear()
        runCatching { cpmCallback?.let { cpm?.unregisterCallback(it) } }
        runCatching { car?.disconnect() }
        runCatching { Reflect.call(vehicle, "release") }
        runCatching { Reflect.call(navi, "release") }
        thread?.quitSafely()
        thread = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val f = asm.frame((System.nanoTime() - t0) / 1e9)
            // what this car reports, for S05 내 차 익히기 (the data-contract channels)
            if (f.beltsBuckled >= 0) caps.add("belt")
            if (f.brakeOn >= 0) caps.add("brake")
            if (f.turnSignal >= 0 && "방향지시등" in status()) caps.add("turn_signal")
            if (f.lowBeam >= 0) caps.add("low_beam")
            if (f.wiperActive >= 0) caps.add("wiper")
            if (!f.steerDeg.isNaN()) caps.add("steering")
            if (f.driveMode >= 0) caps.add("drive_mode")
            onFrame?.invoke(f)
            handler?.postDelayed(this, 100)
        }
    }

    /**
     * null (show every tour step) until the SDK had 5 s to answer, or when the Vehicle SDK is not in the APK
     * (plain AAOS emulator): then nothing is known about this car and the demo shows the whole tour.
     */
    override fun capabilities(): Set<String>? {
        if (vehicle == null || probedAtNs == 0L || System.nanoTime() - probedAtNs < 5_000_000_000L) return null
        return synchronized(caps) { LinkedHashSet(caps) }
    }

    private fun cabin(signal: String, value: Any?) {
        value ?: return
        caps.add(signal)
        trace(signal, value)
        onCabin?.invoke(signal, value)
    }

    private val traced = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Debug log whenever a channel's value changes: what this car really reports (`adb logcat -s SafeLog/Pleos`). */
    private fun trace(key: String, value: Any?) {
        val s = Reflect.name(value) ?: "null"
        if (traced.put(key, s) != s) Log.d(TAG, "$key = $s")
    }

    private fun onOff(x: Any?): Boolean? = when (val n = Reflect.name(x)) {
        null -> null
        "true", "ON", "ACTIVATED", "ENABLED", "LOCKED", "APPLIED", "ENGAGED" -> true
        "false", "OFF", "DEACTIVATED", "DISABLED", "UNLOCKED", "RELEASED", "DISENGAGED" -> false
        else -> (x as? Number)?.let { it.toDouble() > 0 } ?: if (n.endsWith("_ON")) true else if (n.endsWith("_OFF")) false else null
    }

    /** First getter that exists among [names] in [domain] (SDK versions differ); reports [signal]. */
    private fun cabinPoll(domain: Any?, names: List<String>, signal: String, area: String? = null, map: (Any?) -> Any? = { onOff(it) }) {
        domain ?: return
        for (n in names) if (Reflect.methods(domain, n).isNotEmpty()) { Reflect.get(domain, n, area) { cabin(signal, map(it)) }; return }
    }

    /**
     * ~1 Hz, parked use only (the core ignores these while driving): controls 내 차 익히기 asks for.
     * Area names are the SDK's value names: SeatValue / WindowValue / DoorValue.ROW_1_LEFT is the driver's side
     * (left-hand drive), WindowValue.FRONT_WINDSHIELD the front wipers and defroster.
     */
    private fun pollCabin(v: Any) {
        // Getter names and area values follow Vehicle SDK 2.3.2 (its sources); older guesses stay as fallbacks.
        val level: (Any?) -> Any? = { x -> Reflect.num(x)?.let { it > 0 } ?: onOff(x) }
        val light = Reflect.call(v, "getLight")
        cabinPoll(light, listOf("getCurrentHighBeamLightState", "getCurrentHighBeamState"), "high_beam")
        val brake = Reflect.call(v, "getBrake")
        cabinPoll(brake, listOf("getCurrentBrakePedalStatus", "getCurrentBrakePedalPosition"), "brake") { x -> (x as? Boolean) ?: Reflect.num(x) }
        val seat = Reflect.call(v, "getSeat")
        cabinPoll(seat, listOf("getCurrentSeatForeAftPosition", "getCurrentSeatPosition"), "seat_position", "ROW_1_LEFT") { Reflect.num(it) }
        cabinPoll(seat, listOf("getHvacSeatTemperature", "getCurrentSeatHeatingLevel"), "seat_heat", "ROW_1_LEFT", level)
        cabinPoll(seat, listOf("getHvacSeatVentilationState", "getCurrentSeatVentilationLevel"), "seat_vent", "ROW_1_LEFT", level)
        val hvac = Reflect.call(v, "getHVAC") ?: Reflect.call(v, "getHvac")
        cabinPoll(hvac, listOf("getHvacAcState", "getCurrentAcState"), "ac", "ROW_1_LEFT")
        cabinPoll(hvac, listOf("getHvacFanSpeed", "getCurrentFanSpeed"), "fan", "ROW_1_LEFT") { Reflect.num(it) }
        cabinPoll(hvac, listOf("getHvacDefrosterState", "getCurrentDefrosterState"), "defrost", "FRONT_WINDSHIELD")
        cabinPoll(Reflect.call(v, "getSteeringWheel"), listOf("getHvacSteeringWheelHeatState"), "wheel_heat", null, level)
        cabinPoll(Reflect.call(v, "getSideMirror"), listOf("getHvacSideMirrorHeatState"), "mirror_heat", "DRIVER_LEFT", level)
        cabinPoll(Reflect.call(v, "getWindow"), listOf("getCurrentWindowPosition"), "window", "ROW_1_LEFT") { x -> Reflect.num(x)?.let { it > 0 } }
        cabinPoll(Reflect.call(v, "getDoor"), listOf("getDoorLockState", "getCurrentDoorLockState"), "door_lock", "ROW_1_LEFT")
        cabinPoll(Reflect.call(v, "getWiper"), listOf("getCurrentWiperSwitch", "getCurrentWiperMode"), "wiper_auto", "FRONT_WINDSHIELD") { Reflect.name(it)?.contains("AUTO") }
        pollTelltales(Reflect.call(v, "getSafety"))
    }

    /**
     * Hazard lights, parking brake and auto hold have no getter in Vehicle SDK 2.3.2, but the cluster telltales carry
     * them: both turn-signal lamps on = hazard, (E)PB lamp = parking brake, AUTO_HOLD lamp = auto hold.
     */
    private fun pollTelltales(safety: Any?) {
        safety ?: return
        Reflect.get(safety, "getTelltalesInfo") { t ->
            val list = Reflect.prop(t, "telltales") as? List<*> ?: return@get
            val lit = HashMap<String, Boolean>()
            for (d in list) {
                val type = Reflect.name(Reflect.prop(d, "type")) ?: continue
                lit[type] = Reflect.name(Reflect.prop(d, "state")).let { it == "ON" || it == "BLINK" }
            }
            cabin("hazard", lit["TURN_SIGNAL_LEFT"] == true && lit["TURN_SIGNAL_RIGHT"] == true)
            cabin("parking_brake", lit["ELECTRONIC_PARKING_BRAKE"] == true || lit["PARKING_BRAKE"] == true)
            if ("AUTO_HOLD" in lit) cabin("auto_hold", lit["AUTO_HOLD"] == true)
        }
    }

    /** getVINInfo(): only a hash leaves this class (the VIN itself is never stored or sent). */
    private fun readVin(v: Any) {
        val info = Reflect.call(v, "getCarInfo") ?: Reflect.call(v, "getVehicleInfo") ?: v
        Reflect.get(info, "getVINInfo") { x ->
            val vin = (Reflect.prop(x, "vin", "VIN", "value") ?: x)?.toString()?.takeIf { it.length >= 6 } ?: return@get
            val md = java.security.MessageDigest.getInstance("SHA-256").digest(("safelog:" + vin).toByteArray())
            onVehicleId?.invoke(md.take(8).joinToString("") { "%02x".format(it) })
        }
    }

    // --------------------------------------------------------------------------------------------- AAOS
    private fun startAaos() {
        try {
            val c = Car.createCar(context) ?: return mark("aaos", "없음")
            car = c
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            cpm = m
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                    when (value.propertyId) {
                        // m/s, signed (reverse < 0) -> |v| km/h
                        VehiclePropertyIds.PERF_VEHICLE_SPEED -> asm.speedKph = abs((value.value as Float).toDouble()) * 3.6
                        VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR -> asm.gear = gearOf(value.value as Int)
                        VehiclePropertyIds.NIGHT_MODE -> asm.night = if (value.value as Boolean) 1 else 0
                    }
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
            }
            cpmCallback = cb
            val speedOk = context.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED
            if (speedOk) {
                m.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST)
                mark("속도", "AAOS PERF_VEHICLE_SPEED")
            } else mark("속도", "권한 없음 (CAR_SPEED)")
            runCatching { m.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE); mark("기어", "AAOS GEAR_SELECTION") }
            runCatching { m.registerCallback(cb, VehiclePropertyIds.NIGHT_MODE, CarPropertyManager.SENSOR_RATE_ONCHANGE); mark("야간", "AAOS NIGHT_MODE") }
        } catch (t: Throwable) {
            mark("aaos", "사용 불가: ${t.javaClass.simpleName}")      // plain phone / non-automotive emulator
        }
    }

    /** android.car.VehicleGear: P = 4, R = 2, N = 1, D = 8 (and manual gears >= 16) -> contract 0..3 */
    private fun gearOf(g: Int) = when (g) { 4 -> 0; 2 -> 1; 1 -> 2; 0 -> -1; else -> 3 }

    // --------------------------------------------------------------------------------------------- Vehicle SDK
    private fun startVehicleSdk() {
        val vc = Reflect.cls("ai.pleos.playground.vehicle.Vehicle") ?: return mark("Vehicle SDK", "APK에 없음")
        val v = try { vc.getConstructor(Context::class.java).newInstance(context) } catch (t: Throwable) { return mark("Vehicle SDK", "생성 실패") }
        vehicle = v
        Reflect.call(v, "initialize")
        // the SDK binds to the vehicle service asynchronously: register a moment later
        handler?.postDelayed({ registerVehicleListeners(v); readVin(v); probedAtNs = System.nanoTime() }, 800)
    }

    private fun registerVehicleListeners(v: Any) {
        fun reg(domainGetter: String, register: String, unregisterName: String, listenerCls: String, key: String, onValue: (Any?) -> Unit) {
            val domain = Reflect.call(v, domainGetter) ?: return mark(key, "$domainGetter 없음")
            val l = Reflect.listener("ai.pleos.playground.vehicle.listener.$listenerCls") { m, a ->
                if (m != "onFailed") onValue(a.firstOrNull())
            } ?: return mark(key, "$listenerCls 없음")
            if (Reflect.call(domain, register, l) != null || Reflect.methods(domain, register).isNotEmpty()) {
                mark(key, "Vehicle SDK $register")
                unregister.add { Reflect.call(domain, unregisterName, l) }
            }
        }
        reg("getBrake", "registerBrakePedalStatus", "unregisterBrakePedalStatus", "BrakePedalStatusListener", "브레이크") {
            asm.brakeOn = when (it) { true -> 1; false -> 0; else -> -1 }
        }
        // official: FRONT-WHEEL angle in degrees, leftward negative (data contract v3 keeps it as delivered)
        reg("getSteeringWheel", "registerSteeringWheelAngle", "unregisterSteeringWheelAngle", "SteeringWheelAngleListener", "조향각") {
            Reflect.num(it)?.let { d -> asm.steerDeg = d }
        }
        reg("getTurnSignal", "registerTurnSignalState", "unregisterTurnSignalState", "TurnSignalStateListener", "방향지시등") {
            asm.turnSignal = when (Reflect.name(it)) { "LEFT" -> 1; "RIGHT" -> 2; else -> 0 }      // no hazard state in the API
        }
        if (asm.gear < 0) reg("getGear", "registerGearSelection", "unregisterGearSelection", "GearSelectionListener", "기어(Vehicle SDK)") {
            asm.gear = when (Reflect.name(it)) { "GEAR_PARK" -> 0; "GEAR_REVERSE" -> 1; "GEAR_NEUTRAL" -> 2; null -> -1; else -> 3 }
        }
        // capability bounds tell front-wheel (~40 deg) from steering-wheel (+-540) units
        Reflect.get(Reflect.call(v, "getSteeringWheel"), "checkSteeringWheelAngleCapabilities") { cap ->
            val b = findBound(cap)
            if (b != null) asm.steerBoundDeg = b
        }
        // the seats this car has (a List<SeatArea>); until it answers, or if it cannot, pollSeats asks every SeatValue
        Reflect.get(Reflect.call(v, "getSeat"), "checkSeatOccupancyCapabilities") { list ->
            val names = (list as? List<*>)?.flatMap { Reflect.areaNames(it) }?.distinct()
            if (!names.isNullOrEmpty()) { seatAreas = names; Log.d(TAG, "seats: $names") }
        }
    }

    private fun findBound(x: Any?, depth: Int = 0): Double? {
        if (x == null || depth > 3) return null
        Reflect.num(Reflect.prop(x, "max", "maxValue", "maximum"))?.let { return abs(it) }
        if (x is Iterable<*>) for (e in x) findBound(e, depth + 1)?.let { return it }
        if (x is Map<*, *>) for (e in x.values) findBound(e, depth + 1)?.let { return it }
        for (n in listOf("areaBounds", "bounds", "areaConfig", "value")) findBound(Reflect.prop(x, n), depth + 1)?.let { return it }
        return null
    }

    /** ~1 Hz reads of the slow channels (no listener API, or listener names not confirmed). */
    private val poll = object : Runnable {
        override fun run() {
            val v = vehicle
            if (v != null) {
                Reflect.get(Reflect.call(v, "getWiper"), "getCurrentWiperState", "FRONT_WINDSHIELD") {
                    trace("wiper state", it)
                    asm.wiperActive = when (Reflect.name(it)) { "ON" -> 1; "OFF" -> 0; else -> -1 }   // WiperState: actually wiping
                }
                Reflect.get(Reflect.call(v, "getLight"), "getCurrentLowBeamLightState") {
                    asm.lowBeam = when (Reflect.name(it)) { "ON" -> 1; "OFF", "DAYTIME_RUNNING" -> 0; else -> -1 }
                }
                val dm = Reflect.call(v, "getDrivingMode")
                val cruiseFn = dm?.javaClass?.methods?.firstOrNull { it.name.startsWith("get") && it.name.contains("CruiseControl") && it.name.contains("State") }?.name
                // CruiseControl (SDK 2.3.2): only ACTIVATED / USER_OVERRIDE mean the car is driving; every other reported
                // state (ENABLED = standby, SUSPENDED, OTHER, FORCED_DEACTIVATION_WARNING) leaves the driver in control.
                // A car without cruise answers ERROR_NOT_SUPPORTED_PROPERTY -> not engaged; other failures stay unknown.
                if (cruiseFn != null) Reflect.get(dm, cruiseFn, onError = { e ->
                    if ((e as? Throwable)?.message == "ERROR_NOT_SUPPORTED_PROPERTY") asm.cruiseActive = 0
                }) {
                    asm.cruiseActive = when (Reflect.name(it)) { null -> -1; "ACTIVATED", "USER_OVERRIDE" -> 1; else -> 0 }
                }
                Reflect.get(Reflect.call(v, "getOdometer"), "getCurrentDistanceDriven") { x -> Reflect.num(x)?.let { asm.odoKm = it } }
                pollSeats(Reflect.call(v, "getSeat"))
                if (asm.speedKph.isNaN() || asm.speedKph < 1.0) pollCabin(v)
            }
            // current place / heading: NaviHelper 3.0.5 answers through the listener (onCurrentLocationInfo); needs
            // pleos.car.permission.NAVI_ROUTE. A version that returns the info directly is handled here too.
            Reflect.call(navi, "getCurrentLocationInfo")?.takeIf { it != Unit }?.let { onNavi("CurrentLocation", it) }
            handler?.postDelayed(this, 1000)
        }
    }

    // written by SDK callbacks, read on the poll thread
    private val seatOcc = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val seatBelt = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var seatAreas: List<String>? = null
    private fun pollSeats(seat: Any?) {
        seat ?: return
        val m = Reflect.methods(seat, "getCurrentSeatOccupancy").firstOrNull() ?: return
        // SeatArea(vararg values: SeatValue): its constructor takes SeatValue[], so list the enum through Reflect
        val values = seatAreas ?: Reflect.areaValues(m.parameterTypes.firstOrNull { it.simpleName.endsWith("Area") } ?: return)
        if (values.isEmpty()) return Reflect.warnOnce("getCurrentSeatOccupancy: no seat values")
        for (s in values) {
            Reflect.get(seat, "getCurrentSeatOccupancy", s) { x -> (x as? Boolean)?.let { seatOcc[s] = it; trace("seat $s occupied", it) } }
            Reflect.get(seat, "getCurrentSeatBeltBuckledState", s) { x -> (x as? Boolean)?.let { seatBelt[s] = it; trace("seat $s belt", it) } }
        }
        if (seatOcc.isNotEmpty()) {
            val occ = seatOcc.filterValues { it }.keys
            asm.seatsOccupied = occ.size
            asm.beltsBuckled = if (occ.all { seatBelt.containsKey(it) }) occ.count { seatBelt[it] == true } else -1
        }
    }

    // --------------------------------------------------------------------------------------------- NaviHelper
    private fun startNavi() {
        val nc = Reflect.cls("ai.pleos.playground.navi.helper.NaviHelper") ?: return mark("내비", "NaviHelper 없음")
        try {
            val h = nc.getConstructor(Context::class.java).newInstance(context)
            navi = h
            val l = Reflect.listener("ai.pleos.playground.navi.helper.listener.NaviHelperEventListener") { m, a -> onNavi(m, a.firstOrNull()) }
            val init = Reflect.methods(h, "initialize").maxByOrNull { it.parameterCount }
            val connected = { Reflect.call(h, "addListener", l); mark("내비", "NaviHelper 연결") }
            if (init != null && init.parameterCount >= 2) {
                val args = init.parameterTypes.mapIndexed { k, _ -> if (k == 0) Reflect.fn1 { connected() } else Reflect.fn1 { e -> mark("내비", "연결 실패 $e") } }
                // initialize(onConnected: () -> Unit, onFailed) may use Function0 for the first callback
                val a0 = if (kotlin.jvm.functions.Function0::class.java.isAssignableFrom(init.parameterTypes[0])) ({ connected() } as () -> Unit) else args[0]
                init.invoke(h, a0, *args.drop(1).toTypedArray())
            } else {
                Reflect.call(h, "initialize"); connected()
            }
        } catch (t: Throwable) {
            mark("내비", "시작 실패 ${t.javaClass.simpleName}")
        }
    }

    private fun first(x: Any?): Any? = if (x is List<*>) x.firstOrNull() else x

    private fun onNavi(event: String, arg: Any?) {
        // which events arrive, never the place itself (no address or coordinates in the log)
        trace("navi $event", when (arg) { null, is Number, is Boolean, is Enum<*> -> arg; is List<*> -> "${arg.size} items"; else -> arg.javaClass.simpleName })
        try {
            when {
                event.contains("TBT", true) -> {
                    val tbt = first(arg)
                    if (tbt == null) { asm.tbtDistM = Double.NaN; asm.tbtType = 0; return }
                    asm.tbtDistM = Reflect.num(Reflect.prop(tbt, "distance", "remainDistance")) ?: Double.NaN
                    val type = Reflect.name(Reflect.prop(tbt, "type", "turnType")) ?: ""
                    asm.tbtType = when {
                        type.contains("UTURN") -> 3
                        type == "TURN_LEFT" || type.contains("SHARP_L") -> 1
                        type == "TURN_RIGHT" || type.contains("SHARP_R") -> 2
                        else -> 4
                    }
                }
                event.contains("CautionPoint", true) -> {
                    val list = (arg as? List<*>) ?: listOfNotNull(arg)
                    val near = list.mapNotNull { cp -> Reflect.num(Reflect.prop(cp, "remainDistance", "distance"))?.let { cp to it } }.minByOrNull { abs(it.second) }
                    if (near == null) { asm.cautionType = 0; asm.cautionDistM = Double.NaN; asm.schoolZone = 0; return }
                    val code = when (Reflect.name(Reflect.prop(near.first, "type", "cautionType"))) {
                        "SCHOOL_ZONE" -> 1; "SPEED_BUMP" -> 2; "RAILROAD_CROSSING" -> 3; "ACCIDENT_PRONE_AREA" -> 5; "FOG_AREA" -> 6
                        "WILDLIFE_CROSSING" -> 8; "DOWNHILL_CAUTION_AREA" -> 9; "FALLING_ROCK_AREA" -> 10; "NARROW_ROAD" -> 11
                        "SLIPPERY_ROAD_ALERT" -> 12; else -> 0
                    }
                    asm.cautionType = code; asm.cautionDistM = near.second
                    asm.schoolZone = if (code == 1 && near.second <= 0) 1 else if (list.none { Reflect.name(Reflect.prop(it, "type")) == "SCHOOL_ZONE" }) 0 else asm.schoolZone
                }
                event.contains("CameraAlert", true) -> {
                    val list = (arg as? List<*>) ?: listOfNotNull(arg)
                    asm.cameraOverspeed = if (list.any { Reflect.prop(it, "isOverSpeed", "overSpeed") == true }) 1 else 0
                }
                event.contains("SpeedLimit", true) -> {
                    val lim = Reflect.num(arg) ?: Reflect.num(Reflect.prop(arg, "speedLimit"))
                    asm.speedLimitKph = lim?.takeIf { it > 0 } ?: Double.NaN
                    asm.roadClass = when { lim == null -> asm.roadClass; lim >= 90 -> 2; lim >= 70 -> 1; else -> 0 }
                }
                event.contains("CurrentLocation", true) -> {
                    Reflect.num(Reflect.prop(arg, "heading"))?.let { asm.headingDeg = it }
                    (Reflect.prop(arg, "roadName", "address", "name") as? String)?.takeIf { it.isNotBlank() }?.let { onPlace?.invoke(shortPlace(it)) }
                }
                event.contains("NavigationStatus", true) -> asm.navActive = if (Reflect.name(arg)?.contains("ROUTE") == true) 1 else 0
                event.contains("RouteState", true) || event.contains("Traffic", true) -> {
                    val st = Reflect.name(Reflect.prop(arg, "trafficStatus", "status")) ?: return
                    asm.congestion = when (st) { "HEAVY" -> 2; "SLOW" -> 1; else -> 0 }
                }
            }
        } catch (t: Throwable) {
            Log.w("SafeLog/Navi", "$event: $t")
        }
    }

    /** "서울특별시 서초구 서초대로 397" -> "서초대로" (the journal shows a short place name, no map). */
    private fun shortPlace(s: String): String {
        val parts = s.split(' ').filter { it.isNotBlank() }
        return parts.lastOrNull { it.endsWith("로") || it.endsWith("길") || it.endsWith("사거리") || it.endsWith("교차로") } ?: parts.takeLast(2).joinToString(" ")
    }
}
