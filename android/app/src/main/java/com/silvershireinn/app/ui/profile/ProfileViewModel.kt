package com.silvershireinn.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.repository.ContactRepository
import com.silvershireinn.app.repository.MessageRepository
import com.silvershireinn.app.utils.PreferenceManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

class ProfileViewModel : ViewModel() {

    private val messageRepo = MessageRepository(SilvershireApp.database)
    private val contactRepo = ContactRepository(SilvershireApp.database)

    fun setDiscoverable(discoverable: Boolean) {
        WebSocketManager.sendDiscoverable(PreferenceManager.username, discoverable)
    }

    fun clearHistory() {
        // Remember the cut-off: the next history sync used to bring every message straight back.
        PreferenceManager.setAllHistoryCleared()
        viewModelScope.launch {
            messageRepo.deleteAll()
        }
    }

    companion object {
        /**
         * Panic wipe. Runs on the app scope: the old version ran on this screen's scope, which
         * was cancelled by closing the app right after, so often nothing was wiped locally
         * (and offline it claimed success without touching the server).
         * Online: the server deletes the account and WebSocketService wipes this phone and
         * opens setup. Offline / no answer in 5 s: wipe this phone anyway and say the server
         * account could not be reached.
         */
        fun panicWipe(app: android.content.Context) {
            com.silvershireinn.app.SilvershireApp.appScope.launch {
                val username = PreferenceManager.username
                var serverDone = false
                if (username.isNotBlank() && WebSocketManager.isAuthenticated) {
                    val wait = async {
                        kotlinx.coroutines.withTimeoutOrNull(5_000) {
                            WebSocketManager.events.first { it.optString("type") == "wipe_complete" }
                        }
                    }
                    kotlinx.coroutines.yield()
                    WebSocketManager.sendRaw(org.json.JSONObject().apply {
                        put("type", "panic_wipe"); put("from", username)
                    }.toString())
                    serverDone = wait.await() != null
                }
                if (serverDone) {
                    kotlinx.coroutines.delay(800)   // the service is finishing its own wipe
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(app, "Account and all data wiped", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                WebSocketManager.disconnect()
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    listOf(PreferenceManager.profilePicturePath, PreferenceManager.bannerPicPath).forEach { p ->
                        if (p.isNotBlank()) try { java.io.File(p).delete() } catch (_: Exception) {}
                    }
                    com.silvershireinn.app.utils.AccountData.wipeLocal(app)
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    PreferenceManager.clearAll()   // full wipe intentional for panic
                    try { androidx.core.app.NotificationManagerCompat.from(app).cancelAll() } catch (_: Exception) {}
                    android.widget.Toast.makeText(app,
                        "This phone was wiped. The server couldn't be reached, so the account itself still exists there",
                        android.widget.Toast.LENGTH_LONG).show()
                    app.startActivity(android.content.Intent(app, com.silvershireinn.app.ui.splash.SplashActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
            }
        }
    }
}
