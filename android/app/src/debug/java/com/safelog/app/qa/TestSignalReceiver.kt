package com.safelog.app.qa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.safelog.app.vehicle.TestSignals
import com.safelog.core.json.Json

/**
 * Debug builds only (src/debug, so the release APK has no such entry point): test signals from adb.
 * Guarded by android.permission.DUMP in the debug manifest, which the adb shell holds and apps do not.
 *
 *   adb shell am broadcast -n com.safelog.app/.qa.TestSignalReceiver --es set 'belts_buckled=2,seats_occupied=2'
 *
 * Extras (all optional, applied in this order): clear, set, cabin, place, app. With none it only reports.
 * app is one [DemoCommands] line, e.g. `--es app 'replay commute_city 10'` (the buttons of the app's 시연 panel).
 * The reply (result data) is the current state as JSON; result code 0 = applied, 1 = some entry refused.
 * Every command is also logged as one `SafeLogQA` JSON line, which qa/soak collects.
 * Usage and examples: android/tools/test-signal.sh.
 */
class TestSignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = linkedMapOf<String, Any?>()
        for (k in TestConsoleApi.KEYS) intent.getStringExtra(k)?.let { cmd[k] = it }
        if (cmd["app"] == null) {
            val out = TestConsoleApi.run(cmd, TestSignals.store, null)
            Log.i(TAG, TestConsoleApi.injectLine("adb", cmd, out))
            if (isOrderedBroadcast) { resultCode = code(out); resultData = Json.write(out) }
            return
        }
        // An app command may wait on the main thread or the network (sync): answer from another thread.
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        Thread({
            try {
                val out = TestConsoleApi.run(cmd, TestSignals.store, AppDemo.commands(context))
                Log.i(TAG, TestConsoleApi.injectLine("adb", cmd, out))
                if (ordered) pending.setResult(code(out), Json.write(out), null)
            } finally {
                pending.finish()
            }
        }, "safelog-qa-app").start()
    }

    private fun code(out: Map<String, Any?>) = if (out["ok"] == true) 0 else 1

    companion object {
        const val TAG = "SafeLogQA"
    }
}
