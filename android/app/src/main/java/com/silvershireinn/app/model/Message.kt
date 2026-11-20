package com.silvershireinn.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val fromUser: String,
    val toUser: String,
    val encryptedPayload: String,
    val plaintextPreview: String,
    val timestamp: String,
    val isSent: Boolean,
    val isRead: Boolean = false,
    val isDelivered: Boolean = false,
    /** True once server acked with message_sent (first check turns blue). */
    val isServerAck: Boolean = false,
    val isDeleted: Boolean = false,
    // Attachment fields (null = regular text message)
    val attachmentId: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long = 0L,
    // True while the file is being uploaded — placeholder is shown in chat UI.
    // Reset to false (and attachmentId filled in) once upload completes.
    val isUploading: Boolean = false,
    // Non-null when this message belongs to a group chat.
    val groupId: String? = null
) {
    /** True if this message carries an attachment (even if still uploading). */
    val isAttachment: Boolean get() = attachmentName != null
}
