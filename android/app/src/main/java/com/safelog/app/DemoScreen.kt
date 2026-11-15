package com.safelog.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Debug builds only: which data the activity shows. The test console (src/debug qa/AppDemoTarget) and the
 * debug 시연 panel change it. Release builds never do: they always show the engine (live).
 */
object DemoScreen {
    /** true = the sample data (FakeSafeLogRepository), to review screens without the engine */
    val sample = MutableStateFlow(false)
}
