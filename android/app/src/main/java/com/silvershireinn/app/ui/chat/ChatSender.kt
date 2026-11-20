package com.silvershireinn.app.ui.chat

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.crypto.CryptoManager
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.repository.MessageRepository
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.nowIso
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sending, the same way from the chat screen and the floating chat (chat heads).
 *
 * The floating chat had its own copies of all of this, and they had fallen behind: no bubble
 * while a file uploaded, no retry, no cancel, a failed upload just vanished, and a first
 * message to a friend went out unencrypted straight away.
 *
 *  - Text: saved locally first, encrypted for 1:1 chats, sent with reconnect + retry. A row
 *    that still fails stays unsent and WebSocketService resends it later.
 *  - Files / voice notes: a bubble appears at once with "Uploading 42%", two automatic retries
 *    on a flaky connection, cancel stops the actual upload, failure removes the bubble and
 *    calls [onError].
 * Everything runs on the app scope, so leaving the screen never cancels a send.
 */
object ChatSender {

    /**
     * Thrown instead of ever sending a message unencrypted: the friend's (or every group
     * member's) public key isn't known yet. The message stays on the phone as "sending" and goes
     * out encrypted as soon as the key arrives (the unsent-message loop retries it).
     */
    class NoKeyException(msg: String) : Exception(msg)

