package com.silvershireinn.app.database

import androidx.room.*
import com.silvershireinn.app.model.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    /**
     * Chat list with the unread count worked out from the messages themselves. The stored
     * unreadCount column was never incremented anywhere, so the unread badge in the chat
     * list never showed. One pass over messages (GROUP BY), not one count per chat.
     */
    @Query("""
        SELECT c.contactUsername, c.lastMessagePreview, c.lastMessageTimestamp,
               COALESCE(u.n, 0) AS unreadCount, c.groupName
        FROM conversations c
        LEFT JOIN (SELECT conversationId, COUNT(*) AS n FROM messages
                   WHERE isSent = 0 AND isRead = 0 AND isDeleted = 0
                   GROUP BY conversationId) u
          ON u.conversationId = c.contactUsername
        ORDER BY c.lastMessageTimestamp DESC
    """)
    fun getConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE contactUsername = :id LIMIT 1")
    suspend fun getConversation(id: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: Conversation)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE contactUsername = :username")
    suspend fun clearUnread(username: String)

    @Query("DELETE FROM conversations WHERE contactUsername = :username")
    suspend fun deleteConversation(username: String)

    /** Renames the conversation entry when a contact changes their username. */
    // OR REPLACE: if a row for the new name already exists, a plain UPDATE of the primary key
    // throws and the rename crashed.
    @Query("UPDATE OR REPLACE conversations SET contactUsername = :newName WHERE contactUsername = :oldName")
    suspend fun renameContact(oldName: String, newName: String)

    /** Rows whose time is not in the canonical sortable form (old app versions). */
    @Query("SELECT contactUsername AS id, lastMessageTimestamp AS ts FROM conversations WHERE lastMessageTimestamp != '' AND (length(lastMessageTimestamp) != 24 OR substr(lastMessageTimestamp, 24, 1) != 'Z')")
    suspend fun nonCanonicalTimestamps(): List<IdTs>

    @Query("UPDATE conversations SET lastMessageTimestamp = :ts WHERE contactUsername = :id")
    suspend fun setTimestamp(id: String, ts: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}