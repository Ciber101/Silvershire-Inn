package com.silvershireinn.app.receivers

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.crypto.CryptoManager
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.notifications.NotificationHelper
import com.silvershireinn.app.repository.MessageRepository
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.nowIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * Handles Android Auto / notification reply + mark-as-read actions.
 * Sends via the same WebSocket path used by in-app chat.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPLY -> handleReply(context, intent)
            ACTION_MARK_READ -> handleMarkRead(context, intent)
            ACTION_FRIEND_ACCEPT -> handleFriendResponse(context, intent, accepted = true)
            ACTION_FRIEND_DECLINE -> handleFriendResponse(context, intent, accepted = false)
            ACTION_DISMISSED -> intent.getStringExtra(EXTRA_CONV_KEY)?.let { NotificationHelper.onMessageNotificationDismissed(it) }
        }
    }

    private fun handleReply(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        if (reply.isBlank()) return
        if (com.silvershireinn.app.utils.PanicLock.matchesLockWord(reply)) {
            com.silvershireinn.app.utils.PanicLock.lockNow(context)
            return
        }

        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID) ?: return
        val groupId   = intent.getStringExtra(EXTRA_GROUP_ID)
        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: "Group"
        val notifId   = intent.getIntExtra(EXTRA_NOTIF_ID, contactId.hashCode())
        val me = PreferenceManager.username
        if (me.isBlank()) return

        val pending = goAsync()
        scope.launch {
            try {
                val mid = UUID.randomUUID().toString()
                val ts  = nowIso()
                val repo = MessageRepository(SilvershireApp.database)
                // The app may have been asleep: wake the connection first so the reply goes out
                // now (it used to sit unsent until the app was opened again).
                awaitConnection(context)
                if (!groupId.isNullOrBlank()) {
                    repo.insertGroupOutgoing(me, groupId, groupName, reply, ts, mid)
                    WebSocketManager.sendGroupMessage(groupId, com.silvershireinn.app.ui.chat.ChatSender.encryptForGroup(groupId, reply), mid, ts)
                } else {
                    repo.insertOutgoing(me, contactId, reply, ts, mid)
                    val payload = encryptForContact(contactId, reply)
                    WebSocketManager.sendPrivateMessage(me, contactId, payload, mid, ts)
                }
                // Replying means you've read the chat: clear its unread badge here and on
                // your other devices (and tell the sender, if read receipts are on).
                markConversationRead(context, groupId?.takeIf { it.isNotBlank() } ?: contactId, isGroup = !groupId.isNullOrBlank())
                // Echo reply into MessagingStyle history for Auto / shade
                NotificationHelper.appendOutgoingReply(context, contactId, groupId, groupName, reply)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Reply failed: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleMarkRead(context: Context, intent: Intent) {
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID) ?: return
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        // Cancel the exact posted notification (tag + id) then clear history maps
        if (notifId != -1) {
            NotificationManagerCompat.from(context).cancel(NotificationHelper.TAG_MESSAGE, notifId)
        }
        NotificationHelper.cancelMessageNotifications(context, contactId)
        if (!groupId.isNullOrBlank() && groupId != contactId) {
            NotificationManagerCompat.from(context).cancel(NotificationHelper.TAG_MESSAGE, groupId.hashCode())
            NotificationHelper.cancelMessageNotifications(context, groupId)
        }
        // "Mark as read" = really read: clear the unread badge locally (groups too: they used
        // to stay unread), on my other devices and on the chat head; receipts for 1:1 if on.
        val pending = goAsync()
        scope.launch {
            try {
                awaitConnection(context)
                val isGroup = !groupId.isNullOrBlank()
                markConversationRead(context, if (isGroup) groupId!! else contactId, isGroup)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Mark-read failed: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun markConversationRead(context: Context, id: String, isGroup: Boolean) {
        val repo = MessageRepository(SilvershireApp.database)
        val me = PreferenceManager.username
        val unread = repo.markAllReadAndGetUnread(id)
        if (!isGroup && PreferenceManager.readReceipts) {
            for (mid in unread) WebSocketManager.sendReadReceipt(me, id, mid)
        }
        WebSocketManager.sendConversationRead(me, id)
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            try { com.silvershireinn.app.ui.chathead.ChatHeadService.clearUnread(context, id) } catch (_: Exception) {}
        }
    }

    /**
     * Make sure the socket is up and logged in (start the service if the app was asleep),
     * waiting up to [timeoutMs]. Notification actions are allowed to start it.
     */
    private suspend fun awaitConnection(context: Context, timeoutMs: Long = 8_000L) {
        if (WebSocketManager.isAuthenticated) return
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, com.silvershireinn.app.network.WebSocketService::class.java))
        } catch (_: Exception) {}
        val end = System.currentTimeMillis() + timeoutMs
        while (!WebSocketManager.isAuthenticated && System.currentTimeMillis() < end) kotlinx.coroutines.delay(200)
    }

    private fun handleFriendResponse(context: Context, intent: Intent, accepted: Boolean) {
        val fromUser = intent.getStringExtra(EXTRA_CONTACT_ID) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                val repo = com.silvershireinn.app.repository.ContactRepository(SilvershireApp.database)
                awaitConnection(context)   // the answer used to be lost if the app was asleep
                if (accepted) {
                    repo.confirmFriend(fromUser)
                    WebSocketManager.sendFriendResponse(PreferenceManager.username, fromUser, true)
                } else {
                    repo.deleteContact(fromUser)
                    WebSocketManager.sendFriendResponse(PreferenceManager.username, fromUser, false)
                }
                NotificationHelper.cancelFriendRequestNotification(context)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Friend response failed: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun encryptForContact(contactId: String, plaintext: String): String {
        return try {
            val contact = SilvershireApp.database.contactDao().getContact(contactId)
            val pubKeyB64 = contact?.publicKey?.takeIf { it.isNotBlank() }
            if (pubKeyB64 != null && CryptoManager.hasRsaKeyPair()) {
                CryptoManager.encryptE2E(plaintext, pubKeyB64)
            } else {
                if (pubKeyB64 == null) {
                    WebSocketManager.sendRaw(JSONObject().apply {
                        put("type", "get_public_key")
                        put("target", contactId)
                    }.toString())
                }
                plaintext
            }
        } catch (_: Exception) {
            plaintext
        }
    }

    companion object {
        private const val TAG = "NotifReply"
        const val ACTION_REPLY = "com.silvershireinn.app.ACTION_NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "com.silvershireinn.app.ACTION_NOTIFICATION_MARK_READ"
        const val ACTION_FRIEND_ACCEPT = "com.silvershireinn.app.ACTION_FRIEND_ACCEPT"
        const val ACTION_FRIEND_DECLINE = "com.silvershireinn.app.ACTION_FRIEND_DECLINE"
        const val ACTION_DISMISSED = "com.silvershireinn.app.ACTION_NOTIFICATION_DISMISSED"
        const val EXTRA_CONV_KEY = "conv_key"
        const val KEY_REPLY = "key_notification_reply"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_GROUP_NAME = "group_name"
        const val EXTRA_NOTIF_ID = "notif_id"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.silvershireinn.app.utils.CrashGuard.coroutineHandler)

        fun replyPendingIntent(
            context: Context,
            notifId: Int,
            contactId: String,
            groupId: String? = null,
            groupName: String? = null
        ): PendingIntent {
            val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_GROUP_ID, groupId)
                putExtra(EXTRA_GROUP_NAME, groupName)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            return PendingIntent.getBroadcast(
                context, notifId xor 0x11,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }

        fun markReadPendingIntent(
            context: Context,
            notifId: Int,
            contactId: String,
            groupId: String? = null
        ): PendingIntent {
            val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
                action = ACTION_MARK_READ
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_GROUP_ID, groupId)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            return PendingIntent.getBroadcast(
                context, notifId xor 0x22,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun dismissedPendingIntent(context: Context, notifId: Int, conversationKey: String): PendingIntent {
            val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
                action = ACTION_DISMISSED
                putExtra(EXTRA_CONV_KEY, conversationKey)
            }
            return PendingIntent.getBroadcast(
                context, notifId xor 0x55, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun friendAcceptPendingIntent(context: Context, fromUser: String): PendingIntent {
            val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
                action = ACTION_FRIEND_ACCEPT
                putExtra(EXTRA_CONTACT_ID, fromUser)
            }
            return PendingIntent.getBroadcast(
                context, fromUser.hashCode() xor 0x33,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun friendDeclinePendingIntent(context: Context, fromUser: String): PendingIntent {
            val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
                action = ACTION_FRIEND_DECLINE
                putExtra(EXTRA_CONTACT_ID, fromUser)
            }
            return PendingIntent.getBroadcast(
                context, fromUser.hashCode() xor 0x44,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

