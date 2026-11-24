package com.silvershireinn.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import java.io.File

/**
 * Shared helpers for profile/banner media that may be image or looping muted video.
 */
object ProfileMediaHelper {
    private val EXTS = listOf("jpg", "jpeg", "png", "gif", "webp", "mp4", "webm")

    fun isVideoPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")
    }

    fun isVideoMime(mime: String?): Boolean =
        mime != null && mime.lowercase().startsWith("video/")

    fun extForMime(mime: String?): String = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "image/jpeg", "image/jpg" -> "jpg"
        else -> if (isVideoMime(mime)) "mp4" else "jpg"
    }

    fun findInDir(dir: File, baseName: String): File? {
        if (!dir.exists()) return null
        for (ext in EXTS) {
            val f = File(dir, "$baseName.$ext")
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    fun findOwnProfilePic(context: Context): File? {
        val saved = PreferenceManager.profilePicturePath
        if (saved.isNotBlank()) {
            val f = File(saved)
            if (f.exists()) return f
        }
        for (ext in EXTS) {
            val f = File(context.filesDir, "profile_pic.$ext")
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    fun findOwnBanner(context: Context): File? {
        val saved = PreferenceManager.bannerPicPath
        if (saved.isNotBlank()) {
            val f = File(saved)
            if (f.exists()) return f
        }
        for (ext in EXTS) {
            val f = File(context.filesDir, "banner_pic.$ext")
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    fun contactPic(context: Context, username: String): File? =
        findInDir(File(context.filesDir, "contact_pics"), username)

    fun contactBanner(context: Context, username: String): File? =
        findInDir(File(context.filesDir, "contact_banners"), username)

    /** First video frame for chat-head / list thumbnails. Returns null on failure. */
    // Size is in KB (sizeOf below). It was 24 (= 24 KB), so nothing was ever cached and every
    // chat head / avatar re-decoded the video. 8 MB of small frames is plenty.
    private val frameCache = object : android.util.LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** First frame if it's already decoded (never decodes: safe on the main thread). */
    fun cachedVideoFrame(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val f = File(path)
        return frameCache.get("$path@${f.lastModified()}")
    }

    fun videoFrameBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank() || !isVideoPath(path)) return null
        val f = File(path)
        if (!f.exists()) return null
        val key = "$path@${f.lastModified()}"   // a changed profile video gets a fresh frame
        frameCache.get(key)?.let { return it }
        val retriever = MediaMetadataRetriever()
        val bmp = try {
            retriever.setDataSource(f.absolutePath)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { VideoThumbs.scale(it, 720) }
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        if (bmp != null) frameCache.put(key, bmp)
        return bmp
    }

    fun clearSiblings(dir: File, baseName: String, keepExt: String?) {
        if (!dir.exists()) return
        for (ext in EXTS) {
            if (keepExt != null && ext.equals(keepExt, true)) continue
            if (keepExt != null &&
                ((keepExt == "jpg" && ext == "jpeg") || (keepExt == "jpeg" && ext == "jpg"))
            ) continue
            try { File(dir, "$baseName.$ext").delete() } catch (_: Exception) {}
        }
    }
}

/**
 * Plays a muted looping video into a [TextureView].
 * If [imageView] already has a still drawable, it covers the TextureView until playback starts.
 * An empty ImageView is never forced on top (that hid video app-wide).
 */
class MutedLoopVideoHelper(
    private val context: Context,
    private val textureView: TextureView
) {
    private var mediaPlayer: MediaPlayer? = null
    private var surface: android.view.Surface? = null
    private var pendingPath: String? = null
    private var surfaceReady = false
    private var coverImage: ImageView? = null
    /** Video that should be playing; restarted when the surface comes back after the app resumes. */
    private var currentPath: String? = null
    private var token = 0
    /** Started, but its first frame is not on screen yet: the still keeps covering it until then
     *  (an empty video view showed the picture's coloured background for a moment: a blink). */
    private var awaitingFrame = false
    /** True once the video's own frames are showing. */
    val showingVideo: Boolean get() = mediaPlayer != null && !awaitingFrame && textureView.visibility == View.VISIBLE

    // ── Power: a moving picture only decodes while someone can see it ──
    private var prepared = false
    /** >0: play this long, then hold still on the current frame until [wake] (floating heads). */
    var playForMs: Long = 0L
    private var settled = false
    private val settle = Runnable { settled = true; applyRunState() }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val visListener = android.view.ViewTreeObserver.OnWindowVisibilityChangeListener { applyRunState() }

    private fun shouldPlay(): Boolean =
        screenOn && !settled && textureView.isAttachedToWindow && textureView.windowVisibility == View.VISIBLE

    /** Start or pause to match: screen on, its window showing, its play time not used up. */
    fun applyRunState() {
        val mp = mediaPlayer ?: return
        if (!prepared) return
        try {
            if (shouldPlay()) {
                if (!mp.isPlaying) mp.start()
                if (playForMs > 0) { main.removeCallbacks(settle); main.postDelayed(settle, playForMs) }
            } else if (mp.isPlaying) { mp.pause(); main.removeCallbacks(settle) }
        } catch (_: Throwable) {}
    }

    /** Something new happened (a message came in): move again for a while. */
    fun wake() { settled = false; main.post { applyRunState() } }

    init {
        synchronized(live) { live.add(this) }
        textureView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { try { v.viewTreeObserver.addOnWindowVisibilityChangeListener(visListener) } catch (_: Throwable) {}; applyRunState() }
            override fun onViewDetachedFromWindow(v: View) { try { v.viewTreeObserver.removeOnWindowVisibilityChangeListener(visListener) } catch (_: Throwable) {} }
        })
        if (textureView.isAttachedToWindow) try { textureView.viewTreeObserver.addOnWindowVisibilityChangeListener(visListener) } catch (_: Throwable) {}
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                surfaceReady = true
                val path = pendingPath ?: currentPath
                pendingPath = null
                if (path != null) start(path)
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                surfaceReady = false
                stop()
                // The still covers again, so when the view comes back (moved, window reopened)
                // the empty video view never shows before its first frame.
                coverImage?.let { c -> if (c.drawable != null) { c.visibility = View.VISIBLE; c.bringToFront() } }
                return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                if (!awaitingFrame || mediaPlayer == null) return
                awaitingFrame = false
                // The first real frame is in: now the video can take over from the still.
                textureView.post {
                    try {
                        if (mediaPlayer == null) return@post
                        textureView.bringToFront()
                        coverImage?.visibility = View.GONE
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun play(path: String?, imageView: ImageView? = null) {
        // Same video already playing here: keep it running. Every list update re-binds the rows,
        // and restarting each time left the videos showing a split second, then frozen.
        if (path != null && path == currentPath && mediaPlayer != null && textureView.visibility == View.VISIBLE) {
            if (imageView != null && imageView !== coverImage) { coverImage = imageView; imageView.visibility = View.GONE }
            return
        }
        stop()
        val myToken = ++token
        coverImage = imageView
        currentPath = null
        if (path.isNullOrBlank() || !File(path).exists() || !ProfileMediaHelper.isVideoPath(path)) {
            textureView.visibility = View.GONE
            return
        }
        // Lower-end phones: a still frame instead of a video decoder running nonstop for
        // every looping avatar (chat heads, lists). Big battery / smoothness win.
        if (DeviceTier.isLowEnd && imageView != null) {
            textureView.visibility = View.GONE
            // Decode the frame off the main thread (slow on exactly the phones this path is for).
            Thread {
                val still = ProfileMediaHelper.videoFrameBitmap(path)
                imageView.post {
                    if (myToken == token && still != null) { imageView.setImageBitmap(still); imageView.visibility = View.VISIBLE }
                }
            }.apply { isDaemon = true }.start()
            return
        }
        currentPath = path
        textureView.visibility = View.VISIBLE
        if (imageView != null && imageView.drawable != null) {
            imageView.visibility = View.VISIBLE
            imageView.bringToFront()
        } else {
            imageView?.visibility = View.GONE
        }
        if (surfaceReady && textureView.surfaceTexture != null) {
            start(path)
        } else if (textureView.isAvailable && textureView.surfaceTexture != null) {
            surfaceReady = true
            start(path)
        } else {
            pendingPath = path
        }
    }

    fun clear(imageView: ImageView? = null) {
        stop()
        token++
        pendingPath = null
        currentPath = null
        textureView.visibility = View.GONE
        imageView?.visibility = View.VISIBLE
        coverImage = null
    }

    private fun start(path: String) {
        stop()
        val st = textureView.surfaceTexture
        if (st == null) {
            pendingPath = path
            surfaceReady = false
            return
        }
        try {
            val sf = android.view.Surface(st).also { surface = it }
            val mp = MediaPlayer()
            mp.setDataSource(context, Uri.fromFile(File(path)))
            mp.setSurface(sf)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            mp.setOnPreparedListener { p ->
                try {
                    if (!surfaceReady) {
                        stop()
                        return@setOnPreparedListener
                    }
                    prepared = true
                    textureView.visibility = View.VISIBLE
                    // Only decodes when it can be seen (screen on, window showing); otherwise the
                    // still stays and it starts the moment it is seen.
                    if (shouldPlay()) { p.start(); if (playForMs > 0) { main.removeCallbacks(settle); main.postDelayed(settle, playForMs) } }
                    awaitingFrame = true    // a still on top stays there until the first frame
                    val cover = coverImage
                    if (cover == null || cover.drawable == null || cover.visibility != View.VISIBLE) textureView.bringToFront()
                } catch (_: Exception) {}
            }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                textureView.visibility = View.GONE
                coverImage?.visibility = View.VISIBLE   // show the still frame instead of nothing
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (_: Exception) {
            textureView.visibility = View.GONE
        }
    }

    private fun stop() {
        val mp = mediaPlayer
        mediaPlayer = null
        awaitingFrame = false
        prepared = false
        main.removeCallbacks(settle)
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.reset() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        surface = null
    }

    companion object {
        private val live = java.util.Collections.newSetFromMap(java.util.WeakHashMap<MutedLoopVideoHelper, Boolean>())
        @Volatile var screenOn = true
            private set
        /** Screen on / off: every moving picture starts / stops with it. */
        fun setScreenOn(on: Boolean) {
            screenOn = on
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val all = synchronized(live) { live.toList() }
                all.forEach { it.applyRunState() }
            }
        }
    }

    fun release() {
        stop()
        token++
        pendingPath = null
        currentPath = null
        coverImage = null
    }
}