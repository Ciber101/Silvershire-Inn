package com.silvershireinn.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val contactUsername: String,
    val lastMessagePreview: String = "",
    val lastMessageTimestamp: String = "",
    val unreadCount: Int = 0,
    /** Non-empty for group chats — used as the display name in the chat list. */
    val groupName: String = ""
) {
    val isGroupChat: Boolean get() = groupName.isNotBlank()
}