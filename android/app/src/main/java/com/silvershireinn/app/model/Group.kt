package com.silvershireinn.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group chat. Members are stored as a JSON array string.
 * groupId is the server-assigned UUID; it is also used as the conversationId
 * and Conversation.contactUsername so group chats appear in the chats list.
 */
@Entity(tableName = "groups")
data class Group(
    @PrimaryKey val groupId: String,
    val name: String,
    val membersJson: String,   // JSON array of usernames, e.g. ["alice","bob","carol"]
    val createdBy: String,
    val createdAt: String = ""
)
