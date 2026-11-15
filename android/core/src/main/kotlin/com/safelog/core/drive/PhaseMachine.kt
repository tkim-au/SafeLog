package com.safelog.core.drive

import com.safelog.core.contract.DataContract
import com.safelog.core.contract.Frame

enum class DrivePhase { Parked, Driving, Idling }

/**
 * Gear, speed and brake -> the app phase the screens follow (front-end spec 5-1):
 *   Parked  = P (or no gear signal and standing still for 3 minutes)
 *   Driving = D / R / N and moving
 *   Idling  = D, speed 0 for more than 2 s and the brake pressed (signal wait) -> one line may be spoken
 * A trip starts when the phase leaves Parked and ends when it comes back.
 */
class PhaseMachine(private val hz: Double = DataContract.BASE_HZ) {
    var phase = DrivePhase.Parked
        private set
    private var stillFrames = 0

    /** Returns the new phase. */
    fun step(f: Frame): DrivePhase {
        val v = if (f.speedKph.isNaN()) 0.0 else f.speedKph
        stillFrames = if (v < 1.0) stillFrames + 1 else 0
        val still2s = stillFrames > 2 * hz
        phase = when {
            f.gear == DataContract.GEAR_P -> DrivePhase.Parked
            f.gear < 0 && phase == DrivePhase.Parked && v < 3 -> DrivePhase.Parked
            f.gear < 0 && stillFrames > 180 * hz -> DrivePhase.Parked
            (f.gear == DataContract.GEAR_D || f.gear < 0) && still2s && f.brakeOn == 1 -> DrivePhase.Idling
            else -> DrivePhase.Driving
        }
        return phase
    }

    fun reset() { phase = DrivePhase.Parked; stillFrames = 0 }
}
