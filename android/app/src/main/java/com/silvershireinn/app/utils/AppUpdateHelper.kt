package com.silvershireinn.app.utils

import android.app.Activity
import android.content.Context
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import com.silvershireinn.app.R
import com.silvershireinn.app.repository.FileRepository
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Soft in-app update prompt. Never forces update; never wipes settings.
 * Only prompts when server versionCode is strictly greater than the installed one.
 */
object AppUpdateHelper {
    private const val TAG = "AppUpdateHelper"
    private const val PREF_LAST_SEEN_MTIME = "apk_last_seen_mtime"
    private const val PREF_DISMISSED_CODE = "apk_dismissed_version_code"

    private fun prefs() =
        com.silvershireinn.app.SilvershireApp.instance
            .getSharedPreferences("silvershire_prefs", android.content.Context.MODE_PRIVATE)

    var lastSeenApkMtime: Long
        get() = try { prefs().getLong(PREF_LAST_SEEN_MTIME, 0L) } catch (_: Exception) { 0L }
        set(v) { try { prefs().edit().putLong(PREF_LAST_SEEN_MTIME, v).apply() } catch (_: Exception) {} }

    private fun dismissedCode(): Int =
        try { prefs().getInt(PREF_DISMISSED_CODE, 0) } catch (_: Exception) { 0 }

