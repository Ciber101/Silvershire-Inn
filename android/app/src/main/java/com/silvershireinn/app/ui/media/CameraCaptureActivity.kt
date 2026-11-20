package com.silvershireinn.app.ui.media

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import com.silvershireinn.app.R
import com.silvershireinn.app.utils.toast
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Full camera: max-quality photos (full sensor, JPEG 100, never re-compressed), up to 4K/60
 * video, flash/torch, front "screen flash", self timer, grid, 4:3 / 16:9 / 1:1, zoom presets,
 * tap-to-focus, long-press AE/AF lock, pinch zoom, double-tap flip, swipe to change mode,
 * volume-key shutter, Night / HDR / Portrait / Retouch where the phone supports them,
 * video pause/resume, stabilization, mute, mirror selfie, gallery shortcut, and a review
 * screen (retake / edit / send) before anything is sent.
 *
 * Only the use cases the current mode needs are bound (photo OR video) - binding both at
 * once is what failed on cheaper phones. Every bind has a fallback ladder ending in the
 * system camera, so the camera can always be used.
 */
class CameraCaptureActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var overlay: FrameLayout
    private lateinit var flashOverlay: View
    private lateinit var tvCountdown: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvEv: TextView
    private lateinit var seekEv: SeekBar
    private lateinit var settingsPanel: View
    private lateinit var tvRecTimer: TextView
    private lateinit var rowRecInfo: View
    private lateinit var btnShutter: ImageButton
    private lateinit var btnModePhoto: TextView
    private lateinit var btnModeVideo: TextView
    private lateinit var btnFlash: ImageButton
    private lateinit var btnTimer: ImageButton
    private lateinit var tvTimerBadge: TextView
    private lateinit var btnGrid: ImageButton
    private lateinit var btnAspect: TextView
    private lateinit var btnGallery: ImageButton
    private lateinit var btnRecPause: ImageButton
    private lateinit var btnFlip: ImageButton
    private lateinit var rowZoom: LinearLayout
    private lateinit var rowModes: View
    private lateinit var reviewLayer: View
    private lateinit var ivReview: ImageView
    private lateinit var reviewPlayerView: PlayerView
    private lateinit var tvReviewInfo: TextView
    private var gridView: GridOverlay? = null
    private var focusRing: View? = null

    // ── Camera state ──────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingPaused = false
    private lateinit var cameraExecutor: ExecutorService
    private val ui = Handler(Looper.getMainLooper())
    private var recTick: Runnable? = null
    private var recordingStart = 0L
    private var pausedTotalMs = 0L
    private var pausedAt = 0L
    private var countdownLeft = 0
    private var countdownRunnable: Runnable? = null
    private var aeAfLocked = false
    private var deviceRotation = Surface.ROTATION_0
    private var orientationListener: OrientationEventListener? = null
    private var shutterSound: MediaActionSound? = null
    private var reviewPlayer: ExoPlayer? = null
    private var reviewFile: File? = null
    private var reviewIsVideo = false
    private var savedBrightness = -1f

    // ── Capabilities read from the current lens ───────────────────────────────
    private var evRange: Range<Int>? = null
    private var evStep = 1f / 6f
    private var isoRange: Range<Int>? = null
    private var awbModes: IntArray = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
    private var fpsRanges: List<Range<Int>> = emptyList()
    private var supportedVideoQualities: List<Quality> = emptyList()
    private var availableExtensions: List<Int> = emptyList()

    // ── User choices (persisted) ──────────────────────────────────────────────
    private val prefs: SharedPreferences by lazy { getSharedPreferences("camera_prefs", MODE_PRIVATE) }
    private var videoMode = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var torchOn = false
    private var timerSec = 0
    private var gridOn = false
    private var aspect = "4:3"            // 4:3 | 16:9 | 1:1
    private var photoRes = "max"          // max | high | medium
    private var videoQuality = "fhd"      // uhd | fhd | hd | sd
    private var videoFps = 30
    private var stabilize = true
    private var mirrorFront = true
    private var muteVideo = false
    private var playShutterSound = true
    private var extMode = ExtensionMode.NONE
    private var awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var evIndex = 0
    private var isoValue = 0
    private var shutterNs = 0L            // 0 = auto exposure time
    private var focusDiopters = -1f       // -1 = autofocus, 0 = infinity, larger = closer
    private var hdrVideo = false
    private var saveToGallery = false
    private var exposureRange: Range<Long>? = null
    private var minFocusDist = 0f
    private var hdrVideoSupported = false

    /** A selectable lens: logical camera id + optional physical id inside it. */
    private data class Lens(val cameraId: String, val physicalId: String?, val facing: Int, val equivMm: Float, var label: String = "")
    private var lenses: List<Lens> = emptyList()
    private var chosenLens: Lens? = null
    private var lastFocusAtMs = 0L
    private var evDragAccum = 0f
    private var nativeCaptureFile: File? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            // With the permission refused the phone's camera app can't be used either (Android
            // blocks it for apps that declare the camera permission), so say why and close.
            toast("Allow the camera in Settings to take photos and videos")
            finish()
        }
    }
    private val systemPhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        onSystemCaptureResult(ok, false)
    }
    private val systemVideo = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        onSystemCaptureResult(ok, true)
    }

    /** Result from the phone's own camera app: review it here like any other capture. */
    private fun onSystemCaptureResult(ok: Boolean, video: Boolean) {
        val f = nativeCaptureFile
        nativeCaptureFile = null
        if (ok && f != null && f.exists() && f.length() > 0) { showReview(f, video); return }
        f?.delete()
        if (camera == null) finish()   // it was the fallback (our camera could not open)
    }
    private var pendingOut: Uri? = null

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) finishOk(uri)
    }
    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val out = r.data?.data
        if (r.resultCode == RESULT_OK && out != null) {
            val path = out.path
            val f = if (out.scheme == "file" && path != null) File(path) else null
            if (f != null && f.exists()) { reviewFile?.takeIf { it != f }?.delete(); showReview(f, false) }
            else finishOk(out)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera_capture)
        cameraExecutor = Executors.newSingleThreadExecutor()
        loadPrefs()
        videoMode = intent.getStringExtra(EXTRA_MODE) == MODE_VIDEO

        previewView   = findViewById(R.id.previewView)
        overlay       = findViewById(R.id.camOverlay)
        flashOverlay  = findViewById(R.id.camFlashOverlay)
        tvCountdown   = findViewById(R.id.tvCountdown)
        tvHint        = findViewById(R.id.tvCamHint)
        tvEv          = findViewById(R.id.tvCamEv)
        seekEv        = findViewById(R.id.seekCamEv)
        settingsPanel = findViewById(R.id.camSettingsPanel)
        tvRecTimer    = findViewById(R.id.tvCamRecTimer)
        rowRecInfo    = findViewById(R.id.rowRecInfo)
        btnShutter    = findViewById(R.id.btnShutter)
        btnModePhoto  = findViewById(R.id.btnModePhoto)
        btnModeVideo  = findViewById(R.id.btnModeVideo)
        btnFlash      = findViewById(R.id.btnFlash)
        btnTimer      = findViewById(R.id.btnTimer)
        tvTimerBadge  = findViewById(R.id.tvTimerBadge)
        btnGrid       = findViewById(R.id.btnGrid)
        btnAspect     = findViewById(R.id.btnAspect)
        btnGallery    = findViewById(R.id.btnCamGallery)
        btnRecPause   = findViewById(R.id.btnRecPause)
        btnFlip       = findViewById(R.id.btnCamFlip)
        rowZoom       = findViewById(R.id.rowZoom)
        rowModes      = findViewById(R.id.rowModes)
        reviewLayer   = findViewById(R.id.reviewLayer)
        ivReview      = findViewById(R.id.ivReview)
        reviewPlayerView = findViewById(R.id.reviewPlayer)
        tvReviewInfo  = findViewById(R.id.tvReviewInfo)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER   // what you see is what you get
        applyInsets()
        findViewById<View>(R.id.btnNativeCam).setOnClickListener { openPhoneCamera() }
        findViewById<View>(R.id.btnLens).setOnClickListener { pickLens() }

        findViewById<View>(R.id.btnCamClose).setOnClickListener { finish() }
        btnFlip.setOnClickListener { flipCamera() }
        findViewById<View>(R.id.btnCamSettings).setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnModePhoto.setOnClickListener { setMode(false) }
        btnModeVideo.setOnClickListener { setMode(true) }
        btnShutter.setOnClickListener { onShutter() }
        btnFlash.setOnClickListener { cycleFlash() }
        btnTimer.setOnClickListener { cycleTimer() }
        btnGrid.setOnClickListener { gridOn = !gridOn; savePrefs(); applyGrid() }
        btnAspect.setOnClickListener { cycleAspect() }
        btnGallery.setOnClickListener {
            try {
                galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            } catch (_: Throwable) { toast("Gallery unavailable") }
        }
        btnRecPause.setOnClickListener { togglePause() }
        findViewById<View>(R.id.btnRetake).setOnClickListener { retake() }
        findViewById<View>(R.id.btnReviewSend).setOnClickListener { reviewFile?.let { finishOk(uriFor(it)) } }
        findViewById<View>(R.id.btnReviewEdit).setOnClickListener {
            val f = reviewFile ?: return@setOnClickListener
            try { editorLauncher.launch(PhotoEditorActivity.intent(this, Uri.fromFile(f))) }
            catch (_: Throwable) { toast("Editor unavailable") }
        }

        gridView = GridOverlay(this).also {
            overlay.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        applyGrid()
        updateTopIcons()
        setupGestures()
        setupOrientation()
        try { shutterSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } } catch (_: Throwable) {}
        setMode(videoMode, rebind = false)
        // Restored after Android closed this screen (low memory) while the phone's camera app
        // was open or a capture was being reviewed: don't lose that photo / video.
        savedInstanceState?.getString("native_file")?.let { nativeCaptureFile = File(it) }
        savedInstanceState?.getString("review_file")?.let { p ->
            val f = File(p)
            if (f.exists()) ui.post { showReview(f, savedInstanceState.getBoolean("review_video")) }
        }

        val need = mutableListOf(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (need.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED })
            permLauncher.launch(need.toTypedArray())
        else bindCamera()
    }

    /** Keep controls clear of the status bar / notch / gesture bar (edge-to-edge screens). */
    private fun applyInsets() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val d = resources.displayMetrics.density
        val top = findViewById<View>(R.id.camTopBar)
        val bottom = findViewById<View>(R.id.camBottomBar)
        val reviewBar = findViewById<View>(R.id.reviewBar)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.camRoot)) { _, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            top.setPadding(b.left, b.top + (4 * d).toInt(), b.right, 0)
            bottom.setPadding(b.left, (8 * d).toInt(), b.right, b.bottom + (14 * d).toInt())
            reviewBar.setPadding(b.left + (14 * d).toInt(), (12 * d).toInt(), b.right + (14 * d).toInt(), b.bottom + (12 * d).toInt())
            (tvReviewInfo.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = b.top + (12 * d).toInt(); tvReviewInfo.layoutParams = it }
            (tvHint.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = b.top + (110 * d).toInt(); tvHint.layoutParams = it }
            insets
        }
        // The review image / video (and the video's own controls) sit ABOVE the Retake / Send bar.
        reviewBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            listOf<View>(ivReview, reviewPlayerView).forEach { pv ->
                val lp = pv.layoutParams as FrameLayout.LayoutParams
                if (lp.bottomMargin != v.height) { lp.bottomMargin = v.height; pv.layoutParams = lp }
            }
        }
        // Settings panel sits just above the bottom controls whatever their height.
        bottom.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val lp = settingsPanel.layoutParams as FrameLayout.LayoutParams
            if (lp.bottomMargin != v.height) { lp.bottomMargin = v.height; settingsPanel.layoutParams = lp }
        }
    }

    // ── Preferences ───────────────────────────────────────────────────────────
    private fun loadPrefs() {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        val lowEnd = am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192
        lensFacing = prefs.getInt("lens", CameraSelector.LENS_FACING_BACK)
        flashMode = prefs.getInt("flash", ImageCapture.FLASH_MODE_OFF)
        timerSec = prefs.getInt("timer", 0)
        gridOn = prefs.getBoolean("grid", false)
        aspect = prefs.getString("aspect", "4:3") ?: "4:3"
        photoRes = prefs.getString("photoRes", if (lowEnd) "high" else "max") ?: "max"
        videoQuality = prefs.getString("videoQuality", "fhd") ?: "fhd"
        videoFps = prefs.getInt("videoFps", 30)
        stabilize = prefs.getBoolean("stabilize", true)
        mirrorFront = prefs.getBoolean("mirrorFront", true)
        muteVideo = prefs.getBoolean("muteVideo", false)
        playShutterSound = prefs.getBoolean("shutterSound", true)
        hdrVideo = prefs.getBoolean("hdrVideo", false)
        saveToGallery = prefs.getBoolean("saveToGallery", false)
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt("lens", lensFacing).putInt("flash", flashMode).putInt("timer", timerSec)
            .putBoolean("grid", gridOn).putString("aspect", aspect).putString("photoRes", photoRes)
            .putString("videoQuality", videoQuality).putInt("videoFps", videoFps)
            .putBoolean("stabilize", stabilize).putBoolean("mirrorFront", mirrorFront)
            .putBoolean("muteVideo", muteVideo).putBoolean("shutterSound", playShutterSound)
            .putBoolean("hdrVideo", hdrVideo).putBoolean("saveToGallery", saveToGallery)
            .apply()
    }

    // ── Mode / top-bar controls ───────────────────────────────────────────────
    private fun setMode(video: Boolean, rebind: Boolean = true) {
        if (recording != null || countdownLeft > 0) return
        videoMode = video
        btnModePhoto.setTextColor(if (video) 0x88FFFFFF.toInt() else 0xFFFFD54F.toInt())
        btnModeVideo.setTextColor(if (video) 0xFFFFD54F.toInt() else 0x88FFFFFF.toInt())
        btnShutter.setImageResource(if (video) R.drawable.ic_video_call else R.drawable.ic_camera)
        btnShutter.backgroundTintList = android.content.res.ColorStateList.valueOf(if (video) 0xFFFF3B30.toInt() else 0xFFEEEEEE.toInt())
        btnShutter.imageTintList = android.content.res.ColorStateList.valueOf(if (video) 0xFFFFFFFF.toInt() else 0xFF222222.toInt())
        if (video) extMode = ExtensionMode.NONE
        updateTopIcons()
        if (rebind) bindCamera()
    }

    private fun flipCamera() {
        if (recording != null || countdownLeft > 0) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                     else CameraSelector.LENS_FACING_BACK
        extMode = ExtensionMode.NONE
        torchOn = false
        chosenLens = null
        focusDiopters = -1f
        savePrefs()
        btnFlip.animate().rotationBy(180f).setDuration(250).start()
        bindCamera()
    }

    private fun cycleFlash() {
        if (videoMode) {
            torchOn = !torchOn
            try { camera?.cameraControl?.enableTorch(torchOn) } catch (_: Throwable) {}
            hint(if (torchOn) "Light on" else "Light off")
        } else {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            try { imageCapture?.flashMode = flashMode } catch (_: Throwable) {}
            hint(when (flashMode) { ImageCapture.FLASH_MODE_AUTO -> "Flash auto"; ImageCapture.FLASH_MODE_ON -> "Flash on"; else -> "Flash off" })
            savePrefs()
        }
        updateTopIcons()
    }

    private fun cycleTimer() {
        timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
        savePrefs(); updateTopIcons()
        hint(if (timerSec == 0) "Timer off" else "Timer ${timerSec}s")
    }

    private fun cycleAspect() {
        if (recording != null) return
        aspect = when (aspect) { "4:3" -> "16:9"; "16:9" -> "1:1"; else -> "4:3" }
        if (videoMode && aspect == "1:1") aspect = "4:3"
        savePrefs(); updateTopIcons(); bindCamera()
    }

    private fun updateTopIcons() {
        btnFlash.setImageResource(
            if (videoMode) (if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            else when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                else -> R.drawable.ic_flash_off
            })
        btnFlash.alpha = if ((videoMode && torchOn) || (!videoMode && flashMode != ImageCapture.FLASH_MODE_OFF)) 1f else 0.7f
        tvTimerBadge.visibility = if (timerSec > 0) View.VISIBLE else View.GONE
        tvTimerBadge.text = "${timerSec}s"
        btnTimer.alpha = if (timerSec > 0) 1f else 0.7f
        btnGrid.alpha = if (gridOn) 1f else 0.6f
        btnAspect.text = aspect
    }

    private fun applyGrid() { gridView?.visibility = if (gridOn) View.VISIBLE else View.GONE; updateTopIcons() }

    private fun hint(text: String, ms: Long = 1200) {
        tvHint.text = text
        tvHint.visibility = View.VISIBLE
        tvHint.removeCallbacks(hideHint)
        tvHint.postDelayed(hideHint, ms)
    }
    private val hideHint = Runnable { tvHint.visibility = View.GONE }

    // ── Gestures: pinch zoom, tap focus, long-press lock, double-tap flip, swipe mode ──
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val z = cam.cameraInfo.zoomState.value ?: return false
                cam.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (settingsPanel.visibility == View.VISIBLE) { settingsPanel.visibility = View.GONE; return true }
                focusAt(e.x, e.y, lock = false); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { flipCamera(); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                // After tapping to focus, drag up / down to brighten / darken (like the phone camera).
                if (System.currentTimeMillis() - lastFocusAtMs > 4000 || abs(dy) < abs(dx)) return false
                val r = evRange ?: return false
                if (r.lower == r.upper) return false
                evDragAccum += dy
                val stepPx = 10 * resources.displayMetrics.density
                val steps = (evDragAccum / stepPx).toInt()
                if (steps != 0) {
                    evDragAccum -= steps * stepPx
                    evIndex = (evIndex + steps).coerceIn(r.lower, r.upper)
                    try { camera?.cameraControl?.setExposureCompensationIndex(evIndex) } catch (_: Throwable) {}
                    lastFocusAtMs = System.currentTimeMillis()
                    hint("Brightness %+.1f".format(evIndex * evStep), 900)
                    seekEv.progress = evIndex - r.lower
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) { focusAt(e.x, e.y, lock = true) }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || scale.isInProgress) return false
                val dx = e2.x - e1.x
                if (abs(dx) > 150 && abs(dx) > abs(e2.y - e1.y) * 2) { setMode(dx < 0); return true }
                return false
            }
        })
        previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) gestures.onTouchEvent(ev)
            true
        }
    }

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val b = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
            if (lock) b.disableAutoCancel() else b.setAutoCancelDuration(4, TimeUnit.SECONDS)
            cam.cameraControl.startFocusAndMetering(b.build())
            aeAfLocked = lock
            lastFocusAtMs = System.currentTimeMillis()
            evDragAccum = 0f
            if (focusDiopters >= 0f) { focusDiopters = -1f; applyCamera2Options() }   // tap returns to autofocus
            showFocusRing(x, y, lock)
            if (!lock) hint("Drag up or down to change brightness", 1400)
            if (lock) hint("Focus and exposure locked", 1600)
        } catch (_: Throwable) {}
    }

    private fun showFocusRing(x: Float, y: Float, locked: Boolean) {
        val size = (72 * resources.displayMetrics.density).toInt()
        val ring = focusRing ?: View(this).also {
            focusRing = it
            overlay.addView(it, FrameLayout.LayoutParams(size, size))
        }
        ring.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((2 * resources.displayMetrics.density).toInt(), if (locked) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt())
        }
        ring.x = x - size / 2f; ring.y = y - size / 2f
        ring.alpha = 1f; ring.scaleX = 1.4f; ring.scaleY = 1.4f
        ring.visibility = View.VISIBLE
        ring.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            if (!locked) ring.animate().alpha(0f).setStartDelay(900).setDuration(300).start()
        }.start()
    }

    // ── Orientation: photos/videos come out upright, icons turn with the phone ──
    private fun setupOrientation() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (r == deviceRotation) return
                deviceRotation = r
                try { imageCapture?.targetRotation = r } catch (_: Throwable) {}
                if (recording == null) try { videoCapture?.targetRotation = r } catch (_: Throwable) {}
                val deg = when (r) { Surface.ROTATION_90 -> 90f; Surface.ROTATION_180 -> 180f; Surface.ROTATION_270 -> -90f; else -> 0f }
                listOf<View>(btnFlash, btnTimer, btnGrid, btnAspect, btnGallery, btnFlip, btnShutter, btnRecPause)
                    .forEach { it.animate().rotation(deg).setDuration(200).start() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try { orientationListener?.enable() } catch (_: Throwable) {}
    }

    override fun onPause() {
        super.onPause()
        try { orientationListener?.disable() } catch (_: Throwable) {}
        cancelCountdown()
        reviewPlayer?.pause()
    }

    // ── Volume keys = shutter ─────────────────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
             keyCode == KeyEvent.KEYCODE_CAMERA) && reviewLayer.visibility != View.VISIBLE) {
            if (event?.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Binding
    // ═════════════════════════════════════════════════════════════════════════
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                if (extensionsManager == null && !videoMode) {
                    try {
                        val ef = ExtensionsManager.getInstanceAsync(this, provider)
                        ef.addListener({
                            extensionsManager = try { ef.get() } catch (_: Throwable) { null }
                            if (!isFinishing && !videoMode) { readExtensions(); buildExtensionRow() }
                        }, ContextCompat.getMainExecutor(this))
                    } catch (_: Throwable) {}
                }
                doBind(provider)
            } catch (e: Throwable) {
                android.util.Log.e("CamCap", "provider failed ${e.message}")
                fallbackSystemCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Fallback ladder: full options -> plain options -> defaults -> system camera. */
    private fun doBind(provider: ProcessCameraProvider) {
        val baseSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!hasLens(provider, baseSelector)) {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        }
        readLenses(provider)
        val lens = chosenLens?.takeIf { it.facing == lensFacing && lenses.contains(it) }
        val selector0 = if (lens != null) CameraSelector.Builder().addCameraFilter { infos ->
                infos.filter { cameraIdOf(it) == lens.cameraId }.ifEmpty { infos.take(1) }
            }.requireLensFacing(lensFacing).build()
            else CameraSelector.Builder().requireLensFacing(lensFacing).build()
        readCaps(provider, selector0)
        for (attempt in 0..2) {
            try {
                provider.unbindAll()
                val selector = if (attempt == 0 && !videoMode && extMode != ExtensionMode.NONE)
                    extensionsManager?.let { m ->
                        if (m.isExtensionAvailable(selector0, extMode)) m.getExtensionEnabledCameraSelector(selector0, extMode) else null
                    } ?: selector0
                else selector0
                val preview = buildPreview(attempt)
                camera = if (videoMode) {
                    val vc = buildVideoCapture(attempt)
                    videoCapture = vc; imageCapture = null
                    provider.bindToLifecycle(this, selector, preview, vc)
                } else {
                    val ic = buildImageCapture(attempt)
                    imageCapture = ic; videoCapture = null
                    provider.bindToLifecycle(this, selector, preview, ic)
                }
                preview.setSurfaceProvider(previewView.surfaceProvider)
                afterBind(applyInterop = attempt < 2 && (videoMode || extMode == ExtensionMode.NONE))
                return
            } catch (e: Throwable) {
                android.util.Log.w("CamCap", "bind attempt $attempt failed: ${e.message}")
            }
        }
        fallbackSystemCamera()
    }

    private fun hasLens(provider: ProcessCameraProvider, sel: CameraSelector): Boolean =
        try { provider.hasCamera(sel) } catch (_: Throwable) { false }

    private fun aspectStrategy(): AspectRatioStrategy = when (aspect) {
        "16:9" -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else   -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY   // 1:1 is cropped from 4:3
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun <T> withPhysical(b: androidx.camera.core.ExtendableBuilder<T>, attempt: Int) {
        val pid = chosenLens?.physicalId ?: return
        if (attempt < 2) try { androidx.camera.camera2.interop.Camera2Interop.Extender(b).setPhysicalCameraId(pid) } catch (_: Throwable) {}
    }

    private fun buildPreview(attempt: Int): Preview {
        val b = Preview.Builder()
        withPhysical(b, attempt)
        if (attempt < 2) b.setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy()).build())
        b.setTargetRotation(Surface.ROTATION_0)
        return b.build()
    }

    private fun buildImageCapture(attempt: Int): ImageCapture {
        val b = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)
            .setFlashMode(if (lensFacing == CameraSelector.LENS_FACING_FRONT) ImageCapture.FLASH_MODE_OFF else flashMode)
            .setTargetRotation(deviceRotation)
        withPhysical(b, attempt)
        if (attempt < 2) {
            val rs = ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy())
            when (photoRes) {
                "max" -> {
                    rs.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    if (attempt == 0) rs.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                }
                "high" -> rs.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                else -> rs.setResolutionStrategy(ResolutionStrategy(Size(2048, 1536),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            }
            b.setResolutionSelector(rs.build())
        }
        return b.build()
    }

    private fun qualityFor(key: String): Quality = when (key) {
        "uhd" -> Quality.UHD; "hd" -> Quality.HD; "sd" -> Quality.SD; else -> Quality.FHD
    }

    private fun bitrateFor(key: String): Int {
        val base = when (key) { "uhd" -> 45_000_000; "hd" -> 10_000_000; "sd" -> 4_000_000; else -> 20_000_000 }
        return if (videoFps >= 60) (base * 1.5).toInt() else base
    }

    private fun buildVideoCapture(attempt: Int): VideoCapture<Recorder> {
        val q = qualityFor(videoQuality)
        val rb = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
        if (attempt < 2) rb.setTargetVideoEncodingBitRate(bitrateFor(videoQuality))
        val vb = VideoCapture.Builder(rb.build())
            .setMirrorMode(if (mirrorFront) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            .setTargetRotation(deviceRotation)
        withPhysical(vb, attempt)
        if (attempt == 0) {
            if (videoFps != 30) vb.setTargetFrameRate(Range(videoFps, videoFps))
            if (stabilize) vb.setVideoStabilizationEnabled(true)
            if (hdrVideo && hdrVideoSupported) vb.setDynamicRange(DynamicRange.HLG_10_BIT)
        }
        return vb.build()
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun readCaps(provider: ProcessCameraProvider, selector: CameraSelector) {
        try {
            val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return
            val c2 = Camera2CameraInfo.from(info)
            evRange = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let { evStep = it.toFloat() }
            isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            awbModes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?: intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
            fpsRanges = (c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: emptyArray()).toList()
            supportedVideoQualities = try {
                Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
            } catch (_: Throwable) { emptyList() }
            hdrVideoSupported = try {
                Recorder.getVideoCapabilities(info).supportedDynamicRanges.any { it == DynamicRange.HLG_10_BIT }
            } catch (_: Throwable) { false }
            val caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val manual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            exposureRange = if (manual) c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null
            if (!manual) isoRange = null
            minFocusDist = if (manual) (c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) else 0f
        } catch (_: Throwable) {}
    }

    private fun readExtensions() {
        val m = extensionsManager ?: return
        val sel = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        availableExtensions = listOf(ExtensionMode.NIGHT, ExtensionMode.HDR, ExtensionMode.BOKEH,
            ExtensionMode.FACE_RETOUCH, ExtensionMode.AUTO).filter {
            try { m.isExtensionAvailable(sel, it) } catch (_: Throwable) { false }
        }
    }

    private fun afterBind(applyInterop: Boolean) {
        val cam = camera ?: return
        if (applyInterop) applyCamera2Options()
        if (videoMode && torchOn) try { cam.cameraControl.enableTorch(true) } catch (_: Throwable) {}
        btnFlash.visibility = if (cam.cameraInfo.hasFlashUnit() || lensFacing == CameraSelector.LENS_FACING_FRONT) View.VISIBLE else View.GONE
        cam.cameraInfo.zoomState.removeObservers(this)
        cam.cameraInfo.zoomState.observe(this) { z -> updateZoomChips(z.zoomRatio) }
        buildZoomChips()
        buildSettingsUi()
        readExtensions()
        buildExtensionRow()
        gridView?.squareCrop = aspect == "1:1"
        gridView?.invalidate()
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyCamera2Options() {
        val cam = camera ?: return
        try {
            val ctrl = Camera2CameraControl.from(cam.cameraControl)
            val b = CaptureRequestOptions.Builder()
            if (awbMode in awbModes) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            evRange?.let { r -> b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex.coerceIn(r.lower, r.upper)) }
            // Manual exposure: ISO and/or shutter speed (the other one gets a sensible default).
            val ir = isoRange; val er = exposureRange
            if ((isoValue > 0 && ir != null) || (shutterNs > 0 && er != null)) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                val iso = if (isoValue > 0 && ir != null) isoValue.coerceIn(ir.lower, ir.upper) else (ir?.let { 400.coerceIn(it.lower, it.upper) } ?: 400)
                val exp = if (shutterNs > 0 && er != null) shutterNs.coerceIn(er.lower, er.upper) else (er?.let { 16_666_666L.coerceIn(it.lower, it.upper) } ?: 16_666_666L)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            }
            // Manual focus (0 = infinity).
            if (focusDiopters >= 0f && minFocusDist > 0f) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters.coerceIn(0f, minFocusDist))
            }
            ctrl.setCaptureRequestOptions(b.build())
        } catch (e: Throwable) {
            android.util.Log.w("CamCap", "Camera2 options skipped: ${e.message}")
        }
    }

    // ── Lenses: every camera the phone exposes (ultra wide / main / tele / macro / front) ──
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun cameraIdOf(info: androidx.camera.core.CameraInfo): String =
        try { Camera2CameraInfo.from(info).cameraId } catch (_: Throwable) { "" }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun equivMm(info: androidx.camera.core.CameraInfo): Float = try {
        val c2 = Camera2CameraInfo.from(info)
        val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val w = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f
        if (f > 0f && w > 0f) f * 36f / w else 0f
    } catch (_: Throwable) { 0f }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun readLenses(provider: ProcessCameraProvider) {
        val out = mutableListOf<Lens>()
        try {
            for (info in provider.availableCameraInfos) {
                val facing = try { Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) } catch (_: Throwable) { null }
                val camFacing = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraSelector.LENS_FACING_FRONT
                    CameraCharacteristics.LENS_FACING_BACK -> CameraSelector.LENS_FACING_BACK
                    else -> continue
                }
                if (camFacing != lensFacing) continue
                val id = cameraIdOf(info)
                out += Lens(id, null, camFacing, equivMm(info))
                // Phones that group several lenses into one "logical" camera can still expose
                // each physical lens; offer those too.
                val phys = try { info.physicalCameraInfos } catch (_: Throwable) { emptySet() }
                if (phys.size > 1) for (p in phys) {
                    val pid = cameraIdOf(p)
                    if (pid.isNotBlank() && pid != id) out += Lens(id, pid, camFacing, equivMm(p))
                }
            }
        } catch (_: Throwable) {}
        // De-duplicate lenses with the same field of view; label relative to the main lens.
        val uniq = out.filter { it.equivMm > 0f }.distinctBy { Math.round(it.equivMm / 2f) }
        val main = uniq.minByOrNull { abs(it.equivMm - 26f) } ?: uniq.firstOrNull()
        uniq.forEach { l ->
            val x = if (main != null && main.equivMm > 0f) l.equivMm / main.equivMm else 1f
            val kind = when {
                lensFacing == CameraSelector.LENS_FACING_FRONT -> if (x < 0.9f) "Wide selfie" else "Selfie"
                x < 0.8f -> "Ultra wide"
                x > 1.6f -> "Telephoto"
                else -> "Main"
            }
            l.label = "$kind  " + (if (x < 1f) ".%d".format(Math.round(x * 10)) else if (abs(x - Math.round(x)) < 0.1f) "${Math.round(x)}" else "%.1f".format(x)) + "x  (${Math.round(l.equivMm)}mm)"
        }
        lenses = uniq.sortedBy { it.equivMm }
        val btn = findViewById<TextView>(R.id.btnLens)
        btn.visibility = if (lenses.size > 1) View.VISIBLE else View.GONE
        val cur = chosenLens?.takeIf { lenses.contains(it) } ?: main
        btn.text = cur?.label?.substringAfter("  ")?.substringBefore("  ") ?: "1x"
    }

    private fun pickLens() {
        if (recording != null || lenses.size < 2) return
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Choose camera")
            .setItems(lenses.map { it.label }.toTypedArray()) { _, i ->
                chosenLens = lenses[i]
                extMode = ExtensionMode.NONE
                focusDiopters = -1f
                bindCamera()
                hint(lenses[i].label.substringBefore("  "))
            }.show()
    }

    /** Hand over to the phone's own camera app: every native mode and setting it has. */
    private fun openPhoneCamera() {
        if (recording != null) return
        try {
            val ext = if (videoMode) ".mp4" else ".jpg"
            val file = File(cacheDir, "native_${System.currentTimeMillis()}$ext")
            nativeCaptureFile = file
            val uri = uriFor(file)
            pendingOut = uri
            if (videoMode) systemVideo.launch(uri) else systemPhoto.launch(uri)
        } catch (_: Throwable) { toast("No camera app found") }
    }

    // ── Zoom chips (.5x / 1x / 2x / 5x / 10x, whatever this lens can do) ────────
    private val zoomChips = mutableListOf<Pair<Float, TextView>>()

    private fun buildZoomChips() {
        rowZoom.removeAllViews(); zoomChips.clear()
        val z = camera?.cameraInfo?.zoomState?.value ?: return
        val stops = mutableListOf<Float>()
        if (z.minZoomRatio < 0.95f) stops.add(z.minZoomRatio)
        stops.add(1f)
        listOf(2f, 5f, 10f).filter { it <= z.maxZoomRatio + 0.01f }.forEach { stops.add(it) }
        val d = resources.displayMetrics.density
        stops.forEach { s ->
            val tv = TextView(this).apply {
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
                setOnClickListener { try { camera?.cameraControl?.setZoomRatio(s) } catch (_: Throwable) {} }
            }
            val lp = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt()).apply { marginStart = (6 * d).toInt(); marginEnd = (6 * d).toInt() }
            rowZoom.addView(tv, lp)
            zoomChips.add(s to tv)
        }
        updateZoomChips(z.zoomRatio)
    }

    private fun updateZoomChips(current: Float) {
        // The chip closest below the current zoom shows the live value (e.g. "2.7x").
        val active = zoomChips.lastOrNull { current >= it.first - 0.05f } ?: zoomChips.firstOrNull()
        zoomChips.forEach { (stop, tv) ->
            val on = tv === active?.second
            tv.text = if (on) fmtZoom(current) + "×" else fmtZoom(stop)
            tv.setTextColor(if (on) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun fmtZoom(z: Float): String =
        if (z < 1f) ".%d".format(Math.round(z * 10)) else if (abs(z - Math.round(z)) < 0.05f) "${Math.round(z)}" else "%.1f".format(z)

    // ── Night / HDR / Portrait chips ──────────────────────────────────────────
    private fun buildExtensionRow() {
        val row = findViewById<LinearLayout>(R.id.rowCamExt)
        val scroll = findViewById<View>(R.id.scrollCamExt)
        row.removeAllViews()
        if (videoMode || availableExtensions.isEmpty()) { scroll.visibility = View.GONE; return }
        scroll.visibility = View.VISIBLE
        val items = listOf(ExtensionMode.NONE to "Standard") + availableExtensions.map {
            it to when (it) {
                ExtensionMode.NIGHT -> "Night"; ExtensionMode.HDR -> "HDR"; ExtensionMode.BOKEH -> "Portrait"
                ExtensionMode.FACE_RETOUCH -> "Retouch"; else -> "Auto+"
            }
        }
        chipRow(row, items.map { it.second to it.first }, extMode) { extMode = it; bindCamera() }
    }

    // ── Settings panel ────────────────────────────────────────────────────────
    private fun buildSettingsUi() {
        val resLabel = findViewById<TextView>(R.id.tvResLabel)
        val fpsLabel = findViewById<TextView>(R.id.tvFpsLabel)
        val rowFps = findViewById<LinearLayout>(R.id.rowCamFps)
        if (videoMode) {
            resLabel.text = "Video quality"
            val all = listOf("uhd" to "4K", "fhd" to "1080p", "hd" to "720p", "sd" to "480p")
            val supported = if (supportedVideoQualities.isEmpty()) all
                else all.filter { qualityFor(it.first) in supportedVideoQualities }.ifEmpty { all }
            chipRow(findViewById(R.id.rowCamRes), supported.map { it.second to it.first }, videoQuality) {
                videoQuality = it; savePrefs(); bindCamera()
            }
            fpsLabel.visibility = View.VISIBLE; rowFps.visibility = View.VISIBLE
            val fps = listOf(24, 30, 60).filter { f -> fpsRanges.isEmpty() || fpsRanges.any { it.upper >= f } }.ifEmpty { listOf(30) }
            chipRow(rowFps, fps.map { "${it} fps" to it }, videoFps) { videoFps = it; savePrefs(); bindCamera() }
        } else {
            resLabel.text = "Photo quality"
            chipRow(findViewById(R.id.rowCamRes), listOf(
                "Max (full sensor)" to "max", "High" to "high", "Smaller file" to "medium"
            ), photoRes) { photoRes = it; savePrefs(); bindCamera() }
            fpsLabel.visibility = View.GONE; rowFps.visibility = View.GONE
        }

        val wbMap = listOf(
            "Auto" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
            "Indoor" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            "Fluorescent" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            "Daylight" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            "Cloudy" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            "Twilight" to CaptureRequest.CONTROL_AWB_MODE_TWILIGHT,
            "Shade" to CaptureRequest.CONTROL_AWB_MODE_SHADE
        ).filter { it.second in awbModes }
        chipRow(findViewById(R.id.rowCamWb), wbMap, awbMode) { awbMode = it; applyCamera2Options() }

        val range = evRange
        if (range == null || range.lower == range.upper) {
            seekEv.visibility = View.GONE; tvEv.visibility = View.GONE
        } else {
            seekEv.visibility = View.VISIBLE; tvEv.visibility = View.VISIBLE
            seekEv.max = range.upper - range.lower
            seekEv.progress = evIndex - range.lower
            tvEv.text = "Brightness (EV %+.1f)".format(evIndex * evStep)
            seekEv.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    evIndex = range.lower + p
                    tvEv.text = "Brightness (EV %+.1f)".format(evIndex * evStep)
                    if (fromUser) applyCamera2Options()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val isoRow = findViewById<LinearLayout>(R.id.rowCamIso)
        val isoChoices = mutableListOf("ISO Auto" to 0)
        isoRange?.let { r -> listOf(50, 100, 200, 400, 800, 1600, 3200).filter { it in r.lower..r.upper }.forEach { isoChoices.add("ISO $it" to it) } }
        isoRow.visibility = if (isoChoices.size == 1) View.GONE else View.VISIBLE
        if (isoChoices.size > 1) chipRow(isoRow, isoChoices, isoValue) { isoValue = it; applyCamera2Options() }

        val toggles = findViewById<LinearLayout>(R.id.rowCamToggles)
        toggles.removeAllViews()
        // ── Pro: shutter speed + manual focus (only on phones that allow manual control) ──
        exposureRange?.let { er ->
            val speeds = listOf(1_000_000_000L / 8000, 1_000_000_000L / 4000, 1_000_000_000L / 2000, 1_000_000_000L / 1000,
                1_000_000_000L / 500, 1_000_000_000L / 250, 1_000_000_000L / 125, 1_000_000_000L / 60, 1_000_000_000L / 30,
                1_000_000_000L / 15, 1_000_000_000L / 8, 1_000_000_000L / 4, 1_000_000_000L / 2, 1_000_000_000L)
                .filter { it in er.lower..er.upper }
            if (speeds.isNotEmpty()) {
                toggles.addView(label("Shutter speed"))
                val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                hs.addView(row); toggles.addView(hs)
                chipRow(row, listOf("Auto" to 0L) + speeds.map { ns ->
                    (if (ns >= 1_000_000_000L) "${ns / 1_000_000_000L}s" else "1/${1_000_000_000L / ns}") to ns
                }, shutterNs) { shutterNs = it; applyCamera2Options() }
            }
        }
        if (minFocusDist > 0f) {
            toggles.addView(label(if (focusDiopters < 0f) "Focus: auto (tap to focus)" else "Focus: manual"))
            val sb = SeekBar(this).apply {
                max = 100
                progress = if (focusDiopters < 0f) 0 else (100 * focusDiopters / minFocusDist).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        focusDiopters = minFocusDist * p / 100f
                        applyCamera2Options()
                        hint(if (p == 0) "Focus: far" else if (p > 90) "Focus: closest" else "Focus $p%", 600)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { buildSettingsUi() }
                })
            }
            toggles.addView(sb)
            if (focusDiopters >= 0f) toggles.addView(TextView(this).apply {
                text = "Back to autofocus"; setTextColor(0xFFFFD54F.toInt()); textSize = 12f; setPadding(0, 4, 0, 8)
                setOnClickListener { focusDiopters = -1f; applyCamera2Options(); buildSettingsUi() }
            })
        }
        if (videoMode) {
            addToggle(toggles, "Stabilization", stabilize) { stabilize = it; savePrefs(); bindCamera() }
            addToggle(toggles, "Record sound", !muteVideo) { muteVideo = !it; savePrefs() }
            if (hdrVideoSupported) addToggle(toggles, "HDR video (10-bit)", hdrVideo) { hdrVideo = it; savePrefs(); bindCamera() }
        }
        addToggle(toggles, "Also save to my gallery", saveToGallery) { saveToGallery = it; savePrefs() }
        addToggle(toggles, "Mirror selfies", mirrorFront) { mirrorFront = it; savePrefs(); if (videoMode) bindCamera() }
        addToggle(toggles, "Shutter sound", playShutterSound) { playShutterSound = it; savePrefs() }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(0xAAFFFFFF.toInt()); textSize = 11f; setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun addToggle(parent: LinearLayout, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
            text = label
            isChecked = on
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, 6, 0, 6)
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        parent.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun <T> chipRow(row: LinearLayout, items: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
        row.removeAllViews()
        val d = resources.displayMetrics.density
        items.forEach { (label, value) ->
            val on = value == selected
            val tv = TextView(this).apply {
                text = label
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                setTextColor(if (on) 0xFF1A1A1E.toInt() else 0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(if (on) 0xFFFFC107.toInt() else 0x33FFFFFF)
                }
                textSize = 12f
                setOnClickListener {
                    try { onPick(value) } catch (_: Throwable) {}
                    chipRow(row, items, value, onPick)
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * d).toInt(); lp.topMargin = (4 * d).toInt()
            row.addView(tv, lp)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Capture
    // ═════════════════════════════════════════════════════════════════════════
    private fun onShutter() {
        if (reviewLayer.visibility == View.VISIBLE) return
        // A photo is still being taken: ignore (volume-key presses used to queue extra shots).
        if (!videoMode && !btnShutter.isEnabled) return
        settingsPanel.visibility = View.GONE
        if (countdownLeft > 0) { cancelCountdown(); hint("Timer cancelled"); return }
        if (videoMode) {
            if (recording != null) { stopRecord(); return }
            if (timerSec > 0) startCountdown { startRecord() } else startRecord()
        } else {
            if (timerSec > 0) startCountdown { takePhoto() } else takePhoto()
        }
    }

    private fun startCountdown(then: () -> Unit) {
        countdownLeft = timerSec
        tvCountdown.visibility = View.VISIBLE
        val r = object : Runnable {
            override fun run() {
                if (countdownLeft <= 0) {
                    tvCountdown.visibility = View.GONE
                    countdownRunnable = null
                    then(); return
                }
                tvCountdown.text = countdownLeft.toString()
                tvCountdown.scaleX = 1.4f; tvCountdown.scaleY = 1.4f
                tvCountdown.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
                countdownLeft--
                ui.postDelayed(this, 1000)
            }
        }
        countdownRunnable = r
        ui.post(r)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { ui.removeCallbacks(it) }
        countdownRunnable = null
        countdownLeft = 0
        tvCountdown.visibility = View.GONE
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run { fallbackSystemCamera(); return }
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        if (front && flashMode == ImageCapture.FLASH_MODE_ON) {
            // Front "flash": light the face with a full-brightness white screen.
            screenFlash(true)
            ui.postDelayed({ doTakePhoto(capture, front) }, 450)
        } else doTakePhoto(capture, front)
    }

    private fun doTakePhoto(capture: ImageCapture, front: Boolean) {
        val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = front && mirrorFront }
        val opts = ImageCapture.OutputFileOptions.Builder(file).setMetadata(meta).build()
        btnShutter.isEnabled = false
        try { capture.targetRotation = deviceRotation } catch (_: Throwable) {}
        capture.takePicture(opts, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() {
                runOnUiThread { blink(); if (playShutterSound) try { shutterSound?.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Throwable) {} }
            }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                // Runs on the camera thread: the square crop never blocks the screen.
                val finalFile = if (aspect == "1:1") cropSquare(file) else file
                runOnUiThread {
                    screenFlash(false)
                    btnShutter.isEnabled = true
                    if (!isFinishing) showReview(finalFile, false)
                }
            }
            override fun onError(exc: ImageCaptureException) {
                runOnUiThread {
                    screenFlash(false)
                    btnShutter.isEnabled = true
                    toast("Capture failed, try again")
                }
            }
        })
    }

    /**
     * 1:1 is cropped from the full 4:3 frame (centre square, rotation-aware, highest JPEG
     * quality). Only the centre square is decoded (region decoder): decoding the whole
     * 12-50 MP photo first ran cheap phones out of memory.
     */
    @Suppress("DEPRECATION")
    private fun cropSquare(src: File): File = try {
        val exif = androidx.exifinterface.media.ExifInterface(src.absolutePath)
        val rot = exif.rotationDegrees
        val dec = android.graphics.BitmapRegionDecoder.newInstance(src.absolutePath, false) ?: throw IllegalStateException()
        val w = dec.width; val h = dec.height
        val side = minOf(w, h)
        val rect = android.graphics.Rect((w - side) / 2, (h - side) / 2, (w - side) / 2 + side, (h - side) / 2 + side)
        val region = dec.decodeRegion(rect, null) ?: throw IllegalStateException()
        dec.recycle()
        val sq = if (rot != 0) android.graphics.Bitmap.createBitmap(region, 0, 0, region.width, region.height,
            android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true) else region
        val out = File(cacheDir, "photo_sq_${System.currentTimeMillis()}.jpg")
        out.outputStream().use { sq.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it) }
        if (sq !== region) region.recycle()
        sq.recycle()
        src.delete(); out
    } catch (_: Throwable) { src }   // out of memory etc: keep the full frame rather than fail

    private fun blink() {
        flashOverlay.setBackgroundColor(0xFF000000.toInt())
        flashOverlay.visibility = View.VISIBLE
        flashOverlay.alpha = 0.8f
        flashOverlay.animate().alpha(0f).setDuration(180).withEndAction { flashOverlay.visibility = View.GONE }.start()
    }

    private fun screenFlash(on: Boolean) {
        val lp = window.attributes
        if (on) {
            savedBrightness = lp.screenBrightness
            lp.screenBrightness = 1f
            window.attributes = lp
            flashOverlay.animate().cancel()
            flashOverlay.setBackgroundColor(0xFFFFFFFF.toInt())
            flashOverlay.alpha = 1f
            flashOverlay.visibility = View.VISIBLE
        } else if (savedBrightness != -1f || flashOverlay.alpha == 1f) {
            lp.screenBrightness = savedBrightness
            window.attributes = lp
            savedBrightness = -1f
            flashOverlay.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecord() {
        val vc = videoCapture ?: run { fallbackSystemCamera(); return }
        val file = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
        try { vc.targetRotation = deviceRotation } catch (_: Throwable) {}
        val pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        val micAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val withAudio = !muteVideo && micAllowed
        // Say so instead of silently recording a video with no sound.
        if (!muteVideo && !micAllowed) hint("Recording without sound (microphone not allowed)", 2500)
        val rec = if (withAudio) pending.withAudioEnabled() else pending
        recordingStart = System.currentTimeMillis()
        pausedTotalMs = 0L; recordingPaused = false
        recording = try {
            rec.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    setRecordingUi(false)
                    if (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                        if (file.exists() && file.length() > 0) showReview(file, true) else toast("Video was too short")
                    } else {
                        file.delete()
                        toast(if (event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) "Video was too short" else "Video failed, try again")
                    }
                }
            }
        } catch (e: Throwable) {
            toast("Could not start recording"); null
        }
        if (recording != null) {
            setRecordingUi(true)
            if (playShutterSound) try { shutterSound?.play(MediaActionSound.START_VIDEO_RECORDING) } catch (_: Throwable) {}
        }
    }

    private fun stopRecord() {
        try { recording?.stop() } catch (_: Throwable) {}
        if (playShutterSound) try { shutterSound?.play(MediaActionSound.STOP_VIDEO_RECORDING) } catch (_: Throwable) {}
    }

    private fun togglePause() {
        val r = recording ?: return
        try {
            if (recordingPaused) { r.resume(); pausedTotalMs += System.currentTimeMillis() - pausedAt; recordingPaused = false }
            else { r.pause(); pausedAt = System.currentTimeMillis(); recordingPaused = true }
        } catch (_: Throwable) {}
        btnRecPause.setImageResource(if (recordingPaused) R.drawable.ic_play else R.drawable.ic_pause)
        findViewById<View>(R.id.recDot).alpha = if (recordingPaused) 0.3f else 1f
    }

    private fun setRecordingUi(on: Boolean) {
        rowRecInfo.visibility = if (on) View.VISIBLE else View.GONE
        btnRecPause.visibility = if (on) View.VISIBLE else View.GONE
        btnGallery.visibility = if (on) View.GONE else View.VISIBLE
        rowModes.visibility = if (on) View.INVISIBLE else View.VISIBLE
        btnFlip.isEnabled = !on; btnFlip.alpha = if (on) 0.3f else 1f
        btnAspect.isEnabled = !on
        btnShutter.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_video_call)
        recTick?.let { ui.removeCallbacks(it) }
        if (on) {
            btnRecPause.setImageResource(R.drawable.ic_pause)
            recTick = object : Runnable {
                override fun run() {
                    val pausedNow = if (recordingPaused) System.currentTimeMillis() - pausedAt else 0L
                    val s = ((System.currentTimeMillis() - recordingStart - pausedTotalMs - pausedNow) / 1000).toInt().coerceAtLeast(0)
                    tvRecTimer.text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
                    ui.postDelayed(this, 500)
                }
            }.also { ui.post(it) }
        } else recTick = null
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Review
    // ═════════════════════════════════════════════════════════════════════════
    private fun showReview(file: File, isVideo: Boolean) {
        reviewFile = file
        reviewIsVideo = isVideo
        reviewLayer.visibility = View.VISIBLE
        findViewById<View>(R.id.btnReviewEdit).visibility = if (isVideo) View.GONE else View.VISIBLE
        val sizeMb = file.length() / 1048576.0
        if (isVideo) {
            ivReview.visibility = View.GONE
            reviewPlayerView.visibility = View.VISIBLE
            val p = reviewPlayer ?: ExoPlayer.Builder(this).build().also { reviewPlayer = it; reviewPlayerView.player = it }
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.prepare(); p.playWhenReady = true
            tvReviewInfo.text = "%.1f MB  (sent as recorded)".format(sizeMb)
        } else {
            reviewPlayer?.stop()
            reviewPlayerView.visibility = View.GONE
            ivReview.visibility = View.VISIBLE
            val dm = resources.displayMetrics
            ivReview.load(file) { size(maxOf(dm.widthPixels, dm.heightPixels)); memoryCacheKey(file.absolutePath + file.lastModified()) }
            val dims = try {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
                val mp = o.outWidth.toLong() * o.outHeight / 1_000_000.0
                "${o.outWidth}×${o.outHeight} (%.1f MP)".format(mp)
            } catch (_: Throwable) { "" }
            tvReviewInfo.text = "$dims  %.1f MB  (sent at full quality)".format(sizeMb)
        }
    }

    private fun retake() {
        reviewPlayer?.stop()
        reviewFile?.delete()
        reviewFile = null
        reviewLayer.visibility = View.GONE
    }

    override fun onBackPressed() {
        when {
            reviewLayer.visibility == View.VISIBLE -> retake()
            settingsPanel.visibility == View.VISIBLE -> settingsPanel.visibility = View.GONE
            recording != null -> stopRecord()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.provider", f)

    private fun fallbackSystemCamera() {
        try {
            val ext = if (videoMode) ".mp4" else ".jpg"
            val file = File(cacheDir, "sys_${System.currentTimeMillis()}$ext")
            nativeCaptureFile = file
            val uri = uriFor(file)
            pendingOut = uri
            if (videoMode) systemVideo.launch(uri) else systemPhoto.launch(uri)
        } catch (e: Throwable) {
            toast("Camera unavailable")
            finish()
        }
    }

    private fun finishOk(uri: Uri) {
        val f = reviewFile
        if (saveToGallery && f != null && f.exists()) {
            // Saved in the background straight from the capture (a big video used to be copied
            // on the main thread first, freezing the screen as you tapped Send).
            val isVid = f.name.endsWith(".mp4")
            val app = applicationContext
            com.silvershireinn.app.SilvershireApp.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.silvershireinn.app.repository.FileRepository.saveFileToGallery(app, f,
                    "Silvershire_" + f.name, if (isVid) "video/mp4" else "image/jpeg")
            }
        }
        reviewFile = null   // handed over, do not delete
        setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    /** Survive Android closing this screen while the phone's camera app is open / during review. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        nativeCaptureFile?.let { outState.putString("native_file", it.absolutePath) }
        reviewFile?.let { outState.putString("review_file", it.absolutePath); outState.putBoolean("review_video", reviewIsVideo) }
    }

    override fun onDestroy() {
        super.onDestroy()
        recTick?.let { ui.removeCallbacks(it) }
        cancelCountdown()
        try { recording?.stop() } catch (_: Throwable) {}
        try { reviewPlayer?.release() } catch (_: Throwable) {}
        reviewPlayer = null
        try { shutterSound?.release() } catch (_: Throwable) {}
        try { cameraProvider?.unbindAll() } catch (_: Throwable) {}
        cameraExecutor.shutdown()
        if (isFinishing) reviewFile?.delete()   // abandoned capture
    }

    /** Rule-of-thirds grid (plus the square frame in 1:1). */
    private class GridOverlay(ctx: Context) : View(ctx) {
        var squareCrop = false
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = 1.5f * ctx.resources.displayMetrics.density }
        private val shade = Paint().apply { color = 0x88000000.toInt() }
        override fun onDraw(c: Canvas) {
            var l = 0f; var t = 0f; var r = width.toFloat(); var b = height.toFloat()
            if (squareCrop) {
                val side = minOf(width, height).toFloat()
                t = (height - side) / 2f; b = t + side; l = (width - side) / 2f; r = l + side
                c.drawRect(0f, 0f, width.toFloat(), t, shade)
                c.drawRect(0f, b, width.toFloat(), height.toFloat(), shade)
            }
            val w = r - l; val h = b - t
            for (i in 1..2) {
                c.drawLine(l + w * i / 3f, t, l + w * i / 3f, b, line)
                c.drawLine(l, t + h * i / 3f, r, t + h * i / 3f, line)
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PHOTO = "photo"
        const val MODE_VIDEO = "video"

        fun intent(ctx: Context, mode: String): Intent =
            Intent(ctx, CameraCaptureActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}
