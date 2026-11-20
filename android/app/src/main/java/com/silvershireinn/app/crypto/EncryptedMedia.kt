package com.silvershireinn.app.crypto

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import com.silvershireinn.app.network.MediaAuth
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.utils.MediaKeys
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Makes end-to-end encrypted files work everywhere pictures and videos are shown.
 *
 *  - [CoilInterceptor]: any picture load of a ".../file/<id>" URL whose key we know is
 *    swapped for the decrypted local copy (downloaded + decrypted once, then cached).
 *  - [DataSourceFactory]: video playback straight from the server, decrypting on the fly.
 *    A seek fetches only the chunk it lands in (no waiting for the whole video).
 */
object EncryptedMedia {

    class CoilInterceptor : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val data = chain.request.data
            val url = when (data) {
                is String -> data
                is Uri -> data.toString()
                is okhttp3.HttpUrl -> data.toString()
                else -> null
            }
            val fid = MediaKeys.fileIdFromUrl(url)
            val info = if (fid != null) MediaKeys.get(fid) else null
            if (fid != null && info != null) {
                val f = FileRepository.ensureCached(chain.request.context, fid, info.mime, info.name)
                if (f != null) return chain.proceed(chain.request.newBuilder().data(f).build())
            }
            return chain.proceed(chain.request)
        }
    }

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Plays encrypted files by decrypting chunks as they stream; anything else is plain HTTP. */
    @UnstableApi
    class DataSourceFactory : DataSource.Factory {
        override fun createDataSource(): DataSource = Switching()
    }

    @UnstableApi
    private class Switching : BaseDataSource(true) {
        private var inner: DataSource? = null
        override fun open(dataSpec: DataSpec): Long {
            val fid = MediaKeys.fileIdFromUrl(dataSpec.uri.toString())
            val info = if (fid != null) MediaKeys.get(fid) else null
            val ds: DataSource = if (info != null && info.size >= 0) Decrypting(info)
                else DefaultHttpDataSource.Factory().setDefaultRequestProperties(MediaAuth.headers()).createDataSource()
            inner = ds
            transferInitializing(dataSpec)
            val n = ds.open(dataSpec)
            transferStarted(dataSpec)
            return n
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = inner?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
            if (n > 0) bytesTransferred(n)
            return n
        }
        override fun getUri(): Uri? = inner?.uri
        override fun close() { try { inner?.close() } finally { inner = null; transferEnded() } }
    }

    @UnstableApi
    private class Decrypting(private val info: MediaKeys.Info) : BaseDataSource(true) {
        private var uri: Uri? = null
        private var body: InputStream? = null
        private var resp: okhttp3.Response? = null
        private var chunk = ByteArray(0); private var chunkPos = 0
        private var index = 0L
        private var remaining = 0L
        private val step get() = info.key.cs + 16
        private val lastIndex get() = MediaCrypto.chunkCount(info.size, info.key.cs) - 1

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            val pos = dataSpec.position
            if (pos > info.size) throw IOException("seek past end")
            index = pos / info.key.cs
            val skip = (pos % info.key.cs).toInt()
            val req = Request.Builder().url(dataSpec.uri.toString())
                .header("Range", "bytes=${index * step}-")
                .also { MediaAuth.apply(it) }.build()
            val r = http.newCall(req).execute()
            if (!r.isSuccessful) { r.close(); throw IOException("HTTP ${r.code}") }
            resp = r
            body = r.body?.byteStream() ?: throw IOException("no body")
            // A server that ignored Range sent the file from the start: skip to our chunk.
            if (r.code == 200 && index > 0) skipFully(body!!, index * step)
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else info.size - pos
            chunk = ByteArray(0); chunkPos = 0
            if (skip > 0) { nextChunk(); chunkPos = minOf(skip, chunk.size) }
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else info.size - pos
        }

        private fun skipFully(s: InputStream, n: Long) {
            var left = n
            while (left > 0) { val k = s.skip(left); if (k <= 0) { if (s.read() < 0) throw IOException("short"); left-- } else left -= k }
        }

        private fun nextChunk(): Boolean {
            if (index > lastIndex) return false
            val want = if (index == lastIndex) (info.size - index * info.key.cs + 16).toInt() else step
            val buf = ByteArray(want)
            var n = 0
            val s = body ?: return false
            while (n < want) { val r = s.read(buf, n, want - n); if (r < 0) break; n += r }
            if (n < want) throw IOException("encrypted video is cut off")
            chunk = MediaCrypto.decryptChunk(info.key, index, buf, n, index == lastIndex)
            chunkPos = 0
            index++
            return true
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0) return C.RESULT_END_OF_INPUT
            while (chunkPos >= chunk.size) if (!nextChunk()) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), (chunk.size - chunkPos).toLong(), remaining).toInt()
            System.arraycopy(chunk, chunkPos, buffer, offset, n)
            chunkPos += n; remaining -= n
            return n
        }

        override fun getUri(): Uri? = uri
        override fun close() {
            try { body?.close() } catch (_: Exception) {}
            try { resp?.close() } catch (_: Exception) {}
            body = null; resp = null
        }
    }
}
