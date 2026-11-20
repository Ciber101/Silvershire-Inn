package com.silvershireinn.app.repository

import com.silvershireinn.app.database.AppDatabase
import com.silvershireinn.app.model.Message
import com.silvershireinn.app.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import com.silvershireinn.app.utils.canonicalTs
import com.silvershireinn.app.utils.isoToMillis
import com.silvershireinn.app.utils.millisToIso
import java.util.UUID

class MessageRepository(private val db: AppDatabase) {

    /**
     * Messages in true chronological order. Sorted by parsed time (stable), not by the raw
     * string, because older rows mix formats (with/without millis, offsets) and a plain
     * string sort put some messages above ones that were sent before them.
     */
    fun getMessages(conversationId: String): Flow<List<Message>> =
        db.messageDao().getMessagesForConversation(conversationId)
            .map { inTimeOrder(it) }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)

    fun getAttachments(conversationId: String): Flow<List<Message>> =
        db.messageDao().getAttachmentsForConversation(conversationId)
            .map { inTimeOrder(it) }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)

    /**
     * Stored times are normalized to one sortable form (startup migration + every insert), so
     * the SQL order is already right. Re-parsing every timestamp with a regex on every change
     * (the old way, on the main thread) made big chats stutter; now only a list that still
     * holds an odd format gets the slow sort.
     */
    private fun inTimeOrder(list: List<Message>): List<Message> {
        val canonical = list.all { t -> t.timestamp.length == 24 && t.timestamp.endsWith("Z") }
        return if (canonical) list else list.sortedBy { isoToMillis(it.timestamp) ?: Long.MAX_VALUE }
    }

    /** Chat-list text for an attachment: "Photo", "Video", "Voice message", file name. */
    private fun attPrev(mime: String?, name: String?) = com.silvershireinn.app.utils.attachmentPreview(mime, name)

    /**
     * Timestamp for a message created on THIS device: never earlier than the newest message
     * already in the chat, so a reply always lands below what it answers even when this
     * phone's clock runs behind. Replaced by the server's time once message_sent arrives.
     */
    private suspend fun nextLocalTs(conversationId: String, requested: String): String {
        val want = isoToMillis(requested) ?: System.currentTimeMillis()
        val latest = db.messageDao().recentTimestamps(conversationId)
            .mapNotNull { isoToMillis(it) }
            // Ignore wildly future-dated rows (a device clock that was way off) so one bad
            // row cannot drag every new message into the future.
            .filter { it - want < 24L * 60 * 60 * 1000 }
            .maxOrNull()
        val t = if (latest != null && want <= latest) latest + 1 else want
        return millisToIso(t)
    }

    /** Adopt the server's authoritative timestamp (from message_sent) for our sent message. */
    suspend fun applyServerTimestamp(messageId: String, serverTs: String) {
        if (isoToMillis(serverTs) == null) return
        val ts = canonicalTs(serverTs)
        val msg = db.messageDao().getMessageById(messageId) ?: return
        if (msg.timestamp == ts) return
        db.messageDao().updateTimestamp(messageId, ts)
        val conv = db.conversationDao().getConversation(msg.conversationId)
        if (conv != null && conv.lastMessageTimestamp == msg.timestamp) {
            db.conversationDao().insertConversation(conv.copy(lastMessageTimestamp = ts))
        }
    }

    fun getConversations(): Flow<List<Conversation>> =
        db.conversationDao().getConversations()

    suspend fun insertOutgoing(from: String, to: String, plaintext: String, timestamp: String, messageId: String) {
        val timestamp = nextLocalTs(to, timestamp)
        val preview = if (plaintext.length > 60) plaintext.take(60) + "…" else plaintext
        val msg = Message(
            messageId        = messageId,
            conversationId   = to,
            fromUser         = from,
            toUser           = to,
            encryptedPayload = plaintext,
            plaintextPreview = preview,
            timestamp        = timestamp,
            isSent           = true
        )
        db.messageDao().insertMessage(msg)
        db.conversationDao().insertConversation(Conversation(to, preview, timestamp, 0))
    }

    suspend fun insertIncoming(from: String, to: String, payload: String, timestamp: String, messageId: String) {
        val timestamp = canonicalTs(timestamp)
        val preview = if (payload.length > 60) payload.take(60) + "…" else payload
        val msg = Message(
            messageId        = messageId,
            conversationId   = from,
            fromUser         = from,
            toUser           = to,
            encryptedPayload = payload,
            plaintextPreview = preview,
            timestamp        = timestamp,
            isSent           = false
        )
        db.messageDao().insertMessage(msg)
        db.conversationDao().insertConversation(Conversation(from, preview, timestamp, 0))
    }

    /**
     * Insert a placeholder message that appears in the chat while a file is uploading.
     * [isUploading]=true shows an animated spinner; call [markUploadComplete] when done.
     * [attachmentId] is temporarily null (filled in once upload succeeds).
     */
    suspend fun insertUploadingPlaceholder(
        from: String,
        to: String,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        timestamp: String,
        messageId: String,
        groupName: String = ""
    ) {
        val timestamp = nextLocalTs(to, timestamp)
        val preview = "📎 $fileName"
        val msg = Message(
            messageId        = messageId,
            conversationId   = to,
            fromUser         = from,
            toUser           = to,
            encryptedPayload = preview,
            plaintextPreview = preview,
            timestamp        = timestamp,
            isSent           = true,
            attachmentId     = null,    // filled in by markUploadComplete
            attachmentName   = fileName,
            attachmentMime   = mimeType,
            attachmentSize   = fileSize,
            isUploading      = true
        )
        db.messageDao().insertMessage(msg)
        db.conversationDao().insertConversation(Conversation(to, attPrev(mimeType, fileName), timestamp, 0, groupName))
    }

    suspend fun insertOutgoingAttachment(
        from: String,
        to: String,
        fileId: String,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        timestamp: String,
        messageId: String,
        groupName: String = ""
    ) {
        val timestamp = nextLocalTs(to, timestamp)
        val preview = "📎 $fileName"
        val msg = Message(
            messageId        = messageId,
            conversationId   = to,
            fromUser         = from,
            toUser           = to,
            encryptedPayload = preview,
            plaintextPreview = preview,
            timestamp        = timestamp,
            isSent           = true,
            attachmentId     = fileId,
            attachmentName   = fileName,
            attachmentMime   = mimeType,
            attachmentSize   = fileSize
        )
        db.messageDao().insertMessage(msg)
        db.conversationDao().insertConversation(Conversation(to, attPrev(mimeType, fileName), timestamp, 0, groupName))
    }

    suspend fun insertIncomingAttachment(
        from: String,
        to: String,
        fileId: String,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        timestamp: String,
        messageId: String
    ) {
        val timestamp = canonicalTs(timestamp)
        val preview = "📎 $fileName"   // 📎
        val msg = Message(
            messageId        = messageId,
            conversationId   = from,
            fromUser         = from,
            toUser           = to,
            encryptedPayload = preview,
            plaintextPreview = preview,
            timestamp        = timestamp,
            isSent           = false,
            attachmentId     = fileId,
            attachmentName   = fileName,
            attachmentMime   = mimeType,
            attachmentSize   = fileSize
        )
        db.messageDao().insertMessage(msg)
        db.conversationDao().insertConversation(Conversation(from, attPrev(mimeType, fileName), timestamp, 0))
    }

    // ── Call message ─────────────────────────────────────────────────────────

    /**
     * Records a completed call as a message in the chat history.
     * Uses the attachment mechanism with mime = "silvershire/call" so no DB migration is needed.
     *
     * @param isSent       true = we placed the call, false = we received it
     * @param direction    "outgoing" | "incoming" | "missed" | "rejected"
     * @param durationSecs call duration; 0 for missed/rejected
     */
    suspend fun insertCallMessage(
        from: String,
        to: String,
        isSent: Boolean,
        direction: String,
        durationSecs: Int,
        timestamp: String
    ) {
        val timestamp = nextLocalTs(if (isSent) to else from, timestamp)
        val label = "📞 " + callLabel(direction, durationSecs, isSent) +
            (if (durationSecs > 0) " · ${formatCallDuration(durationSecs)}" else "")
        val convId = if (isSent) to else from
        val msg = Message(
            messageId        = "call_${System.currentTimeMillis()}_${(Math.random() * 9999).toInt()}",
            conversationId   = convId,
            fromUser         = from,
            toUser           = to,
            encryptedPayload = label,
            plaintextPreview = label,
            timestamp        = timestamp,
            isSent           = isSent,
            isRead           = isSent,
            isDelivered      = isSent,
            // Reuse attachment fields — fake non-null attachmentId flags isAttachment = true
            attachmentId     = "call_${System.currentTimeMillis()}",
            attachmentName   = direction,
            attachmentMime   = "silvershire/call",
            attachmentSize   = durationSecs.toLong()
        )
        db.messageDao().insertMessage(msg)
        db.conversationDao().insertConversation(Conversation(convId, label, timestamp, 0))
    }

    private fun formatCallDuration(secs: Int): String {
        val h = secs / 3600
        if (h > 0) return "${h}h ${(secs % 3600) / 60}m"
        val m = secs / 60
        val s = secs % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    /**
     * Marks all received messages in [conversationId] as read locally (clears badge/unread).
     * Returns the IDs of messages that were unread (so caller can send read_receipts to the server).
     */
    suspend fun markAllReadAndGetUnread(conversationId: String): List<String> {
        val unread = db.messageDao().getUnreadIncoming(conversationId)
        db.messageDao().markAllRead(conversationId)
        db.conversationDao().clearUnread(conversationId)
        return unread.map { it.messageId }
    }

    suspend fun markAllRead(conversationId: String) {
        db.messageDao().markAllRead(conversationId)
        db.conversationDao().clearUnread(conversationId)
    }

    suspend fun markServerAck(messageId: String) = db.messageDao().markServerAck(messageId)
    /** Recipient device got the message — cascade delivered like read. */
    suspend fun markDelivered(messageId: String) {
        val msg = db.messageDao().getMessageById(messageId)
        if (msg != null && msg.isSent) {
            db.messageDao().markDeliveredUpTo(msg.conversationId, msg.timestamp)
        } else {
            db.messageDao().markDelivered(messageId)
        }
    }
    /** Cascade delivered+serverAck for our sent messages up to timestamp (history restore). */
    suspend fun markDeliveredUpTo(conversationId: String, upToTs: String) {
        db.messageDao().markDeliveredUpTo(conversationId, canonicalTs(upToTs))
    }
    /** Called when the recipient sends us a read_receipt — shows the double checkmark */
    suspend fun markRead(messageId: String) {
        val msg = db.messageDao().getMessageById(messageId)
        if (msg != null && msg.isSent) {
            // Cascade: everything we sent up to this one is also read.
            db.messageDao().markReadUpTo(msg.conversationId, msg.timestamp)
        } else {
            db.messageDao().markRead(messageId)
        }
    }

    /** Peer replied / messaged us — treat our earlier outgoing as read. */
    suspend fun markOutgoingReadUpTo(conversationId: String, upToTs: String) {
        db.messageDao().markReadUpTo(conversationId, canonicalTs(upToTs))
    }
    suspend fun markDeleted(messageId: String) {
        val msg = db.messageDao().getMessageById(messageId)
        db.messageDao().markDeleted(messageId)
        // If it was the newest message, the chat list kept showing its text.
        if (msg != null) {
            val conv = db.conversationDao().getConversation(msg.conversationId)
            if (conv != null && conv.lastMessageTimestamp == msg.timestamp)
                db.conversationDao().insertConversation(conv.copy(lastMessagePreview = "Message deleted"))
        }
    }
    /** Completes an uploading placeholder — sets the real fileId + clears the uploading flag. */
    suspend fun markUploadComplete(messageId: String, fileId: String) =
        db.messageDao().markUploadComplete(messageId, fileId)
    /** Removes a single message row — used to discard a cancelled upload placeholder. */
    suspend fun deleteMessage(messageId: String) = db.messageDao().deleteMessage(messageId)

    suspend fun deleteConversation(username: String) {
        db.messageDao().deleteConversation(username)
        db.conversationDao().deleteConversation(username)
    }

    suspend fun deleteAll() {
        db.messageDao().deleteAll()
        db.conversationDao().deleteAll()
    }

    /** Returns null if the message doesn't exist locally — used for dedup during history sync. */
    suspend fun getMessageById(messageId: String): Message? =
        db.messageDao().getMessageById(messageId)

    // ── Group messages ────────────────────────────────────────────────────────

    /**
     * Inserts an outgoing group message. [groupId] is both the conversationId and the groupId column.
     */
    suspend fun insertGroupOutgoing(
        from: String,
        groupId: String,
        groupName: String,
        text: String,
        timestamp: String,
        messageId: String
    ) {
        val timestamp = nextLocalTs(groupId, timestamp)
        val att = parseAttachment(text)
        val displayText = att?.let { "📎 ${it.name}" } ?: text
        val listText = att?.let { attPrev(it.mime, it.name) } ?: text
        val preview = if (listText.length > 60) listText.take(60) + "…" else listText
        val msg = Message(
            messageId        = messageId,
            conversationId   = groupId,
            fromUser         = from,
            toUser           = groupId,
            encryptedPayload = if (att != null) displayText else text,
            plaintextPreview = preview,
            timestamp        = timestamp,
            isSent           = true,
            isRead           = true,
            isDelivered      = true,
            groupId          = groupId,
            attachmentId     = att?.fileId,
            attachmentName   = att?.name,
            attachmentMime   = att?.mime,
            attachmentSize   = att?.size ?: -1L
        )
        db.messageDao().insertMessage(msg)
        db.conversationDao().insertConversation(
            com.silvershireinn.app.model.Conversation(groupId, preview, timestamp, 0, groupName)
        )
    }

    /**
     * Inserts an incoming group message from another member.
     */
    suspend fun insertGroupIncoming(
        from: String,
        groupId: String,
        groupName: String,
        text: String,
        timestamp: String,
        messageId: String
    ) {
        val timestamp = canonicalTs(timestamp)
        val att = parseAttachment(text)
        val sys = parseSystem(text)
        val displayText = sys ?: att?.let { "📎 ${it.name}" } ?: text
        val listText = sys ?: att?.let { attPrev(it.mime, it.name) } ?: text
        val who = com.silvershireinn.app.utils.PreferenceManager.displayNameFor(from)
        val preview = if (sys != null) sys
                      else "$who: ${if (listText.length > 50) listText.take(50) + "…" else listText}"
        val msg = Message(
            messageId        = messageId,
            conversationId   = groupId,
            fromUser         = from,
            toUser           = groupId,
            encryptedPayload = if (att != null) displayText else text,
            plaintextPreview = displayText,
            timestamp        = timestamp,
            isSent           = false,
            // "X left the group" style notes are not unread messages.
            isRead           = sys != null,
            groupId          = groupId,
            attachmentId     = att?.fileId,
            attachmentName   = att?.name,
            attachmentMime   = att?.mime,
            attachmentSize   = att?.size ?: -1L
        )
        db.messageDao().insertMessage(msg)
        db.conversationDao().insertConversation(
            com.silvershireinn.app.model.Conversation(groupId, preview, timestamp, 0, groupName)
        )
    }

    /**
     * Inserts a historical group message (from group_history_response).
     */
    suspend fun insertGroupHistorical(
        from: String,
        groupId: String,
        groupName: String,
        text: String,
        timestamp: String,
        messageId: String,
        isSent: Boolean,
        updateConversation: Boolean = true
    ): String {
        val timestamp = canonicalTs(timestamp)
        val att = parseAttachment(text)
        val sys = parseSystem(text)
        val displayText = sys ?: att?.let { "📎 ${it.name}" } ?: text
        val listText = sys ?: att?.let { attPrev(it.mime, it.name) } ?: text
        val preview = when {
            sys != null  -> sys
            isSent       -> if (listText.length > 60) listText.take(60) + "…" else listText
            else         -> "${com.silvershireinn.app.utils.PreferenceManager.displayNameFor(from)}: ${if (listText.length > 50) listText.take(50) + "…" else listText}"
        }
        val msg = Message(
            messageId        = messageId,
            conversationId   = groupId,
            fromUser         = from,
            toUser           = groupId,
            encryptedPayload = if (att != null) displayText else text,
            plaintextPreview = displayText,
            timestamp        = timestamp,
            isSent           = isSent,
            isServerAck      = isSent,   // came from the server, so never resend it
            isRead           = true,
            isDelivered      = true,
            groupId          = groupId,
            attachmentId     = att?.fileId,
            attachmentName   = att?.name,
            attachmentMime   = att?.mime,
            attachmentSize   = att?.size ?: -1L
        )
        db.messageDao().insertMessage(msg)
        if (updateConversation) {
            db.conversationDao().insertConversation(
                com.silvershireinn.app.model.Conversation(groupId, preview, timestamp, 0, groupName)
            )
        }
        return preview
    }

    /** Parses an _att JSON payload into attachment fields, or returns null if not an attachment. */
    private data class AttachmentData(val fileId: String, val name: String, val mime: String, val size: Long)
    private fun parseAttachment(payload: String): AttachmentData? {
        return try {
            val j = org.json.JSONObject(payload)
            val att = j.optJSONObject("_att") ?: return null
            val fileId = att.optString("fileId").takeIf { it.isNotBlank() } ?: return null
            com.silvershireinn.app.utils.MediaKeys.learn(att)   // encrypted file: remember its key
            AttachmentData(
                fileId = fileId,
                name   = att.optString("name", "file"),
                mime   = att.optString("mime", "application/octet-stream"),
                size   = att.optLong("size", -1L)
            )
        } catch (_: Exception) { null }
    }

    /** Returns the system message text if payload is {"_sys":"..."}, otherwise null. */
    private fun parseSystem(payload: String): String? {
        return try {
            val j = org.json.JSONObject(payload)
            j.optString("_sys").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    /**
     * Inserts a message from server history that is not yet in the local DB.
     * Outgoing: server-acked only; delivered/read applied from peerLast* on history_response.
     * Incoming historical: mark locally read (no receipt flood for old messages).
     */
    suspend fun insertHistorical(
        from: String,
        to: String,
        payload: String,
        timestamp: String,
        messageId: String,
        isSent: Boolean,
        updateConversation: Boolean = true
    ): String {
        val timestamp = canonicalTs(timestamp)
        val conversationId = if (isSent) to else from
        val preview = if (payload.length > 60) payload.take(60) + "…" else payload
        val msg = Message(
            messageId        = messageId,
            conversationId   = conversationId,
            fromUser         = from,
            toUser           = to,
            encryptedPayload = payload,
            plaintextPreview = preview,
            timestamp        = timestamp,
            isSent           = isSent,
            // Outgoing: server-acked (was on server); delivered/read restored via peerLast* cutoffs.
            // Incoming historical: mark locally read (no receipt flood for old messages).
            isServerAck      = isSent,
            isDelivered      = !isSent,
            isRead           = !isSent
        )
        db.messageDao().insertMessage(msg)
        if (updateConversation) {
            db.conversationDao().insertConversation(
                Conversation(conversationId, preview, timestamp, 0)
            )
        }
        return preview
    }

    /**
     * Inserts a historical attachment message (from server history sync).
     * Uses REPLACE conflict strategy so bad rows stored as raw JSON get overwritten.
     */
    suspend fun insertHistoricalAttachment(
        from: String,
        to: String,
        fileId: String,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        timestamp: String,
        messageId: String,
        isSent: Boolean,
        updateConversation: Boolean = true
    ): String {
        val timestamp = canonicalTs(timestamp)
        val preview = "📎 $fileName"
        val listPreview = attPrev(mimeType, fileName)
        val conversationId = if (isSent) to else from
        val msg = Message(
            messageId        = messageId,
            conversationId   = conversationId,
            fromUser         = from,
            toUser           = to,
            encryptedPayload = preview,
            plaintextPreview = preview,
            timestamp        = timestamp,
            isSent           = isSent,
            isServerAck      = isSent,
            isDelivered      = !isSent,
            isRead           = !isSent,
            attachmentId     = fileId,
            attachmentName   = fileName,
            attachmentMime   = mimeType,
            attachmentSize   = fileSize
        )
        db.messageDao().insertMessage(msg)
        if (updateConversation) {
            db.conversationDao().insertConversation(
                Conversation(conversationId, listPreview, timestamp, 0)
            )
        }
        return listPreview
    }

    /** Upsert conversation row once after a bulk history sync (preserves unread). */
    suspend fun applyHistoryConversationPreview(
        conversationId: String,
        preview: String,
        timestamp: String,
        groupName: String = ""
    ) {
        val timestamp = canonicalTs(timestamp)
        val existing = db.conversationDao().getConversation(conversationId)
        val unread = existing?.unreadCount ?: 0
        if (existing != null &&
            (isoToMillis(existing.lastMessageTimestamp) ?: 0L) >= (isoToMillis(timestamp) ?: 0L)) return
        val gName = groupName.ifBlank { existing?.groupName ?: "" }
        db.conversationDao().insertConversation(
            Conversation(conversationId, preview, timestamp, unread, gName)
        )
    }
}

/**
 * Text for a call log entry. [direction] is "outgoing" | "incoming" | "missed" | "rejected" |
 * "declined", optionally followed by ":video". Shared by the chat list preview and the bubble.
 */
fun callLabel(direction: String?, durationSecs: Int, isSent: Boolean): String {
    val d = direction.orEmpty()
    val kind = if (d.endsWith(":video")) "video call" else "voice call"
    val title = kind.replaceFirstChar { it.uppercase() }
    return when (d.substringBefore(':')) {
        "missed"   -> "Missed $kind"
        "rejected" -> "Call declined"
        "declined" -> "Declined $kind"
        else -> if (durationSecs <= 0 && isSent) "No answer" else title
    }
}
