package com.silvershireinn.app.ui.call

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.silvershireinn.app.R
import com.silvershireinn.app.databinding.ActivityCallBinding
import com.silvershireinn.app.network.CallManager
import com.silvershireinn.app.util.HapticUtil

class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding

    private val timerHandler  = Handler(Looper.getMainLooper())
    private var timerSeconds  = 0
    private var timerRunning  = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (CallManager.isConnected) timerSeconds++   // count talk time only
            showStatus()
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ── Incoming call vibration (repeating pattern: vibrate 1s, pause 1s) ─────
    private var vibrator: Vibrator? = null
    private val incomingVibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)

    // ── Outgoing call tone (deep beep every 3 seconds, like a ringback) ────────
    private var toneGenerator: ToneGenerator? = null
    private val toneHandler = Handler(Looper.getMainLooper())
    private val toneRunnable = object : Runnable {
        override fun run() {
            try { toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1200) } catch (_: Exception) {}
            toneHandler.postDelayed(this, 3000L)
        }
    }

    companion object {
        private const val EXTRA_REMOTE_USER  = "remote_user"
        private const val EXTRA_IS_INCOMING  = "is_incoming"

        /** Called when the local user taps the call button in ChatActivity */
        fun outgoingIntent(context: Context, remoteUser: String): Intent =
            Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_REMOTE_USER, remoteUser)
                putExtra(EXTRA_IS_INCOMING, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        /** Called by WebSocketService when a call_offer arrives */
        fun incomingIntent(context: Context, remoteUser: String): Intent =
            Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_REMOTE_USER, remoteUser)
                putExtra(EXTRA_IS_INCOMING, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Show over lock screen so incoming calls wake the device
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }

        // Init tone generator (medium volume on voice call stream)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
        } catch (_: Exception) {}

        val remoteUser = intent.getStringExtra(EXTRA_REMOTE_USER) ?: CallManager.remoteUser
        @Suppress("UNUSED_VARIABLE") val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)

        // Name they're saved as (it showed the raw username)
        binding.tvCallName.text      = com.silvershireinn.app.utils.PreferenceManager.displayNameFor(remoteUser)
        binding.tvAvatarInitial.text = binding.tvCallName.text.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        showCallAvatar(remoteUser)

        // Wire CallManager state listener (kept so onDestroy only clears OUR listener)
        val stateL: (CallManager.State) -> Unit = { state -> runOnUiThread { applyState(state, remoteUser) } }
        val statusL: () -> Unit = { runOnUiThread { showStatus(); refreshScreenUi() } }
        myStateListener = stateL; myStatusListener = statusL
        CallManager.onStateChanged = stateL
        CallManager.onStatusChanged = statusL
        com.silvershireinn.app.notifications.NotificationHelper.cancelCallNotification(this)

        // Apply initial state
        applyState(CallManager.state, remoteUser)

        // Incoming-call buttons
        binding.btnAcceptCall.setOnClickListener {
            HapticUtil.vibrate(this)
            accept()
        }
        binding.btnRejectCall.setOnClickListener {
            HapticUtil.vibrate(this)
            stopIncomingVibration()
            CallManager.rejectCall()
            finish()
        }
        // Outgoing call: make sure we can use the microphone (otherwise the other person
        // heard nothing and nobody knew why).
        if (CallManager.state == CallManager.State.OUTGOING) ensureMic {}
        handleAutoActions(intent)

        // Active-call controls
        binding.btnEndCall.setOnClickListener {
            HapticUtil.vibrate(this)
            stopIncomingVibration()
            stopCallerTone()
            CallManager.endCall()
            finish()
        }
        binding.btnMute.setOnClickListener {
            HapticUtil.vibrate(this)
            CallManager.toggleMute()
            updateMuteUI()
        }
        binding.btnSpeaker.setOnClickListener {
            HapticUtil.vibrate(this)
            CallManager.toggleSpeaker(this)
            acquireProximity()
            updateSpeakerUI()
        }

        // Back arrow — minimise back to app without ending the call
        binding.btnCallBack.setOnClickListener { HapticUtil.vibrate(this); finish() }
        setupScreenShare()
    }

    // ── Share screen (works in voice calls too: the picture is added to the call) ──
    private var screenBtn: com.google.android.material.button.MaterialButton? = null
    private var screenView: org.webrtc.SurfaceViewRenderer? = null
    private var screenBox: android.widget.FrameLayout? = null
    private var screenViewReady = false
    private var screenHidden = false            // "Back to call" pressed: hidden until they share again
    private val screenLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        if (!com.silvershireinn.app.network.WebSocketService.setScreenShare(true)) {
            android.widget.Toast.makeText(this, "Screen sharing isn't allowed on this phone", android.widget.Toast.LENGTH_LONG).show(); return@registerForActivityResult
        }
        if (!com.silvershireinn.app.network.RtcCall.startScreenShare(this, data)) {
            com.silvershireinn.app.network.WebSocketService.setScreenShare(false)
            android.widget.Toast.makeText(this, "Couldn't share the screen", android.widget.Toast.LENGTH_LONG).show()
        }
        refreshScreenUi()
    }

    private fun setupScreenShare() {
        val root = binding.root as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        val d = resources.displayMetrics.density
        val gold = 0xFFC9A84C.toInt()
        val b = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Share screen"; isAllCaps = false
            setTextColor(gold)
            strokeColor = android.content.res.ColorStateList.valueOf(gold)
            icon = androidx.core.content.ContextCompat.getDrawable(this@CallActivity, R.drawable.ic_screen_share)
            iconTint = android.content.res.ColorStateList.valueOf(gold)
            visibility = View.GONE
            setOnClickListener {
                HapticUtil.vibrate(this@CallActivity)
                if (com.silvershireinn.app.network.RtcCall.sharingScreen) { com.silvershireinn.app.network.RtcCall.stopScreenShare(); refreshScreenUi(); return@setOnClickListener }
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager ?: return@setOnClickListener
                try { screenLauncher.launch(mpm.createScreenCaptureIntent()) } catch (_: Exception) {}
            }
        }
        root.addView(b, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(-2, -2).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = (6 * d).toInt(); marginEnd = (12 * d).toInt()
        })
        screenBtn = b
        // Their shared screen, over the whole call screen
        val box = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK); visibility = View.GONE; elevation = 30f }
        val sv = org.webrtc.SurfaceViewRenderer(this)
        box.addView(sv, android.widget.FrameLayout.LayoutParams(-1, -1))
        val back = com.google.android.material.button.MaterialButton(this).apply {
            text = "Back to call"; isAllCaps = false
            setOnClickListener { screenHidden = true; refreshScreenUi() }
        }
        box.addView(back, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (36 * d).toInt() })
        root.addView(box, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, 0).apply {
            val P = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = P; bottomToBottom = P; startToStart = P; endToEnd = P
        })
        screenBox = box; screenView = sv
        com.silvershireinn.app.network.RtcCall.onScreenShareChanged = { runOnUiThread { refreshScreenUi() } }
        refreshScreenUi()
    }

    private fun refreshScreenUi() {
        val rtc = com.silvershireinn.app.network.RtcCall
        val live = CallManager.state == CallManager.State.ACTIVE && rtc.isActive
        screenBtn?.visibility = if (live) View.VISIBLE else View.GONE
        screenBtn?.text = if (rtc.sharingScreen) "Stop sharing" else "Share screen"
        if (!rtc.remoteSharing) screenHidden = false
        val sv = screenView ?: return
        val show = live && rtc.remoteSharing && !screenHidden
        if (show) {
            if (!screenViewReady) {
                val egl = rtc.eglContext ?: return
                try { sv.init(egl, null); sv.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT); screenViewReady = true } catch (_: Throwable) { return }
            }
            rtc.remoteSink.target = sv
            screenBox?.visibility = View.VISIBLE
        } else {
            if (rtc.remoteSink.target === sv) rtc.remoteSink.target = null
            screenBox?.visibility = View.GONE
        }
    }

    private var myStateListener: ((CallManager.State) -> Unit)? = null
    private var myStatusListener: (() -> Unit)? = null
    private var pendingMicAction: (() -> Unit)? = null
    private val micPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val a = pendingMicAction; pendingMicAction = null
        if (granted) a?.invoke()
        else android.widget.Toast.makeText(this, "Allow the microphone so they can hear you", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun ensureMic(then: () -> Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) then()
        else { pendingMicAction = then; micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
    }

    private fun accept() {
        stopIncomingVibration()
        ensureMic { CallManager.acceptCall() }
    }

    /** Accept / Decline pressed in the incoming-call notification (they only opened this screen). */
    private fun handleAutoActions(i: Intent) {
        when {
            i.getBooleanExtra("auto_reject", false) && CallManager.state == CallManager.State.INCOMING -> {
                stopIncomingVibration(); CallManager.rejectCall(); finish()
            }
            i.getBooleanExtra("auto_accept", false) && CallManager.state == CallManager.State.INCOMING -> accept()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoActions(intent)
    }

    private fun applyState(state: CallManager.State, remoteUser: String) {
        when (state) {
            CallManager.State.IDLE -> {
                stopTimer()
                stopIncomingVibration()
                stopCallerTone()
                finish()
            }
            CallManager.State.OUTGOING -> {
                binding.tvCallStatus.text = "Calling…"
                binding.layoutIncomingButtons.visibility = View.GONE
                startCallerTone()
            }
            CallManager.State.INCOMING -> {
                binding.tvCallStatus.text = "Incoming call"
                binding.layoutIncomingButtons.visibility = View.VISIBLE
                startIncomingVibration()
            }
            CallManager.State.ACTIVE -> {
                binding.layoutIncomingButtons.visibility = View.GONE
                stopIncomingVibration()
                stopCallerTone()
                startTimer()
                acquireProximity()
            }
        }
        showStatus()
    }

    /** "Connecting..." / "Reconnecting..." until audio actually flows, then the timer. */
    private fun showStatus() {
        if (CallManager.state != CallManager.State.ACTIVE) return
        binding.tvCallStatus.text = when {
            CallManager.statusText.isNotBlank() -> CallManager.statusText
            CallManager.isConnected -> formatTimer(CallManager.callDurationSecs())
            else -> "Connecting..."
        }
    }

    // Screen turns off when the phone is held to your ear (earpiece only).
    private var proximityLock: android.os.PowerManager.WakeLock? = null
    private fun acquireProximity() {
        try {
            if (proximityLock == null) {
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                if (pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                    proximityLock = pm.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "silvershire:call")
            }
            if (!CallManager.isSpeaker) { if (proximityLock?.isHeld == false) proximityLock?.acquire(3 * 60 * 60 * 1000L) }
            else releaseProximity()
        } catch (_: Throwable) {}
    }
    private fun releaseProximity() { try { if (proximityLock?.isHeld == true) proximityLock?.release() } catch (_: Throwable) {} }

    // ── Incoming call vibration ───────────────────────────────────────────────

    private var ringtone: android.media.Ringtone? = null

    /**
     * Incoming call: ring (the voice call screen only vibrated, so a call was easy to miss)
     * and vibrate, following the phone's ringer mode (silent = nothing, vibrate = buzz only).
     */
    @Suppress("DEPRECATION")
    private fun startIncomingVibration() {
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) try {
            if (ringtone == null) ringtone = android.media.RingtoneManager.getRingtone(this,
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = true
            if (ringtone?.isPlaying != true) ringtone?.play()
        } catch (_: Exception) {}
        if (mode == AudioManager.RINGER_MODE_SILENT) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(incomingVibrationPattern, 0) // 0 = repeat from index 0
                )
            } else {
                vibrator?.vibrate(incomingVibrationPattern, 0) // 0 = repeat from index 0
            }
        } catch (_: Exception) {}
    }

    private fun stopIncomingVibration() {
        try { vibrator?.cancel() } catch (_: Exception) {}
        try { ringtone?.stop() } catch (_: Exception) {}
    }

    // ── Outgoing call ringback tone ───────────────────────────────────────────

    private fun startCallerTone() {
        toneHandler.removeCallbacks(toneRunnable)
        toneRunnable.run()   // play first beep immediately, then repeat every 3s
    }

    private fun stopCallerTone() {
        toneHandler.removeCallbacks(toneRunnable)
        try { toneGenerator?.stopTone() } catch (_: Exception) {}
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        if (timerRunning) return
        timerSeconds = 0
        timerRunning = true
        timerHandler.postDelayed(timerRunnable, 1000)
    }

    private fun stopTimer() {
        timerRunning = false
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun formatTimer(secs: Int): String {
        val m = secs / 60
        val s = secs % 60
        return "%d:%02d".format(m, s)
    }

    // ── Button state refresh ──────────────────────────────────────────────────

    private fun updateMuteUI() {
        val muted = CallManager.isMuted
        binding.ivMuteIcon.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        binding.tvMuteLabel.text = if (muted) "Unmute" else "Mute"
        binding.btnMute.setCardBackgroundColor(
            if (muted) 0xFF3A2E08.toInt() else 0xFF2A1E08.toInt()
        )
    }

    private fun updateSpeakerUI() {
        val speaker = CallManager.isSpeaker
        binding.tvSpeakerLabel.text = if (speaker) "Earpiece" else "Speaker"
        binding.btnSpeaker.setCardBackgroundColor(
            if (speaker) 0xFF3A2E08.toInt() else 0xFF2A1E08.toInt()
        )
    }

    override fun onResume() {
        super.onResume()
        // Sync UI in case user navigated back and state changed
        applyState(CallManager.state, intent.getStringExtra(EXTRA_REMOTE_USER) ?: "")
        updateMuteUI()
        updateSpeakerUI()
    }

    override fun onDestroy() {
        com.silvershireinn.app.network.RtcCall.onScreenShareChanged = null
        screenView?.let { sv -> if (com.silvershireinn.app.network.RtcCall.remoteSink.target === sv) com.silvershireinn.app.network.RtcCall.remoteSink.target = null
            if (screenViewReady) try { sv.release() } catch (_: Throwable) {} }
        stopTimer()
        stopIncomingVibration()
        stopCallerTone()
        toneGenerator?.release()
        toneGenerator = null
        releaseProximity()
        // Only clear the listeners if they are still ours (a newer call screen may have set its own).
        if (CallManager.onStatusChanged === myStatusListener) CallManager.onStatusChanged = null
        if (CallManager.onStateChanged === myStateListener) CallManager.onStateChanged = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Back minimises; call continues until End is pressed
        finish()
    }

    /** Their profile picture (still frame for video pictures) in their shape, with their border. */
    private fun showCallAvatar(user: String?) {
        if (user.isNullOrBlank()) return
        val dm = resources.displayMetrics.density
        val shape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(user)
        val a = 120 * dm
        binding.cardAvatar.radius = when (shape) {
            com.silvershireinn.app.utils.AvatarShape.CIRCLE -> a / 2
            com.silvershireinn.app.utils.AvatarShape.ROUNDED_SQUARE -> a * 0.22f
            com.silvershireinn.app.utils.AvatarShape.SQUIRCLE -> a * 0.34f
            com.silvershireinn.app.utils.AvatarShape.DIAMOND -> if (Build.VERSION.SDK_INT >= 30) 0f else a / 2
        }
        if (shape == com.silvershireinn.app.utils.AvatarShape.DIAMOND) {
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(binding.cardAvatar, shape)
            binding.cardAvatar.strokeWidth = 0
        }
        val f = com.silvershireinn.app.utils.ProfileMediaHelper.contactPic(this, user)
        if (f != null && f.exists()) {
            val path = f.absolutePath
            val still = if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(path))
                com.silvershireinn.app.utils.ProfileMediaHelper.cachedVideoFrame(path) else com.silvershireinn.app.utils.VideoThumbs.decodeSmall(path, 512)
            if (still != null) {
                binding.ivCallAvatar.setImageBitmap(still); binding.ivCallAvatar.visibility = View.VISIBLE
            } else if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(path)) {
                Thread {
                    val b = com.silvershireinn.app.utils.ProfileMediaHelper.videoFrameBitmap(path) ?: return@Thread
                    runOnUiThread { if (!isFinishing) { binding.ivCallAvatar.setImageBitmap(b); binding.ivCallAvatar.visibility = View.VISIBLE } }
                }.start()
            }
        }
        // Their border; the name moves down so the border never covers it
        val framed = com.silvershireinn.app.utils.ProfileFrames.applyFull(binding.cardAvatar, user)
        if (framed) {
            binding.cardAvatar.strokeWidth = 0
            (binding.tvCallName.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = (lp.topMargin + 60 * dm).toInt(); binding.tvCallName.layoutParams = lp
            }
        }
    }
}
