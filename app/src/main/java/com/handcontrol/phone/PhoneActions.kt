package com.handcontrol.phone

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * System-wide actions: they work for any video / music app and the
 * volume works even when nothing is playing.
 */
class PhoneActions(context: Context) {

    private val audio =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Explicit PLAY / PAUSE media keys go to the app that owns the
    // active media session (YouTube, VLC, Netflix, ...).
    fun play() = sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)

    fun pause() = sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)

    private fun sendKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun volumeUp() = adjust(AudioManager.ADJUST_RAISE)

    fun volumeDown() = adjust(AudioManager.ADJUST_LOWER)

    private fun adjust(direction: Int) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }
}