    private fun setDismissedCode(code: Int) {
        try { prefs().edit().putInt(PREF_DISMISSED_CODE, code).apply() } catch (_: Exception) {}
    }

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val apkMtime: Long
    )

    fun localVersionCode(activity: Activity): Int = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Exception) { 0 }

    fun downloadUrl(info: VersionInfo): String {
        if (info.apkUrl.startsWith("http://") || info.apkUrl.startsWith("https://")) return info.apkUrl
        val base = FileRepository.publicHttpBase()
        val path = if (info.apkUrl.startsWith("/")) info.apkUrl else "/${info.apkUrl}"
        return base + path
    }

    /**
     * If a newer APK is available (versionCode > installed), show a non-blocking dialog.
     * Republish with the same versionCode must NOT prompt; we only refresh lastSeenApkMtime.
     * @param forceShow ignore dismiss memory (e.g. from About). If already current, toast instead.
     */
    suspend fun checkAndPrompt(activity: Activity, forceShow: Boolean = false) {
        val info = withContext(Dispatchers.IO) { FileRepository.fetchAppVersion() } ?: return
        val local = localVersionCode(activity)

        // Gate ONLY on versionCode. apkMtime alone must never open the download dialog.
        if (info.versionCode <= local) {
            if (info.apkMtime > 0L) lastSeenApkMtime = info.apkMtime
            if (forceShow) {
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing) {
                        Toast.makeText(activity, "You're on the latest version", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        if (!forceShow && info.versionCode > 0 && info.versionCode <= dismissedCode()) {
            if (info.apkMtime > 0L) lastSeenApkMtime = info.apkMtime
            return
        }

        val url = downloadUrl(info)
        val verBit = if (info.versionName.isNotBlank()) " (v${info.versionName})" else ""
        val message = "A newer Silvershire Inn APK is available$verBit.\n\n" +
            "Download and install when you are ready. Your settings will not be wiped."

        withContext(Dispatchers.Main) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext
            try { showThemedDialog(activity, info, url, message) } catch (_: Exception) {}
        }
    }

    private fun showThemedDialog(activity: Activity, info: VersionInfo, url: String, message: String) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null, false)
        dialog.setContentView(view)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        view.findViewById<TextView>(R.id.tvUpdateTitle).text = "Update available"
        view.findViewById<TextView>(R.id.tvUpdateMessage).text = message

        view.findViewById<MaterialButton>(R.id.btnUpdateDownload).setOnClickListener {
            openDownload(activity, url)
            if (info.apkMtime > 0L) lastSeenApkMtime = info.apkMtime
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btnUpdateLater).setOnClickListener {
            if (info.versionCode > 0) setDismissedCode(info.versionCode)
            if (info.apkMtime > 0L) lastSeenApkMtime = info.apkMtime
            dialog.dismiss()
        }
        dialog.show()
    }

    /** Open the download URL directly (e.g. from Silvershire Updates DM). */
    fun openDownload(context: Context, urlHint: String? = null) {
        val url = when {
            !urlHint.isNullOrBlank() && (urlHint.startsWith("http://") || urlHint.startsWith("https://")) -> urlHint
            !urlHint.isNullOrBlank() && urlHint.contains("/download") -> {
                val base = FileRepository.publicHttpBase()
                if (urlHint.startsWith("/")) base + urlHint else "$base/download/silvershire-inn.apk"
            }
            else -> FileRepository.publicHttpBase() + "/download/silvershire-inn.apk"
        }
        // In-app download with the system progress notification, then the installer opens by
        // itself. (It used to hand the link to the browser and leave you to find the file.)
        if (!downloadAndInstall(context, url)) openInBrowser(context, url)
    }

    private fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "openDownload: ${e.message}")
        }
    }

    @Volatile private var activeDownloadId = -1L

    // ── Progress shown on every Download button (chat and floating chat) ──
    /** null = nothing going on, 0..100 = downloading (%), 101 = opening the installer. */
    @Volatile var progress: Int? = null
        private set
    private val buttons = java.util.WeakHashMap<android.widget.TextView, Boolean>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A Download button on screen: it shows the download's progress from now on. */
    fun track(b: android.widget.TextView) { buttons[b] = true; paint(b) }

    private fun paint(b: android.widget.TextView) {
        val p = progress
        b.text = when {
            p == null -> "Download"
            p >= 101 -> "Opening the installer..."
            p <= 0 -> "Starting download..."
            else -> "Downloading... $p%"
        }
        b.isEnabled = p == null
        b.alpha = if (p == null) 1f else 0.85f
    }

    private fun setProgress(p: Int?) {
        progress = p
        main.post { buttons.keys.toList().forEach { try { paint(it) } catch (_: Throwable) {} } }
    }

    /** Polls the download while it runs (the system notification shows it too). */
    private fun watch(dm: android.app.DownloadManager, id: Long) {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (activeDownloadId != id) return
                try {
                    dm.query(android.app.DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (c.moveToFirst()) {
                            val got = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val st = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                            if (st == android.app.DownloadManager.STATUS_FAILED) {
                                activeDownloadId = -1L; setProgress(null)
                                Toast.makeText(com.silvershireinn.app.SilvershireApp.instance, "Update download failed. Try again.", Toast.LENGTH_LONG).show()
                                return
                            }
                            if (total > 0) setProgress(((got * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                } catch (_: Throwable) {}
                main.postDelayed(this, 300)
            }
        }, 300)
    }

    private fun downloadAndInstall(context: Context, url: String): Boolean {
        return try {
            val app = context.applicationContext
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager ?: return false
            if (activeDownloadId >= 0) {
                Toast.makeText(context, "Update is already downloading", Toast.LENGTH_SHORT).show()
                return true
            }
            val name = "silvershire-update.apk"
            java.io.File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), name).delete()
            val req = android.app.DownloadManager.Request(Uri.parse(url))
                .setTitle("Silvershire Inn update")
                .setDescription("Downloading the new version")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, android.os.Environment.DIRECTORY_DOWNLOADS, name)
            com.silvershireinn.app.network.MediaAuth.headers().forEach { (k, v) -> req.addRequestHeader(k, v) }
            val id = dm.enqueue(req)
            activeDownloadId = id
            setProgress(0)
            watch(dm, id)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val done = i?.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (done != id) return
                    try { app.unregisterReceiver(this) } catch (_: Exception) {}
                    activeDownloadId = -1L
                    val uri = try { dm.getUriForDownloadedFile(id) } catch (_: Exception) { null }
                    if (uri == null) { setProgress(null); Toast.makeText(app, "Update download failed", Toast.LENGTH_LONG).show(); return }
                    setProgress(101)
                    // The floating chat would cover Android's installer: step it aside first
                    com.silvershireinn.app.ui.chathead.ChatHeadService.closeForInstaller(app)
                    main.postDelayed({ openInstaller(app, uri); main.postDelayed({ setProgress(null) }, 4000) }, 350)
                }
            }
            val filter = android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall: ${e.message}")
            activeDownloadId = -1L
            setProgress(null)
            false
        }
    }

    private fun openInstaller(context: Context, apk: Uri) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                // Android asks once to allow installing updates from this app.
                Toast.makeText(context, "Allow Silvershire to install updates, then tap the download notification", Toast.LENGTH_LONG).show()
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apk, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "openInstaller: ${e.message}")
            Toast.makeText(context, "Update downloaded. Tap the download notification to install.", Toast.LENGTH_LONG).show()
        }
    }

}
