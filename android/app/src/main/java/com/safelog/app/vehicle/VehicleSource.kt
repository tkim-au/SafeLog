package com.safelog.app.vehicle

import com.safelog.core.contract.Frame

/** Something that produces 10 Hz frames in the data contract v3 format (real car, emulator, or a demo replay). */
interface VehicleSource {
    val name: String
    /** Starts producing frames; [onFrame] and [onPlace] may be called from a background thread. */
    /**
     * [onCabin]: readings outside data contract v3 that only S05 내 차 익히기 uses (hazard, high beam, parking brake,
     * HVAC, seat position ...). [onVehicleId]: a hash of the VIN, to notice another car.
     */
    fun start(onFrame: (Frame) -> Unit, onPlace: (String) -> Unit, onCabin: (String, Any?) -> Unit = { _, _ -> }, onVehicleId: (String) -> Unit = {})
    fun stop()

    /** Signals this car can report (tour step filter). null = unknown: show every step. */
    fun capabilities(): Set<String>? = null
    /** What this source can read, for the settings screen / logs ("speed: ok", "steering: no permission" ...). */
    fun status(): Map<String, String> = emptyMap()
}

/**
 * Holds the latest value of every channel. ON_CHANGE signals are HELD (never timed out), continuous ones
 * (speed) too; a 10 Hz ticker turns the held state into frames. Thread-safe: callbacks arrive on SDK threads.
 */
class FrameAssembler {
    @Volatile var speedKph = Double.NaN
    @Volatile var gear = -1
    @Volatile var brakeOn = -1
    @Volatile var steerDeg = Double.NaN
    @Volatile var steerBoundDeg = Double.NaN
    @Volatile var headingDeg = Double.NaN
    @Volatile var odoKm = Double.NaN
    @Volatile var cruiseActive = -1
    @Volatile var turnSignal = 0
    @Volatile var wiperActive = -1
    @Volatile var lowBeam = -1
    @Volatile var night = -1
    @Volatile var seatsOccupied = -1
    @Volatile var beltsBuckled = -1
    @Volatile var ttTpms = -1
    @Volatile var speedLimitKph = Double.NaN
    @Volatile var roadClass = 0
    @Volatile var tbtDistM = Double.NaN
    @Volatile var tbtType = 0
    @Volatile var cautionType = 0
    @Volatile var cautionDistM = Double.NaN
    @Volatile var schoolZone = 0
    @Volatile var cameraOverspeed = 0
    @Volatile var congestion = 0
    @Volatile var navActive = -1

    fun frame(t: Double) = Frame(
        t = t, speedKph = speedKph, gear = gear, brakeOn = brakeOn, steerDeg = steerDeg, steerBoundDeg = steerBoundDeg,
        headingDeg = headingDeg, odoKm = odoKm, cruiseActive = cruiseActive, turnSignal = turnSignal, wiperActive = wiperActive,
        lowBeam = lowBeam, night = night, seatsOccupied = seatsOccupied, beltsBuckled = beltsBuckled, ttTpms = ttTpms,
        speedLimitKph = speedLimitKph, roadClass = roadClass, tbtDistM = tbtDistM, tbtType = tbtType, cautionType = cautionType,
        cautionDistM = cautionDistM, schoolZone = schoolZone, cameraOverspeed = cameraOverspeed, congestion = congestion,
        navActive = navActive,
        // telltales are not readable by third-party apps (PRIVATE_CAR_SAFETY): always unknown in data contract v3
    )
}
