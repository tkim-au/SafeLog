package com.safelog.app.live

import android.content.Context
import android.util.Log
import com.safelog.core.gate.PraiseGate
import com.safelog.core.gate.TcnGate
import java.io.File

/**
 * The learned praise gate slot. The app ships EMPTY (rules only). The model trained on the PC is connected by
 * exporting it with backend/tools/export_gate.py, which writes one JSON bundle, and putting that file in either place:
 *
 *  1. app/src/main/assets/gate/gate_v3.json  (built into the APK), or
 *  2. <filesDir>/gate/gate_v3.json           (no rebuild: `adb push gate_v3.json /sdcard/Download/` then
 *                                             the debug 시연 panel's "모델 불러오기", or `adb shell run-as`).
 *
 * The bundle is checked (format, data contract v3, feature spec); anything else is refused and the app keeps
 * judging with rules only. The gate can only veto praise, never add it.
 */
object GateLoader {
    const val FILE = "gate_v3.json"

    data class Loaded(val gate: PraiseGate?, val source: String)

    fun load(context: Context): Loaded {
        val local = File(context.filesDir, "gate/$FILE")
        val candidates = buildList<Pair<String, () -> String>> {
            if (local.isFile) add("기기 파일 ${local.path}" to { local.readText() })
            if (runCatching { context.assets.list("gate")?.contains(FILE) == true }.getOrDefault(false)) {
                add("APK assets/gate/$FILE" to { context.assets.open("gate/$FILE").bufferedReader().readText() })
            }
        }
        for ((where, text) in candidates) {
            try {
                val g = TcnGate.load(text())
                Log.i("SafeLog/Gate", "loaded from $where: ${g.description}")
                return Loaded(g, where)
            } catch (t: Throwable) {
                Log.w("SafeLog/Gate", "refused $where: ${t.message}")
            }
        }
        return Loaded(null, if (candidates.isEmpty()) "모델 없음 (규칙만)" else "모델 거부됨 (규칙만)")
    }

    /** Copy a bundle (e.g. from /sdcard/Download) into the app's own folder. Throws if the bundle is refused. */
    fun install(context: Context, from: File): PraiseGate {
        val text = from.readText()
        val g = TcnGate.load(text)                                          // validate before replacing
        val dst = File(context.filesDir, "gate/$FILE")
        dst.parentFile?.mkdirs()
        dst.writeText(text)
        return g
    }
}
