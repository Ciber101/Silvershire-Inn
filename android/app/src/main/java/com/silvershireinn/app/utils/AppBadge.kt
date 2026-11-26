package com.silvershireinn.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.silvershireinn.app.database.ConvUnread
import com.silvershireinn.app.notifications.NotificationHelper

/**
 * Keeps the number on the app's icon in step with what is really unread. A chat read in the
 * floating window (or on the web) used to keep its count on the icon until the app was opened:
 * its notification is now cleared the moment the chat has nothing unread, and launchers that
 * take a number directly are told the total.
 */
object AppBadge {
    @Volatile private var lastTotal = -1

    fun onUnread(ctx: Context, list: List<ConvUnread>) {
        val app = ctx.applicationContext
        val unread = list.filter { it.n > 0 }.associate { it.conversationId to it.n }
        // Message notifications of chats with nothing unread any more: gone (the icon's dot /
        // number on most phones counts these)
        if (Build.VERSION.SDK_INT >= 23) try {
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            val stillUnread = unread.keys.flatMap { listOf(it.hashCode(), ("group_$it").hashCode()) }.toSet()
            for (sbn in nm?.activeNotifications ?: emptyArray()) {
                if (sbn.tag != NotificationHelper.TAG_MESSAGE) continue
                if (sbn.id !in stillUnread) nm?.cancel(sbn.tag, sbn.id)
            }
        } catch (_: Throwable) {}
        val total = unread.values.sum()
        if (total == lastTotal) return
        lastTotal = total
        setCount(app, total)
    }

    /** Launchers that show a number from the app itself (several brands' own launchers). */
    private fun setCount(app: Context, n: Int) {
        val cls = try { app.packageManager.getLaunchIntentForPackage(app.packageName)?.component?.className } catch (_: Throwable) { null } ?: return
        try {
            app.sendBroadcast(Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", n)
                putExtra("badge_count_package_name", app.packageName)
                putExtra("badge_count_class_name", cls)
            })
        } catch (_: Throwable) {}
        // Huawei / Honor launchers
        try {
            app.contentResolver.call(Uri.parse("content://com.huawei.android.launcher.settings/badge/"), "change_badge", null,
                Bundle().apply { putString("package", app.packageName); putString("class", cls); putInt("badgenumber", n) })
        } catch (_: Throwable) {}
    }
}
