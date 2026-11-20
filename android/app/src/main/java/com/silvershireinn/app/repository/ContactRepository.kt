package com.silvershireinn.app.repository

import com.silvershireinn.app.database.AppDatabase
import com.silvershireinn.app.model.Contact
import com.silvershireinn.app.utils.PreferenceManager
import kotlinx.coroutines.flow.Flow

class ContactRepository(private val db: AppDatabase) {

    fun getContacts(): Flow<List<Contact>> = db.contactDao().getContacts()
    fun getFriendRequests(): Flow<List<Contact>> = db.contactDao().getFriendRequests()

    suspend fun insertOrUpdateContact(username: String) {
        // Never re-add contacts the user deliberately deleted or blocked
        if (PreferenceManager.getDeletedContacts().contains(username)) return
        if (PreferenceManager.getBlockedUsers().contains(username)) return
        val existing = db.contactDao().getContact(username)
        if (existing == null) {
            db.contactDao().insertContact(Contact(username = username))
        }
    }

    suspend fun setOnlineStatus(username: String, online: Boolean) =
        db.contactDao().updateOnlineStatus(username, online)

    suspend fun addFriendRequest(username: String) {
        db.contactDao().insertContact(Contact(username = username, isRequest = true))
    }

    suspend fun confirmFriend(username: String) {
        val existing = db.contactDao().getContact(username)
        db.contactDao().insertContact(
            (existing ?: Contact(username)).copy(isRequest = false, isPending = false)
        )
    }

    suspend fun deleteContact(username: String) = db.contactDao().deleteContact(username)

    suspend fun renameContact(oldName: String, newName: String) {
        val existing = db.contactDao().getContact(oldName) ?: return
        // Migrate the contact row
        db.contactDao().deleteContact(oldName)
        db.contactDao().insertContact(existing.copy(username = newName))
        // Migrate all messages: conversationId, fromUser, toUser pointing at oldName → newName
        db.messageDao().renameConversation(oldName, newName)
        // Migrate the conversation list entry (the chat tab row)
        db.conversationDao().renameContact(oldName, newName)
        // Nickname, colors, backgrounds and per-chat settings follow the new name.
        PreferenceManager.migrateContactKeys(oldName, newName)
    }

    suspend fun getAllConfirmedContacts(): List<Contact> =
        db.contactDao().getAllConfirmedContacts()

    suspend fun deleteAll() = db.contactDao().deleteAll()
}