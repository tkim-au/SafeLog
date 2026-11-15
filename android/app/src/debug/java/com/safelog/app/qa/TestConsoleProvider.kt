package com.safelog.app.qa

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.safelog.app.vehicle.TestSignals

/**
 * Debug builds only: starts the test console when the app process starts (a content provider is created before
 * the Application, and only the debug manifest declares this one). It provides no data.
 *
 *   adb -s emulator-5554 forward tcp:8077 tcp:8077   then   http://127.0.0.1:8077
 *   (or android/tools/test-signal.sh -s emulator-5554 open)
 */
class TestConsoleProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val api = TestConsoleApi(
            TestSignals.store,
            page = { ctx.assets.open("test-console/index.html").use { it.readBytes() } },
            log = { Log.i(TestSignalReceiver.TAG, it) },
            demo = AppDemo.commands(ctx),
        )
        val server = TestConsoleServer(api, TestConsoleApi.PORT) { Log.w(TAG, it) }
        Thread({ startWithRetry(server) }, "safelog-console-start").apply { isDaemon = true }.start()
        return true
    }

    /** A restarted process may find the port still held for a moment by the old one. */
    private fun startWithRetry(server: TestConsoleServer) {
        val p = TestConsoleApi.PORT
        repeat(10) {
            if (server.start()) { Log.i(TAG, "test console on 127.0.0.1:$p (adb forward tcp:$p tcp:$p)"); return }
            Thread.sleep(1000)
        }
        Log.w(TAG, "test console not started: port $p busy")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val TAG = "SafeLog/Console"
    }
}
