package com.silvershireinn.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.silvershireinn.app.R
import com.silvershireinn.app.ui.call.CallActivity
import com.silvershireinn.app.ui.call.VideoCallActivity
import com.silvershireinn.app.ui.chat.ChatActivity
import com.silvershireinn.app.ui.main.MainActivity
import com.silvershireinn.app.utils.PreferenceManager

object NotificationHelper {

    const val CHANNEL_MESSAGES      = "silvershire_messages"
    const val CHANNEL_SERVICE       = "silvershire_service"
    const val CHANNEL_CALLS         = "silvershire_calls"
    const val CHANNEL_BUBBLES       = "silvershire_bubbles"
    const val CHANNEL_FRIEND_REQ    = "silvershire_friend_requests"

    private const val CALL_NOTIF_ID       = 888
    private const val VIDEO_CALL_NOTIF_ID = 889
    private const val FRIEND_REQ_NOTIF_ID = 777

    /** Last time each conversation's notification made a sound / buzz. */
    private val lastAlertAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Round, small avatar for a contact (null = none / failed). Off the main thread only. */
    private fun avatarIcon(context: Context, username: String): IconCompat? = try {
        val f = com.silvershireinn.app.utils.ProfileMediaHelper.contactPic(context, username)
        val src = when {
            f == null -> null
            com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(f.absolutePath) ->
                com.silvershireinn.app.utils.ProfileMediaHelper.videoFrameBitmap(f.absolutePath)
            else -> com.silvershireinn.app.utils.VideoThumbs.decodeSmall(f.absolutePath, 128)
        }
        src?.let { IconCompat.createWithBitmap(circle(it)) }
    } catch (_: Throwable) { null }

