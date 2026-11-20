package com.silvershireinn.app.utils

import android.content.Context
import com.silvershireinn.app.SilvershireApp
import kotlinx.coroutines.runBlocking
import java.io.File

/** Local data that belongs to one account. Only cleared after switching to a DIFFERENT account. */
object AccountData {
    /** Call off the main thread. */
    fun wipeLocal(context: Context) {
        try { PanicLock.clearLocal() } catch (_: Exception) {}
        try { GroupKeys.clear() } catch (_: Exception) {}
        try { MediaKeys.clear() } catch (_: Exception) {}
        try { PeerLocks.clear() } catch (_: Exception) {}   // a new account starts without the old words
        try {
            runBlocking {
                val db = SilvershireApp.database
                db.messageDao().deleteAll()
                db.contactDao().deleteAll()
                db.conversationDao().deleteAll()
                db.groupDao().deleteAll()   // the old account's groups used to linger
            }
        } catch (_: Exception) {}
        // Contact pictures / banners so the new account doesn't inherit faces.
        listOf("contact_pics", "contact_banners").forEach { d ->
            try { File(context.filesDir, d).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        }
        // Cached photos / videos / thumbnails from the old account's chats.
        try { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {}
        try {
            context.getSharedPreferences("blocked_users", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("deleted_contacts", Context.MODE_PRIVATE).edit().clear().apply()
            PreferenceManager.clearUserFacingSettings()
        } catch (_: Exception) {}
    }
}
