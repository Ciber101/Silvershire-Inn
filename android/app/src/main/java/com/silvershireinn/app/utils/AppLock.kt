package com.silvershireinn.app.utils

import android.app.Activity
import android.content.Intent
import com.silvershireinn.app.ui.lock.LockScreenActivity

/**
 * One place that decides whether the app must show the lock screen.
 *
 * Before this, only the launcher path (Splash) and MainActivity's idle timer checked the lock,
 * so opening a chat from a notification or a chat head skipped the password entirely, and the
 * idle timer kept running while you were busy chatting (locking you out mid-conversation).
 */
object AppLock {
    /** Passed the lock screen in this process. */
    @Volatile private var unlocked = false
    @Volatile private var lastSeen = 0L
    @Volatile private var lastSaved = 0L

    private fun hasCredentials(): Boolean =
        PreferenceManager.passwordHash.isNotBlank() ||
        (PreferenceManager.pinEnabled && PreferenceManager.pinHash.isNotBlank()) ||
        PreferenceManager.biometricEnabled

    /** "Require password on open" is on and something to check against exists. */
    fun configured(): Boolean = PreferenceManager.requirePasswordOnOpen && hasCredentials()

    /** Any touch in the app. Kept in memory; saved at most every 15 s (it was a disk write per touch). */
    fun noteActivity() {
        val now = System.currentTimeMillis()
        lastSeen = now
        if (now - lastSaved > 15_000L) { lastSaved = now; PreferenceManager.lastActivityTime = now }
    }

    private fun lastActive(): Long = maxOf(lastSeen, PreferenceManager.lastActivityTime)

    /** Auto-lock after inactivity is on and the limit has passed. */
    fun idleExpired(): Boolean =
        PreferenceManager.autoLogoutEnabled &&
        System.currentTimeMillis() - lastActive() >= PreferenceManager.autoLogoutMinutes * 60_000L

    fun needsLock(): Boolean = PanicLock.locked || (configured() && (!unlocked || idleExpired()))

    fun markUnlocked() { unlocked = true; noteActivity() }

    /**
     * If the lock is needed, open the lock screen (coming back to [returnTo] afterwards),
     * finish [activity] and return true. Call early in onCreate / onResume.
     */
    fun gate(activity: Activity, returnTo: Intent? = activity.intent): Boolean {
        if (!needsLock()) return false
        unlocked = false
        if (PanicLock.locked) {
            // Panic lock: only the unlock word opens it, and nothing is reopened behind it.
            try {
                activity.startActivity(Intent(activity, com.silvershireinn.app.ui.lock.PanicLockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
            } catch (_: Exception) {}
            activity.finish()
            return true
        }
        try {
            activity.startActivity(Intent(activity, LockScreenActivity::class.java).apply {
                if (returnTo != null) putExtra(LockScreenActivity.EXTRA_RETURN, Intent(returnTo))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        } catch (_: Exception) {}
        activity.finish()
        return true
    }
}
