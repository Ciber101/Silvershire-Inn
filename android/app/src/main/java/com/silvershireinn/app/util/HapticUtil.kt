package com.silvershireinn.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.silvershireinn.app.utils.PreferenceManager

object HapticUtil {

    /**
     * Vibrate for ~50 ms if button haptics are enabled in settings.
     * Call this from any click listener.
     */
    fun vibrate(context: Context) {
        if (!PreferenceManager.buttonHapticsEnabled) return
        try { vibrateNow(context) } catch (_: Throwable) { /* no vibrator / blocked: ignore */ }
    }

    @Suppress("DEPRECATION")
    private fun vibrateNow(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager ?: return
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vib.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vib.vibrate(50)
        }
    }

}
