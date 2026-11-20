package com.silvershireinn.app.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the battery-optimization (Doze) exemption.
 *
 * This is the single biggest lever for keeping the messaging connection alive while the
 * phone sleeps. When the app is on the "not optimized" list, Android lets it keep network
 * access during Doze AND lets it start a foreground service from the background (which the
 * watchdog relies on). The app is sideloaded, so prompting for this directly is fine
 * (there is no Google Play policy restriction here).
 */
object BatteryOptimizationHelper {

    /** True if the OS is NOT battery-optimizing us (i.e. we're exempt), or pre-Marshmallow. */
    fun isIgnoring(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try { pm.isIgnoringBatteryOptimizations(context.packageName) } catch (_: Exception) { false }
    }

    /**
     * Launch the system dialog asking the user to exempt this app from battery optimization.
     * Falls back to the full battery-optimization settings list if the direct request fails.
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoring(context)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) { /* nothing else we can do */ }
        }
    }
}
