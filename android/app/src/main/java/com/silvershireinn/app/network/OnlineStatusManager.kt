package com.silvershireinn.app.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.silvershireinn.app.utils.formatTimestamp
import com.silvershireinn.app.utils.isoToMillis
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory tracker for which contacts are currently online, their presence mode,
 * and when they were last seen.
 *
 * Updated by WebSocketService whenever an `online_status` WS message arrives.
 * Observed by ConversationAdapter / ContactAdapter / ChatActivity via [statusFlow].
 */
object OnlineStatusManager {

    val PRESENCE_MODES = listOf("online", "afk", "dnd", "busy", "brb", "invisible")

    // Map of username → online flag
    // Written from the socket thread, read by the UI: concurrent maps (plain HashMaps could
    // throw or loop while being resized under a reader).
    private val onlineMap = ConcurrentHashMap<String, Boolean>()
    // Map of username → last-seen ISO string (absent if unknown)
    private val lastSeenMap = ConcurrentHashMap<String, String>()
    // Map of username → presence mode while online (absent = treat as online)
    private val presenceMap = ConcurrentHashMap<String, String>()
    // Map of username → using the app right now (Active) vs only open in the background (Online)
    private val activeMap = ConcurrentHashMap<String, Boolean>()

    /** Active: using the app / web app right now. Online: open in the background. */
    const val ACTIVE_COLOR = 0xFF4CAF50.toInt()
    const val BACKGROUND_COLOR = 0xFF4DA3FF.toInt()

    /** Version counter; a new value whenever any status changes (adapters collect this).
     *  A counter, not the clock: two updates in the same millisecond used to be swallowed. */
    private val version = AtomicLong(0L)
    private val _statusFlow = MutableStateFlow(0L)
    val statusFlow: StateFlow<Long> = _statusFlow.asStateFlow()

    /**
     * @param presence presence/mode from the server (may be null)
     * @param presenceProvided true when the WS message included a presence/mode field
     *        (so we can keep the previous mode when the field is omitted while still online)
     */
    fun setStatus(
        username: String,
        online: Boolean,
        lastSeenIso: String? = null,
        presence: String? = null,
        presenceProvided: Boolean = false,
        active: Boolean? = null
    ) {
        if (username.isBlank()) return
        onlineMap[username] = online
        if (!online) {
            presenceMap.remove(username)
            activeMap.remove(username)
            if (lastSeenIso != null) lastSeenMap[username] = lastSeenIso
        } else {
            // An older server only reported people in use as online: that is Active
            activeMap[username] = active ?: true
            if (lastSeenIso != null) lastSeenMap[username] = lastSeenIso
            if (presenceProvided) {
                val normalized = normalizePresenceMode(presence)
                if (normalized != null) presenceMap[username] = normalized
                else presenceMap.remove(username)
            }
            // else: keep previous presence while still online
        }
        _statusFlow.value = version.incrementAndGet()
    }

    fun isOnline(username: String): Boolean = onlineMap[username] == true

    /** Using the app or web app right now (not just open in the background). */
    fun isActive(username: String): Boolean = isOnline(username) && activeMap[username] != false

    /** Normalized presence for an online user, or null if offline / unset. */
    fun getPresence(username: String): String? {
        if (!isOnline(username)) return null
        return presenceMap[username]
    }

    /** Effective mode used for labels/colors when online (defaults to online). */
    fun effectivePresence(username: String): String? {
        if (!isOnline(username)) return null
        return presenceMap[username] ?: "online"
    }

    fun normalizePresenceMode(v: String?): String? {
        if (v.isNullOrBlank()) return null
        var s = v.trim().lowercase()
        if (s == "away") s = "afk"
        if (s == "do not disturb" || s == "do_not_disturb") s = "dnd"
        return if (s in PRESENCE_MODES) s else null
    }

    fun modeLabel(mode: String?): String {
        return when (normalizePresenceMode(mode) ?: "online") {
            "online" -> "Active / Online"         // automatic: Active while using it, Online in the background
            "afk" -> "AFK"
            "brb" -> "BRB"
            "dnd" -> "Do not disturb"
            "busy" -> "Busy"
            "invisible" -> "Invisible"
            else -> "Online"
        }
    }

    /** Green / amber / red-ish / dim — parity with web client. */
    fun colorForMode(mode: String?): Int {
        return when (normalizePresenceMode(mode) ?: "online") {
            "online" -> 0xFF4CAF50.toInt()
            "afk", "brb" -> 0xFFE0A84A.toInt()
            "dnd", "busy" -> 0xFFCF6679.toInt()
            "invisible" -> 0xFF555555.toInt()
            else -> 0xFF4CAF50.toInt()
        }
    }

    fun statusColor(username: String): Int {
        val mode = effectivePresence(username) ?: return 0xFF757575.toInt()
        if (mode != "online") return colorForMode(mode)
        return if (isActive(username)) ACTIVE_COLOR else BACKGROUND_COLOR
    }

    /** "Active" / "Online" / "AFK" / … when connected; last-seen text when offline. */
    fun statusLabel(username: String): String {
        if (isOnline(username)) {
            val mode = presenceMap[username] ?: "online"
            if (mode != "online") return modeLabel(mode)
            if (isActive(username)) return "Active"
            // Open in the background: when they last actually used it
            val ago = lastSeenAgo(username)
            return if (ago.isEmpty()) "Online" else "Online · last seen $ago"
        }
        val ago = lastSeenAgo(username)
        return if (ago.isEmpty()) "" else "Last seen $ago"
    }

    /** "just now" / "5m ago" / "3h ago" / a date, from when they last used the app; "" if unknown. */
    fun lastSeenAgo(username: String): String {
        val isoStr = lastSeenMap[username] ?: return ""
        // java.time is missing on Android 7 (and the old catch did not cover that error), so
        // this used the app's own parser. Older than a day reads like message times.
        val then = isoToMillis(isoStr) ?: isoStr.toLongOrNull() ?: return ""
        val mins = (System.currentTimeMillis() - then) / 60_000L
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 24 * 60 -> "${mins / 60}h ago"
            else -> com.silvershireinn.app.utils.millisToIso(then).formatTimestamp().replaceFirst("Yesterday", "yesterday")
        }
    }

    fun clear() {
        onlineMap.clear()
        lastSeenMap.clear()
        presenceMap.clear()
        activeMap.clear()
        _statusFlow.value = version.incrementAndGet()
    }
}