package com.silvershireinn.app.network

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.silvershireinn.app.utils.PreferenceManager

/**
 * Periodic safety-net that keeps the messaging connection alive.
 *
 * Why this exists: a single persistent WebSocket inside a foreground service is not
 * enough on real devices. `START_STICKY` does not reliably revive the service on many
 * OEMs (Samsung/Xiaomi/etc.), the process can be killed under memory pressure, and Doze
 * suspends networking while the phone sleeps. Without a watchdog the user simply stops
 * receiving message notifications after a while — exactly the reported bug.
 *
 * How it works: every ~15 minutes this receiver wakes, makes sure [WebSocketService] is
 * running (which reconnects if needed), and re-arms the next tick. We use
 * `setAndAllowWhileIdle` so the alarm still fires during Doze maintenance windows
 * WITHOUT needing the exact-alarm permission. Manifest-registered receivers can be woken
 * even after the process was killed, so this brings the app back from the dead.
 *
 * `setAndAllowWhileIdle` alarms are one-shot, so the receiver re-schedules itself on every
 * fire — the chain continues for as long as the user is logged in. The biggest reliability
 * win comes from pairing this with the battery-optimization exemption (see
 * BatteryOptimizationHelper): being exempt lets us start a foreground service from the
 * background on Android 12+, which is otherwise blocked.
 *
 * Purely additive: nothing else depends on this, and it no-ops when logged out.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm FIRST so the chain never breaks, even if the work below throws.
        schedule(context)

        // Only keep the service alive while the user is actually set up + logged in.
        if (PreferenceManager.username.isBlank() || PreferenceManager.serverUrl.isBlank()) return
        if (!PreferenceManager.isSetupComplete) return

        // Starting the service is a no-op when it's already Connected (onStartCommand
        // returns early), so this is cheap to call on every tick.
        ensureServiceRunning(context)
    }

    companion object {
        private const val REQUEST_CODE = 4711
        const val ACTION_TICK = "com.silvershireinn.WATCHDOG_TICK"

        /** ~15 min — the practical floor for background wakeups under Doze. */
        private const val INTERVAL_MS = 15 * 60 * 1000L
        /** Short delay used by onTaskRemoved so we come back almost immediately after a swipe-away. */
        private const val SOON_MS = 3 * 1000L

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogReceiver::class.java).apply { action = ACTION_TICK }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
        }

        private fun armAfter(context: Context, delayMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Fires even in Doze (maintenance window) — no exact-alarm permission needed.
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
                }
            } catch (_: Exception) { /* alarm scheduling can throw on some OEM ROMs — ignore */ }
        }

        /** (Re)schedule the next periodic tick. Safe to call repeatedly (replaces the pending alarm). */
        fun schedule(context: Context) = armAfter(context, INTERVAL_MS)

        /** Schedule a near-immediate revival (used after the task is swiped away). */
        fun scheduleSoon(context: Context) = armAfter(context, SOON_MS)

        /** Cancel the watchdog entirely (logout / wipe). */
        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            try { am.cancel(pendingIntent(context)) } catch (_: Exception) {}
        }

        /** Start (or wake) the foreground service. Swallows the Android 12+ background-start exception. */
        fun ensureServiceRunning(context: Context) {
            try {
                val svc = Intent(context, WebSocketService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } catch (_: Exception) {
                // ForegroundServiceStartNotAllowedException can occur on Android 12+ if we're in
                // the background WITHOUT the battery-optimization exemption. Nothing else we can
                // safely do here; the exemption (or the next foreground entry) recovers it.
            }
        }
    }
}
