package com.silvershireinn.app.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.ImageView
import android.widget.TextView
import com.silvershireinn.app.R
import java.io.File

/**
 * In-app + web branding, plus best-effort launcher alias switching.
 * Arbitrary gallery bitmaps cannot reliably replace the system launcher icon
 * at runtime on stock Android — that requires a restamped sideload APK.
 */
object BrandingHelper {

    const val ALIAS_DEFAULT = "com.silvershireinn.app.LauncherAliasDefault"
    const val ALIAS_EMBER = "com.silvershireinn.app.LauncherAliasEmber"
    const val ALIAS_NIGHT = "com.silvershireinn.app.LauncherAliasNight"

    fun appLabel(): String {
        val custom = PreferenceManager.appDisplayName
        return if (custom.isNotBlank()) custom else "Silvershire Inn"
    }

    fun splashText(): String {
        val custom = PreferenceManager.splashText
        return if (custom.isNotBlank()) custom else "Private. Secure. Yours."
    }

    fun applyTo(context: Context, titleView: TextView?, logoView: ImageView?, splashView: TextView? = null) {
        titleView?.text = appLabel()
        splashView?.text = splashText()
        val logo = PreferenceManager.brandLogoPath
        if (logo.isNotBlank()) {
            val f = File(logo)
            if (f.exists()) {
                val bmp = VideoThumbs.decodeSmall(f.absolutePath, 512)
                if (bmp != null) logoView?.setImageBitmap(bmp)
            }
        }
    }

    fun applyLauncherPack(context: Context, pack: String) {
        val pm = context.packageManager
        val map = mapOf(
            "default" to ALIAS_DEFAULT,
            "ember" to ALIAS_EMBER,
            "night" to ALIAS_NIGHT
        )
        val chosen = map[pack] ?: ALIAS_DEFAULT
        // Disable the activity's own launcher filter by preferring aliases.
        val splash = ComponentName(context, "com.silvershireinn.app.ui.splash.SplashActivity")
        try {
            pm.setComponentEnabledSetting(
                splash,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
        for ((_, alias) in map) {
            val state = if (alias == chosen)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            try {
                pm.setComponentEnabledSetting(
                    ComponentName(context, alias),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }
        PreferenceManager.launcherPack = pack
    }

    fun restoreDefaultLauncher(context: Context) {
        val pm = context.packageManager
        try {
            pm.setComponentEnabledSetting(
                ComponentName(context, "com.silvershireinn.app.ui.splash.SplashActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
        for (alias in listOf(ALIAS_DEFAULT, ALIAS_EMBER, ALIAS_NIGHT)) {
            try {
                pm.setComponentEnabledSetting(
                    ComponentName(context, alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }
        PreferenceManager.launcherPack = "default"
    }
}
