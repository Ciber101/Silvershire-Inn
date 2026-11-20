package com.silvershireinn.app.utils

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Moving video profile pictures for the small avatars beside messages, without a video player
 * per bubble (a chat can show dozens): the first few seconds of each friend's video are turned
 * into a short loop of small frames ONCE, then one shared timer flips every visible avatar of
 * that friend to the current frame. All their bubbles move together, for very little battery.
 */
object AvatarLoop {
    private const val FPS = 12
    private const val SECONDS = 3
    private const val SIZE = 96

    private class Loop(val frames: List<Bitmap>, val frameMs: Long)

    // Small LRU of finished loops (about 1 MB each).
    private val loops = object : LinkedHashMap<String, Loop>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Loop>?) = size > 10
    }
    private val building = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "avatar-loop").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val views = WeakHashMap<ImageView, String>()
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            var any = false
            val it = views.entries.iterator()
            while (it.hasNext()) {
                val (iv, key) = it.next().let { e -> e.key to e.value }
                if (iv == null || iv.tag != key.substringBeforeLast('|')) { it.remove(); continue }
                if (!iv.isAttachedToWindow) continue
                val loop = synchronized(loops) { loops[key] } ?: continue
                any = true
                val f = loop.frames[((now / loop.frameMs) % loop.frames.size).toInt()]
                iv.setImageBitmap(f)
            }
            if (any || views.isNotEmpty()) main.postDelayed(this, 1000L / FPS) else ticking = false
        }
    }

    /** Show [path] (a video profile picture) animated in [iv]. [still] shows until the loop is ready. */
    fun bind(iv: ImageView, path: String, still: (ImageView) -> Unit) {
        iv.tag = path
        if (DeviceTier.isLowEnd) { still(iv); return }      // weak phones: still frame only
        // Keyed by the file's time too, so a friend's new video replaces the old loop.
        val key = path + "|" + java.io.File(path).lastModified()
        val ready = synchronized(loops) { loops[key] }
        if (ready != null) iv.setImageBitmap(ready.frames[0]) else still(iv)
        views[iv] = key
        if (!ticking) { ticking = true; main.post(tick) }
        if (ready == null && building.add(key)) pool.execute { build(path, key) }
    }

    /** The view shows something else now. */
    fun unbind(iv: ImageView) { views.remove(iv) }

    /** A friend's picture changed: drop the old loop. */
    fun forget(path: String) { synchronized(loops) { loops.remove(path) } }

    private fun build(path: String, key: String) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val span = if (durMs in 1..(SECONDS * 1000L)) durMs else SECONDS * 1000L
            val count = ((span * FPS) / 1000L).toInt().coerceIn(1, SECONDS * FPS)
            val step = span * 1000L / count                       // microseconds
            val frames = ArrayList<Bitmap>(count)
            for (i in 0 until count) {
                val t = i * step
                val raw = if (Build.VERSION.SDK_INT >= 27)
                    r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, SIZE, SIZE)
                else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)?.let { VideoThumbs.scale(it, SIZE) }
                val f = raw ?: continue
                frames += if (f.config == Bitmap.Config.RGB_565) f else (f.copy(Bitmap.Config.RGB_565, false) ?: f).also { if (it !== f) f.recycle() }
            }
            if (frames.size >= 2) synchronized(loops) { loops[key] = Loop(frames, (span / frames.size).coerceAtLeast(40L)) }
        } catch (_: Throwable) {
        } finally {
            try { r.release() } catch (_: Throwable) {}
            building.remove(key)
        }
    }
}
