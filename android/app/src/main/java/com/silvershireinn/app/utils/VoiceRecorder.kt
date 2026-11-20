package com.silvershireinn.app.utils

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Starts a voice-note recording with the best settings the phone accepts.
 *
 * The old setup used the phone-call microphone mode (VOICE_COMMUNICATION), which applies
 * call-style echo cancelling and noise gating tuned for calls: voices came out muffled and
 * "underwater" on many phones. Voice notes now use the normal microphone at 48 kHz / 128 kbps
 * AAC. If a phone rejects a setting we step down instead of crashing; null means the mic is
 * unavailable (for example another app is using it).
 */
object VoiceRecorder {

    private data class Cfg(val source: Int, val rate: Int, val bitrate: Int)

    private val configs = listOf(
        Cfg(MediaRecorder.AudioSource.MIC, 48_000, 128_000),
        Cfg(MediaRecorder.AudioSource.MIC, 44_100, 96_000),
        Cfg(MediaRecorder.AudioSource.VOICE_RECOGNITION, 44_100, 96_000),
        Cfg(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000, 48_000),
    )

    fun start(context: Context, file: File): MediaRecorder? {
        PanicVoice.pauseForMic()   // the panic listener lets go of the microphone
        PanicVoice.resumeAfterMicLater = context.applicationContext
        for (c in configs) {
            val r = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            } catch (_: Throwable) { return null }
            try {
                r.setAudioSource(c.source)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(c.rate)
                r.setAudioEncodingBitRate(c.bitrate)
                r.setAudioChannels(1)
                r.setOutputFile(file.absolutePath)
                r.prepare()
                r.start()
                return r
            } catch (_: Throwable) {
                try { r.reset() } catch (_: Throwable) {}
                try { r.release() } catch (_: Throwable) {}
                file.delete()
            }
        }
        return null
    }

    /** Stop safely. Returns false when nothing usable was recorded (stopped too fast etc). */
    fun stop(r: MediaRecorder?): Boolean {
        PanicVoice.resumeAfterMicLater?.let { PanicVoice.resumeAfterMic(it) }
        PanicVoice.resumeAfterMicLater = null
        if (r == null) return false
        val ok = try { r.stop(); true } catch (_: Throwable) { false }
        try { r.release() } catch (_: Throwable) {}
        return ok
    }
}
