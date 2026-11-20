package com.silvershireinn.app.ui.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.silvershireinn.app.R
import com.silvershireinn.app.network.RtcCall
import com.silvershireinn.app.network.VideoCallManager
import com.silvershireinn.app.notifications.NotificationHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.toAvatarColor
import com.silvershireinn.app.utils.toInitials
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Video call screen on top of [VideoCallManager] / [RtcCall]. */
class VideoCallActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_REMOTE_USER = "remote_user"
        private const val EXTRA_IS_INCOMING = "is_incoming"

        fun outgoingIntent(context: Context, remoteUser: String): Intent =
            Intent(context, VideoCallActivity::class.java).apply {
                putExtra(EXTRA_REMOTE_USER, remoteUser)
                putExtra(EXTRA_IS_INCOMING, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        fun incomingIntent(context: Context, remoteUser: String): Intent =
            Intent(context, VideoCallActivity::class.java).apply {
                putExtra(EXTRA_REMOTE_USER, remoteUser)
                putExtra(EXTRA_IS_INCOMING, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }

    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var localFrame: FrameLayout
    private lateinit var placeholder: LinearLayout
    private lateinit var tvName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var activeBar: LinearLayout
    private lateinit var incomingBar: LinearLayout
    private lateinit var btnMute: ImageButton
    private lateinit var btnCam: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private var renderersReady = false
    private var swapped = false   // true = my video big, theirs small
    private var controlsVisible = true
    private var remoteUser = ""
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateStatus(); ui.postDelayed(this, 1000) }
    }
    private val autoHide = Runnable { if (VideoCallManager.isConnected) setControls(false) }
    private var pendingAction: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingAction?.invoke(); pendingAction = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        remoteUser = intent.getStringExtra(EXTRA_REMOTE_USER) ?: VideoCallManager.remoteUser
        buildUi()
        RtcCall.prepare(this)
        initRenderers()

        val stateL: (VideoCallManager.State) -> Unit = { s -> runOnUiThread { applyState(s) } }
        val statusL: () -> Unit = { runOnUiThread { updateStatus(); updatePlaceholder() } }
        myStateL = stateL; myStatusL = statusL
        VideoCallManager.onStateChanged = stateL
        VideoCallManager.onStatusChanged = statusL
        NotificationHelper.cancelCallNotification(this)
        applyState(VideoCallManager.state)
        handleAutoActions(intent)
        ui.post(tick)
    }

    private var myStateL: ((VideoCallManager.State) -> Unit)? = null
    private var myStatusL: (() -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoActions(intent)
    }

    private fun handleAutoActions(i: Intent) {
        when {
            i.getBooleanExtra("auto_reject", false) && VideoCallManager.state == VideoCallManager.State.INCOMING -> { stopRinging(); VideoCallManager.rejectCall(); finish() }
            i.getBooleanExtra("auto_accept", false) && VideoCallManager.state == VideoCallManager.State.INCOMING -> accept()
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        remoteView = SurfaceViewRenderer(this)
        root.addView(remoteView, FrameLayout.LayoutParams(-1, -1))

        placeholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val av = TextView(context).apply {
                text = PreferenceManager.displayNameFor(remoteUser).toInitials()
                textSize = 36f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(remoteUser.toAvatarColor()) }
            }
            addView(av, LinearLayout.LayoutParams(dp(120), dp(120)))
            addView(TextView(context).apply { tag = "camoff"; setTextColor(0xCCFFFFFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, 0) })
        }
        root.addView(placeholder, FrameLayout.LayoutParams(-1, -1))

        localFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF222222.toInt()) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = dp(6).toFloat()
        }
        localView = SurfaceViewRenderer(this)
        localFrame.addView(localView, FrameLayout.LayoutParams(-1, -1))
        root.addView(localFrame, FrameLayout.LayoutParams(dp(108), dp(160), Gravity.TOP or Gravity.END).apply { topMargin = dp(90); rightMargin = dp(14) })
        makeDraggable(localFrame)

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0))
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        tvName = TextView(this).apply { text = PreferenceManager.displayNameFor(remoteUser); setTextColor(Color.WHITE); textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        tvStatus = TextView(this).apply { setTextColor(0xDDFFFFFF.toInt()); textSize = 14f }
        topBar.addView(tvName); topBar.addView(tvStatus)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        activeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
            setPadding(dp(8), dp(28), dp(8), dp(24))
        }
        btnMute = roundBtn(R.drawable.ic_mic, 0x44FFFFFF) { VideoCallManager.toggleMute(); refreshButtons() }
        btnCam = roundBtn(R.drawable.ic_video_call, 0x44FFFFFF) { VideoCallManager.toggleCamera(); refreshButtons(); updateLocalVisibility() }
        val btnFlip = roundBtn(R.drawable.ic_flip_camera, 0x44FFFFFF) { VideoCallManager.flipCamera(); ui.postDelayed({ localView.setMirror(RtcCall.frontCamera) }, 400) }
        btnSpeaker = roundBtn(R.drawable.ic_speaker, 0x44FFFFFF) { VideoCallManager.toggleSpeaker(); refreshButtons() }
        val btnEnd = roundBtn(R.drawable.ic_call_end, 0xFFE53935.toInt()) { VideoCallManager.endCall(); finish() }
        listOf(btnMute, btnCam, btnFlip, btnSpeaker, btnEnd).forEach {
            activeBar.addView(it, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(7); marginEnd = dp(7) })
        }
        root.addView(activeBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        incomingBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), dp(48))
        }
        val decline = roundBtn(R.drawable.ic_call_end, 0xFFE53935.toInt()) { VideoCallManager.rejectCall(); finish() }
        val acceptBtn = roundBtn(R.drawable.ic_video_call, 0xFF34C759.toInt()) { accept() }
        incomingBar.addView(decline, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(64) })
        incomingBar.addView(acceptBtn, LinearLayout.LayoutParams(dp(72), dp(72)))
        root.addView(incomingBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        root.setOnClickListener { setControls(!controlsVisible) }
        remoteView.setOnClickListener { setControls(!controlsVisible) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(dp(16) + b.left, dp(12) + b.top, dp(16) + b.right, dp(24))
            activeBar.setPadding(dp(8) + b.left, dp(28), dp(8) + b.right, dp(20) + b.bottom)
            incomingBar.setPadding(dp(8), dp(24), dp(8), dp(40) + b.bottom)
            insets
        }
    }

    private fun roundBtn(icon: Int, color: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setColorFilter(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        setPadding(dp(14), dp(14), dp(14), dp(14))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setOnClickListener { onClick(); bumpControls() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(v: View) {
        var dX = 0f; var dY = 0f; var downX = 0f; var downY = 0f; var moved = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dX = view.x - e.rawX; dY = view.y - e.rawY; downX = e.rawX; downY = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - downX) + Math.abs(e.rawY - downY) > dp(8)) moved = true
                    val p = view.parent as View
                    view.x = (e.rawX + dX).coerceIn(0f, (p.width - view.width).toFloat())
                    view.y = (e.rawY + dY).coerceIn(0f, (p.height - view.height).toFloat())
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) swapViews()
                    else {   // snap to the nearest side
                        val p = view.parent as View
                        val toLeft = view.x + view.width / 2 < p.width / 2
                        view.animate().x(if (toLeft) dp(14).toFloat() else (p.width - view.width - dp(14)).toFloat()).setDuration(180).start()
                    }
                }
            }
            true
        }
    }

    /** Tap the small window: swap who is big and who is small. */
    private fun swapViews() {
        swapped = !swapped
        RtcCall.remoteSink.target = if (swapped) localView else remoteView
        RtcCall.localSink.target = if (swapped) remoteView else localView
        remoteView.setMirror(swapped && RtcCall.frontCamera)
        localView.setMirror(!swapped && RtcCall.frontCamera)
        updatePlaceholder()
    }

    private fun initRenderers() {
        val ctx = RtcCall.eglContext ?: return
        try {
            remoteView.init(ctx, null)
            remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL, RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            remoteView.setEnableHardwareScaler(true)
            localView.init(ctx, null)
            localView.setZOrderMediaOverlay(true)
            localView.setMirror(true)
            localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            renderersReady = true
            RtcCall.remoteSink.target = remoteView
            RtcCall.localSink.target = localView
        } catch (_: Throwable) {}
    }

    private fun setControls(show: Boolean) {
        controlsVisible = show
        val a = if (show) 1f else 0f
        topBar.animate().alpha(a).setDuration(200).start()
        if (VideoCallManager.state == VideoCallManager.State.ACTIVE) activeBar.animate().alpha(a).setDuration(200).withEndAction {
            activeBar.visibility = if (show) View.VISIBLE else View.INVISIBLE
        }.withStartAction { if (show) activeBar.visibility = View.VISIBLE }.start()
        if (show) bumpControls()
    }

    private fun bumpControls() { ui.removeCallbacks(autoHide); ui.postDelayed(autoHide, 5000) }

    private fun refreshButtons() {
        btnMute.setImageResource(if (VideoCallManager.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        btnMute.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (VideoCallManager.isMuted) Color.WHITE else 0x44FFFFFF) }
        btnMute.setColorFilter(if (VideoCallManager.isMuted) Color.BLACK else Color.WHITE)
        btnCam.alpha = if (VideoCallManager.cameraOn) 1f else 0.5f
        btnSpeaker.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (VideoCallManager.isSpeaker) Color.WHITE else 0x44FFFFFF) }
        btnSpeaker.setColorFilter(if (VideoCallManager.isSpeaker) Color.BLACK else Color.WHITE)
    }

    private fun updateLocalVisibility() {
        localFrame.visibility = if (VideoCallManager.cameraOn) View.VISIBLE else View.INVISIBLE
    }

    private fun updatePlaceholder() {
        val remoteOn = VideoCallManager.remoteVideoOn && VideoCallManager.isConnected
        placeholder.visibility = if (!remoteOn && !swapped) View.VISIBLE else View.GONE
        placeholder.findViewWithTag<TextView>("camoff")?.text =
            if (VideoCallManager.isConnected && !VideoCallManager.remoteVideoOn) "Camera is off" else ""
    }

    private fun updateStatus() {
        val st = VideoCallManager.state
        tvStatus.text = when {
            st == VideoCallManager.State.INCOMING -> "Incoming video call"
            st == VideoCallManager.State.OUTGOING -> "Ringing..."
            VideoCallManager.statusText.isNotBlank() -> VideoCallManager.statusText
            VideoCallManager.isConnected -> { val s = VideoCallManager.callDurationSecs(); "%d:%02d".format(s / 60, s % 60) }
            else -> "Connecting..."
        }
    }

    private fun applyState(s: VideoCallManager.State) {
        when (s) {
            VideoCallManager.State.INCOMING -> {
                incomingBar.visibility = View.VISIBLE; activeBar.visibility = View.GONE
                startRinging()
            }
            VideoCallManager.State.OUTGOING -> {
                incomingBar.visibility = View.GONE; activeBar.visibility = View.VISIBLE
                ensurePermissions { }   // camera / mic for the self view as soon as possible
                startRingback()
            }
            VideoCallManager.State.ACTIVE -> {
                stopRinging()
                incomingBar.visibility = View.GONE; activeBar.visibility = View.VISIBLE
                bumpControls()
            }
            VideoCallManager.State.IDLE -> { stopRinging(); finish() }
        }
        refreshButtons(); updateStatus(); updatePlaceholder(); updateLocalVisibility()
    }

    private fun accept() {
        stopRinging()
        ensurePermissions { VideoCallManager.acceptCall() }
    }

    private fun ensurePermissions(then: () -> Unit) {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) then() else { pendingAction = then; permLauncher.launch(need.toTypedArray()) }
    }

    // ── Sounds ────────────────────────────────────────────────────────────────
    private fun startRinging() {
        // Follow the phone's ringer mode (it rang / buzzed even on silent).
        val mode = (getSystemService(AUDIO_SERVICE) as? AudioManager)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) try {
            if (ringtone == null) ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = true
            if (ringtone?.isPlaying != true) ringtone?.play()
        } catch (_: Throwable) {}
        if (mode == AudioManager.RINGER_MODE_SILENT) return
        try {
            vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                       else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (_: Throwable) {}
    }

    private fun startRingback() {
        try { if (tone == null) tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60); tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE) } catch (_: Throwable) {}
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Throwable) {}
        try { vibrator?.cancel() } catch (_: Throwable) {}
        try { tone?.stopTone() } catch (_: Throwable) {}
    }

    // ── Picture in picture ────────────────────────────────────────────────────
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (VideoCallManager.state == VideoCallManager.State.ACTIVE && Build.VERSION.SDK_INT >= 26) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()) } catch (_: Throwable) {}
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        val v = if (inPip) View.GONE else View.VISIBLE
        topBar.visibility = v; localFrame.visibility = if (inPip) View.GONE else if (VideoCallManager.cameraOn) View.VISIBLE else View.INVISIBLE
        activeBar.visibility = if (inPip || VideoCallManager.state != VideoCallManager.State.ACTIVE) View.GONE else View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back keeps the call going in a small window instead of hanging up.
        if (VideoCallManager.state == VideoCallManager.State.ACTIVE && Build.VERSION.SDK_INT >= 26) onUserLeaveHint()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopRinging()
        try { tone?.release() } catch (_: Throwable) {}
        if (RtcCall.remoteSink.target === remoteView || RtcCall.remoteSink.target === localView) RtcCall.remoteSink.target = null
        if (RtcCall.localSink.target === localView || RtcCall.localSink.target === remoteView) RtcCall.localSink.target = null
        if (renderersReady) { try { remoteView.release() } catch (_: Throwable) {}; try { localView.release() } catch (_: Throwable) {} }
        // Only clear listeners that are still ours (a newer call screen may have set its own).
        if (VideoCallManager.onStateChanged === myStateL) VideoCallManager.onStateChanged = null
        if (VideoCallManager.onStatusChanged === myStatusL) VideoCallManager.onStatusChanged = null
        // Leaving the screen any other way while ringing = decline.
        if (isFinishing && VideoCallManager.state == VideoCallManager.State.INCOMING) VideoCallManager.rejectCall()
        super.onDestroy()
    }
}