    private fun waitingForKeyToast(who: String) {
        val app = SilvershireApp.instance
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { android.widget.Toast.makeText(app, "Waiting for $who's encryption key. It sends (encrypted) as soon as it arrives.", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    private val repo by lazy { MessageRepository(SilvershireApp.database) }
    private val jobs = ConcurrentHashMap<String, Job>()

    private val me get() = PreferenceManager.username

    /** Send a text message to a friend ([groupId] null) or a group. */
    fun sendText(contactId: String, groupId: String?, groupName: String, text: String) {
        // Last line of defence: the panic word is never sent, it locks instead.
        if (com.silvershireinn.app.utils.PanicLock.matchesLockWord(text)) {
            com.silvershireinn.app.utils.PanicLock.lockNow(SilvershireApp.instance)
            return
        }
        val messageId = UUID.randomUUID().toString()
        val ts = nowIso()
        SilvershireApp.appScope.launch {
            try {
                if (groupId != null) {
                    repo.insertGroupOutgoing(me, groupId, groupName, text, ts, messageId)
                    val payload = encryptForGroup(groupId, text)
                    sendWithRetry { WebSocketManager.sendGroupMessage(groupId, payload, messageId, ts) }
                } else {
                    // Always store plaintext locally - the DB is never on the server
                    repo.insertOutgoing(me, contactId, text, ts, messageId)
                    val payload = encryptFor(contactId, text)
                    sendWithRetry { WebSocketManager.sendPrivateMessage(me, contactId, payload, messageId, ts) }
                }
            } catch (e: NoKeyException) {
                waitingForKeyToast(if (groupId != null) groupName.ifBlank { "the group" } else com.silvershireinn.app.utils.PreferenceManager.displayNameFor(contactId))
            } catch (e: Exception) {
                android.util.Log.e("ChatSender", "text send failed: ${e.message}")
            }
        }
    }

    /** Stream a picked / captured file. Returns the message id (for cancel). */
    fun sendFile(
        context: Context, contactId: String, groupId: String?, groupName: String, uri: Uri,
        mimeHint: String? = null, nameHint: String? = null, onError: (String) -> Unit = {}
    ): String {
        val messageId = UUID.randomUUID().toString()
        val ts = nowIso()
        val app = context.applicationContext
        val conv = groupId ?: contactId
        jobs[messageId] = SilvershireApp.appScope.launch {
            try {
                val cr = app.contentResolver
                val name = nameHint ?: fileName(app, uri)
                val mime = mimeHint?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                    ?: cr.getType(uri)?.takeIf { it != "application/octet-stream" } ?: guessMime(name)
                val size = try { cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L } catch (_: Exception) { -1L }
                repo.insertUploadingPlaceholder(me, conv, name, mime, size, ts, messageId, groupName)
                // End-to-end: a fresh key per file; the server only gets the encrypted bytes.
                val key = com.silvershireinn.app.crypto.MediaCrypto.newKey()
                val thumb = if (mime.startsWith("video/")) videoThumbB64(app, uri) else null
                var fileId: String? = null
                for (attempt in 0 until 3) {
                    if (attempt > 0) delay(if (attempt == 1) 2_000L else 6_000L)
                    fileId = FileRepository.uploadFileFromUri(cr, uri, conv, name, mime, size, messageId, key)
                    if (fileId != null) break
                }
                if (fileId != null && size in 0..(50L * 1024 * 1024)) {
                    // Keep our own copy so our bubble doesn't download + decrypt it again.
                    try {
                        val dst = java.io.File(app.cacheDir, "viewer_$fileId${FileRepository.extFor(mime, name)}")
                        cr.openInputStream(uri)?.use { i -> dst.outputStream().use { i.copyTo(it) } }
                    } catch (_: Exception) {}
                }
                finish(messageId, fileId, contactId, groupId, name, mime, size, ts, onError, key, thumb)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                fail(messageId, "Upload failed - ${e.message ?: "unexpected error"}", onError)
            } finally { jobs.remove(messageId) }
        }
        return messageId
    }

    /** Upload something already in memory (voice notes, edited photos). */
    fun sendBytes(
        contactId: String, groupId: String?, groupName: String, name: String, mime: String,
        bytes: ByteArray, onError: (String) -> Unit = {}
    ): String {
        val messageId = UUID.randomUUID().toString()
        val ts = nowIso()
        val conv = groupId ?: contactId
        jobs[messageId] = SilvershireApp.appScope.launch {
            try {
                repo.insertUploadingPlaceholder(me, conv, name, mime, bytes.size.toLong(), ts, messageId, groupName)
                val key = com.silvershireinn.app.crypto.MediaCrypto.newKey()
                var fileId: String? = null
                for (attempt in 0 until 3) {
                    if (attempt > 0) delay(if (attempt == 1) 2_000L else 6_000L)
                    // Groups: the server stores the message under this id (no duplicate).
                    fileId = FileRepository.uploadFile(conv, name, mime, bytes, messageId, key)
                    if (fileId != null) break
                }
                if (fileId != null) try {
                    java.io.File(SilvershireApp.instance.cacheDir, "viewer_$fileId${FileRepository.extFor(mime, name)}").writeBytes(bytes)
                } catch (_: Exception) {}
                finish(messageId, fileId, contactId, groupId, name, mime, bytes.size.toLong(), ts, onError, key, null)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                fail(messageId, "Upload failed - ${e.message ?: "unexpected error"}", onError)
            } finally { jobs.remove(messageId) }
        }
        return messageId
    }

    /** Small preview picture for a video message (travels encrypted inside the message). */
    private fun videoThumbB64(context: Context, uri: Uri): String? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val frame = (if (android.os.Build.VERSION.SDK_INT >= 27)
                r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
            else r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: return null
            val small = com.silvershireinn.app.utils.VideoThumbs.scale(frame, 320)
            val out = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            if (out.size() > 60_000) null
            else android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Throwable) { null } finally { try { r.release() } catch (_: Throwable) {} }
    }

    /** Stop an upload and remove its bubble. */
    fun cancel(messageId: String) {
        jobs.remove(messageId)?.cancel()
        FileRepository.cancelUpload(messageId)   // stop the HTTP upload itself
        SilvershireApp.appScope.launch { try { repo.deleteMessage(messageId) } catch (_: Exception) {} }
    }

    private suspend fun finish(
        messageId: String, fileId: String?, contactId: String, groupId: String?,
        name: String, mime: String, size: Long, ts: String, onError: (String) -> Unit,
        key: com.silvershireinn.app.crypto.MediaCrypto.Key? = null, thumbB64: String? = null
    ) {
        if (fileId == null) { fail(messageId, "Upload failed - check connection", onError); return }
        val attObj = JSONObject().put("fileId", fileId).put("name", name).put("mime", mime).put("size", size)
        key?.putInto(attObj)
        if (!thumbB64.isNullOrBlank()) attObj.put("th", thumbB64)
        com.silvershireinn.app.utils.MediaKeys.learn(attObj)   // before the bubble tries to show it
        repo.markUploadComplete(messageId, fileId)
        val att = JSONObject().put("_att", attObj).toString()
        try { sendAtt(att, contactId, groupId, messageId, ts) }
        catch (e: NoKeyException) { waitingForKeyToast(if (groupId != null) "the group" else com.silvershireinn.app.utils.PreferenceManager.displayNameFor(contactId)) }
    }

    private suspend fun sendAtt(att: String, contactId: String, groupId: String?, messageId: String, ts: String) {
        if (groupId != null) {
            // The server no longer announces group files in plain text (x-group-e2e):
            // the file message goes out encrypted like any group message.
            val gPayload = encryptForGroup(groupId, att)
            sendWithRetry { WebSocketManager.sendGroupMessage(groupId, gPayload, messageId, ts) }
            return
        }
        val payload = encryptFor(contactId, att)
        sendWithRetry { WebSocketManager.sendPrivateMessage(me, contactId, payload, messageId, ts) }
    }

    private suspend fun fail(messageId: String, msg: String, onError: (String) -> Unit) {
        try { repo.deleteMessage(messageId) } catch (_: Exception) {}
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            try { onError(msg) } catch (_: Exception) {}
        }
    }

    /**
     * End-to-end encrypt for [contactId]. No key yet (new friend, fresh install): ask for it
     * and wait up to 3 s instead of sending this message unencrypted right away.
     */
    suspend fun encryptFor(contactId: String, plaintext: String): String {
        return try {
            val dao = SilvershireApp.database.contactDao()
            var key = dao.getContact(contactId)?.publicKey?.takeIf { it.isNotBlank() }
            if (key == null) {
                WebSocketManager.sendRaw(JSONObject().put("type", "get_public_key").put("target", contactId).toString())
                repeat(15) {
                    if (key == null) {
                        delay(200)
                        key = dao.getContact(contactId)?.publicKey?.takeIf { it.isNotBlank() }
                    }
                }
            }
            val k = key
            if (k != null && CryptoManager.hasRsaKeyPair()) CryptoManager.encryptE2E(plaintext, k)
            else throw NoKeyException("no key for $contactId")          // never sent unencrypted
        } catch (e: NoKeyException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ChatSender", "E2E encrypt failed: ${e.javaClass.simpleName}")
            throw NoKeyException("encrypt failed")
        }
    }

    /**
     * End-to-end encrypt a group message for every member (key wrapped per member). Member
     * keys come from the server's group list; missing or stale ones are fetched (up to 3 s).
     * With no member keys at all it refuses (NoKeyException): nothing is ever sent unencrypted.
     */
    suspend fun encryptForGroup(groupId: String, plaintext: String): String {
        return try {
            val group = com.silvershireinn.app.repository.GroupRepository(SilvershireApp.database).getGroup(groupId)
            val members = try {
                val arr = org.json.JSONArray(group?.membersJson ?: "[]")
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() && it != me }
            } catch (_: Exception) { emptyList() }
            val dao = SilvershireApp.database.contactDao()
            suspend fun keyFor(u: String): String? =
                com.silvershireinn.app.utils.GroupKeys.get(u) ?: dao.getContact(u)?.publicKey?.takeIf { it.isNotBlank() }
            var missing = members.filter { keyFor(it) == null }
            if (missing.isNotEmpty() || !com.silvershireinn.app.utils.GroupKeys.fresh(groupId)) {
                WebSocketManager.requestGroupKeys(groupId)
                if (missing.isNotEmpty()) repeat(15) {
                    if (missing.isNotEmpty()) { delay(200); missing = members.filter { keyFor(it) == null } }
                }
            }
            val keys = members.mapNotNull { keyFor(it) }
            if (!CryptoManager.hasRsaKeyPair()) throw NoKeyException("no own key")
            if (keys.isEmpty()) {
                if (members.isEmpty()) CryptoManager.encryptGroupE2E(plaintext, emptyList())
                else throw NoKeyException("no member keys for $groupId")      // never sent unencrypted
            } else {
                if (missing.isNotEmpty()) android.util.Log.w("ChatSender", "group E2E: no key for ${missing.size} member(s)")
                CryptoManager.encryptGroupE2E(plaintext, keys)
            }
        } catch (e: NoKeyException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ChatSender", "group encrypt failed: ${e.javaClass.simpleName}")
            throw NoKeyException("group encrypt failed")
        }
    }

    /** Send now; if the socket is down, force a reconnect and keep trying for ~4 s. */
    suspend fun sendWithRetry(send: () -> Boolean): Boolean {
        if (send()) return true
        WebSocketManager.reconnectNow(force = true)
        repeat(12) {
            delay(350)
            if (send()) return true
        }
        return false   // the row stays unsent; WebSocketService resends it later
    }

    /** "> Name: their text" header for a reply (names, not usernames; no nested quotes). */
    fun quoteFor(reply: com.silvershireinn.app.model.Message): String {
        val who = if (reply.isSent) "You" else PreferenceManager.displayNameFor(reply.fromUser)
        val body = when {
            reply.isAttachment -> "[file] ${reply.attachmentName ?: "Attachment"}"
            else -> reply.encryptedPayload.let { t ->
                val sep = t.indexOf("\n\n")
                (if (t.startsWith("> ") && sep > 0) t.substring(sep + 2) else t).replace('\n', ' ').trim()
            }
        }
        return "> $who: ${body.take(120)}"
    }

    fun fileName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val n: String? = it.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file_${System.currentTimeMillis()}"
    }

    fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
