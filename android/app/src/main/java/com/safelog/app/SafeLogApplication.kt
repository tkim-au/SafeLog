package com.safelog.app

import android.app.Application
import com.safelog.app.data.FakeSafeLogRepository
import com.safelog.app.live.LiveSafeLogRepository

/** One repository per process: a trip keeps recording across screen rotation and while navigation is in front. */
class SafeLogApplication : Application() {
    val live by lazy { LiveSafeLogRepository(this, BuildConfig.SAFELOG_SERVER.ifBlank { null }) }
    val fake by lazy { FakeSafeLogRepository(firstRunDone = false, recordingConsent = false) }
}
