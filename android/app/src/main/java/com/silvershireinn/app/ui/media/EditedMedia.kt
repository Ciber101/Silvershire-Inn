package com.silvershireinn.app.ui.media

import java.io.File

/**
 * A photo edited from the viewer and meant to be sent: it waits here until its chat is shown,
 * then appears there as a draft (add a caption, then send).
 */
object EditedMedia {
    @Volatile private var pending: Pair<String, File>? = null
    fun offer(conversationId: String, file: File) { pending = conversationId to file }
    /** The edited photo for [conversationId], once. */
    fun take(conversationId: String?): File? {
        val p = pending ?: return null
        if (conversationId.isNullOrBlank() || p.first != conversationId) return null
        pending = null
        return p.second.takeIf { it.exists() }
    }
}
