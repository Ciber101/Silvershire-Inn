package com.silvershireinn.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.silvershireinn.app.network.WatchdogReceiver
import com.silvershireinn.app.network.WebSocketService
import com.silvershireinn.app.utils.PreferenceManager

/**
 * Restart the WebSocket foreground service after device reboot OR after an app update
 * (MY_PACKAGE_REPLACED), so the user keeps receiving messages and call notifications even
 * if they never reopened the app. Also (re)arms the periodic watchdog.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                Intent.ACTION_MY_PACKAGE_REPLACED
            )
        ) return

        // Only restart if the user has previously set up the app
        if (PreferenceManager.username.isBlank() || PreferenceManager.serverUrl.isBlank()) return

        val serviceIntent = Intent(context, WebSocketService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Background FGS start can be blocked on Android 12+ without the battery
            // exemption; the watchdog (armed below) will retry shortly.
        }

        // Make sure the safety-net watchdog is armed after boot/update.
        WatchdogReceiver.schedule(context)
    }
}
