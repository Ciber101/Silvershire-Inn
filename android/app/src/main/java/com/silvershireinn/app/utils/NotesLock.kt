package com.silvershireinn.app.utils

import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional lock on the Notes chat: none, PIN, password, or fingerprint / face (with a backup
 * PIN). Only a salted PBKDF2 hash of the PIN / password is stored (and synced, so the web app
 * can ask for the same one). Unlocked until you leave Notes for more than a minute.
 */
object NotesLock {
    private const val ITER = 120_000
    @Volatile private var unlocked = false
    @Volatile private var leftAt = 0L

    val type: String get() = PreferenceManager.notesLockType
    val enabled: Boolean get() = type != "none" && (type == "biometric" || PreferenceManager.notesLockHash.isNotBlank())

    fun hash(secret: String, saltB64: String): String {
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(secret.toCharArray(), salt, ITER, 256)).encoded
        return Base64.encodeToString(k, Base64.NO_WRAP)
    }

    /** Save a new lock. [secret] is the PIN / password (backup PIN for biometric). */
    fun set(newType: String, secret: String?) {
        if (newType == "none") {
            PreferenceManager.notesLockHash = ""; PreferenceManager.notesLockSalt = ""
            PreferenceManager.notesLockType = "none"
            return
        }
        if (secret.isNullOrBlank()) return
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        PreferenceManager.notesLockSalt = saltB64
        PreferenceManager.notesLockHash = hash(secret, saltB64)
        PreferenceManager.notesLockType = newType
        unlocked = true
    }

    fun check(secret: String): Boolean {
        val s = PreferenceManager.notesLockSalt; val h = PreferenceManager.notesLockHash
        if (s.isBlank() || h.isBlank()) return false
        return try { hash(secret, s) == h } catch (_: Throwable) { false }
    }

    /** Notes was left (screen closed / app in the background). */
    fun noteLeft() { leftAt = System.currentTimeMillis() }

    fun needsUnlock(): Boolean {
        if (!enabled) return false
        if (unlocked && (leftAt == 0L || System.currentTimeMillis() - leftAt < 60_000L)) return false
        unlocked = false
        return true
    }

    /** Ask for the lock; [onDone] gets true when opened, false when cancelled. */
    fun prompt(a: FragmentActivity, onDone: (Boolean) -> Unit) {
        val ok: () -> Unit = { unlocked = true; leftAt = 0L; onDone(true) }
        if (type == "biometric" && BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) {
            val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { ok() }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) askSecret(a, true, ok, onDone) else onDone(false)
                }
            })
            p.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Open Notes")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("Use PIN")
                .build())
            return
        }
        askSecret(a, type != "password", ok, onDone)
    }

    /** Choose the lock (asks for the current one first when there is one). */
    fun configure(a: FragmentActivity, onChanged: () -> Unit) {
        val go = {
            val labels = arrayOf("None", "PIN", "Password", "Fingerprint / face (with a backup PIN)")
            val keys = arrayOf("none", "pin", "password", "biometric")
            AlertDialog.Builder(a).setTitle("Lock Notes with")
                .setSingleChoiceItems(labels, keys.indexOf(type).coerceAtLeast(0)) { dlg, i ->
                    dlg.dismiss()
                    val k = keys[i]
                    if (k == "none") { set("none", null); onChanged(); return@setSingleChoiceItems }
                    if (k == "biometric" && BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) {
                        android.widget.Toast.makeText(a, "No fingerprint or face is set up on this phone", android.widget.Toast.LENGTH_LONG).show()
                        return@setSingleChoiceItems
                    }
                    newSecret(a, k != "password", if (k == "biometric") "Backup PIN" else null) { s -> set(k, s); onChanged() }
                }
                .setNegativeButton("Cancel", null).show()
        }
        if (enabled && needsUnlock()) prompt(a) { ok -> if (ok) go() } else go()
    }

    /** Ask for a new PIN / password twice. */
    private fun newSecret(a: FragmentActivity, pin: Boolean, title: String?, onSet: (String) -> Unit) {
        fun field(h: String) = EditText(a).apply {
            inputType = if (pin) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = h
        }
        val e1 = field(if (pin) "New PIN (4 or more digits)" else "New password (6 or more)")
        val e2 = field("Again")
        val box = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * a.resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, 0)
            addView(e1); addView(e2)
        }
        val d = AlertDialog.Builder(a).setTitle(title ?: if (pin) "Notes PIN" else "Notes password").setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s1 = e1.text.toString(); val s2 = e2.text.toString()
                when {
                    s1.length < (if (pin) 4 else 6) -> e1.error = if (pin) "At least 4 digits" else "At least 6 characters"
                    s1 != s2 -> e2.error = "They don't match"
                    else -> { d.dismiss(); onSet(s1) }
                }
            }
        }
        d.show()
    }

    private fun askSecret(a: FragmentActivity, pin: Boolean, ok: () -> Unit, onDone: (Boolean) -> Unit) {
        val et = EditText(a).apply {
            inputType = if (pin) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (pin) "PIN" else "Password"
        }
        val box = FrameLayout(a).apply {
            val p = (20 * a.resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, 0); addView(et)
        }
        val d = AlertDialog.Builder(a)
            .setTitle("Open Notes")
            .setView(box)
            .setPositiveButton("Open", null)
            .setNegativeButton("Cancel") { _, _ -> onDone(false) }
            .setOnCancelListener { onDone(false) }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (check(et.text.toString())) { d.dismiss(); ok() }
                else { et.text = null; et.error = if (pin) "Wrong PIN" else "Wrong password" }
            }
        }
        d.show()
    }
}
