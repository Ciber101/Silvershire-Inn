package com.silvershireinn.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.silvershireinn.app.crypto.CryptoManager
import com.silvershireinn.app.database.AppDatabase
import com.silvershireinn.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.sqlcipher.database.SQLiteDatabase

class SilvershireApp : Application(), coil.ImageLoaderFactory {

    companion object {
        lateinit var instance: SilvershireApp
            private set
        lateinit var database: AppDatabase
            private set

        /**
         * App-wide scope for work that must finish even if the screen that started it
         * closes (sending a message, uploading a file). viewModelScope was cancelled the
         * moment the user left the chat, sometimes between saving and transmitting.
         */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.silvershireinn.app.utils.CrashGuard.coroutineHandler)

        /** Number of our activities currently started (visible), excluding invisible proxies. */
        @Volatile private var startedActivities = 0

        /** Called (main thread) when the app goes from not visible to visible. */
        @Volatile var onAppForegrounded: (() -> Unit)? = null

        /** True while any real (visible) screen of the app is on screen. */
        val isAppVisible: Boolean get() = startedActivities > 0

        /** The floating chat window (chat head overlay) is open. */
        @Volatile var overlayOpen = false
            set(v) { field = v; reportUse() }

        /** In use = app on screen or the floating chat open, with the screen on and unlocked.
         *  This is what "online" means (a floating chat left open on a dark phone is not). */
        val isInUse: Boolean get() = (isAppVisible || overlayOpen) && screenUsable()

        private fun screenUsable(): Boolean = try {
            val c = instance
            val pm = c.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            val km = c.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            (pm?.isInteractive != false) && (km?.isKeyguardLocked != true)
        } catch (_: Throwable) { true }

        private var lastReported: Boolean? = null
        private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
        private val reportRunnable = Runnable {
            val now = isInUse
            if (now != lastReported) {
                lastReported = now
                com.silvershireinn.app.network.WebSocketManager.sendAppState(now)
            }
        }

        /** Debounced: switching between screens briefly drops to 0 visible; don't flicker offline. */
        fun reportUse(force: Boolean = false) {
            if (force) lastReported = null
            mainHandler.removeCallbacks(reportRunnable)
            mainHandler.postDelayed(reportRunnable, if (isInUse) 0L else 1500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // First: anything that goes wrong from here on is caught, logged and reported.
        com.silvershireinn.app.utils.CrashGuard.install(this)
        CryptoManager.init(this)
        SQLiteDatabase.loadLibs(this)
        database = AppDatabase.getInstance(this)
        NotificationHelper.createChannel(this)
        recordAppVersion()
        registerActivityLifecycleCallbacks(VisibilityTracker)
        // Screen off / on / unlocked: online changes with it
        try {
            val f = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { reportUse() }
            }, f)
        } catch (_: Throwable) {}
        com.silvershireinn.app.utils.OrnateTheme.install(this)
        normalizeStoredTimestampsOnce()
    }

    /**
     * One image loader for the whole app, sized to the phone: smaller memory cache and
     * 16-bit bitmaps on low-end phones (half the memory), a 250 MB disk cache so photos
     * are only downloaded once, and server cache headers ignored (files never change).
     */
    override fun newImageLoader(): coil.ImageLoader {
        val low = com.silvershireinn.app.utils.DeviceTier.isLowEnd
        return coil.ImageLoader.Builder(this)
            .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(if (low) 0.15 else 0.25).build() }
            .diskCache {
                coil.disk.DiskCache.Builder().directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024).build()
            }
            .allowRgb565(low)
            .respectCacheHeaders(false)
            // End-to-end encrypted chat pictures: decrypt before decoding.
            .components { add(com.silvershireinn.app.crypto.EncryptedMedia.CoilInterceptor()) }
            .crossfade(!low)
            .build()
    }

    /**
     * Tracks real app visibility. Process importance (what the chat head used before) stays
     * "foreground" for a while after leaving and is affected by overlay windows, so taps on
     * the chat head guessed wrong and opened the overlay over the app.
     */
    private object VisibilityTracker : ActivityLifecycleCallbacks {
        private fun counts(a: Activity) =
            a !is com.silvershireinn.app.ui.chathead.ChatHeadPickerActivity

        override fun onActivityStarted(a: Activity) {
            if (!counts(a)) return
            val wasHidden = startedActivities == 0
            startedActivities++
            if (wasHidden) { onAppForegrounded?.invoke(); reportUse() }
            com.silvershireinn.app.utils.PanicVoice.refresh(a)
        }
        override fun onActivityStopped(a: Activity) {
            if (!counts(a)) return
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) reportUse()
            com.silvershireinn.app.utils.PanicVoice.refresh(a)
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {
            // Panic phrase: every text box on every screen is watched.
            try { com.silvershireinn.app.utils.PanicVoice.attach(a) } catch (_: Throwable) {}
            // The camera records sound: give it the microphone.
            if (a is com.silvershireinn.app.ui.media.CameraCaptureActivity) com.silvershireinn.app.utils.PanicVoice.pauseForMic()
        }
        override fun onActivityPaused(a: Activity) {
            if (a is com.silvershireinn.app.ui.media.CameraCaptureActivity) com.silvershireinn.app.utils.PanicVoice.resumeAfterMic(a)
        }
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    /**
     * Older versions stored times in several formats ("...:05Z", "...:05.1Z", "+00:00"). The
     * database sorts by the text, so mixed formats put some messages, chats and gallery items
     * out of order. Rewrite every old-format time to the canonical UTC form once.
     */
    private fun normalizeStoredTimestampsOnce() {
        val meta = getSharedPreferences("app_meta", MODE_PRIVATE)
        if (meta.getBoolean("ts_normalized_v1", false)) return
        appScope.launch {
            try {
                val mdao = database.messageDao()
                mdao.nonCanonicalTimestamps().forEach { r ->
                    com.silvershireinn.app.utils.isoToMillis(r.ts)?.let { mdao.updateTimestamp(r.id, com.silvershireinn.app.utils.millisToIso(it)) }
                }
                val cdao = database.conversationDao()
                cdao.nonCanonicalTimestamps().forEach { r ->
                    com.silvershireinn.app.utils.isoToMillis(r.ts)?.let { cdao.setTimestamp(r.id, com.silvershireinn.app.utils.millisToIso(it)) }
                }
                meta.edit().putBoolean("ts_normalized_v1", true).apply()
            } catch (_: Throwable) { /* try again next start */ }
        }
    }

    /**
     * Records the installed versionCode.
     *
     * This used to WIPE all local messages, contacts and conversations on every update and
     * re-sync from the server. The server only keeps the last 200 messages per chat, so each
     * update silently deleted older history and any unsent messages, and the wiped group
     * conversations then got every group deleted on the next login. Contacts are already
     * reconciled against the server's contacts_list on every login, so no wipe is needed.
     */
    private fun recordAppVersion() {
        val metaPrefs = getSharedPreferences("app_meta", MODE_PRIVATE)
        val currentVersion = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Exception) { 0 }
        if (currentVersion != 0) {
            metaPrefs.edit().putInt("version_code", currentVersion).apply()
        }
    }
}
