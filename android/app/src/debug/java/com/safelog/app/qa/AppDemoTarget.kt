package com.safelog.app.qa

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.safelog.app.DemoScreen
import com.safelog.app.SafeLogApplication
import com.safelog.app.data.SafeLogRepository
import com.safelog.app.live.GateLoader
import com.safelog.app.model.DriveMode
import com.safelog.app.model.DrivePhase
import com.safelog.app.vehicle.DemoTrip
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Debug builds only: [DemoCommands] on the device. Calls the same repository functions the debug 시연 panel calls,
 * on the main thread like the panel's buttons (the console server and the adb receiver call from their own threads).
 * The repositories are looked up per call: the console starts before the Application is created.
 */
class AppDemoTarget(private val ctx: Context) : DemoTarget {
    private val app get() = ctx.applicationContext as SafeLogApplication
    private val live get() = app.live
    private val fake get() = app.fake
    private val shown: SafeLogRepository get() = if (sampleShown) fake else live

    override val trips: List<DemoTrip> by lazy { live.demoTrips() }
    override val replaying get() = live.demoRunning.value
    override val sampleShown get() = DemoScreen.sample.value
    override val parked get() = shown.phase.value == DrivePhase.Parked
    override val tourNext get() = shown.carTour.value.next?.title
    override val training get() = shown.briefing.value.mode == DriveMode.Training

    override fun replay(trip: String, speed: Double) = onMain { live.startDemo(trip, speed) }
    override fun arrive() = onMain { live.stopDemo() }
    override fun tourStep() = onMain { if (sampleShown) fake.demoCarStep() else live.demoCarStep() }
    override fun newCar() = onMain { if (sampleShown) fake.demoNewCar() else live.demoNewCar() }
    override fun modeFlip() = onMain { if (sampleShown) fake.demoModeChange() else live.demoModeChange() }
    override fun seedHistory() = onMain { live.seedHistory() }
    override fun points(n: Int) = onMain { live.demoCredit(n) }

    override fun loadModel(): String {
        GateLoader.install(ctx, File("/sdcard/Download/${GateLoader.FILE}"))   // throws when missing or not a valid bundle
        live.loadGate()
        return live.gateSource
    }

    /** Waits for the network (up to a few seconds), so never on the main thread. */
    override fun sync(): String {
        live.syncNow()
        return live.syncInfo.value
    }

    override fun showSample(on: Boolean) { DemoScreen.sample.value = on }

    override fun sample(moment: String) = onMain {
        when (moment) {
            "park" -> fake.demoPark()
            "drive" -> fake.demoDrive()
            "idle" -> fake.demoIdle()
            "praise" -> fake.demoPraise()
            "insurance" -> fake.demoInsuranceGoal()
            else -> throw IllegalArgumentException(moment)
        }
    }

    override fun info(): Map<String, Any?> {
        val s = shown
        val tour = s.carTour.value
        return linkedMapOf(
            "screen" to if (sampleShown) "sample" else "live",
            "replay" to replaying,
            "phase" to s.phase.value.name,
            "mode" to s.briefing.value.mode.name,
            "points" to live.briefing.value.pointsBalance,   // `points` always goes to the engine
            "tour_next" to tour.next?.title,
            "tour_finished" to tour.finished,
            "engine" to live.engineInfo.value,
            "sync" to live.syncInfo.value,
        )
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        Handler(Looper.getMainLooper()).post(task)
        return task.get(5, TimeUnit.SECONDS)
    }
}

/** The one [DemoCommands] the console and the adb receiver share (one per process). */
object AppDemo {
    @Volatile private var commands: DemoCommands? = null

    fun commands(context: Context): DemoCommands =
        commands ?: synchronized(this) { commands ?: DemoCommands(AppDemoTarget(context)).also { commands = it } }
}
