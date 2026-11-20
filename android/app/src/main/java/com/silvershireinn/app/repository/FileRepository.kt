package com.silvershireinn.app.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.silvershireinn.app.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SharedFileInfo(
    val fileId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val uploadedBy: String,
    val uploadedAt: String
)

object FileRepository {

    private const val TAG = "FileRepo"

    /** Our auth headers on every request (per-device token, or the legacy password pair). */
    private val authInterceptor = okhttp3.Interceptor { chain ->
        val b = chain.request().newBuilder()
        com.silvershireinn.app.network.MediaAuth.headers().forEach { (k, v) -> b.header(k, v) }
        chain.proceed(b.build())
    }

    /** Standard client for small requests (profile pics, metadata, downloads). */
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    /** Long-timeout client for large file uploads (videos). */
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    /** http(s) base URL. Older installs stored ws:// / wss://; only the scheme is swapped. */
    private fun httpBase(): String {
        val u = PreferenceManager.serverUrl.trim().trimEnd('/')
        return when {
            u.startsWith("wss://", true) -> "https://" + u.substring(6)
            u.startsWith("ws://", true) -> "http://" + u.substring(5)
            else -> u
        }
    }

    // ── Upload progress / cancel ─────────────────────────────────────────────
    /** Percent (0-100) of each running upload, keyed by message id (shown on the bubble). */
    val uploadProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    private val activeCalls = java.util.concurrent.ConcurrentHashMap<String, okhttp3.Call>()

    /** Stop an upload for real. Cancelling the coroutine alone let the HTTP upload finish,
     *  so a "cancelled" group file still reached everyone. */
    fun cancelUpload(messageId: String) { activeCalls.remove(messageId)?.cancel() }

    @Synchronized private fun setProgress(key: String?, pct: Int?) {
        if (key == null) return
        val m = uploadProgress.value.toMutableMap()
        if (pct == null) m.remove(key) else m[key] = pct
        uploadProgress.value = m
    }

    /** Counts bytes as they are written so the bubble can show a real percentage. */
    private class Counting(
        inner: okio.Sink, private val total: Long, private val onPct: (Int) -> Unit
    ) : okio.ForwardingSink(inner) {
        private var done = 0L; private var last = -1
        override fun write(source: okio.Buffer, byteCount: Long) {
            super.write(source, byteCount)
            done += byteCount
            if (total > 0) {
                val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                if (p != last) { last = p; onPct(p) }
            }
        }
    }

    private fun RequestBody.withProgress(key: String?, total: Long): RequestBody {
        if (key == null || total <= 0) return this
        val src = this
        return object : RequestBody() {
            override fun contentType() = src.contentType()
            override fun contentLength() = src.contentLength()
            override fun writeTo(sink: BufferedSink) {
                val counting = Counting(sink, total) { setProgress(key, it) }.buffer()
                src.writeTo(counting)
                counting.flush()
            }
        }
    }

    /** Run a call that can be cancelled by [cancelUpload]; always cleans up. */
    private fun <T> tracked(key: String?, call: okhttp3.Call, block: (okhttp3.Response) -> T): T {
        if (key != null) activeCalls[key] = call
        try {
            return call.execute().use(block)
        } finally {
            if (key != null) { activeCalls.remove(key); setProgress(key, null) }
        }
    }

    /** Public HTTP base for download links / update prompts. */
    fun publicHttpBase(): String = httpBase()

    // %20 for spaces: URLEncoder's '+' broke names like "Silvershire Updates" in URL paths.
    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ─────────────────────────────────────────────────────────────────────────
    //  UPLOAD — PUT /upload
    //  Returns the server-assigned fileId, or null on failure.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun uploadFile(
        conversationWith: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        messageId: String? = null,  // group uploads: server stores the message under this id
        key: com.silvershireinn.app.crypto.MediaCrypto.Key? = null   // end-to-end encrypt the file
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url      = "${httpBase()}/upload"
            val data = if (key != null) com.silvershireinn.app.crypto.MediaCrypto.encryptBytes(bytes, key) else bytes
            val body    = data.toRequestBody((if (key != null) "application/octet-stream" else mimeType).toMediaTypeOrNull())
                .withProgress(messageId, data.size.toLong())
            val request = Request.Builder()
                .url(url)
                .put(body)
                .header("x-conversation-with",  conversationWith)
                .header("x-group-e2e", "1")   // group files: we send the encrypted message ourselves
                // Encrypted: the server learns neither the name nor the type.
                .header("x-filename",           if (key != null) "file" else encode(fileName))
                .header("x-mime-type",          if (key != null) "application/octet-stream" else mimeType)
                .apply { if (key != null) header("x-e2e", "1") }
                .apply { if (!messageId.isNullOrBlank()) header("x-message-id", messageId) }
                .build()

