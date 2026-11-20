package com.silvershireinn.app.utils

/**
 * The Notes chat: a chat with yourself. Messages are end-to-end encrypted to your own key and
 * synced between your devices through your server; nobody else ever gets them. Pinned first in
 * the chats list while the setting is on.
 */
object NotesChat {
    const val TITLE = "Notes"
    fun isNotes(id: String?): Boolean = !id.isNullOrBlank() && id == PreferenceManager.username
    val enabled: Boolean get() = PreferenceManager.notesEnabled
}
