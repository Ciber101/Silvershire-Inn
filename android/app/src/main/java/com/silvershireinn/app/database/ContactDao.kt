package com.silvershireinn.app.database

import androidx.room.*
import com.silvershireinn.app.model.Contact
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE isRequest = 0 AND isPending = 0 ORDER BY username ASC")
    fun getContacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE isRequest = 1")
    fun getFriendRequests(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE username = :username LIMIT 1")
    suspend fun getContact(username: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: Contact)

    @Query("UPDATE contacts SET isOnline = :online WHERE username = :username")
    suspend fun updateOnlineStatus(username: String, online: Boolean)

    @Query("UPDATE contacts SET publicKey = :publicKey WHERE username = :username")
    suspend fun updatePublicKey(username: String, publicKey: String)

    @Query("DELETE FROM contacts WHERE username = :username")
    suspend fun deleteContact(username: String)

    @Query("SELECT * FROM contacts WHERE isRequest = 0 AND isPending = 0")
    suspend fun getAllConfirmedContacts(): List<Contact>

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}