            tracked(messageId, uploadClient.newCall(request)) { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Upload failed HTTP ${resp.code}")
                    null
                } else {
                    resp.body?.string()?.let { JSONObject(it).optString("fileId").takeIf { id -> id.isNotBlank() } }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UPLOAD (streaming) — PUT /upload
    //  Reads directly from a ContentResolver Uri without loading into memory.
    //  Use for large files (videos) to avoid OOM. Returns fileId or null.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun uploadFileFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        conversationWith: String,
        fileName: String,
        mimeType: String,
        knownFileSize: Long = -1L,  // pre-computed by caller to avoid opening the FD twice
        messageId: String? = null,  // group uploads: server stores the message under this id
        key: com.silvershireinn.app.crypto.MediaCrypto.Key? = null   // end-to-end encrypt the file
    ): String? = withContext(Dispatchers.IO) {
        try {
            val username = PreferenceManager.username
            val password = PreferenceManager.serverPassword
            val url      = "${httpBase()}/upload"

            // Use the caller-provided size if available; otherwise fall back to a FD open.
            // Avoids opening the content URI's file descriptor more than once, which can
            // fail silently for some content providers (e.g. cloud-backed gallery URIs).
            val fileSize = if (knownFileSize >= 0L) knownFileSize else try {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Exception) { -1L }

            // Build a streaming RequestBody that reads from the URI InputStream on demand
            val sendSize = if (key != null) com.silvershireinn.app.crypto.MediaCrypto.encryptedSize(fileSize) else fileSize
            val body = object : RequestBody() {
                override fun contentType()   = (if (key != null) "application/octet-stream" else mimeType).toMediaTypeOrNull()
                override fun contentLength() = sendSize
                override fun writeTo(sink: BufferedSink) {
                    // A missing stream used to upload an EMPTY file "successfully".
                    val raw = contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Cannot read the file")
                    val stream = if (key != null) com.silvershireinn.app.crypto.MediaCrypto.EncryptingStream(raw, key) else raw
                    stream.use { sink.writeAll(it.source()) }
                }
            }.withProgress(messageId, sendSize)

            val request = Request.Builder()
                .url(url)
                .put(body)
                .header("x-conversation-with",  conversationWith)
                .header("x-group-e2e", "1")   // group files: we send the encrypted message ourselves
                .header("x-filename",           if (key != null) "file" else encode(fileName))
                .header("x-mime-type",          if (key != null) "application/octet-stream" else mimeType)
                .apply { if (key != null) header("x-e2e", "1") }
                .apply { if (!messageId.isNullOrBlank()) header("x-message-id", messageId) }
                .build()

            tracked(messageId, uploadClient.newCall(request)) { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Streaming upload failed HTTP ${resp.code}")
                    null
                } else {
                    resp.body?.string()?.let { JSONObject(it).optString("fileId").takeIf { id -> id.isNotBlank() } }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming upload exception: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DOWNLOAD — GET /file/:fileId
    //  Returns raw bytes, or null on failure.
    //  WARNING: loads the entire file into memory — only use for small files
    //  (images, voice messages). For videos use downloadFileToPath() instead.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun downloadFile(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url      = "${httpBase()}/file/$fileId"
            val request  = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Download failed HTTP ${resp.code}")
                    null
                } else {
                    val raw = resp.body?.bytes()
                    val info = com.silvershireinn.app.utils.MediaKeys.get(fileId)
                    if (raw != null && info != null) com.silvershireinn.app.crypto.MediaCrypto.decryptBytes(raw, info.key) else raw
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download exception: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DOWNLOAD (streaming) — GET /file/:fileId
    //  Streams the HTTP response body directly to destFile without loading
    //  the whole file into memory. Use for videos and any large attachment.
    //  Returns true on success, false on failure.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun downloadFileToPath(fileId: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url      = "${httpBase()}/file/$fileId"
                val request  = Request.Builder().url(url).get().build()
                // Use uploadClient for large video downloads (300 s read timeout)
                uploadClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Stream download failed HTTP ${resp.code}")
                        return@withContext false
                    }
                    val body = resp.body ?: return@withContext false
                    destFile.parentFile?.mkdirs()
                    val info = com.silvershireinn.app.utils.MediaKeys.get(fileId)
                    body.byteStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            // End-to-end encrypted: decrypt while saving (fails on any change).
                            if (info != null) com.silvershireinn.app.crypto.MediaCrypto.decryptStream(input.buffered(1 shl 16), output, info.key)
                            else input.copyTo(output)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Stream download exception: ${e.message}")
                destFile.delete()   // clean up partial file
                false
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  LIST shared files in a conversation — GET /files/:userA/:userB
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun getSharedFiles(conversationWith: String): List<SharedFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                val username = PreferenceManager.username
                val userEnc  = encode(username)
                val otherEnc = encode(conversationWith)
                val url      = "${httpBase()}/files/$userEnc/$otherEnc"
                val request  = Request.Builder().url(url).get().build()
                val text = client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                } ?: return@withContext emptyList()
                val json     = JSONObject(text)
                val arr      = json.optJSONArray("files") ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull {
                    val f = arr.optJSONObject(it) ?: return@mapNotNull null
                    // Encrypted files: the server only has "file"; the real details are ours.
                    val known = com.silvershireinn.app.utils.MediaKeys.get(f.optString("fileId"))
                    SharedFileInfo(
                        fileId     = f.optString("fileId"),
                        fileName   = known?.name ?: f.optString("originalName"),
                        mimeType   = known?.mime ?: f.optString("mimeType"),
                        size       = known?.size?.takeIf { s -> s >= 0 } ?: f.optLong("size"),
                        uploadedBy = f.optString("uploadedBy"),
                        uploadedAt = f.optString("uploadedAt")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "List files exception: ${e.message}")
                emptyList()
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  SAVE to Downloads folder
    // ─────────────────────────────────────────────────────────────────────────
    fun saveToDownloads(context: Context, fileName: String, mimeType: String, bytes: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveToDownloads: ${e.message}")
            false
        }
    }

    /** Extension used for cached copies of an attachment. */
    fun extFor(mime: String?, name: String? = null): String {
        val fromName = name?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }?.lowercase()
        if (fromName != null) return ".$fromName"
        val m = mime?.lowercase() ?: return ""
        return when {
            m.contains("jpeg") || m.contains("jpg") -> ".jpg"
            m.contains("png") -> ".png"; m.contains("gif") -> ".gif"; m.contains("webp") -> ".webp"
            m.contains("heic") -> ".heic"; m.contains("mp4") -> ".mp4"; m.contains("webm") -> ".webm"
            m.contains("quicktime") -> ".mov"; m.contains("mpeg") && m.startsWith("audio") -> ".mp3"
            m.startsWith("audio/") -> ".m4a"
            else -> ""
        }
    }

