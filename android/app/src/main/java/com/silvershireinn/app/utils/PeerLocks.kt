package com.silvershireinn.app.utils

import android.content.Context
import com.silvershireinn.app.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Friends (and group members) who used their panic phrase. While someone is locked, every chat
 * with them is closed on THIS phone too: their 1:1 chat shows only a lock notice (our messages
 * and theirs), their messages in groups show as locked, their files can't be opened (the server
 * refuses them too) and the chat list hides the preview. Nothing is deleted: it all comes back
 * when they unlock. The server tells us at sign in (lockedPeers) and as it happens.
 */
object PeerLocks {
    private fun prefs() = com.silvershireinn.app.SilvershireApp.instance
        .getSharedPreferences("silvershire_peer_locks", Context.MODE_PRIVATE)

    private val _locked = MutableStateFlow(prefs().getStringSet("names", emptySet())!!.toSet())
    val locked: StateFlow<Set<String>> = _locked

    fun isLocked(name: String?): Boolean = name != null && name in _locked.value

    private fun save(s: Set<String>) { _locked.value = s; prefs().edit().putStringSet("names", s).apply() }

    fun setAll(names: Collection<String>) = save(names.filter { it.isNotBlank() }.toSet())
    fun lock(name: String) { if (name.isNotBlank()) save(_locked.value + name) }
    fun unlock(name: String) = save(_locked.value - name)
    fun clear() = save(emptySet())

    private const val NOTE = "🔒 %s locked this chat. It opens again when they unlock it."

    private fun lockNotice(conv: String, name: String, ts: String) = Message(
        messageId = "peerlock_$conv", conversationId = conv, fromUser = "", toUser = "",
        encryptedPayload = org.json.JSONObject().put("_sys", NOTE.format(PreferenceManager.displayNameFor(name))).toString(),
        plaintextPreview = "Locked", timestamp = ts, isSent = false, isRead = true
    )

    /** What a chat shows while someone in it is locked. [isGroup] chats only hide that person. */
    fun mask(conv: String, list: List<Message>, lockedNames: Set<String> = _locked.value, isGroup: Boolean = false): List<Message> {
        if (lockedNames.isEmpty()) return list
        if (!isGroup) {
            if (conv !in lockedNames) return list
            return listOf(lockNotice(conv, conv, list.lastOrNull()?.timestamp ?: nowIso()))
        }
        if (list.none { it.fromUser in lockedNames }) return list
        return list.map { m ->
            if (m.fromUser !in lockedNames) m
            else m.copy(encryptedPayload = "🔒 Locked by ${PreferenceManager.displayNameFor(m.fromUser)}",
                        plaintextPreview = "Locked", attachmentId = null, attachmentName = null,
                        attachmentMime = null, attachmentSize = 0L)
        }
    }

    /** Shared files / gallery of a chat while someone in it is locked. */
    fun maskFiles(conv: String, list: List<Message>, lockedNames: Set<String> = _locked.value, isGroup: Boolean = false): List<Message> =
        if (!isGroup) { if (conv in lockedNames) emptyList() else list }
        else list.filter { it.fromUser !in lockedNames }
}
