package com.silvershireinn.app.utils

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Keeps the app alive when something goes wrong, and records what happened.
 *
 *  - Errors on background threads / coroutines are logged and swallowed instead of
 *    killing the whole app (Android's default is to crash the process).
 *  - Errors on the main thread outside of screen start-up are caught and the app keeps
 *    running. If a screen itself fails to open, only that screen is closed.
 *  - Out-of-memory errors free image caches and carry on.
 *  - Every caught error is written to a crash report that is uploaded to the server on the
 *    next start (data/crash_reports), so problems on other people's phones can be fixed.
 *  - If errors repeat rapidly (a real crash loop) it gives up and lets Android close the app,
 *    so the phone never gets stuck on a frozen screen.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private var app: Application? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private val recentMainErrors = ArrayDeque<Long>()
    @Volatile private var topActivity: WeakReference<Activity>? = null

    /** Attach to every app-owned CoroutineScope so a failed job never takes the app down. */
    val coroutineHandler = CoroutineExceptionHandler { _, e -> report(e, "coroutine", fatal = false) }

    fun install(application: Application) {
        app = application
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { topActivity = WeakReference(a) }
            override fun onActivityResumed(a: Activity) { topActivity = WeakReference(a) }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        // Background threads: record and keep the process alive.
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            if (thread === Looper.getMainLooper().thread) {
                // Reaching here on the main thread means our loop guard gave up.
                report(e, "main-fatal", fatal = true)
                defaultHandler?.uncaughtException(thread, e)
            } else {
                report(e, "thread:${thread.name}", fatal = false)
                if (e is OutOfMemoryError) freeMemory()
            }
        }

        // Main thread: run the message loop inside a guard.
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (e: Throwable) {
                    if (!handleMainError(e)) throw e
                }
            }
        }
    }

    /** @return true when the app can keep going, false to let Android close it. */
    private fun handleMainError(e: Throwable): Boolean {
        val now = System.currentTimeMillis()
        recentMainErrors.addLast(now)
        while (recentMainErrors.isNotEmpty() && now - recentMainErrors.first() > 10_000L) recentMainErrors.removeFirst()
        if (recentMainErrors.size > 6) {
            report(e, "main-loop", fatal = true)
            return false
        }
        report(e, "main", fatal = false)
        if (e is OutOfMemoryError) { freeMemory(); return true }
        if (isScreenStartupError(e)) {
            val a = topActivity?.get()
            if (a == null || a.isTaskRoot) return false   // nothing safe to fall back to
            try { a.finish() } catch (_: Throwable) { return false }
            try {
                android.widget.Toast.makeText(a.applicationContext,
                    "That screen hit a problem and was closed", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
        return true
    }

    private fun isScreenStartupError(e: Throwable): Boolean {
        val trace = stackText(e)
        return trace.contains("performLaunchActivity") || trace.contains("handleLaunchActivity") ||
            trace.contains("performResumeActivity") || trace.contains("handleResumeActivity") ||
            trace.contains("performCreate") || trace.contains("performStart") ||
            trace.contains("SuperNotCalledException")
    }

    private fun freeMemory() {
        try { coil.Coil.imageLoader(app ?: return).memoryCache?.clear() } catch (_: Throwable) {}
        try { System.gc() } catch (_: Throwable) {}
    }

    private fun stackText(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun reportDir(ctx: Context) = File(ctx.filesDir, "crash_reports").apply { mkdirs() }

    /** Write a crash report. Never throws. Reports are capped so storage can't fill up. */
    fun report(e: Throwable, where: String, fatal: Boolean) {
        try { Log.e(TAG, "Caught error ($where)", e) } catch (_: Throwable) {}
        val ctx = app ?: return
        try {
            val dir = reportDir(ctx)
            val existing = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (existing.size >= 20) existing.take(existing.size - 19).forEach { it.delete() }
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            val ver = try {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (_: Throwable) { "?" }
            val text = buildString {
                appendLine("time: $iso")
                appendLine("where: $where   fatal: $fatal")
                appendLine("app: $ver")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
                appendLine("ram: total ${mi.totalMem / 1048576}MB, free ${mi.availMem / 1048576}MB, lowMemory=${mi.lowMemory}, heapLimit ${rt.maxMemory() / 1048576}MB, heapUsed ${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB")
                appendLine("screen: ${topActivity?.get()?.javaClass?.simpleName ?: "-"}")
                appendLine()
                append(stackText(e))
            }
            File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(text)
        } catch (_: Throwable) {}
    }

    /** Upload saved reports to the server (data/crash_reports) and delete them. Call off the main thread. */
    fun uploadPending(ctx: Context) {
        try {
            val dir = reportDir(ctx)
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            if (files.isEmpty()) return
            val base = PreferenceManager.serverUrl
                .replace("ws://", "http://").replace("wss://", "https://").trimEnd('/')
            if (base.isBlank()) return
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            for (f in files) {
                val req = Request.Builder().url("$base/crash_report")
                    .post(f.readText().toRequestBody("text/plain".toMediaTypeOrNull()))
                    .header("x-password", PreferenceManager.serverPassword)
                    .header("x-username", PreferenceManager.username)
                    .build()
                val ok = try { client.newCall(req).execute().use { it.isSuccessful } } catch (_: Throwable) { false }
                if (ok) f.delete() else break
            }
        } catch (_: Throwable) {}
    }
}