    /**
     * Local copy of an attachment (cacheDir/viewer_<id><ext>), downloaded once by streaming
     * to disk. Shared by the viewer, voice playback, save and share so nothing downloads twice
     * and nothing is ever held fully in memory.
     */
    private val cacheLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    suspend fun ensureCached(context: Context, fileId: String, mime: String?, name: String? = null): File? =
        withContext(Dispatchers.IO) {
            if (fileId.isBlank()) return@withContext null
            val ext = extFor(mime, name)
            val f = File(context.cacheDir, "viewer_$fileId$ext")
            if (f.exists() && f.length() > 0) return@withContext f
            // One download per file: the viewer, voice player and save could start the same
            // download at once and write over each other's partial file (corrupt media).
            val lock = cacheLocks.getOrPut(fileId) { kotlinx.coroutines.sync.Mutex() }
            lock.lock()
            try {
                if (f.exists() && f.length() > 0) return@withContext f
                val tmp = File(context.cacheDir, "viewer_$fileId$ext.part")
                if (downloadFileToPath(fileId, tmp) && tmp.length() > 0) {
                    if (tmp.renameTo(f)) f else tmp
                } else { tmp.delete(); null }
            } finally {
                lock.unlock()
                cacheLocks.remove(fileId, lock)
            }
        }

