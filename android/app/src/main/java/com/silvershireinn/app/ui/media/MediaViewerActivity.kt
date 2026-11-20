package com.silvershireinn.app.ui.media

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.dispose
import coil.load
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.R
import com.silvershireinn.app.network.MediaAuth
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.VideoThumbs
import com.silvershireinn.app.utils.formatTimestamp
import com.silvershireinn.app.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Full-screen photo & video viewer.
 *
 * Swipe left/right through every photo and video in the chat, swipe down to close, pinch or
 * double-tap to zoom (images load sharp, sized to the phone's memory). Videos: play/pause,
 * seek bar, -10 / +10 s, double-tap sides to skip, speed 0.25x-3x, loop, mute, picture-in-
 * picture, landscape toggle, save the current frame as a photo, and resume where you left
 * off. Every item: save to gallery (original quality), share, open with, copy, info.
 */
class MediaViewerActivity : AppCompatActivity() {

    data class Item(val fileId: String, val name: String, val mime: String, val sender: String,
                    val timestamp: String, val isSent: Boolean, val size: Long) {
        val isVideo get() = mime.startsWith("video/")
    }

    private val items = mutableListOf<Item>()
    private lateinit var pager: ViewPager2
    private lateinit var root: DismissFrame
    private lateinit var topBar: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var controls: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnLoop: ImageButton
    private lateinit var tvSpeed: TextView
    private lateinit var tvSeekHint: TextView

    private var player: ExoPlayer? = null
    private var playerHost: FrameLayout? = null
    private var playerView: PlayerView? = null
    private var currentIndex = -1
    private var chromeVisible = true
    private var userSeeking = false
    private var speed = 1f
    private var muted = false
    private var inPip = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideChrome = Runnable { if (player?.isPlaying == true) setChrome(false) }
    private val tick = object : Runnable {
        override fun run() { updateProgress(); handler.postDelayed(this, 250) }
    }
    private val resumePrefs by lazy { getSharedPreferences("video_resume", MODE_PRIVATE) }
    private lateinit var btnEdit: ImageButton

    /** Edited photo back from the editor: send it in this chat (as a draft) or save it. */
    private val editLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val f = r.data?.data?.path?.let { File(it) }?.takeIf { it.exists() } ?: return@registerForActivityResult
        val conv = intent.getStringExtra(CONVERSATION_ID)
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        if (!conv.isNullOrBlank()) opts += "Send in this chat" to {
            EditedMedia.offer(conv, f)
            toast("Ready to send in the chat")
            finish()
        }
        opts += "Save to phone" to {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { FileRepository.saveFileToGallery(this@MediaViewerActivity, f, "edited_${System.currentTimeMillis()}.jpg", "image/jpeg") }
                toast(if (ok) "Saved to gallery" else "Save failed")
            }
        }
        AlertDialog.Builder(this).setTitle("Edited photo").setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Crop, rotate, draw, text, filters (the same editor as the camera). */
    private fun editCurrent() = withLocalFile { item, f ->
        if (item.isVideo) return@withLocalFile
        try { editLauncher.launch(PhotoEditorActivity.intent(this, Uri.fromFile(f))) } catch (_: Throwable) { toast("Can't open the editor") }
    }

    /** Largest edge images are decoded at, by phone memory (cheap phones get less). */
    private val imageCap: Int by lazy {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        when {
            am.isLowRamDevice || am.memoryClass < 160 -> 1600
            am.memoryClass < 256 -> 2560
            else -> 4096
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        buildUi()

        val first = Item(intent.getStringExtra(FILE_ID) ?: "", intent.getStringExtra(FILE_NAME) ?: "file",
            intent.getStringExtra(MIME_TYPE) ?: "", "", "", false, -1L)
        val conv = intent.getStringExtra(CONVERSATION_ID)
        lifecycleScope.launch {
            val list = if (conv.isNullOrBlank()) emptyList() else withContext(Dispatchers.IO) {
                try {
                    SilvershireApp.database.messageDao().getAttachmentsForConversation(conv).first()
                        .filter { !it.isDeleted && it.attachmentId != null &&
                            (it.attachmentMime?.startsWith("image/") == true || it.attachmentMime?.startsWith("video/") == true) }
                        .map { Item(it.attachmentId!!, it.attachmentName ?: "media", it.attachmentMime ?: "",
                            if (it.isSent) "You" else PreferenceManager.displayNameFor(it.fromUser), it.timestamp, it.isSent, it.attachmentSize) }
                } catch (_: Throwable) { emptyList() }
            }
            items.clear()
            if (list.any { it.fileId == first.fileId }) items.addAll(list) else items.add(list.firstOrNull { it.fileId == first.fileId } ?: first)
            pager.adapter = PageAdapter()
            val start = items.indexOfFirst { it.fileId == first.fileId }.coerceAtLeast(0)
            pager.setCurrentItem(start, false)
            pager.post { onPageShown(start) }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI
    // ═════════════════════════════════════════════════════════════════════════
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = DismissFrame(this).apply { setBackgroundColor(Color.BLACK) }
        pager = ViewPager2(this).apply { offscreenPageLimit = 1 }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))
        root.canStartDismiss = { !currentPageZoomed() && !inPip }
        root.onDismissProgress = { f -> root.setBackgroundColor(Color.argb((255 * (1f - f)).toInt().coerceIn(0, 255), 0, 0, 0)); if (f > 0f) setChrome(false) }
        root.onDismiss = { finish() }

        // Top bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(dp(4), 0, dp(4), dp(12))
        }
        topBar.addView(icon(R.drawable.ic_back, "Back") { finish() })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvTitle = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        tvSub = TextView(this).apply { setTextColor(0xBBFFFFFF.toInt()); textSize = 12f; maxLines = 1 }
        titles.addView(tvTitle); titles.addView(tvSub)
        topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        btnEdit = icon(R.drawable.ic_edit, "Edit") { editCurrent() }
        topBar.addView(btnEdit)
        topBar.addView(icon(R.drawable.ic_share, "Share") { share() })
        topBar.addView(icon(R.drawable.ic_image_save, "Save to gallery") { saveToGallery() })
        topBar.addView(icon(R.drawable.ic_more_vert, "More") { showMore() })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Video controls
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000))
            setPadding(dp(8), dp(24), dp(8), dp(8))
            visibility = View.GONE
        }
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        seek = SeekBar(this).apply { max = 1000 }
        tvTime = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(6), 0, dp(4), 0) }
        seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f)); seekRow.addView(tvTime)
        controls.addView(seekRow)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        btnMute = icon(R.drawable.ic_volume_up, "Mute") { toggleMute() }
        tvSpeed = TextView(this).apply {
            text = "1x"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { pickSpeed() }
        }
        btnLoop = icon(R.drawable.ic_loop, "Loop") { toggleLoop() }
        btnPlay = icon(R.drawable.ic_pause, "Play / pause") { togglePlay() }.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        }
        btnRow.addView(btnMute, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnRow.addView(tvSpeed, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnRow.addView(icon(R.drawable.ic_replay_10, "Back 10 seconds") { seekBy(-10_000) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnRow.addView(btnPlay, LinearLayout.LayoutParams(dp(56), dp(56)))
        btnRow.addView(icon(R.drawable.ic_forward_10, "Forward 10 seconds") { seekBy(10_000) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnRow.addView(btnLoop, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnRow.addView(icon(R.drawable.ic_screen_rotation, "Rotate") { toggleLandscape() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(btnRow)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        tvSeekHint = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x88000000.toInt()) }
            visibility = View.GONE
        }
        root.addView(tvSeekHint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(dp(4) + bars.left, bars.top + dp(4), dp(4) + bars.right, dp(12))
            controls.setPadding(dp(8) + bars.left, dp(24), dp(8) + bars.right, bars.bottom + dp(8))
            insets
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val pl = player ?: return
                val dur = pl.duration.takeIf { it > 0 } ?: return
                pl.seekTo(dur * p / 1000L)
                tvTime.text = "${fmt(dur * p / 1000L)} / ${fmt(dur)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true; handler.removeCallbacks(hideChrome) }
            override fun onStopTrackingTouch(sb: SeekBar?) { userSeeking = false; scheduleHide() }
        })

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { onPageShown(position) }
        })
    }

    private fun icon(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res); contentDescription = desc
        setColorFilter(Color.WHITE)
        background = null
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        setOnClickListener { onClick() }
    }

    private fun setChrome(show: Boolean) {
        chromeVisible = show
        val item = items.getOrNull(currentIndex)
        topBar.animate().alpha(if (show) 1f else 0f).setDuration(200).withStartAction { if (show) topBar.visibility = View.VISIBLE }
            .withEndAction { if (!show) topBar.visibility = View.GONE }.start()
        if (item?.isVideo == true) {
            controls.animate().alpha(if (show) 1f else 0f).setDuration(200).withStartAction { if (show) controls.visibility = View.VISIBLE }
                .withEndAction { if (!show) controls.visibility = View.GONE }.start()
        } else controls.visibility = View.GONE
        val c = WindowInsetsControllerCompat(window, root)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        if (show) scheduleHide()
    }

    private fun toggleChrome() = setChrome(!chromeVisible)

    private fun scheduleHide() {
        handler.removeCallbacks(hideChrome)
        handler.postDelayed(hideChrome, 3500)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Pages
    // ═════════════════════════════════════════════════════════════════════════
    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position].isVideo) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val f = FrameLayout(parent.context).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
            return VH(f)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val f = holder.frame
            f.removeAllViews()
            f.tag = item.fileId
            val spinner = ProgressBar(f.context)
            if (item.isVideo) {
                val zoom = ZoomableFrameLayout(f.context)
                val host = FrameLayout(f.context)
                val thumb = ImageView(f.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                host.addView(thumb, FrameLayout.LayoutParams(-1, -1))
                zoom.addView(host, FrameLayout.LayoutParams(-1, -1))
                f.addView(zoom, FrameLayout.LayoutParams(-1, -1))
                zoom.onSingleTap = { toggleChrome() }
                zoom.onDoubleTap = { e -> if (e.x < zoom.width / 2f) seekBy(-10_000) else seekBy(10_000) }
                VideoThumbs.cached(item.fileId)?.let { thumb.setImageBitmap(it) } ?: lifecycleScope.launch {
                    val b = VideoThumbs.get(this@MediaViewerActivity, item.fileId, MediaAuth.fileUrl(item.fileId))
                    if (f.tag == item.fileId && b != null) thumb.setImageBitmap(b)
                }
                host.tag = "host"
            } else {
                val iv = ZoomableImageView(f.context)
                f.addView(iv, FrameLayout.LayoutParams(-1, -1))
                f.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                iv.onSingleTap = { toggleChrome() }
                iv.tag = "image"
                val local = File(cacheDir, "viewer_${item.fileId}${FileRepository.extFor(item.mime, item.name)}")
                val data: Any = if (local.exists() && local.length() > 0) local else MediaAuth.fileUrl(item.fileId)
                iv.load(data) {
                    MediaAuth.headers().forEach { (k, v) -> addHeader(k, v) }
                    size(imageCap)
                    crossfade(150)
                    listener(
                        onSuccess = { _, _ -> spinner.visibility = View.GONE },
                        onError = { _, _ -> spinner.visibility = View.GONE; if (position == currentIndex) toast("Could not load photo") }
                    )
                }
            }
        }

        override fun onViewRecycled(holder: VH) {
            holder.frame.findViewWithTag<ImageView>("image")?.dispose()
            if (playerHost != null && holder.frame.findViewWithTag<View>("host") === playerHost) detachPlayer()
            holder.frame.removeAllViews()
        }
    }

    private fun currentPageView(): FrameLayout? =
        (pager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(currentIndex)?.itemView as? FrameLayout

    private fun currentPageZoomed(): Boolean {
        val page = currentPageView() ?: return false
        (page.getChildAt(0) as? ZoomableFrameLayout)?.let { return it.isZoomed }
        (page.findViewWithTag<View>("image") as? ZoomableImageView)?.let { iv ->
            val m = iv.copyDisplayMatrix(); val v = FloatArray(9); m.getValues(v)
            val d = iv.drawable ?: return false
            val fit = minOf(iv.width.toFloat() / d.intrinsicWidth.coerceAtLeast(1), iv.height.toFloat() / d.intrinsicHeight.coerceAtLeast(1))
            return v[0] > fit * 1.05f
        }
        return false
    }

    private fun onPageShown(position: Int) {
        if (position !in items.indices) return
        if (position == currentIndex && player != null) return
        saveResumePosition()
        currentIndex = position
        val item = items[position]
        tvTitle.text = item.sender.ifBlank { item.name }
        btnEdit.visibility = if (item.isVideo) View.GONE else View.VISIBLE
        tvSub.text = buildString {
            if (item.timestamp.isNotBlank()) append(item.timestamp.formatTimestamp())
            if (items.size > 1) { if (isNotEmpty()) append("  ·  "); append("${position + 1} of ${items.size}") }
        }
        if (item.isVideo) {
            controls.visibility = if (chromeVisible) View.VISIBLE else View.GONE
            controls.alpha = 1f
            pager.post { attachPlayer(item) }
        } else {
            controls.visibility = View.GONE
            detachPlayer()
            // Warm the local copy in the background so share/save/zoom-in is instant next time.
            lifecycleScope.launch { FileRepository.ensureCached(this@MediaViewerActivity, item.fileId, item.mime, item.name) }
        }
        setChrome(true)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Video
    // ═════════════════════════════════════════════════════════════════════════
    // Audio focus + "becoming noisy": pauses music while a video plays (and gives it back), and
    // pauses the video when headphones are unplugged instead of playing out loud.
    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(this)
        .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build().also { p ->
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) scheduleHide() else { handler.removeCallbacks(hideChrome); if (!inPip) setChrome(true) }
                updatePipParams()
            }
            override fun onPlaybackStateChanged(state: Int) {
                currentPageView()?.findViewWithTag<View>("spinner")?.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    // The video is ready: drop the still frame shown while loading.
                    (playerHost?.getChildAt(0) as? ImageView)?.visibility = View.GONE
                    updateProgress()
                }
                if (state == Player.STATE_ENDED) {
                    items.getOrNull(currentIndex)?.let { resumePrefs.edit().remove(it.fileId).apply() }
                    setChrome(true)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val item = items.getOrNull(currentIndex) ?: return
                // Streaming failed: download the file and play it locally instead.
                toast("Loading video...")
                lifecycleScope.launch {
                    val f = FileRepository.ensureCached(this@MediaViewerActivity, item.fileId, item.mime, item.name)
                    if (f != null && items.getOrNull(currentIndex)?.fileId == item.fileId) {
                        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); p.prepare(); p.playWhenReady = true
                    } else if (f == null) toast("Could not play this video")
                }
            }
        })
        handler.post(tick)
    }

    private fun attachPlayer(item: Item) {
        val page = currentPageView() ?: run { pager.postDelayed({ if (items.getOrNull(currentIndex) == item) attachPlayer(item) }, 120); return }
        val host = page.findViewWithTag<FrameLayout>("host") ?: return
        val p = ensurePlayer()
        detachPlayer(keepPlayer = true)
        val pv = playerView ?: PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.TRANSPARENT)
            playerView = this
        }
        (pv.parent as? ViewGroup)?.removeView(pv)
        host.addView(pv, FrameLayout.LayoutParams(-1, -1))
        if (page.findViewWithTag<View>("spinner") == null) page.addView(ProgressBar(this).apply { tag = "spinner" }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        playerHost = host
        pv.player = p

        val local = File(cacheDir, "viewer_${item.fileId}${FileRepository.extFor(item.mime, item.name)}")
        if (local.exists() && local.length() > 0) {
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(local)))
        } else {
            // Plain HTTP for old files; encrypted files are decrypted chunk by chunk as they stream.
            val http = com.silvershireinn.app.crypto.EncryptedMedia.DataSourceFactory()
            p.setMediaSource(ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(MediaAuth.fileUrl(item.fileId))))
            // Keep a local copy for next time (and for share / save / frame grab).
            lifecycleScope.launch { FileRepository.ensureCached(this@MediaViewerActivity, item.fileId, item.mime, item.name) }
        }
        p.setPlaybackSpeed(speed)
        p.volume = if (muted) 0f else 1f
        p.prepare()
        val resume = resumePrefs.getLong(item.fileId, 0L)
        if (resume > 5_000) { p.seekTo(resume); hint("Resumed at ${fmt(resume)}") }
        p.playWhenReady = true
    }

    private fun detachPlayer(keepPlayer: Boolean = true) {
        saveResumePosition()
        player?.stop()
        playerView?.let { (it.parent as? ViewGroup)?.removeView(it); it.player = null }
        playerHost = null
        if (!keepPlayer) { player?.release(); player = null }
    }

    private fun saveResumePosition() {
        val p = player ?: return
        val item = items.getOrNull(currentIndex) ?: return
        if (!item.isVideo) return
        val pos = p.currentPosition; val dur = p.duration
        if (dur > 30_000 && pos > 5_000 && pos < dur - 5_000) resumePrefs.edit().putLong(item.fileId, pos).apply()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun seekBy(delta: Long) {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0)
        p.seekTo((p.currentPosition + delta).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE))
        hint(if (delta > 0) "+${delta / 1000}s" else "${delta / 1000}s")
        updateProgress()
    }

    private fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        btnMute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        hint(if (muted) "Muted" else "Sound on")
    }

    private fun toggleLoop() {
        val p = player ?: return
        p.repeatMode = if (p.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        val on = p.repeatMode == Player.REPEAT_MODE_ONE
        btnLoop.setColorFilter(if (on) 0xFFFFC107.toInt() else Color.WHITE)
        hint(if (on) "Loop on" else "Loop off")
    }

    private fun pickSpeed() {
        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
        val labels = speeds.map { if (it == 1f) "Normal" else "${it}x".replace(".0x", "x") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Playback speed")
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == speed }) { d, i ->
                speed = speeds[i]
                player?.setPlaybackSpeed(speed)
                tvSpeed.text = if (speed == 1f) "1x" else "${speed}x".replace(".0x", "x")
                d.dismiss()
            }.show()
    }

    private fun toggleLandscape() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Give auto-rotate back after a moment so turning the phone still works.
        handler.postDelayed({ requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }, 4000)
    }

    private fun updateProgress() {
        val p = player ?: return
        if (items.getOrNull(currentIndex)?.isVideo != true) return
        val dur = p.duration; val pos = p.currentPosition
        if (!userSeeking && dur > 0) {
            seek.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            seek.secondaryProgress = (p.bufferedPosition * 1000 / dur).toInt().coerceIn(0, 1000)
        }
        if (!userSeeking) tvTime.text = "${fmt(pos)} / ${if (dur > 0) fmt(dur) else "--:--"}"
    }

    private fun fmt(ms: Long): String {
        val t = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    private fun hint(text: String) {
        tvSeekHint.text = text
        tvSeekHint.visibility = View.VISIBLE
        tvSeekHint.removeCallbacks(hideHint)
        tvSeekHint.postDelayed(hideHint, 700)
    }
    private val hideHint = Runnable { tvSeekHint.visibility = View.GONE }

    // ── Picture in picture ────────────────────────────────────────────────────
    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val size = player?.videoSize
        val r = if (size != null && size.width > 0 && size.height > 0)
            Rational(size.width, size.height).let { if (it.toFloat() > 2.39f) Rational(239, 100) else if (it.toFloat() < 0.42f) Rational(42, 100) else it }
        else Rational(16, 9)
        val b = PictureInPictureParams.Builder().setAspectRatio(r)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(player?.isPlaying == true)
        return b.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) try { pipParams()?.let { setPictureInPictureParams(it) } } catch (_: Throwable) {}
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { toast("Not supported on this phone"); return }
        try { pipParams()?.let { enterPictureInPictureMode(it) } } catch (_: Throwable) { toast("Picture-in-picture is off for this app") }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) { topBar.visibility = View.GONE; controls.visibility = View.GONE } else setChrome(true)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Actions
    // ═════════════════════════════════════════════════════════════════════════
    private fun withLocalFile(action: (Item, File) -> Unit) {
        val item = items.getOrNull(currentIndex) ?: return
        val existing = File(cacheDir, "viewer_${item.fileId}${FileRepository.extFor(item.mime, item.name)}")
        if (existing.exists() && existing.length() > 0) { action(item, existing); return }
        toast("Downloading...")
        lifecycleScope.launch {
            val f = FileRepository.ensureCached(this@MediaViewerActivity, item.fileId, item.mime, item.name)
            if (f == null) toast("Download failed") else action(item, f)
        }
    }

    private fun contentUri(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.provider", f)

    private fun saveToGallery() = withLocalFile { item, f ->
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { FileRepository.saveFileToGallery(this@MediaViewerActivity, f, item.name, item.mime) }
            toast(if (ok) "Saved to gallery (original quality)" else "Save failed")
        }
    }

    private fun share() = withLocalFile { item, f ->
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = item.mime.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, contentUri(f))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share"))
        } catch (_: Throwable) { toast("No app to share with") }
    }

    private fun openWith() = withLocalFile { item, f ->
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri(f), item.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Throwable) { toast("No app can open this") }
    }

    private fun copyToClipboard() = withLocalFile { item, f ->
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newUri(contentResolver, item.name, contentUri(f)))
            toast("Copied")
        } catch (_: Throwable) { toast("Copy failed") }
    }

    /** Save the paused video frame as a full-resolution photo. */
    private fun grabFrame() {
        val posUs = (player?.currentPosition ?: 0L) * 1000
        withLocalFile { item, f ->
            lifecycleScope.launch {
                val out = withContext(Dispatchers.IO) {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(f.absolutePath)
                        val bmp = r.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@withContext null
                        val file = File(cacheDir, "frame_${System.currentTimeMillis()}.jpg")
                        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                        bmp.recycle()
                        val name = item.name.substringBeforeLast('.') + "_frame_${posUs / 1_000_000}s.jpg"
                        if (FileRepository.saveFileToGallery(this@MediaViewerActivity, file, name, "image/jpeg")) file else null
                    } catch (_: Throwable) { null } finally { try { r.release() } catch (_: Throwable) {} }
                }
                toast(if (out != null) "Frame saved to gallery" else "Could not grab this frame")
            }
        }
    }

    private fun showInfo() = withLocalFile { item, f ->
        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) {
                val lines = mutableListOf<String>()
                lines += "Name: ${item.name}"
                lines += "Type: ${item.mime}"
                lines += "Size: ${FileRepository.formatSize(f.length())}"
                if (item.isVideo) {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(f.absolutePath)
                        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                        if (w != null && h != null) lines += "Resolution: $w × $h"
                        if (d != null) lines += "Length: ${fmt(d)}"
                        if (br != null) lines += "Bitrate: ${br / 1000} kbps"
                        if (Build.VERSION.SDK_INT >= 23) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                            ?.toFloatOrNull()?.let { lines += "Frame rate: ${"%.0f".format(it)} fps" }
                    } catch (_: Throwable) {} finally { try { r.release() } catch (_: Throwable) {} }
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, o)
                    if (o.outWidth > 0) lines += "Resolution: ${o.outWidth} × ${o.outHeight} (%.1f MP)".format(o.outWidth.toLong() * o.outHeight / 1_000_000.0)
                    try {
                        val ex = androidx.exifinterface.media.ExifInterface(f.absolutePath)
                        val model = listOfNotNull(ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE),
                            ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL)).joinToString(" ")
                        if (model.isNotBlank()) lines += "Camera: $model"
                        ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)?.let { lines += "Taken: $it" }
                    } catch (_: Throwable) {}
                }
                if (item.sender.isNotBlank()) lines += "Sent by: ${item.sender}"
                if (item.timestamp.isNotBlank()) lines += "Sent: ${item.timestamp.formatTimestamp()}"
                lines += "Quality: original file, exactly as sent"
                lines.joinToString("\n")
            }
            AlertDialog.Builder(this@MediaViewerActivity).setTitle("Details").setMessage(details).setPositiveButton("OK", null).show()
        }
    }

    private fun showMore() {
        val item = items.getOrNull(currentIndex) ?: return
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        if (item.isVideo) {
            opts += "Picture in picture" to { enterPip() }
            opts += "Save this frame as a photo" to { player?.pause(); grabFrame() }
            opts += "Playback speed" to { pickSpeed() }
        }
        opts += "Open with..." to { openWith() }
        opts += "Copy" to { copyToClipboard() }
        opts += "Details" to { showInfo() }
        AlertDialog.Builder(this).setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }.show()
    }

    // ═════════════════════════════════════════════════════════════════════════
    override fun onPause() {
        super.onPause()
        saveResumePosition()
        val pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!pip) player?.pause()
    }

    override fun onStop() {
        super.onStop()
        if (inPip) player?.pause()   // PiP window closed
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        saveResumePosition()
        playerView?.player = null
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }

    /** Lets the user drag the media down to close the viewer. */
    private class DismissFrame(ctx: Context) : FrameLayout(ctx) {
        var canStartDismiss: () -> Boolean = { true }
        var onDismissProgress: (Float) -> Unit = {}
        var onDismiss: () -> Unit = {}
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount == 1 && canStartDismiss()) {
                        val dy = ev.y - downY; val dx = ev.x - downX
                        if (dy > slop * 2 && dy > abs(dx) * 1.6f) { dragging = true; return true }
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val content = getChildAt(0) ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val dy = (ev.y - downY).coerceAtLeast(0f)
                    content.translationY = dy
                    val f = (dy / (height * 0.5f)).coerceIn(0f, 1f)
                    content.scaleX = 1f - f * 0.15f; content.scaleY = 1f - f * 0.15f
                    onDismissProgress(f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    if (content.translationY > height * 0.18f) onDismiss()
                    else {
                        content.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(180).start()
                        onDismissProgress(0f)
                    }
                }
            }
            return true
        }
    }

    companion object {
        const val FILE_ID = "file_id"
        const val FILE_NAME = "file_name"
        const val MIME_TYPE = "mime_type"
        const val MEDIA_URL = "media_url"
        const val CONVERSATION_ID = "conversation_id"

        fun start(context: Context, fileId: String, fileName: String, mimeType: String,
                  mediaUrl: String = "", conversationId: String? = null) {
            context.startActivity(Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(FILE_ID, fileId)
                putExtra(FILE_NAME, fileName)
                putExtra(MIME_TYPE, mimeType)
                putExtra(MEDIA_URL, mediaUrl)
                if (conversationId != null) putExtra(CONVERSATION_ID, conversationId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