    private fun circle(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val size = minOf(src.width, src.height, 128).coerceAtLeast(1)
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val s = minOf(src.width, src.height)
        val sx = (src.width - s) / 2; val sy = (src.height - s) / 2
        c.drawBitmap(src, android.graphics.Rect(sx, sy, sx + s, sy + s), android.graphics.Rect(0, 0, size, size), p)
        return out
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_MESSAGES,
                "Silvershire Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New message notifications"
                enableLights(true)
                enableVibration(true)
            })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_SERVICE,
                "Silvershire Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background connection service"
            })

            // CALLS channel — highest importance so the full-screen intent fires
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_CALLS,
                "Silvershire Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming voice and video calls"
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })

            // BUBBLES channel — required for Android 11+ Bubbles API (chat heads)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_BUBBLES,
                    "Silvershire Bubbles",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Chat head bubbles for active conversations"
                    enableLights(true)
                    enableVibration(true)
                    // Allow bubbles on this channel
                    setAllowBubbles(true)
                })
            }

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_FRIEND_REQ,
                "Silvershire Friend Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming friend requests"
                enableLights(true)
                enableVibration(true)
            })
        }
    }

    // ── Incoming voice call ───────────────────────────────────────────────────

    fun showIncomingCallNotification(context: Context, fromUser: String) {
        if (com.silvershireinn.app.utils.PanicLock.locked) return   // panic lock: show nothing
        val fullScreenIntent = CallActivity.incomingIntent(context, fromUser)

        val fullScreenPi = PendingIntent.getActivity(
            context, CALL_NOTIF_ID, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = CallActivity.incomingIntent(context, fromUser).apply {
            putExtra("auto_accept", true)
        }
        val acceptPi = PendingIntent.getActivity(
            context, CALL_NOTIF_ID + 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(context, CallActivity::class.java).apply {
            putExtra("remote_user", fromUser)
            putExtra("is_incoming", true)
            putExtra("auto_reject", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val rejectPi = PendingIntent.getActivity(
            context, CALL_NOTIF_ID + 2, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("📞 Incoming call")
            .setContentText("${PreferenceManager.displayNameFor(fromUser)} is calling…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .addAction(R.drawable.ic_call_end, "Decline", rejectPi)
            .addAction(R.drawable.ic_call, "Accept", acceptPi)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(CALL_NOTIF_ID, notification)
        } catch (e: SecurityException) { /* no permission */ }
    }

    fun cancelCallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(CALL_NOTIF_ID)
        NotificationManagerCompat.from(context).cancel(VIDEO_CALL_NOTIF_ID)
    }

    // ── Incoming video call ───────────────────────────────────────────────────

    fun showIncomingVideoCallNotification(context: Context, fromUser: String) {
        if (com.silvershireinn.app.utils.PanicLock.locked) return   // panic lock: show nothing
        val fullScreenIntent = VideoCallActivity.incomingIntent(context, fromUser)
        val fullScreenPi = PendingIntent.getActivity(
            context, VIDEO_CALL_NOTIF_ID, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept → go straight to VideoCallActivity (auto-accept handled by onNewIntent)
        val acceptIntent = VideoCallActivity.incomingIntent(context, fromUser).apply {
            putExtra("auto_accept", true)
        }
        val acceptPi = PendingIntent.getActivity(
            context, VIDEO_CALL_NOTIF_ID + 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline → open activity briefly just to reject, then finish
        val declineIntent = VideoCallActivity.incomingIntent(context, fromUser).apply {
            putExtra("auto_reject", true)
        }
        val declinePi = PendingIntent.getActivity(
            context, VIDEO_CALL_NOTIF_ID + 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_video_call)
            .setContentTitle("📹 Incoming video call")
            .setContentText("${PreferenceManager.displayNameFor(fromUser)} is video calling…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPi, true)   // auto-launches on supported devices
            .setContentIntent(fullScreenPi)
            .addAction(R.drawable.ic_call_end, "Decline", declinePi)
            .addAction(R.drawable.ic_video_call, "Accept", acceptPi)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(VIDEO_CALL_NOTIF_ID, notification)
        } catch (e: SecurityException) { /* no permission */ }
    }

    // ── Regular message notification ──────────────────────────────────────────

    /**
     * Notification tag used for per-conversation notifications.
     * Using a tag lets us cancel all notifications for a given user with a single call.
     */
    const val TAG_MESSAGE = "msg"

    // Per-conversation MessagingStyle history for Android Auto / notification shade
    private val messageHistories = java.util.concurrent.ConcurrentHashMap<String, MutableList<NotificationCompat.MessagingStyle.Message>>()

    fun showMessageNotification(context: Context, fromUser: String, preview: String) {
        if (com.silvershireinn.app.utils.PanicLock.locked) return   // panic lock: show nothing
        val display = PreferenceManager.displayNameFor(fromUser)
        showNotificationInternal(
            context    = context,
            notifId    = fromUser.hashCode(),
            conversationKey = fromUser,
            title      = display,
            // a locked chat never shows what was said
            preview    = if (com.silvershireinn.app.utils.ChatLocks.isLocked(fromUser)) "New message" else preview,
            personName = display,
            personKey  = fromUser,
            chatIntent = Intent(context, ChatActivity::class.java).apply {
                putExtra("contact_username", fromUser)
                action = Intent.ACTION_VIEW
                flags  = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            shortcutId = "chat_$fromUser",
            contactId  = fromUser,
            groupId    = null,
            groupName  = null
        )
    }

    fun showGroupMessageNotification(
        context: Context,
        groupId: String,
        groupName: String,
        fromUser: String,
        preview: String
    ) {
        if (com.silvershireinn.app.utils.PanicLock.locked) return   // panic lock: show nothing
        val displayName = groupName.ifBlank { "Group" }
        val senderName = PreferenceManager.displayNameFor(fromUser).ifBlank {
            fromUser.ifBlank { displayName }
        }
        showNotificationInternal(
            context    = context,
            notifId    = groupId.hashCode(),
            conversationKey = "group_$groupId",
            title      = displayName,
            preview    = if (com.silvershireinn.app.utils.ChatLocks.isLocked(groupId)) "New message" else preview,
            personName = senderName,
            personKey  = fromUser.ifBlank { groupId },
            chatIntent = Intent(context, ChatActivity::class.java).apply {
                putExtra("contact_username", groupId)
                putExtra("group_id",         groupId)
                putExtra("group_name",       displayName)
                action = Intent.ACTION_VIEW
                flags  = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            shortcutId = "chat_group_$groupId",
            contactId  = groupId,
            groupId    = groupId,
            groupName  = displayName
        )
    }

    /** After a successful Auto/notification reply, refresh the shade with the outgoing text. */
    fun appendOutgoingReply(
        context: Context,
        contactId: String,
        groupId: String?,
        groupName: String?,
        text: String
    ) {
        val key = if (!groupId.isNullOrBlank()) "group_$groupId" else contactId
        val me = PreferenceManager.username.ifBlank { "Me" }
        val history = messageHistories.getOrPut(key) { mutableListOf() }
        val self = Person.Builder().setName(me).setKey(me).build()
        history.add(
            NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), self)
        )
        while (history.size > 6) history.removeAt(0)
        val isGroup = !groupId.isNullOrBlank()
        val title = if (isGroup) {
            (groupName ?: "Group")
        } else {
            PreferenceManager.displayNameFor(contactId)
        }
        val notifId = (if (isGroup) groupId!! else contactId).hashCode()
        showNotificationInternal(
            context = context,
            notifId = notifId,
            conversationKey = key,
            title = title,
            preview = text,
            personName = title,
            personKey = if (isGroup) (groupId ?: contactId) else contactId,
            chatIntent = Intent(context, ChatActivity::class.java).apply {
                putExtra("contact_username", if (isGroup) groupId else contactId)
                if (isGroup) {
                    putExtra("group_id", groupId)
                    putExtra("group_name", title)
                }
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            shortcutId = if (isGroup) "chat_group_$groupId" else "chat_$contactId",
            contactId = if (isGroup) groupId!! else contactId,
            groupId = groupId,
            groupName = groupName,
            skipIncomingAppend = true
        )
    }

    private fun showNotificationInternal(
        context: Context,
        notifId: Int,
        conversationKey: String,
        title: String,
        preview: String,
        personName: String,
        personKey: String,
        chatIntent: Intent,
        shortcutId: String,
        contactId: String,
        groupId: String?,
        groupName: String?,
        skipIncomingAppend: Boolean = false
    ) {
        // Master switch - when the user has turned notifications off in-app, post nothing.
        if (!PreferenceManager.notificationsEnabled) return
        // Android 13+ runtime permission / user disabled channel/app notifications
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val pendingIntent = PendingIntent.getActivity(
            context, notifId, chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shownText = if (PreferenceManager.notificationPreview) preview else "New message"
        // The friend's own picture on the notification (and on the conversation shortcut)
        // instead of a generic icon.
        val senderIcon = if (personKey != PreferenceManager.username) avatarIcon(context, personKey) else null
        val sender = Person.Builder()
            .setName(personName)
            .setKey(personKey)
            .apply { if (senderIcon != null) setIcon(senderIcon) }
            .build()
        val history = messageHistories.getOrPut(conversationKey) { mutableListOf() }
        if (!skipIncomingAppend) {
            history.add(
                NotificationCompat.MessagingStyle.Message(
                    shownText, System.currentTimeMillis(), sender
                )
            )
            while (history.size > 6) history.removeAt(0)
        }

        val meName = PreferenceManager.username.ifBlank { "Me" }
        val me = Person.Builder().setName(meName).setKey(meName).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (groupId != null) title else null)
            .setGroupConversation(groupId != null)
        history.forEach { style.addMessage(it) }

        // Long-lived conversation shortcut required for Android 11+ / Auto conversation surface
        try {
            val shortcutPerson = if (groupId != null) {
                Person.Builder().setName(title).setKey(groupId).build()
            } else {
                sender
            }
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(title.ifBlank { personName })
                .setLongLabel(title.ifBlank { personName })
                .setIcon((if (groupId == null) senderIcon else null)
                    ?: IconCompat.createWithResource(context, R.drawable.ic_flame))
                .setIntent(chatIntent)
                .setPerson(shortcutPerson)
                .setLongLived(true)
                .setCategories(setOf("android.shortcut.conversation"))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (_: Exception) { /* shortcut APIs optional; messaging still works */ }

        val replyLabel = "Reply"
        val remoteInput = androidx.core.app.RemoteInput.Builder(
            com.silvershireinn.app.receivers.NotificationReplyReceiver.KEY_REPLY
        ).setLabel(replyLabel).build()

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_send,
            replyLabel,
            com.silvershireinn.app.receivers.NotificationReplyReceiver.replyPendingIntent(
                context, notifId, contactId, groupId, groupName
            )
        ).addRemoteInput(remoteInput)
         .setAllowGeneratedReplies(true)
         .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
         // Critical for Android Auto inline reply (do not launch phone UI)
         .setShowsUserInterface(false)
         .build()

        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_close,
            "Mark as read",
            com.silvershireinn.app.receivers.NotificationReplyReceiver.markReadPendingIntent(
                context, notifId, contactId, groupId
            )
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
         .setShowsUserInterface(false)
         .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(title)
            .setContentText(shownText)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setShortcutId(shortcutId)
            .setAutoCancel(true)
            // Sound / buzz for each new message, but not for a burst (several within 4 s) and
            // not for my own reply. Alert-once meant a second message minutes later was silent
            // if the first notification was still in the shade.
            .setOnlyAlertOnce(skipIncomingAppend || run {
                val now = System.currentTimeMillis()
                val last = lastAlertAt[conversationKey] ?: 0L
                if (now - last < 4_000L) true else { lastAlertAt[conversationKey] = now; false }
            })
            // Swiping it away forgets the shown history, so the next notification starts fresh
            // instead of repeating messages already seen.
            .setDeleteIntent(com.silvershireinn.app.receivers.NotificationReplyReceiver.dismissedPendingIntent(context, notifId, conversationKey))
            .addAction(replyAction)
            .addAction(markReadAction)
            .extend(
                NotificationCompat.CarExtender()
                    .setColor(0xFFC9A227.toInt())
            )

        if (!PreferenceManager.notificationSounds) builder.setSilent(true)
        // "Mute" in this chat's settings: still listed in the shade, but no sound, buzz or pop-up.
        // (The switch existed but nothing read it.)
        if (!skipIncomingAppend && com.silvershireinn.app.ui.chat.ChatSettingsActivity.isMuted(context, groupId ?: contactId)) {
            builder.setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
        }

        try {
            NotificationManagerCompat.from(context).notify(TAG_MESSAGE, notifId, builder.build())
        } catch (e: SecurityException) { /* no permission */ }
    }

    /**
     * Cancel all message notifications for [fromUser]. Call when the chat with that user is opened.
     */
    fun cancelMessageNotifications(context: Context, fromUser: String) {
        NotificationManagerCompat.from(context).cancel(TAG_MESSAGE, fromUser.hashCode())
        messageHistories.remove(fromUser)
        messageHistories.remove("group_$fromUser")
    }

    /** The user swiped a message notification away. */
    fun onMessageNotificationDismissed(conversationKey: String) {
        messageHistories.remove(conversationKey)
    }

    /** Cancel the friend request notification. Call when a request is accepted or declined. */
    fun cancelFriendRequestNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(FRIEND_REQ_NOTIF_ID)
    }

    // ── Friend request notification ───────────────────────────────────────────

    /**
     * Show a notification when a new friend request arrives.
     * Tapping it opens the Contacts tab where the user can accept or decline.
     */
    fun showFriendRequestNotification(context: Context, fromUser: String) {
        if (com.silvershireinn.app.utils.PanicLock.locked) return   // panic lock: show nothing
        // Respect the master notifications switch.
        if (!PreferenceManager.notificationsEnabled) return
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_tab", "contacts")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, FRIEND_REQ_NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val approvePi = com.silvershireinn.app.receivers.NotificationReplyReceiver.friendAcceptPendingIntent(context, fromUser)
        val declinePi = com.silvershireinn.app.receivers.NotificationReplyReceiver.friendDeclinePendingIntent(context, fromUser)
        val notification = NotificationCompat.Builder(context, CHANNEL_FRIEND_REQ)
            .setSmallIcon(R.drawable.ic_contacts)
            .setContentTitle("Friend request")
            .setContentText("${PreferenceManager.displayNameFor(fromUser)} (@$fromUser) wants to add you")
            .addAction(R.drawable.ic_contacts, "Approve", approvePi)
            .addAction(R.drawable.ic_close, "Decline", declinePi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(FRIEND_REQ_NOTIF_ID, notification)
        } catch (e: SecurityException) { /* no permission */ }
    }

    // ── Foreground service notification ───────────────────────────────────────

    fun buildServiceNotification(
        context: Context,
        statusText: String = "Connected and secure"
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            // Tapping the connection notification opens the app (it used to do nothing).
            .setContentIntent(PendingIntent.getActivity(
                context, 4242,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
}
