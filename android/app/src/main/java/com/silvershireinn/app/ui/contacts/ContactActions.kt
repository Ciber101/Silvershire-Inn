package com.silvershireinn.app.ui.contacts

import android.content.Context
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.utils.PreferenceManager

/**
 * Remove / block a contact, the same way from every screen.
 *
 * The profile sheet used to send a "remove_friend" message the server doesn't know, and only
 * removed the contact locally when it was opened from the Contacts tab, so removing or
 * blocking someone from a chat did nothing at all.
 */
object ContactActions {

    /** Remove [username] here and on the server (they stay removed on other devices too). */
    suspend fun remove(context: Context, username: String) {
        SilvershireApp.database.contactDao().deleteContact(username)
        // Keep the server's contacts list from re-adding them on this phone.
        PreferenceManager.addDeletedContact(username)
        WebSocketManager.sendRemoveContact(username)
        try { com.silvershireinn.app.ui.chathead.ChatHeadService.dismiss(context.applicationContext, username) } catch (_: Exception) {}
    }

    /** Remove and block: their friend requests are declined automatically from now on. */
    suspend fun block(context: Context, username: String) {
        val prefs = SilvershireApp.instance.getSharedPreferences("blocked_users", Context.MODE_PRIVATE)
        val set = (prefs.getStringSet("list", emptySet()) ?: emptySet()).toMutableSet()
        set.add(username)
        prefs.edit().putStringSet("list", set).apply()
        remove(context, username)
    }
}
