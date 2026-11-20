package com.silvershireinn.app.utils

import android.app.ActivityManager
import android.content.Context
import com.silvershireinn.app.SilvershireApp

/** Rough device class, so cheap phones skip the heaviest eye candy instead of stuttering. */
object DeviceTier {
    val isLowEnd: Boolean by lazy {
        try {
            val am = SilvershireApp.instance.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            // Only genuinely weak phones (it caught ordinary mid-range ones before, which then
            // showed frozen video profile pictures everywhere).
            am.isLowRamDevice || mi.totalMem < 3L * 1024 * 1024 * 1024 ||
                Runtime.getRuntime().availableProcessors() <= 2
        } catch (_: Throwable) { false }
    }
}
