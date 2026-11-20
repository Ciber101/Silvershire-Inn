package com.silvershireinn.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.silvershireinn.app.network.MediaAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Video thumbnails that are fast and cannot run a cheap phone out of memory.
 *
 *  memory (size-capped by bytes) -> disk (cacheDir/vthumbs, survives restarts)
 *  -> already-downloaded video -> first 2 MB of the file -> streamed index read.
 *
 * Frames are scaled down to [MAX_DIM] before caching (a full 4K frame is ~33 MB) and at
 * most two videos are decoded at the same time.
 */
object VideoThumbs {
    private const val MAX_DIM = 480

    private val mem = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceIn(4 * 1024, 24 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val gate = Semaphore(2)
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    fun cached(fileId: String): Bitmap? = mem.get(fileId)

    suspend fun get(context: Context, fileId: String, url: String): Bitmap? {
        if (fileId.isBlank()) return null
        mem.get(fileId)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                mem.get(fileId)?.let { return@withPermit it }
                val dir = File(context.cacheDir, "vthumbs").apply { mkdirs() }
                val disk = File(dir, "${fileId.hashCode()}_${fileId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)}.jpg")
                if (disk.exists() && disk.length() > 0) {
                    val b = try { BitmapFactory.decodeFile(disk.absolutePath) } catch (_: Throwable) { null }
                    if (b != null) { mem.put(fileId, b); return@withPermit b }
                }
                val enc = MediaKeys.get(fileId)
                val frame = if (enc != null) {
                    // Encrypted video: the preview it carried, else the start of the file decrypted.
                    fromThumb(fileId) ?: fromLocal(context, fileId) ?: fromEncryptedStart(context, url, enc)
                } else fromLocal(context, fileId) ?: fromPartial(context, url) ?: fromStream(url)
                val small = frame?.let { scale(it) }
                if (small != null) {
                    mem.put(fileId, small)
                    try { disk.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) } } catch (_: Throwable) {}
                }
                small
            }
        }
    }

    private fun fromThumb(fileId: String): Bitmap? {
        val f = MediaKeys.thumbFile(fileId)
        return if (f.exists() && f.length() > 0) try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null } else null
    }

    /** First two chunks (about 2 MB) of an encrypted video, decrypted, for a frame. */
    private fun fromEncryptedStart(context: Context, url: String, info: MediaKeys.Info): Bitmap? {
        if (url.isBlank() || info.size < 0) return null
        val cs = info.key.cs
        val chunks = com.silvershireinn.app.crypto.MediaCrypto.chunkCount(info.size, cs)
        val take = minOf(2L, chunks)
        val step = cs + 16
        val tmp = try { File.createTempFile("vt_", ".mp4", context.cacheDir) } catch (_: Throwable) { return null }
        return try {
            val req = Request.Builder().url(url).header("Range", "bytes=0-${take * step - 1}")
                .also { MediaAuth.apply(it) }.build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val src = resp.body?.byteStream() ?: return null
                tmp.outputStream().use { out ->
                    for (i in 0 until take) {
                        val last = i == chunks - 1
                        val want = if (last) (info.size - i * cs + 16).toInt() else step
                        val buf = ByteArray(want); var n = 0
                        while (n < want) { val r = src.read(buf, n, want - n); if (r < 0) break; n += r }
                        if (n < want) break
                        out.write(com.silvershireinn.app.crypto.MediaCrypto.decryptChunk(info.key, i, buf, n, last))
                    }
                }
            }
            if (tmp.length() <= 0L) null else frameOf { it.setDataSource(tmp.absolutePath) }
        } catch (_: Throwable) { null } finally { tmp.delete() }
    }

    private fun fromLocal(context: Context, fileId: String): Bitmap? {
        val f = listOf(".mp4", ".webm", ".mov", ".mkv").map { File(context.cacheDir, "viewer_$fileId$it") }
            .firstOrNull { it.exists() && it.length() > 0 } ?: return null
        return frameOf { it.setDataSource(f.absolutePath) }
    }

    private fun fromPartial(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val tmp = try { File.createTempFile("vt_", ".mp4", context.cacheDir) } catch (_: Throwable) { return null }
        return try {
            val req = Request.Builder().url(url).header("Range", "bytes=0-2097151")
                .also { MediaAuth.apply(it) }.build()
            // Read at most 2 MB even if the server ignores Range (a whole video in memory = OOM).
            val wrote = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val src = resp.body?.byteStream() ?: return null
                var total = 0L
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (total < 2_097_152) {
                        val n = src.read(buf, 0, minOf(buf.size.toLong(), 2_097_152 - total).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n); total += n
                    }
                }
                total
            }
            if (wrote <= 0L) return null
            frameOf { it.setDataSource(tmp.absolutePath) }
        } catch (_: Throwable) { null } finally { tmp.delete() }
    }

    /** Phone-recorded videos keep their index at the END, so the first 2 MB is not enough. */
    private fun fromStream(url: String): Bitmap? {
        if (url.isBlank()) return null
        return frameOf { it.setDataSource(url, MediaAuth.headers()) }
    }

    private fun frameOf(setup: (MediaMetadataRetriever) -> Unit): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            setup(r)
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_DIM, MAX_DIM)
                    ?: r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_NEXT_SYNC, MAX_DIM, MAX_DIM)
            } else {
                r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_NEXT_SYNC)
            }
        } catch (_: Throwable) { null } finally { try { r.release() } catch (_: Throwable) {} }
    }

    fun scale(src: Bitmap, maxDim: Int = MAX_DIM): Bitmap {
        val w = src.width; val h = src.height
        val big = maxOf(w, h)
        if (big <= maxDim) return src
        val f = maxDim.toFloat() / big
        return try {
            Bitmap.createScaledBitmap(src, (w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1), true)
                .also { if (it !== src) src.recycle() }
        } catch (_: Throwable) { src }
    }

    /**
     * Decode an image file at most [maxDim] px on its longest side (power-of-two sampling,
     * cached by path + modified time). Use instead of BitmapFactory.decodeFile for avatars:
     * a big profile photo decoded at full size can use 50+ MB.
     */
    private val small = object : LruCache<String, Bitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun decodeSmall(path: String?, maxDim: Int = 256): Bitmap? {
        if (path.isNullOrBlank()) return null
        val f = File(path)
        if (!f.exists()) return null
        val key = "$path@${f.lastModified()}#$maxDim"
        small.get(key)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            bmp?.let { scale(it, maxDim) }?.also { small.put(key, it) }
        } catch (_: Throwable) { null }
    }
}
