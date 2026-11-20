package com.silvershireinn.app.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Always listening for the panic phrases.
 *
 *  - Typed: every text box in the app is watched ([attach] on each screen, new boxes included).
 *  - Spoken (setting "Listen out loud"): speech recognition runs in a loop while the app is
 *    open, on the lock screen (listening for the UNLOCK phrase), and, if allowed, in the
 *    background. On-device recognition is used when the phone has it (nothing leaves the
 *    phone); otherwise Android's recognizer, which may use Google's online service.
 * It pauses during calls, voice-message recording and the camera, which need the microphone.
 */
object PanicVoice {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var running = false
    private var restartPending = false
    @Volatile var micBusy = 0            // >0 while a call / recording / camera has the microphone
    @Volatile var lockScreenOpen = false
    @Volatile private var checking = false
    @Volatile var resumeAfterMicLater: Context? = null   // set by VoiceRecorder while recording

    fun hasMicPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun onDevice(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && try { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) } catch (_: Throwable) { false }

    private fun callActive(): Boolean = try {
        com.silvershireinn.app.network.CallManager.state != com.silvershireinn.app.network.CallManager.State.IDLE ||
            com.silvershireinn.app.network.VideoCallManager.isBusy()
    } catch (_: Throwable) { false }

    private fun shouldRun(ctx: Context): Boolean {
        if (!PanicLock.listenOutLoud || !PanicLock.isSet() || !hasMicPermission(ctx)) return false
        if (micBusy > 0 || callActive()) return false
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return false
        return PanicLock.locked && lockScreenOpen ||
               com.silvershireinn.app.SilvershireApp.isInUse ||
               PanicLock.listenInBackground
    }

    /** Start / stop to match the current situation. Safe to call often, from any thread. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            if (shouldRun(app)) { if (!running) start(app) } else stop()
        }
    }

    fun pauseForMic() { micBusy++; main.post { stop() } }
    fun resumeAfterMic(ctx: Context) { micBusy = (micBusy - 1).coerceAtLeast(0); refresh(ctx) }

    private fun start(ctx: Context) {
        try {
            val r = if (onDevice(ctx) && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                    else SpeechRecognizer.createSpeechRecognizer(ctx)
            r.setRecognitionListener(listener(ctx))
            rec = r; running = true
            listen()
        } catch (e: Throwable) { android.util.Log.w("PanicVoice", "can't listen: ${e.message}"); running = false }
    }

    private fun listen() {
        val r = rec ?: return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, "com.silvershireinn.app")
        }
        try { r.startListening(i) } catch (_: Throwable) { scheduleRestart(1500) }
    }

    private fun scheduleRestart(ms: Long) {
        if (restartPending || !running) return
        restartPending = true
        main.postDelayed({
            restartPending = false
            val app = com.silvershireinn.app.SilvershireApp.instance
            if (!shouldRun(app)) { stop(); return@postDelayed }
            listen()
        }, ms)
    }

    fun stop() {
        running = false
        try { rec?.cancel() } catch (_: Throwable) {}
        try { rec?.destroy() } catch (_: Throwable) {}
        rec = null
    }

    private fun handle(ctx: Context, texts: List<String>, final: Boolean) {
        if (texts.isEmpty()) return
        if (PanicLock.locked) {
            if (!final || checking) return
            checking = true
            com.silvershireinn.app.SilvershireApp.appScope.launch(Dispatchers.Default) {
                try {
                    val proofs = texts.flatMap { PanicLock.unlockProofs(it) }.distinct().take(16)
                    if (proofs.isNotEmpty()) PanicLock.requestUnlock(proofs)
                } finally { checking = false }
            }
        } else if (texts.any { PanicLock.heardLockPhrase(it) }) {
            main.post { PanicLock.lockNow(ctx) }
        }
    }

    private fun listener(ctx: Context) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            handle(ctx, partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(), false)
        }
        override fun onResults(results: Bundle?) {
            handle(ctx, results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(), true)
            scheduleRestart(250)
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stop()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(2000)
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(250)
                else -> scheduleRestart(1500)
            }
        }
    }

    // ── typed: watch every text box ──

    private val watched = java.util.WeakHashMap<EditText, Boolean>()

    /** Watch every text box on this screen, including ones added later. */
    fun attach(activity: Activity) {
        if (activity is com.silvershireinn.app.ui.lock.PanicLockActivity) return
        val root = activity.window?.decorView ?: return
        scan(activity, root)
        if (root.getTag(com.silvershireinn.app.R.id.panic_watch_tag) == null) {
            root.setTag(com.silvershireinn.app.R.id.panic_watch_tag, true)
            root.viewTreeObserver.addOnGlobalLayoutListener { scan(activity, root) }
        }
    }

    private fun scan(activity: Activity, v: View) {
        if (v is EditText) {
            if (watched.containsKey(v)) return
            watched[v] = true
            v.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (PanicLock.matchesLockWord(s)) {
                        v.post { try { v.setText("") } catch (_: Throwable) {}; PanicLock.lockNow(activity) }
                    }
                }
            })
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) scan(activity, v.getChildAt(i))
        }
    }
}
