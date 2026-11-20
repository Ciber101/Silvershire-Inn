package com.silvershireinn.app.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object PreferenceManager {
    private const val PREFS_NAME = "silvershire_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Setup
    var serverUrl: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("server_url", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("server_url", v).apply() }

    var serverPassword: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("server_password", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("server_password", v).apply() }

    var username: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("username", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("username", v).apply() }

    /** The server refused our server password (it was changed). No reconnects until a new one is entered. */
    var serverPasswordRejected: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("server_pw_rejected", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("server_pw_rejected", v).apply() }

    var isSetupComplete: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("setup_complete", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("setup_complete", v).apply() }

    var isRegistered: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("registered", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("registered", v).apply() }

    // Password hash/salt
    var passwordHash: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("pwd_hash", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("pwd_hash", v).apply() }

    var passwordSalt: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("pwd_salt", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("pwd_salt", v).apply() }

    // Lockout
    var failedAttempts: Int
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getInt("failed_attempts", 0)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putInt("failed_attempts", v).apply() }

    var lockoutUntil: Long
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getLong("lockout_until", 0L)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putLong("lockout_until", v).apply() }

    // Settings
    var requirePasswordOnOpen: Boolean
        get() {
            // Self-heal: if the flag is stored as true but there's no password hash,
            // the user never actually set a password — clear the stale value and return false.
            val stored = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("req_pwd_open", false)
            if (stored && passwordHash.isBlank()) {
                prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("req_pwd_open", false).apply()
                return false
            }
            return stored
        }
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("req_pwd_open", v).apply() }

    /** Number of consecutive biometric failures on the lock screen (resets on success or app wipe). */
    var biometricFailures: Int
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getInt("biometric_failures", 0)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putInt("biometric_failures", v).apply() }

    var readReceipts: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("read_receipts", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("read_receipts", v).apply() }

    var typingIndicators: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("typing_indicators", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("typing_indicators", v).apply() }

    var discoverable: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("discoverable", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("discoverable", v).apply() }

    /**
     * Own presence mode: online, afk, dnd, busy, brb, invisible.
     * Default online. Does NOT write the key on read when absent (never invent prefs).
     */
    var presenceMode: String
        get() {
            val p = prefs(com.silvershireinn.app.SilvershireApp.instance)
            if (!p.contains("presence_mode")) return "online"
            val raw = p.getString("presence_mode", "online")
            return com.silvershireinn.app.network.OnlineStatusManager.normalizePresenceMode(raw) ?: "online"
        }
        set(v) {
            val mode = com.silvershireinn.app.network.OnlineStatusManager.normalizePresenceMode(v) ?: "online"
            prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("presence_mode", mode).apply()
        }

    /** Chat message font size index: 0=small, 1=medium (default), 2=large, 3=x-large */
    var fontSize: Int
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getInt("font_size", 1)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putInt("font_size", v).apply() }

    /** UI font size index: 0=small, 1=medium (default), 2=large */
    var uiFontSize: Int
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getInt("ui_font_size", 1)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putInt("ui_font_size", v).apply() }

    /** Returns the sp value for the UI font size (buttons, labels, settings rows). */
    fun uiFontSizeSp(): Float = when (uiFontSize) {
        0 -> 12f
        2 -> 17f
        else -> 14f   // default medium
    }

    /** true = user has permanently hidden the Ko-fi support block from their profile. */
    var supportBlockHidden: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("support_block_hidden", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("support_block_hidden", v).apply() }

    var notificationSounds: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("notif_sounds", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("notif_sounds", v).apply() }

    var notificationPreview: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("notif_preview", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("notif_preview", v).apply() }

    /**
     * Master switch for passive notifications (incoming messages, group messages,
     * friend requests). When false, the background connection keeps running and
     * messages still sync — only the pop-up notifications are suppressed. This is the
     * user-facing "disable notifications" control. Incoming voice/video call
     * notifications are intentionally NOT gated by this (calls are time-critical).
     * Default true.
     */
    var notificationsEnabled: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("notifications_enabled", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("notifications_enabled", v).apply() }

    var compactBubbles: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("compact_bubbles", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("compact_bubbles", v).apply() }

    /** Own picture (with border) beside your own messages. Off by default. */
    var showMyAvatar: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("show_my_avatar", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("show_my_avatar", v).apply() }

    /** The Notes chat (messages to yourself) pinned at the top of the chats list. */
    var notesEnabled: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("notes_enabled", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("notes_enabled", v).apply() }

    /** Friend's picture beside received messages: 0 = every message (default), 1 = last of a run, 2 = off. */
    var chatAvatarMode: Int
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getInt("chat_avatar_mode", 0)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putInt("chat_avatar_mode", v).apply() }

    /**
     * When true, sending a message always scrolls the chat to the bottom (classic behavior).
     * When false (default), sending while scrolled up keeps your reading position — the view
     * is not yanked down. (Sending while already at the bottom always stays at the bottom.)
     */
    var jumpToBottomOnSend: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("jump_to_bottom_on_send", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("jump_to_bottom_on_send", v).apply() }

    var chatHeads: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("chat_heads", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("chat_heads", v).apply() }

    /** 0–100, stored as integer; default 100 (fully opaque) */
    var chatHeadAlpha: Int
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getInt("chat_head_alpha", 100)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putInt("chat_head_alpha", v).apply() }

    /**
     * true = chat head bubble disappears when tapped (shows again on next message).
     * false (default) = bubble stays; tapping it while the chat is open minimizes the app.
     */
    var chatHeadDismissOnTap: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("chat_head_dismiss_on_tap", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("chat_head_dismiss_on_tap", v).apply() }

    /** Save last-drag position for a given user's chat head */
    fun saveChatHeadPos(user: String, x: Int, y: Int) {
        prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
            .putInt("ch_x_$user", x).putInt("ch_y_$user", y).apply()
    }

    /** Restore last-drag position; defaults stagger heads on first show */
    fun loadChatHeadPos(user: String, defaultY: Int): Pair<Int, Int> {
        val p = prefs(com.silvershireinn.app.SilvershireApp.instance)
        return Pair(p.getInt("ch_x_$user", 0), p.getInt("ch_y_$user", defaultY))
    }

    // ── Profile picture path ─────────────────────────────────────────────────

    var profilePicturePath: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("profile_pic_path", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("profile_pic_path", v).apply() }

    // ── Banner picture ────────────────────────────────────────────────────────

    /** Local file path of the user's own banner pic (filesDir/banner_pic.jpg). */
    var bannerPicPath: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("banner_pic_path", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("banner_pic_path", v).apply() }

    /** Whether to show the banner at all on the user's own profile screen. */
    var bannerEnabled: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("banner_enabled", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("banner_enabled", v).apply() }

    /** Whether to share the banner picture with mutual contacts (sent to server on upload). */
    var bannerVisibleToContacts: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("banner_visible_to_contacts", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("banner_visible_to_contacts", v).apply() }

    // ── Blocked users ────────────────────────────────────────────────────────

    fun getBlockedUsers(): Set<String> =
        com.silvershireinn.app.SilvershireApp.instance
            .getSharedPreferences("blocked_users", android.content.Context.MODE_PRIVATE)
            .getStringSet("list", emptySet()) ?: emptySet()

    fun unblockUser(username: String) {
        val prefs = com.silvershireinn.app.SilvershireApp.instance
            .getSharedPreferences("blocked_users", android.content.Context.MODE_PRIVATE)
        val set = prefs.getStringSet("list", mutableSetOf())!!.toMutableSet()
        set.remove(username)
        prefs.edit().putStringSet("list", set).apply()
    }

    // ── Deleted contacts (never re-add from server contacts_list) ────────────

    fun getDeletedContacts(): Set<String> =
        com.silvershireinn.app.SilvershireApp.instance
            .getSharedPreferences("deleted_contacts", android.content.Context.MODE_PRIVATE)
            .getStringSet("list", emptySet()) ?: emptySet()

    fun addDeletedContact(username: String) {
        val prefs = com.silvershireinn.app.SilvershireApp.instance
            .getSharedPreferences("deleted_contacts", android.content.Context.MODE_PRIVATE)
        val set = prefs.getStringSet("list", mutableSetOf())!!.toMutableSet()
        set.add(username)
        prefs.edit().putStringSet("list", set).apply()
    }

    fun removeDeletedContact(username: String) {
        val prefs = com.silvershireinn.app.SilvershireApp.instance
            .getSharedPreferences("deleted_contacts", android.content.Context.MODE_PRIVATE)
        val set = prefs.getStringSet("list", mutableSetOf())!!.toMutableSet()
        set.remove(username)
        prefs.edit().putStringSet("list", set).apply()
    }

    // ── Section collapsed state ───────────────────────────────────────────────

    fun isSectionCollapsed(section: String): Boolean =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("sec_collapsed_$section", true)

    fun setSectionCollapsed(section: String, collapsed: Boolean) {
        prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
            .putBoolean("sec_collapsed_$section", collapsed).apply()
    }

    // ── PIN auth ─────────────────────────────────────────────────────────────

    var pinEnabled: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("pin_enabled", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("pin_enabled", v).apply() }

    /** SHA-256 hex of the user's PIN */
    var pinHash: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("pin_hash", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("pin_hash", v).apply() }

    // ── Biometric auth ───────────────────────────────────────────────────────

    var biometricEnabled: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("biometric_enabled", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("biometric_enabled", v).apply() }

    // ── First-launch permissions ─────────────────────────────────────────────

    var permissionsRequested: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("perms_requested", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("perms_requested", v).apply() }

    /**
     * Device-local flag: true once we've shown the one-time battery-optimization
     * exemption prompt. NOT synced to the server (battery exemption is per-device).
     */
    var batteryOptPromptShown: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("battery_opt_prompt_shown", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("battery_opt_prompt_shown", v).apply() }

    /**
     * "Also ask for password": with a PIN or fingerprint on, ALSO require the account password
     * (two steps). Off by default: fingerprint / PIN alone unlocks, and the password is the
     * fallback. (The password used to be added back in front of the fingerprint.)
     */
    var lockUsePassword: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("lock_password_also", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("lock_password_also", v).apply() }

    // ── Cleared history ──────────────────────────────────────────────────────
    /**
     * Messages up to this time (ms) were cleared / the chat was deleted on this phone, so the
     * server's history sync must not bring them back (it did, on the very next reconnect).
     */
    fun clearedAt(conversationId: String): Long {
        val p = prefs(com.silvershireinn.app.SilvershireApp.instance)
        return maxOf(p.getLong("history_cleared_all", 0L), p.getLong("cleared_at_$conversationId", 0L))
    }
    fun setClearedAt(conversationId: String, ms: Long = System.currentTimeMillis()) {
        prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putLong("cleared_at_$conversationId", ms).apply()
    }
    fun setAllHistoryCleared(ms: Long = System.currentTimeMillis()) {
        prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putLong("history_cleared_all", ms).apply()
    }

    // ── Auto-logout ──────────────────────────────────────────────────────────

    /** Whether auto-logout after inactivity is enabled */
    var autoLogoutEnabled: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("auto_logout_enabled", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("auto_logout_enabled", v).apply() }

    /** Auto-logout timeout in minutes (default 5) */
    var autoLogoutMinutes: Int
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getInt("auto_logout_minutes", 5)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putInt("auto_logout_minutes", v).apply() }

    /** Timestamp (ms) of last user interaction; used by auto-logout */
    var lastActivityTime: Long
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getLong("last_activity_time", System.currentTimeMillis())
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putLong("last_activity_time", v).apply() }

    // ── Bubble colors ────────────────────────────────────────────────────────

    /**
     * Hex color string (#RRGGBB) for the current user's outgoing bubbles.
     * null = use the theme default drawn by bg_message_sent*.
     */
    var myBubbleColor: String?
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("my_bubble_color", null)
        set(v) {
            val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
            if (v != null) {
                e.putString("my_bubble_color", v)
                // Record when the color was set — only messages after this timestamp get the custom color
                e.putString("my_bubble_color_since", nowIso())
            } else {
                e.remove("my_bubble_color")
                e.remove("my_bubble_color_since")
            }
            e.apply()
        }

    /** ISO timestamp after which the custom sent-bubble color applies. null = all messages. */
    val myBubbleColorSince: String?
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("my_bubble_color_since", null)

    /**
     * Hex color (#RRGGBB) for the global default received bubble color.
     * Per-contact colors override this. null = theme default.
     */
    var theirBubbleColor: String?
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("their_bubble_color", null)
        set(v) {
            val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
            if (v != null) {
                e.putString("their_bubble_color", v)
                e.putString("their_bubble_color_since", nowIso())
            } else {
                e.remove("their_bubble_color")
                e.remove("their_bubble_color_since")
            }
            e.apply()
        }

    /** ISO timestamp after which the global their-bubble color applies. null = all messages. */
    val theirBubbleColorSince: String?
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("their_bubble_color_since", null)


    /**
     * Hex color string (#RRGGBB) for a contact's incoming bubble color.
     * null = use theme default (bg_message_received drawable).
     * Note: per-contact colors set via ChatSettingsActivity are also stored there separately
     * under KEY_BUBBLE_COLOR. This method reads the global prefs copy used by MessageAdapter.
     */
    fun getContactBubbleColor(username: String): String? =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("bubble_color_$username", null)

    fun setContactBubbleColor(username: String, color: String?) {
        val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
        if (color != null) {
            e.putString("bubble_color_$username", color)
            // Record when the color was changed — only newer messages get the custom color
            e.putString("bubble_color_since_$username", nowIso())
        } else {
            e.remove("bubble_color_$username")
            e.remove("bubble_color_since_$username")
        }
        e.apply()
    }

    /** ISO timestamp after which the custom received-bubble color for [username] applies. */
    fun getContactBubbleColorSince(username: String): String? =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("bubble_color_since_$username", null)

    // ── Nickname ────────────────────────────────────────────────────────────

    const val ACTION_NAMES_CHANGED = "com.silvershireinn.NAMES_CHANGED"

    fun getNickname(username: String): String =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("nickname_$username", "") ?: ""

    fun setNickname(username: String, nickname: String) {
        prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
            .putString("nickname_$username", nickname).apply()
        try {
            com.silvershireinn.app.SilvershireApp.instance
                .getSharedPreferences("chat_prefs_$username", android.content.Context.MODE_PRIVATE)
                .edit().putString("nickname", nickname).apply()
        } catch (_: Exception) {}
        notifyNamesChanged(username)
    }

    fun clearNickname(username: String) {
        prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
            .remove("nickname_$username").apply()
        try {
            com.silvershireinn.app.SilvershireApp.instance
                .getSharedPreferences("chat_prefs_$username", android.content.Context.MODE_PRIVATE)
                .edit().remove("nickname").apply()
        } catch (_: Exception) {}
        notifyNamesChanged(username)
    }

    fun getContactDisplayName(username: String): String =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("display_name_$username", "") ?: ""

    fun setContactDisplayName(username: String, name: String) {
        val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
        if (name.isBlank() || name == username) e.remove("display_name_$username")
        else e.putString("display_name_$username", name)
        e.apply()
    }

    /** Synced per-contact avatar shape from server (circle|rounded|squircle|diamond). */
    fun getContactAvatarShape(username: String): String =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("avatar_shape_$username", "") ?: ""

    fun setContactAvatarShape(username: String, shape: String?) {
        val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
        val s = shape?.trim().orEmpty()
        if (s.isBlank()) e.remove("avatar_shape_$username")
        else e.putString("avatar_shape_$username", s)
        e.apply()
    }

    /** nickname > friend self-chosen display name > username */
    fun displayNameFor(username: String): String {
        if (username.isBlank()) return username
        val nick = getNickname(username)
        if (nick.isNotBlank()) return nick
        val self = getContactDisplayName(username)
        if (self.isNotBlank()) return self
        return username
    }

    fun notifyNamesChanged(username: String = "") {
        try {
            val ctx = com.silvershireinn.app.SilvershireApp.instance
            ctx.sendBroadcast(android.content.Intent(ACTION_NAMES_CHANGED).apply {
                putExtra("username", username)
                `package` = ctx.packageName
            })
        } catch (_: Exception) {}
    }

    var myDisplayName: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("my_display_name", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("my_display_name", v).apply() }

    var signupUsername: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("signup_username", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("signup_username", v).apply() }

    // ── Accent / theme color ─────────────────────────────────────────────────

    /**
     * Hex color string for the UI accent (replaces the default cozy gold #D4AF37).
     * null = use default gold.
     */
    var accentColor: String?
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("accent_color", null)
        set(v) {
            val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
            if (v != null) e.putString("accent_color", v) else e.remove("accent_color")
            e.apply()
        }

    /** Returns the active accent color as an ARGB int. Falls back to cozy gold #D4AF37. */
    fun accentColorInt(): Int {
        val raw = accentColor
        // Legacy defaults (old silver-grey accent, alternate gold) map back to cozy gold
        val hex = when {
            raw.isNullOrBlank() -> null
            raw.equals("#A8A9AD", ignoreCase = true) || raw.equals("A8A9AD", ignoreCase = true) -> null
            raw.equals("#C9A84C", ignoreCase = true) || raw.equals("C9A84C", ignoreCase = true) -> null
            else -> raw
        } ?: return android.graphics.Color.parseColor("#D4AF37")
        return try { android.graphics.Color.parseColor(hex) } catch (_: Exception) { android.graphics.Color.parseColor("#D4AF37") }
    }

    // ── UI text color ────────────────────────────────────────────────────────

    /**
     * Hex color string (#RRGGBB) for the global UI text color.
     * null = use the theme default (color_text_primary from colors.xml).
     */
    var uiTextColor: String?
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("ui_text_color", null)
        set(v) {
            val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
            if (v != null) e.putString("ui_text_color", v) else e.remove("ui_text_color")
            e.apply()
        }

    /** Returns the active UI text color as an ARGB int, or null to use theme default. */
    fun uiTextColorInt(): Int? {
        val hex = uiTextColor ?: return null
        return try { android.graphics.Color.parseColor(hex) } catch (_: Exception) { null }
    }

    // ── Per-chat text color ──────────────────────────────────────────────────

    /**
     * Hex color string (#RRGGBB) for the text color in a specific chat.
     * null = fall back to the global [uiTextColor] (or theme default if that's also null).
     */
    fun getChatTextColor(username: String): String? =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("chat_text_color_$username", null)

    fun setChatTextColor(username: String, color: String?) {
        val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
        if (color != null) e.putString("chat_text_color_$username", color)
        else e.remove("chat_text_color_$username")
        e.apply()
    }

    /** Returns the per-chat text color as an ARGB int, or null if not set. */
    fun chatTextColorInt(username: String): Int? {
        val hex = getChatTextColor(username) ?: return null
        return try { android.graphics.Color.parseColor(hex) } catch (_: Exception) { null }
    }

    // ── Font family ──────────────────────────────────────────────────────────

    /**
     * System font family name used for message text.
     * Options: "sans-serif" (default), "serif", "monospace", "sans-serif-condensed", "cursive"
     */
    var fontFamily: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("font_family", "sans-serif") ?: "sans-serif"
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("font_family", v).apply() }

    // ── Tab / chat video backgrounds ─────────────────────────────────────────

    /**
     * Absolute path to a looping video file used as background for [tabKey].
     * [tabKey] is "chats", "contacts", or "profile".  null = no video background.
     */
    fun getTabVideoBg(tabKey: String): String? =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("tab_videobg_$tabKey", null)

    fun setTabVideoBg(tabKey: String, path: String?) {
        val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
        if (path != null) e.putString("tab_videobg_$tabKey", path) else e.remove("tab_videobg_$tabKey")
        e.apply()
    }

    /**
     * Absolute path to a looping video file used as background for a specific chat.
     * [username] identifies the conversation. null = no video background.
     */
    fun getChatVideoBg(username: String): String? =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("chat_videobg_$username", null)

    fun setChatVideoBg(username: String, path: String?) {
        val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
        if (path != null) e.putString("chat_videobg_$username", path) else e.remove("chat_videobg_$username")
        e.apply()
    }

    // ── Clock format ─────────────────────────────────────────────────────────

    /** Show the full day, date and time on every message (off = short "3:42 PM" style). */
    var showFullTimestamps: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("full_timestamps", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("full_timestamps", v).apply() }

    /** true = 24-hour clock (default); false = 12-hour clock with AM/PM */
    var use24HourClock: Boolean
        get() {
            // Not chosen yet: follow the phone's own 12/24-hour setting.
            val p = prefs(com.silvershireinn.app.SilvershireApp.instance)
            return if (p.contains("use_24h_clock")) p.getBoolean("use_24h_clock", true)
                   else try { android.text.format.DateFormat.is24HourFormat(com.silvershireinn.app.SilvershireApp.instance) } catch (_: Exception) { true }
        }
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("use_24h_clock", v).apply() }

    // ── Navigation layout ────────────────────────────────────────────────────

    /** true = nav bar sits at the top of the screen; false (default) = bottom */
    var navBarAtTop: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("nav_bar_top", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("nav_bar_top", v).apply() }

    /**
     * Comma-separated tab keys in display order.
     * Valid keys: "chats", "contacts", "profile"
     * Default: "chats,contacts,profile"
     */
    var tabOrder: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("tab_order", "chats,contacts,profile") ?: "chats,contacts,profile"
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("tab_order", v).apply() }

    // ── Settings restore signal ──────────────────────────────────────────────

    /**
     * Set to true by WebSocketService after applying login_ok settings from the server.
     * MainActivity reads this in onResume() and calls recreate() to refresh all UI.
     * Cleared immediately before recreate() so there's no infinite restart loop.
     */
    var settingsJustRestored: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("settings_just_restored", false)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("settings_just_restored", v).apply() }

    // ── Cross-device settings sync ───────────────────────────────────────────

    /**
     * Set to true while applying settings received from the server so the
     * SharedPreferences change listener in WebSocketService doesn't echo them
     * back to the server in an infinite loop.
     */
    @Volatile
    var isApplyingRemoteSettings = false

    /**
     * SharedPreferences keys whose changes should be pushed to the server.
     * Security and device-specific keys (PIN, biometric, paths) are intentionally excluded.
     */
    val SYNCABLE_KEYS = setOf(
        "accent_color", "font_family", "font_size", "use_24h_clock",
        "compact_bubbles", "ui_opacity", "nav_bar_top", "tab_order",
        "jump_to_bottom_on_send",
        "discoverable", "read_receipts", "typing_indicators", "presence_mode",
        "notif_sounds", "notif_preview", "notifications_enabled",
        "my_bubble_color", "their_bubble_color", "chat_head_alpha",
        "banner_enabled", "banner_visible_to_contacts",
        "chat_head_dismiss_on_tap",
        "chat_head_snap", "chat_head_shape", "stack_switcher_opacity",
        "app_display_name", "splash_text", "auto_login", "my_display_name",
        "launcher_pack", "chat_avatar_mode", "button_haptics"
    )

    /** Returns true when this prefs key should trigger a settings sync to the server. */
    fun isSyncableKey(key: String): Boolean =
        key in SYNCABLE_KEYS || key.startsWith("sec_collapsed_")

    /**
     * Serialize the current user-facing settings into a JSON object for server storage.
     * Excludes security credentials and device-local file paths.
     */
    fun toSettingsJson(): JSONObject = JSONObject().apply {
        // nullable strings → JSON null when not set
        put("accentColor",         accentColor          ?: JSONObject.NULL)
        put("myBubbleColor",       myBubbleColor        ?: JSONObject.NULL)
        put("theirBubbleColor",    theirBubbleColor     ?: JSONObject.NULL)
        put("fontFamily",          fontFamily)
        put("fontSize",            fontSize)
        put("use24HourClock",      use24HourClock)
        put("fullTimestamps",      showFullTimestamps)
        put("compactBubbles",      compactBubbles)
        put("chatAvatarMode",      chatAvatarMode)
        put("jumpToBottomOnSend",  jumpToBottomOnSend)
        put("uiOpacity",           uiOpacity)
        put("navBarAtTop",         navBarAtTop)
        put("tabOrder",            tabOrder)
        put("discoverable",        discoverable)
        put("readReceipts",        readReceipts)
        put("typingIndicators",    typingIndicators)
        put("presenceMode",         presenceMode)
        put("notificationSounds",  notificationSounds)
        put("notificationPreview",       notificationPreview)
        put("notificationsEnabled",      notificationsEnabled)
        put("chatHeadAlpha",             chatHeadAlpha)
        put("chatHeadDismissOnTap",      chatHeadDismissOnTap)
        put("chatHeadSnap",              chatHeadSnapEnabled)
        put("chatHeadShape",             chatHeadShape)
        put("avatarShape",              AvatarShape.fromPref(chatHeadShape).toServer())
        put("stackSwitcherOpacity",      stackSwitcherOpacity)
        put("appDisplayName",            appDisplayName)
        put("splashText",                splashText)
        put("autoLogin",                 autoLogin)
        put("myDisplayName",             myDisplayName)
        put("launcherPack",              launcherPack)
        put("buttonHapticsEnabled",      buttonHapticsEnabled)
        put("bannerEnabled",             bannerEnabled)
        put("bannerHiddenFromContacts",  !bannerVisibleToContacts)
        // Section collapsed states
        val sectionKeys = listOf("branding","privacy","notifications","security","appearance","navigation","blocked","about","support")
        sectionKeys.forEach { put("sec_$it", isSectionCollapsed(it)) }
        // Video/image backgrounds — absolute paths are device-local.
        // Stored in server sync so they survive reinstall on the same device.
        // Ignored silently on other devices if the path doesn't exist.
        put("tabVideoBgChats",    getTabVideoBg("chats")    ?: JSONObject.NULL)
        put("tabVideoBgContacts", getTabVideoBg("contacts") ?: JSONObject.NULL)
        put("tabVideoBgProfile",  getTabVideoBg("profile")  ?: JSONObject.NULL)
        put("tabImageBgChats",    getTabImageBg("chats")    ?: JSONObject.NULL)
        put("tabImageBgContacts", getTabImageBg("contacts") ?: JSONObject.NULL)
        put("tabImageBgProfile",  getTabImageBg("profile")  ?: JSONObject.NULL)
    }

    /**
     * Apply settings received from the server.  Sets [isApplyingRemoteSettings] to prevent
     * the change listener from echoing the writes back to the server.
     *
     * Must be called on the main thread (SharedPreferences callbacks fire synchronously).
     */
    fun applySettingsJson(json: JSONObject) {
        isApplyingRemoteSettings = true
        try {
            if (json.has("accentColor"))
                accentColor         = if (json.isNull("accentColor")) null
                                      else json.optString("accentColor").takeIf { it.isNotBlank() }
            if (json.has("myBubbleColor")) {
                // Write directly to avoid overwriting the _since timestamp on existing devices
                val hex = if (json.isNull("myBubbleColor")) null
                           else json.optString("myBubbleColor").takeIf { it.isNotBlank() }
                val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
                if (hex != null) e.putString("my_bubble_color", hex)
                else             e.remove("my_bubble_color")
                e.apply()
            }
                        if (json.has("theirBubbleColor")) {
                val hex = if (json.isNull("theirBubbleColor")) null
                           else json.optString("theirBubbleColor").takeIf { it.isNotBlank() }
                val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
                if (hex != null) {
                    e.putString("their_bubble_color", hex)
                    // Do not rewrite since unless absent — preserve existing since on sync merge
                    if (!prefs(com.silvershireinn.app.SilvershireApp.instance).contains("their_bubble_color_since")) {
                        e.putString("their_bubble_color_since", nowIso())
                    }
                } else {
                    e.remove("their_bubble_color")
                    e.remove("their_bubble_color_since")
                }
                e.apply()
            }
            if (json.has("fontFamily"))
                fontFamily          = json.optString("fontFamily").ifBlank { "sans-serif" }
            if (json.has("fontSize"))
                fontSize            = json.optInt("fontSize", 1)
            if (json.has("use24HourClock"))
                use24HourClock      = json.optBoolean("use24HourClock", true)
            if (json.has("fullTimestamps"))
                showFullTimestamps  = json.optBoolean("fullTimestamps", false)
            if (json.has("compactBubbles"))
                compactBubbles      = json.optBoolean("compactBubbles", false)
            if (json.has("chatAvatarMode"))
                chatAvatarMode      = json.optInt("chatAvatarMode", 0)
            if (json.has("jumpToBottomOnSend"))
                jumpToBottomOnSend  = json.optBoolean("jumpToBottomOnSend", false)
            if (json.has("uiOpacity"))
                uiOpacity           = json.optInt("uiOpacity", 100)
            if (json.has("navBarAtTop"))
                navBarAtTop         = json.optBoolean("navBarAtTop", false)
            if (json.has("tabOrder"))
                tabOrder            = json.optString("tabOrder", "chats,contacts,profile")
                                        .ifBlank { "chats,contacts,profile" }
            if (json.has("discoverable"))
                discoverable        = json.optBoolean("discoverable", true)
            if (json.has("readReceipts"))
                readReceipts        = json.optBoolean("readReceipts", true)
            if (json.has("typingIndicators"))
                typingIndicators    = json.optBoolean("typingIndicators", true)
            if (json.has("presenceMode") && !json.isNull("presenceMode")) {
                val pm = com.silvershireinn.app.network.OnlineStatusManager
                    .normalizePresenceMode(json.optString("presenceMode"))
                if (pm != null) presenceMode = pm
            }
            if (json.has("notificationSounds"))
                notificationSounds  = json.optBoolean("notificationSounds", true)
            if (json.has("notificationPreview"))
                notificationPreview = json.optBoolean("notificationPreview", true)
            if (json.has("notificationsEnabled"))
                notificationsEnabled = json.optBoolean("notificationsEnabled", true)
            if (json.has("chatHeadAlpha"))
                chatHeadAlpha            = json.optInt("chatHeadAlpha", 100)
            if (json.has("chatHeadDismissOnTap"))
                chatHeadDismissOnTap     = json.optBoolean("chatHeadDismissOnTap", false)
            if (json.has("chatHeadSnap"))
                chatHeadSnapEnabled      = json.optBoolean("chatHeadSnap", true)
            if (json.has("avatarShape") && !json.isNull("avatarShape")) {
                val sh = json.optString("avatarShape", "").trim()
                if (sh.isNotBlank()) chatHeadShape = AvatarShape.fromServer(sh).prefKey()
            } else if (json.has("chatHeadShape"))
                chatHeadShape            = json.optString("chatHeadShape", "circle")
            if (json.has("stackSwitcherOpacity"))
                stackSwitcherOpacity     = json.optInt("stackSwitcherOpacity", 0)
            if (json.has("appDisplayName"))
                appDisplayName           = json.optString("appDisplayName", "")
            if (json.has("splashText"))
                splashText               = json.optString("splashText", "")
            if (json.has("autoLogin"))
                autoLogin                = json.optBoolean("autoLogin", true)
            if (json.has("myDisplayName"))
                myDisplayName            = json.optString("myDisplayName", "")
            if (json.has("launcherPack"))
                launcherPack             = json.optString("launcherPack", "default")
            if (json.has("buttonHapticsEnabled"))
                buttonHapticsEnabled     = json.optBoolean("buttonHapticsEnabled", true)
            if (json.has("bannerEnabled"))
                bannerEnabled            = json.optBoolean("bannerEnabled", true)
            if (json.has("bannerHiddenFromContacts"))
                bannerVisibleToContacts  = !json.optBoolean("bannerHiddenFromContacts", false)
            // Section collapsed states
            val sectionKeys2 = listOf("branding","privacy","notifications","security","appearance","navigation","blocked","about","support")
            sectionKeys2.forEach { k ->
                if (json.has("sec_$k")) setSectionCollapsed(k, json.optBoolean("sec_$k", true))
            }
            // Video/image backgrounds — only restore if the file still exists on this device.
            // Video and image are mutually exclusive per tab: if video is restored for a tab,
            // clear the image to prevent the old image from re-appearing after a settings sync.
            fun restorePath(key: String): String? {
                if (!json.has(key) || json.isNull(key)) return null
                val p = json.optString(key).takeIf { it.isNotBlank() } ?: return null
                return if (java.io.File(p).exists()) p else null
            }
            for (tab in listOf("chats", "contacts", "profile")) {
                val videoKey = "tabVideoBg${tab.replaceFirstChar { it.uppercaseChar() }}"
                val imageKey = "tabImageBg${tab.replaceFirstChar { it.uppercaseChar() }}"
                val videoPath = restorePath(videoKey)
                val imagePath = restorePath(imageKey)
                if (videoPath != null) {
                    // Video wins — clear image
                    setTabVideoBg(tab, videoPath)
                    setTabImageBg(tab, null)
                } else {
                    setTabVideoBg(tab, null)
                    setTabImageBg(tab, imagePath)
                }
            }
        } finally {
            isApplyingRemoteSettings = false
        }
    }

    // ── Listener registration ────────────────────────────────────────────────

    /**
     * Register a [SharedPreferences.OnSharedPreferenceChangeListener] on the main prefs file.
     * Hold a strong reference to the listener in the caller — SharedPreferences only keeps a
     * WeakReference internally, so the listener will be GC-collected if you don't.
     */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(com.silvershireinn.app.SilvershireApp.instance).registerOnSharedPreferenceChangeListener(listener)
    }

    /** Unregister a previously registered listener. Call from onDestroy / cleanup. */
    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(com.silvershireinn.app.SilvershireApp.instance).unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Clear all user-facing preferences so a new account starts from defaults.
     * Deliberately does NOT clear server credentials (the user just set those)
     * or security/PIN settings (those are per-device, not per-account).
     *
     * Call this from the registration flow BEFORE saving the new account's credentials.
     */
    fun clearUserFacingSettings() {
        prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
            .remove("accent_color")
            .remove("history_cleared_all")   // belongs to the previous account
            .remove("my_bubble_color")
            .remove("my_bubble_color_since")
            .remove("font_family")
            .remove("font_size")
            .remove("use_24h_clock")
            .remove("compact_bubbles")
            .remove("ui_opacity")
            .remove("nav_bar_top")
            .remove("tab_order")
            .remove("discoverable")
            .remove("read_receipts")
            .remove("typing_indicators")
            .remove("notif_sounds")
            .remove("notif_preview")
            .remove("notifications_enabled")
            .remove("chat_head_alpha")
            .remove("chat_heads")
            .remove("profile_pic_path")
            .remove("their_bubble_color")
            .remove("their_bubble_color_since")
            .remove("my_display_name")
            .remove("signup_username")
            .remove("presence_mode")
            .remove("chat_avatar_mode")
            .apply()
        // The previous account's per-friend settings (nicknames, colors, backgrounds...).
        val p = prefs(com.silvershireinn.app.SilvershireApp.instance)
        val e = p.edit()
        for (k in p.all.keys) if (CONTACT_KEY_PREFIXES.any { k.startsWith(it) }) e.remove(k)
        e.apply()
    }

    /** Every per-contact key prefix stored in the main prefs file. */
    private val CONTACT_KEY_PREFIXES = listOf(
        "nickname_", "display_name_", "avatar_shape_", "bubble_color_since_", "bubble_color_",
        "chat_text_color_", "chat_videobg_", "chat_imagebg_", "ch_x_", "ch_y_", "cleared_at_"
    )

    /**
     * A contact changed their username: carry every per-contact setting (nickname, colors,
     * backgrounds, chat-head position, per-chat settings file) over to the new name.
     */
    fun migrateContactKeys(oldName: String, newName: String) {
        if (oldName.isBlank() || newName.isBlank() || oldName == newName) return
        val ctx = com.silvershireinn.app.SilvershireApp.instance
        val p = prefs(ctx)
        val e = p.edit()
        for ((k, v) in p.all) {
            val prefix = CONTACT_KEY_PREFIXES.firstOrNull { k == it + oldName } ?: continue
            val nk = prefix + newName
            when (v) {
                is String -> e.putString(nk, v); is Int -> e.putInt(nk, v)
                is Boolean -> e.putBoolean(nk, v); is Long -> e.putLong(nk, v); is Float -> e.putFloat(nk, v)
            }
            e.remove(k)
        }
        e.apply()
        try {
            val from = ctx.getSharedPreferences("chat_prefs_$oldName", Context.MODE_PRIVATE)
            if (from.all.isNotEmpty()) {
                val to = ctx.getSharedPreferences("chat_prefs_$newName", Context.MODE_PRIVATE).edit()
                for ((k, v) in from.all) when (v) {
                    is String -> to.putString(k, v); is Int -> to.putInt(k, v)
                    is Boolean -> to.putBoolean(k, v); is Long -> to.putLong(k, v); is Float -> to.putFloat(k, v)
                }
                to.apply()
                from.edit().clear().apply()
            }
        } catch (_: Exception) {}
        notifyNamesChanged(newName)
    }

    /** Full wipe — used only by the panic/nuke-data security feature. */
    fun clearAll() {
        prefs(com.silvershireinn.app.SilvershireApp.instance).edit().clear().apply()
    }

    /**
     * Logout wipe — removes account credentials while preserving all user preferences
     * (fonts, colors, backgrounds, etc.) so they survive logout/login cycles.
     */
    /**
     * Per-device HTTP token from the server (sent after the secure login). Proves who we are
     * for uploads / downloads instead of the shared app password.
     */
    var httpToken: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("http_token", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("http_token", v).apply() }

    /** Invite link the server reports (its public download page); blank until it knows one. */
    var shareUrl: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("share_url", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("share_url", v).apply() }

    fun clearAccountData() {
        // Clear account credentials, session state, and account-specific visuals.
        // Device-specific security settings (PIN, biometric, lock-screen preferences) are
        // intentionally NOT cleared — they are per-device, not per-account, and the user
        // should not have to reconfigure their lock screen every time they log out.
        val p = prefs(com.silvershireinn.app.SilvershireApp.instance)
        val editor = p.edit()
            .remove("username")
            .remove("http_token")
            .remove("setup_complete")
            .remove("registered")
            .remove("pwd_hash")
            .remove("pwd_salt")
            .remove("failed_attempts")
            .remove("lockout_until")
            .remove("perms_requested")
            .remove("last_activity_time")
            .remove("profile_pic_path")
            .remove("banner_pic_path")

        // Clear tab backgrounds so a new account doesn't inherit the previous account's
        // tab image/video backgrounds.
        val tabKeys = listOf("chats", "contacts", "profile")
        for (tab in tabKeys) {
            editor.remove("tab_imagebg_$tab")
            editor.remove("tab_videobg_$tab")
        }

        // Clear all per-contact chat backgrounds (prefix-scan since contacts vary by account)
        for (key in p.all.keys) {
            if (key.startsWith("chat_imagebg_") || key.startsWith("chat_videobg_")) {
                editor.remove(key)
            }
        }

        editor.apply()
        // NOTE: pin_enabled, pin_hash, biometric_enabled, req_pwd_open,
        //       auto_logout_enabled, auto_logout_minutes are preserved through logout.
    }

    // ── Button haptic feedback ───────────────────────────────────────────────

    /** true (default) = every button press vibrates ~50ms */
    var buttonHapticsEnabled: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("button_haptics", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("button_haptics", v).apply() }

    // ── UI opacity ───────────────────────────────────────────────────────────

    /**
     * Opacity of the main app UI panels over the video/image background.
     * 0 = fully transparent, 100 = fully opaque (default).
     */
    var uiOpacity: Int
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getInt("ui_opacity", 100)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putInt("ui_opacity", v).apply() }

    // ── Tab / chat image backgrounds ─────────────────────────────────────────

    /** Absolute path to a static image used as background for [tabKey]. null = no image. */
    fun getTabImageBg(tabKey: String): String? =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("tab_imagebg_$tabKey", null)

    fun setTabImageBg(tabKey: String, path: String?) {
        val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
        if (path != null) e.putString("tab_imagebg_$tabKey", path) else e.remove("tab_imagebg_$tabKey")
        e.apply()
    }

    /** Absolute path to a static image used as background for a specific chat. null = no image. */
    fun getChatImageBg(username: String): String? =
        prefs(com.silvershireinn.app.SilvershireApp.instance).getString("chat_imagebg_$username", null)

    fun setChatImageBg(username: String, path: String?) {
        val e = prefs(com.silvershireinn.app.SilvershireApp.instance).edit()
        if (path != null) e.putString("chat_imagebg_$username", path) else e.remove("chat_imagebg_$username")
        e.apply()
    }

    // ── Chat head polish ─────────────────────────────────────────────────────

    var chatHeadSnapEnabled: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("chat_head_snap", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("chat_head_snap", v).apply() }

    var chatHeadShape: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("chat_head_shape", "circle") ?: "circle"
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("chat_head_shape", v).apply() }

    var stackSwitcherOpacity: Int
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getInt("stack_switcher_opacity", 0)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putInt("stack_switcher_opacity", v).apply() }

    // ── Branding ─────────────────────────────────────────────────────────────

    var appDisplayName: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("app_display_name", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("app_display_name", v).apply() }

    var splashText: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("splash_text", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("splash_text", v).apply() }

    var brandLogoPath: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("brand_logo_path", "") ?: ""
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("brand_logo_path", v).apply() }

    var launcherPack: String
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getString("launcher_pack", "default") ?: "default"
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putString("launcher_pack", v).apply() }

    var autoLogin: Boolean
        get() = prefs(com.silvershireinn.app.SilvershireApp.instance).getBoolean("auto_login", true)
        set(v) { prefs(com.silvershireinn.app.SilvershireApp.instance).edit().putBoolean("auto_login", v).apply() }
}