    /**
     * Save a file into the phone's gallery (photos -> Pictures/Silvershire, videos ->
     * Movies/Silvershire) or Downloads for anything else. Streams, so any size is safe.
     */
    fun saveFileToGallery(context: Context, src: File, fileName: String, mimeType: String): Boolean {
        return try {
            val isImg = mimeType.startsWith("image/"); val isVid = mimeType.startsWith("video/")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = when {
                    isImg -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    isVid -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else  -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, when {
                        isImg -> Environment.DIRECTORY_PICTURES + "/Silvershire"
                        isVid -> Environment.DIRECTORY_MOVIES + "/Silvershire"
                        else  -> Environment.DIRECTORY_DOWNLOADS
                    })
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values) ?: return false
                try {
                    val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("no output")
                    out.use { o -> src.inputStream().use { it.copyTo(o) } }
                    values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (e: Throwable) {
                    // Don't leave a broken half-saved entry in the gallery.
                    try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                    throw e
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(when {
                    isImg -> Environment.DIRECTORY_PICTURES; isVid -> Environment.DIRECTORY_MOVIES
                    else -> Environment.DIRECTORY_DOWNLOADS
                }).let { if (isImg || isVid) File(it, "Silvershire") else it }
                dir.mkdirs()
                // Never overwrite an earlier saved file with the same name.
                var out = File(dir, fileName)
                var n = 1
                while (out.exists()) {
                    val base = fileName.substringBeforeLast('.'); val ext = fileName.substringAfterLast('.', "")
                    out = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"); n++
                }
                src.inputStream().use { i -> FileOutputStream(out).use { i.copyTo(it) } }
                try { android.media.MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf(mimeType), null) } catch (_: Throwable) {}
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "saveFileToGallery: ${e.message}")
            false
        }
    }

    /** Format file size for display, e.g. "2.3 MB" */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024L              -> "$bytes B"
        bytes < 1024L * 1024       -> "%.1f KB".format(bytes / 1024f)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024))
        else                        -> "%.1f GB".format(bytes / (1024f * 1024 * 1024))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PROFILE PICTURE — PUT /profile_pic  (authenticated, own pic)
    //  Returns true on success.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun uploadProfilePic(bytes: ByteArray, mime: String = "image/jpeg"): Boolean =
        withContext(Dispatchers.IO) {
        try {
            val cleanMime = mime.substringBefore(';').trim().ifBlank { "image/jpeg" }
            val isVideo = cleanMime.startsWith("video/")
            val max = if (isVideo) 12L * 1024 * 1024 else 5L * 1024 * 1024
            if (bytes.size.toLong() > max) {
                Log.e(TAG, "Profile pic too large: ${bytes.size} > $max ($cleanMime)")
                return@withContext false
            }
            val username = PreferenceManager.username
            val password = PreferenceManager.serverPassword
            val url      = "${httpBase()}/profile_pic"
            val body     = bytes.toRequestBody(cleanMime.toMediaTypeOrNull())
            val request  = Request.Builder()
                .url(url)
                .put(body)
                .header("x-mime-type", cleanMime)
                .header("Content-Type", cleanMime)
                .build()
            val ok = (if (isVideo) uploadClient else client).newCall(request).execute().use { r ->
                r.isSuccessful.also { if (!it) Log.e(TAG, "Profile pic upload failed HTTP ${r.code}") } }
            if (!ok) return@withContext false
            Log.d(TAG, "Profile pic uploaded successfully ($cleanMime)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Profile pic upload exception: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PROFILE PICTURE — GET /profile_pic/:username  (mutual friend auth)
    //  Downloads and caches to filesDir/contact_pics/{username}.jpg.
    //  Returns the local file path on success, null on failure/not found.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun downloadContactProfilePic(context: Context, contactUsername: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val target   = encode(contactUsername)
                val url      = "${httpBase()}/profile_pic/$target"
                val dir = File(context.filesDir, "contact_pics")
                // null = unchanged (304) or none: callers only refresh avatars on a real change.
                fetchMedia(url, dir, contactUsername, onGone = { contactPicFile(context, contactUsername).delete() })
                    ?.takeIf { it.second }?.first?.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Profile pic download exception for $contactUsername: ${e.message}")
                null
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  PROFILE PICTURE — GET /profile_pic/{own username} (for reinstall restore)
    //  Downloads the own user's profile pic from server and saves it to filesDir.
    //  Returns local path on success, null if the server has no pic.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun downloadOwnProfilePic(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val username = PreferenceManager.username
            val me       = encode(username)
            val url      = "${httpBase()}/profile_pic/$me"
            // Unchanged since last time (304) returns the existing file without downloading.
            fetchMedia(url, context.filesDir, "profile_pic")?.first?.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Own profile pic download exception: ${e.message}")
            null
        }
    }

    /** Returns the local cache file for a contact's profile picture (any supported ext). */
    fun contactPicFile(context: Context, contactUsername: String): File {
        val dir = File(context.filesDir, "contact_pics")
        dir.mkdirs()
        return com.silvershireinn.app.utils.ProfileMediaHelper.findInDir(dir, contactUsername)
            ?: File(dir, "$contactUsername.jpg")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BANNER PICTURE — PUT /banner_pic  (authenticated, own banner)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun uploadBannerPic(bytes: ByteArray, mime: String = "image/jpeg"): Boolean =
        withContext(Dispatchers.IO) {
        try {
            val cleanMime = mime.substringBefore(';').trim().ifBlank { "image/jpeg" }
            val isVideo = cleanMime.startsWith("video/")
            val max = if (isVideo) 25L * 1024 * 1024 else 8L * 1024 * 1024
            if (bytes.size.toLong() > max) {
                Log.e(TAG, "Banner pic too large: ${bytes.size} > $max ($cleanMime)")
                return@withContext false
            }
            val username = PreferenceManager.username
            val password = PreferenceManager.serverPassword
            val url      = "${httpBase()}/banner_pic"
            val body     = bytes.toRequestBody(cleanMime.toMediaTypeOrNull())
            val request  = Request.Builder()
                .url(url)
                .put(body)
                .header("x-mime-type", cleanMime)
                .header("Content-Type", cleanMime)
                .build()
            val ok = (if (isVideo) uploadClient else client).newCall(request).execute().use { r ->
                r.isSuccessful.also { if (!it) Log.e(TAG, "Banner pic upload failed HTTP ${r.code}") } }
            if (!ok) return@withContext false
            Log.d(TAG, "Banner pic uploaded successfully ($cleanMime)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Banner pic upload exception: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BANNER PICTURE — GET /banner_pic/:username  (mutual friend auth)
    //  Downloads and caches to filesDir/contact_banners/{username}.jpg.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun downloadContactBannerPic(context: Context, contactUsername: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val target   = encode(contactUsername)
                val url      = "${httpBase()}/banner_pic/$target"
                val dir = File(context.filesDir, "contact_banners")
                fetchMedia(url, dir, contactUsername, goneCodes = setOf(404, 403),
                    onGone = { contactBannerFile(context, contactUsername).delete() })
                    ?.takeIf { it.second }?.first?.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Banner pic download exception for $contactUsername: ${e.message}")
                null
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  BANNER PICTURE — GET /banner_pic/{own username} (for reinstall restore)
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun downloadOwnBannerPic(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val username = PreferenceManager.username
            val me       = encode(username)
            val url      = "${httpBase()}/banner_pic/$me"
            fetchMedia(url, context.filesDir, "banner_pic")?.first?.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Own banner pic download exception: ${e.message}")
            null
        }
    }

    /** Returns the local cache file for a contact's banner picture (any supported ext). */
    fun contactBannerFile(context: Context, contactUsername: String): File {
        val dir = File(context.filesDir, "contact_banners")
        dir.mkdirs()
        return com.silvershireinn.app.utils.ProfileMediaHelper.findInDir(dir, contactUsername)
            ?: File(dir, "$contactUsername.jpg")
    }


    private fun extFromContentType(ct: String?): String {
        val m = (ct ?: "").substringBefore(';').trim().lowercase()
        return when {
            m == "image/png" -> "png"
            m == "image/gif" -> "gif"
            m == "image/webp" -> "webp"
            m == "video/mp4" -> "mp4"
            m == "video/webm" -> "webm"
            m == "image/jpeg" || m == "image/jpg" -> "jpg"
            m.startsWith("video/") -> "mp4"
            else -> "jpg"
        }
    }

    /**
     * Download a profile / banner picture or video into [dir]/[baseName].<ext>.
     * Sends the last ETag so an unchanged picture is not downloaded again on every login
     * (each reconnect used to re-download every friend's picture). Streams to a temp file and
     * renames, so a half-finished download never replaces a good picture.
     * Returns (file, changed) or null when there is none / the request failed.
     */
    private fun fetchMedia(
        url: String, dir: File, baseName: String,
        goneCodes: Set<Int> = setOf(404), onGone: (() -> Unit)? = null
    ): Pair<File, Boolean>? {
        dir.mkdirs()
        val existing = com.silvershireinn.app.utils.ProfileMediaHelper.findInDir(dir, baseName)
        val etagFile = File(dir, "$baseName.etag")
        val b = Request.Builder().url(url).get()
        if (existing != null && etagFile.exists()) {
            try { b.header("If-None-Match", etagFile.readText().trim()) } catch (_: Exception) {}
        }
        client.newCall(b.build()).execute().use { resp ->
            if (resp.code == 304 && existing != null) return existing to false
            if (!resp.isSuccessful) {
                if (resp.code in goneCodes) { onGone?.invoke(); etagFile.delete() }
                return null
            }
            val body = resp.body ?: return null
            val ext = extFromContentType(resp.header("Content-Type"))
            val tmp = File(dir, "$baseName.$ext.part")
            body.byteStream().use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            if (tmp.length() == 0L) { tmp.delete(); return null }
            // Clear sibling extensions so an old .jpg does not shadow a new .mp4
            com.silvershireinn.app.utils.ProfileMediaHelper.clearSiblings(dir, baseName, ext)
            val file = File(dir, "$baseName.$ext")
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            val etag = resp.header("ETag")
            try { if (etag != null) etagFile.writeText(etag) else etagFile.delete() } catch (_: Exception) {}
            return file to true
        }
    }

    /** Admin only on the server: [adminPassword] is the server's admin password. */
    suspend fun uploadBranding(appName: String, iconFile: File?, adminPassword: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            val boundary = "----gsbrand" + System.currentTimeMillis()
            val crlf = "\r\n"
            val bos = java.io.ByteArrayOutputStream()
            fun part(name: String, filename: String?, contentType: String, data: ByteArray) {
                bos.write("--$boundary$crlf".toByteArray())
                if (filename != null)
                    bos.write("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"$crlf".toByteArray())
                else
                    bos.write("Content-Disposition: form-data; name=\"$name\"$crlf".toByteArray())
                bos.write("Content-Type: $contentType$crlf$crlf".toByteArray())
                bos.write(data)
                bos.write(crlf.toByteArray())
            }
            part("appName", null, "text/plain", appName.toByteArray())
            if (iconFile != null && iconFile.exists()) {
                part("icon", "icon.png", "image/png", iconFile.readBytes())
            }
            bos.write("--$boundary--$crlf".toByteArray())
            val body = bos.toByteArray().toRequestBody("multipart/form-data; boundary=$boundary".toMediaTypeOrNull())
            val req = Request.Builder()
                .url("${httpBase()}/branding/build")
                .header("x-admin-password", adminPassword)
                .post(body)
                .build()
            uploadClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "uploadBranding: ${e.message}")
            false
        }
    }

    suspend fun brandingStatus(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${httpBase()}/branding/status")
                .get().build()
            val txt = client.newCall(req).execute().use { it.body?.string() } ?: "{}"
            val o = JSONObject(txt)
            mapOf(
                "status" to o.optString("status", "unknown"),
                "message" to o.optString("message", ""),
                "apkUrl" to o.optString("apkUrl", "")
            )
        } catch (e: Exception) {
            mapOf("status" to "error", "message" to (e.message ?: "fail"))
        }
    }

    suspend fun downloadBrandedApk(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${httpBase()}/download/silvershire-inn.apk")
                .get().build()
            val dest = File(context.cacheDir, "silvershire-inn-branded.apk")
            uploadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                dest.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
            }
            dest
        } catch (e: Exception) {
            Log.e(TAG, "downloadBrandedApk: ${e.message}")
            null
        }
    }


    /** GET /api/version — soft update metadata. */
    suspend fun fetchAppVersion(): com.silvershireinn.app.utils.AppUpdateHelper.VersionInfo? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("${httpBase()}/api/version").get().build()
                val txt = client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                    ?: return@withContext null
                val o = JSONObject(txt)
                com.silvershireinn.app.utils.AppUpdateHelper.VersionInfo(
                    versionCode = o.optInt("versionCode", 0),
                    versionName = o.optString("versionName", ""),
                    apkUrl = o.optString("apkUrl", "/download/silvershire-inn.apk"),
                    apkMtime = o.optLong("apkMtime", 0L)
                )
            } catch (e: Exception) {
                Log.e(TAG, "fetchAppVersion: ${e.message}")
                null
            }
        }


}
