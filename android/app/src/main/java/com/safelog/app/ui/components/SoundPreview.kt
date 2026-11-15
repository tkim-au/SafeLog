package com.safelog.app.ui.components

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.safelog.app.live.CarVoice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 설정·캐릭터 탭의 "들어보기". 칭찬 효과음과 아틀라스 목소리를 미리 듣는다.
 * 소리 장치는 처음 누를 때 만든다(화면을 그리기만 할 때는 만들지 않는다).
 * ponytail: 효과음은 시스템 기본음이다. 판정 팀의 실제 효과음 파일이 생기면 그 파일로 바꾼다.
 */
class SoundPreview(private val context: Context) {
    private var tone: ToneGenerator? = null
    // 주행 중 한 마디와 같은 목소리(차에서는 Pleos 음성, 그 밖에서는 Android 음성 엔진). 처음 누를 때 만든다.
    private var voice: CarVoice? = null

    fun chime() {
        val t = tone ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()?.also { tone = it } ?: return
        t.startTone(ToneGenerator.TONE_PROP_ACK, 200)
    }

    fun say(text: String, pitch: Float) {
        (voice ?: CarVoice(context).also { voice = it }).say(text, pitch)
    }

    fun release() {
        tone?.release()
        voice?.release()
    }
}

@Composable
fun rememberSoundPreview(): SoundPreview {
    val context = LocalContext.current.applicationContext
    val preview = remember { SoundPreview(context) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    return preview
}
