package com.safelog.app.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale

private const val TAG = "SafeLog/Voice"

/**
 * The spoken line (praise at a signal wait, voice preview). On a Pleos car it is the car's own voice: Pleos
 * TextToSpeech SDK 3.3.1 in on-device mode (no network, no app registration, permission
 * pleos.car.permission.TTS_SERVICE). Anywhere else, or when the SDK refuses, Android's speech engine speaks.
 * The Pleos emulator has no Android speech engine, so before this class the line was silent there.
 */
class CarVoice(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pleos = PleosTts(app, main)
    private var android: AndroidTts? = null

    /** Which voice would speak now, for logs and the debug panel. */
    val engine: String
        get() = if (pleos.ready) "Pleos TextToSpeech" else "Android 음성 엔진" + (pleos.problem?.let { " (Pleos TTS: $it)" } ?: "")

    /**
     * [pitch] only reaches Android's engine: the Pleos SDK has one voice and no pitch setting. While the SDK is
     * still starting (it answers a moment after construction) the sentence waits for it, up to 3 s.
     */
    fun say(text: String, pitch: Float = 1f) = say(text, pitch, 0)

    private fun say(text: String, pitch: Float, waitedMs: Int) {
        if (pleos.say(text)) return
        if (pleos.starting && waitedMs < 3000) {
            main.postDelayed({ say(text, pitch, waitedMs + 200) }, 200)
            return
        }
        (android ?: AndroidTts(app).also { android = it }).say(text, pitch)
    }

    fun stop() {
        pleos.stop()
        android?.stop()
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        pleos.release()
        android?.release()
        android = null
    }
}

/**
 * Pleos TextToSpeech, called by name like the other Pleos SDKs (vehicle/Reflect.kt) so the app also builds and runs
 * without it. The order is the one seen working on emulator v2.1.0: construct with Mode.ON_DEVICE, initialize with a
 * listener, and only in onSuccess add the event listener and set Locale.KOREA (language and country both: with "ko"
 * alone the engine fails every sentence), then speak(text, isFinal = true).
 */
private class PleosTts(context: Context, private val main: Handler) {
    @Volatile var ready = false
        private set
    @Volatile var problem: String? = null
        private set
    /** initialize() was called and has not answered yet */
    @Volatile var starting = false
        private set
    private var tts: Any? = null
    private var speak: Method? = null

    init {
        try {
            val type = Class.forName("ai.pleos.playground.tts.TextToSpeech")
            val modeType = Class.forName("ai.pleos.playground.tts.constant.Mode")
            val onDevice = modeType.enumConstants!!.first { (it as Enum<*>).name == "ON_DEVICE" }
            val t = type.getConstructor(Context::class.java, modeType).newInstance(context, onDevice)
            tts = t
            speak = type.getMethod("speak", String::class.java, java.lang.Boolean.TYPE)
            val eventType = Class.forName("ai.pleos.playground.tts.listener.EventListener")
            val initType = Class.forName("ai.pleos.playground.tts.listener.OnInitializeListener")
            val events = listener(eventType) { name, arg ->
                when (name) {
                    "onStart" -> Log.d(TAG, "Pleos TTS onStart")
                    "onDone" -> Log.d(TAG, "Pleos TTS onDone")
                    // the engine gave up on a sentence: the next ones go to Android's engine
                    "onError" -> { Log.w(TAG, "Pleos TTS error: $arg"); ready = false; problem = arg?.toString() }
                }
            }
            val init = listener(initType) { name, arg ->
                if (name == "onSuccess") main.post {
                    try {
                        type.getMethod("addEventListener", eventType).invoke(t, events)
                        type.getMethod("setLocale", Locale::class.java).invoke(t, Locale.KOREA)
                        ready = true
                        problem = null
                        Log.d(TAG, "Pleos TTS ready")
                    } catch (e: Throwable) {
                        problem = e.javaClass.simpleName
                        Log.w(TAG, "Pleos TTS started but cannot be used: $e")
                    }
                    starting = false
                } else if (name == "onFailure") {
                    starting = false
                    ready = false
                    problem = arg?.toString() ?: "onFailure"
                    Log.w(TAG, "Pleos TTS did not start: $arg")
                }
            }
            starting = true
            type.getMethod("initialize", initType).invoke(t, init)
        } catch (e: Throwable) {
            // no SDK in this build, no TTS service on this device, or another SDK version
            starting = false
            problem = e.javaClass.simpleName
            Log.i(TAG, "Pleos TTS is not usable here: $e")
        }
    }

    /** A stand-in for one of the SDK's listener interfaces. */
    private fun listener(type: Class<*>, onCall: (String, Any?) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SafeLog(${type.simpleName})"
                else -> {
                    onCall(method.name, args?.firstOrNull())
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        else -> null
                    }
                }
            }
        }

    /** True when the car's voice took the sentence. */
    fun say(text: String): Boolean {
        val m = speak ?: return false
        if (!ready) return false
        return try {
            m.invoke(tts, text, true)          // isFinal = true: a whole sentence, not one piece of a stream
            Log.d(TAG, "Pleos TTS speak (${text.length} chars)")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos TTS speak failed: $e")
            ready = false
            problem = e.javaClass.simpleName
            false
        }
    }

    fun stop() = call("stop")

    fun release() {
        ready = false
        starting = false
        call("release")
        tts = null
    }

    private fun call(name: String) {
        val t = tts ?: return
        try {
            t.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(t)
        } catch (e: Throwable) {
            Log.d(TAG, "Pleos TTS $name failed: $e")
        }
    }
}

/** Android's built-in engine, for a phone, a plain AAOS emulator or a car without the Pleos TTS service. */
private class AndroidTts(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    // transient-duck focus so navigation guidance stays audible
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ready = false

    fun say(text: String, pitch: Float) {
        val t = tts
        if (t == null) {
            tts = android.speech.tts.TextToSpeech(context) { status ->
                ready = status == android.speech.tts.TextToSpeech.SUCCESS
                tts?.takeIf { ready }?.let {
                    it.language = Locale.KOREAN
                    it.setAudioAttributes(attrs)
                    it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) { audio?.abandonAudioFocusRequest(focus) }
                        @Deprecated("old API") override fun onError(id: String?) { audio?.abandonAudioFocusRequest(focus) }
                    })
                    speak(it, text, pitch)
                }
            }
            return
        }
        if (ready) speak(t, text, pitch)
    }

    private fun speak(t: android.speech.tts.TextToSpeech, text: String, pitch: Float) {
        audio?.requestAudioFocus(focus)
        t.setPitch(pitch)
        t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, Bundle(), "safelog-line")
    }

    fun stop() { if (ready) tts?.stop() }

    fun release() { tts?.shutdown(); tts = null; ready = false }
}
