package com.silvershireinn.app.ui.chat

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.crypto.CryptoManager
import com.silvershireinn.app.model.Message
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.repository.MessageRepository
import com.silvershireinn.app.repository.SharedFileInfo
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.formatTimestamp
import com.silvershireinn.app.utils.nowIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

sealed class UploadState {
    object Idle : UploadState()
    data class Uploading(val fileName: String) : UploadState()
    data class Success(val fileName: String) : UploadState()
    data class Error(val message: String) : UploadState()
}

class ChatViewModel(val contactUsername: String) : ViewModel() {

    val currentUser: String = PreferenceManager.username
    private val repo = MessageRepository(SilvershireApp.database)

    /** Set by ChatActivity when this is a group conversation. */
    var groupId: String? = null
    var groupName: String = ""

    // A friend who used their panic phrase: the chat (or their part of a group) stays closed.
    val messages: Flow<List<Message>> = kotlinx.coroutines.flow.combine(
        repo.getMessages(contactUsername), com.silvershireinn.app.utils.PeerLocks.locked
    ) { list, locked -> com.silvershireinn.app.utils.PeerLocks.mask(contactUsername, list, locked, isGroup = groupId != null) }
    val attachments: Flow<List<Message>> = kotlinx.coroutines.flow.combine(
        repo.getAttachments(contactUsername), com.silvershireinn.app.utils.PeerLocks.locked
    ) { list, locked -> com.silvershireinn.app.utils.PeerLocks.maskFiles(contactUsername, list, locked, isGroup = groupId != null) }

    private val _isContactTyping = MutableStateFlow(false)
    val isContactTyping: StateFlow<Boolean> = _isContactTyping

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState

    private val _sharedFiles = MutableStateFlow<List<SharedFileInfo>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFileInfo>> = _sharedFiles

