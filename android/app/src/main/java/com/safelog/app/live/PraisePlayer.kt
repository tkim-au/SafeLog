package com.safelog.app.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Driving-time sound: one short chime when a mission was done well, and in "가끔" one short praise line while
 * waiting at a light. Never a warning sound. The chime uses transient-duck audio focus so navigation guidance stays
 * audible; the line is spoken by [CarVoice] (the car's own Pleos voice, Android's engine elsewhere).
 */
class PraisePlayer(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private var tone: ToneGenerator? = null
    private val voice = CarVoice(app)
    @Volatile var pitch = 1f

    /** Which voice speaks the line now (debug panel / logs). */
    val voiceEngine: String get() = voice.engine

    fun chime() {
        val t = tone ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()?.also { tone = it } ?: return
        audio?.requestAudioFocus(focus)
        t.startTone(ToneGenerator.TONE_PROP_ACK, 180)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ audio?.abandonAudioFocusRequest(focus) }, 400)
    }

    fun say(text: String) = voice.say(text, pitch)

    fun release() { tone?.release(); tone = null; voice.release() }
}
