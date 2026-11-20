package com.silvershireinn.app.database

import androidx.room.*
import com.silvershireinn.app.model.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND attachmentName IS NOT NULL ORDER BY timestamp ASC")
    fun getAttachmentsForConversation(conversationId: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    /** Marks only RECEIVED (not sent) messages as locally read — for badge/unread clearing. */
    @Query("UPDATE messages SET isRead = 1 WHERE conversationId = :conversationId AND isSent = 0")
    suspend fun markAllRead(conversationId: String)

    /** Returns all unread incoming messages in a conversation so we can send read_receipts. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND isSent = 0 AND isRead = 0")
    suspend fun getUnreadIncoming(conversationId: String): List<Message>

    @Query("UPDATE messages SET isServerAck = 1 WHERE messageId = :messageId")
    suspend fun markServerAck(messageId: String)

    @Query("UPDATE messages SET isDelivered = 1, isServerAck = 1 WHERE messageId = :messageId")
    suspend fun markDelivered(messageId: String)

    /** Cascade: mark all our SENT messages in this chat up to timestamp as delivered+serverAck. */
    @Query("""
        UPDATE messages SET isDelivered = 1, isServerAck = 1
        WHERE conversationId = :conversationId AND isSent = 1 AND timestamp <= :upToTs AND isDeleted = 0
    """)
    suspend fun markDeliveredUpTo(conversationId: String, upToTs: String)

    @Query("UPDATE messages SET isRead = 1, isDelivered = 1, isServerAck = 1 WHERE messageId = :messageId")
    suspend fun markRead(messageId: String)

    /** A received message that arrived while offline: show it as unread. */
    @Query("UPDATE messages SET isRead = 0 WHERE messageId = :messageId AND isSent = 0")
    suspend fun markIncomingUnread(messageId: String)

    /** Cascade: mark all our SENT messages in this chat up to timestamp as read. */
    @Query("""
        UPDATE messages SET isRead = 1, isDelivered = 1, isServerAck = 1
        WHERE conversationId = :conversationId AND isSent = 1 AND timestamp <= :upToTs AND isDeleted = 0
    """)
    suspend fun markReadUpTo(conversationId: String, upToTs: String)

    /** Deleted for everyone: the text is wiped from this phone too, not just hidden. */
    @Query("UPDATE messages SET isDeleted = 1, encryptedPayload = '', plaintextPreview = '' WHERE messageId = :messageId")
    suspend fun markDeleted(messageId: String)

    /** Messages we sent that the server never confirmed (1:1 and group) — resend on reconnect. */
    @Query("SELECT * FROM messages WHERE isSent = 1 AND isServerAck = 0 AND isUploading = 0 AND isDeleted = 0 ORDER BY timestamp ASC")
    suspend fun getPendingOutgoing(): List<Message>

    /** Adopt the server's authoritative timestamp for a message (from the message_sent ack). */
    @Query("UPDATE messages SET timestamp = :timestamp WHERE messageId = :messageId")
    suspend fun updateTimestamp(messageId: String, timestamp: String)

    /** Most recent timestamps in a conversation (parsed in Kotlin, so mixed formats are fine). */
    @Query("SELECT timestamp FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 25")
    suspend fun recentTimestamps(conversationId: String): List<String>

    /** Called once an upload finishes — sets the real fileId and clears the uploading flag. */
    @Query("UPDATE messages SET attachmentId = :fileId, isUploading = 0 WHERE messageId = :messageId")
    suspend fun markUploadComplete(messageId: String, fileId: String)

    /** Delete a single message row (used to remove cancelled upload placeholders). */
    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    /**
     * Migrates all messages that belong to a renamed contact's conversation.
     * Called when a contact changes their username so the full chat history
     * moves to the new username key without any data loss.
     */
    @Query("""
        UPDATE messages
        SET conversationId = :newId,
            fromUser = CASE WHEN fromUser = :oldId THEN :newId ELSE fromUser END,
            toUser   = CASE WHEN toUser   = :oldId THEN :newId ELSE toUser   END
        WHERE conversationId = :oldId
    """)
    suspend fun renameConversation(oldId: String, newId: String)

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): Message?

    /** Newest message left in a chat (to fix the chat list's preview after a removal). */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestIn(conversationId: String): Message?

    /** Rows whose time is not in the canonical sortable form (old app versions / servers). */
    @Query("SELECT messageId AS id, timestamp AS ts FROM messages WHERE length(timestamp) != 24 OR substr(timestamp, 24, 1) != 'Z'")
    suspend fun nonCanonicalTimestamps(): List<IdTs>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