    private val _downloadEvent = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)
    val downloadEvent: SharedFlow<Pair<String, Boolean>> = _downloadEvent

    private var typingTimeoutJob: Job? = null

    // IDs of received messages that need read receipts but haven't been sent yet.
    // Drained only while the chat screen is visibly in the foreground (WhatsApp-like).
    private val pendingReceiptIds = mutableListOf<String>()

    /**
     * True only while ChatActivity is resumed (messages on screen).
     * Prevents false blue checkmarks when the chat is in the back stack / screen off / backgrounded.
     */
    @Volatile
    private var chatVisible: Boolean = false


    /**
     * Called from ChatActivity.onResume / onPause.
     * Read receipts are ONLY flushed while the conversation is actually visible.
     */
    fun setChatVisible(visible: Boolean) {
        chatVisible = visible
        if (visible) {
            viewModelScope.launch {
                val unreadIds = repo.markAllReadAndGetUnread(contactUsername)
                pendingReceiptIds.addAll(unreadIds.filter { it !in pendingReceiptIds })
                flushPendingReceipts()
                // Tell my other devices (web) to clear badge/notif for this chat.
                WebSocketManager.sendConversationRead(currentUser, contactUsername)
            }
        }
    }

    init {
        // Queue unread IDs; do NOT send receipts until chat is visible via setChatVisible(true).
        viewModelScope.launch {
            val unreadIds = repo.markAllReadAndGetUnread(contactUsername)
            pendingReceiptIds.addAll(unreadIds)
            if (chatVisible) flushPendingReceipts()
        }
        viewModelScope.launch {
            WebSocketManager.events.collect { json ->
                val type = json.optString("type")
                val from = json.optString("from")
                when (type) {
                    // WS authenticated - flush only if this chat is currently on screen
                    "login_ok", "register_ok" -> if (chatVisible) flushPendingReceipts()

                    "typing" -> if (from == contactUsername) {
                        typingTimeoutJob?.cancel()
                        // typing:false = stopped (web). Senders repeat every ~2.5 s while typing,
                        // so 4 s (same as web) keeps the indicator steady instead of blinking.
                        if (json.has("typing") && !json.optBoolean("typing")) {
                            _isContactTyping.value = false
                        } else {
                            _isContactTyping.value = true
                            typingTimeoutJob = viewModelScope.launch {
                                delay(4000)
                                _isContactTyping.value = false
                            }
                        }
                    }
                    "private_message" -> if (from == contactUsername) {
                        typingTimeoutJob?.cancel()
                        _isContactTyping.value = false
                        // Read receipts are sent from onMessagesShown() once the message is
                        // actually in the list on screen.
                    }
                }
            }
        }
    }

    private var markingRead = false

    /** The open chat's list changed while on screen: mark incoming messages read + send receipts. */
    fun onMessagesShown() {
        if (!chatVisible || markingRead) return
        markingRead = true
        viewModelScope.launch {
            try {
                val fresh = repo.markAllReadAndGetUnread(contactUsername)
                if (fresh.isNotEmpty() && chatVisible) fresh.forEach { sendReadReceipt(it) }
            } finally { markingRead = false }
        }
    }

    /** Sends queued read receipts only while the conversation is visibly open. */
    private fun flushPendingReceipts() {
        if (!chatVisible) return
        if (!PreferenceManager.readReceipts) {
            pendingReceiptIds.clear()
            return
        }
        val ids = pendingReceiptIds.toList()
        pendingReceiptIds.clear()
        ids.forEach { sendReadReceipt(it) }
    }

    /** Sends a read_receipt signal to the server so the sender sees ✓✓. */
    private fun sendReadReceipt(messageId: String) {
        if (!PreferenceManager.readReceipts) return
        val json = JSONObject().apply {
            put("type",      "read_receipt")
            put("messageId", messageId)
            put("from",      currentUser)
            put("to",        contactUsername)
        }
        WebSocketManager.sendRaw(json.toString())
    }

    // Called by ChatActivity when the user sends a message - clears typing indicator immediately
    fun clearContactTyping() {
        typingTimeoutJob?.cancel()
        _isContactTyping.value = false
    }



    // ─────────────────────────────────────────────────────────────────────────
    //  TEXT MESSAGE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Encrypt [text] for [contactUsername] using the hybrid RSA+AES scheme, then
     * send via WebSocket.  If no public key is cached, request it from the server
     * and send plain text as a one-time fallback (the next message will be encrypted).
     */
    fun sendMessage(text: String) {
        val gid = groupId
        if (gid != null) {
            sendGroupTextMessage(gid, text)
        } else {
            sendPrivateTextMessage(text)
        }
    }

    private fun sendGroupTextMessage(gid: String, text: String) =
        ChatSender.sendText(contactUsername, gid, groupName, text)

    private fun sendPrivateTextMessage(text: String) =
        ChatSender.sendText(contactUsername, null, groupName, text)

    private var lastTypingSent = 0L

    /** Called on every keystroke; sent at most every 2.5 s (it used to send per keystroke). */
    fun sendTyping() {
        if (!PreferenceManager.typingIndicators || groupId != null) return
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 2_500L) return
        lastTypingSent = now
        WebSocketManager.sendTyping(currentUser, contactUsername)
    }

    /** Delete just for the local user - no server round-trip needed */
    /** Notes: there is only you, so a delete removes it everywhere (no "Message deleted" left). */
    fun deleteNote(msg: com.silvershireinn.app.model.Message) {
        viewModelScope.launch {
            repo.deleteMessage(msg.messageId)
            WebSocketManager.sendRaw(org.json.JSONObject().put("type", "delete_message").put("to", contactUsername)
                .put("messageId", msg.messageId).put("deleteForEveryone", true).toString())
        }
    }

    fun deleteForMe(msg: com.silvershireinn.app.model.Message) {
        viewModelScope.launch { repo.markDeleted(msg.messageId) }
    }

    /** Delete for all participants - server removes from history and notifies both sides */
    fun deleteForEveryone(msg: com.silvershireinn.app.model.Message) {
        viewModelScope.launch {
            repo.markDeleted(msg.messageId)   // optimistic local update
            val json = org.json.JSONObject().apply {
                put("type",            "delete_message")
                put("to",              contactUsername)
                put("messageId",       msg.messageId)
                put("deleteForEveryone", true)
            }
            WebSocketManager.sendRaw(json.toString())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FILE / ATTACHMENT UPLOAD
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Upload any file from a URI using streaming - reads directly from the URI
     * without loading the entire file into memory. Safe for large videos.
     *
     * A placeholder message (isUploading=true) is inserted immediately so the user
     * can see progress in the chat. Long-pressing the placeholder lets them cancel.
     */
    /** Stream a file (gallery, camera, share). See [ChatSender] for what happens. */
    fun uploadAndSendFile(context: Context, uri: Uri) {
        ChatSender.sendFile(context, contactUsername, groupId, groupName, uri) { msg ->
            _uploadState.value = UploadState.Error(msg)
        }
    }

    /** Cancel an in-progress upload and remove its bubble. */
    fun cancelUpload(messageId: String) = ChatSender.cancel(messageId)

    /** Voice notes / edited photos (already in memory). */
    fun uploadAndSendBytes(fileName: String, mimeType: String, bytes: ByteArray) {
        ChatSender.sendBytes(contactUsername, groupId, groupName, fileName, mimeType, bytes) { msg ->
            _uploadState.value = UploadState.Error(msg)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DOWNLOAD
    // ─────────────────────────────────────────────────────────────────────────
    fun downloadAttachment(context: Context, msg: Message) {
        val fileId   = msg.attachmentId   ?: return
        val fileName = msg.attachmentName ?: "file"
        val mimeType = msg.attachmentMime ?: "application/octet-stream"
        // Streamed to disk then copied into the gallery / Downloads: big videos used to be
        // loaded fully into memory here, which crashed phones with little RAM.
        SilvershireApp.appScope.launch {
            val f = FileRepository.ensureCached(context.applicationContext, fileId, mimeType, fileName)
            val ok = f != null && FileRepository.saveFileToGallery(context.applicationContext, f, fileName, mimeType)
            _downloadEvent.emit(fileName to ok)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SHARED FILES DRAWER
    // ─────────────────────────────────────────────────────────────────────────
    fun loadSharedFiles() {
        viewModelScope.launch {
            val pl = com.silvershireinn.app.utils.PeerLocks
            _sharedFiles.value = FileRepository.getSharedFiles(contactUsername).filter {
                if (groupId == null) !pl.isLocked(contactUsername) else !pl.isLocked(it.uploadedBy)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  EXPORT CHAT
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun exportChatAsText(): ByteArray = withContext(Dispatchers.IO) {
        val msgs = repo.getMessages(contactUsername).first()
        buildString {
            appendLine("Chat with ${if (groupId != null) groupName.ifBlank { "group" } else PreferenceManager.displayNameFor(contactUsername)} - Silvershire Inn")
            appendLine("=".repeat(50))
            appendLine()
            for (msg in msgs) {
                val sender = if (msg.isSent) "You" else PreferenceManager.displayNameFor(msg.fromUser)
                val time   = msg.timestamp.formatTimestamp()
                if (msg.isAttachment) appendLine("[$time] $sender: [file] ${msg.attachmentName}")
                else if (!msg.isDeleted) appendLine("[$time] $sender: ${msg.encryptedPayload}")
                else appendLine("[$time] $sender: [deleted]")
            }
        }.toByteArray(Charsets.UTF_8)
    }
}
