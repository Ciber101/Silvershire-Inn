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
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A lock on any chat (Chat settings > Lock this chat): PIN, password, or fingerprint / face with a
 * backup PIN. Only a salted PBKDF2 hash is kept (and synced, so the web app asks for the same
 * one). While locked, the chat's messages stay hidden, its notifications say only "New message"
 * and the chats list shows "Locked". Opened, it stays open until you leave it for a minute.
 */
object ChatLocks {
    private const val ITER = 120_000
    private val openedAt = HashMap<String, Long>()     // chat -> 0 while open, or when it was left

    private fun prefs() = com.silvershireinn.app.SilvershireApp.instance.getSharedPreferences("silvershire_prefs", android.content.Context.MODE_PRIVATE)

    /** Every chat's lock as stored and synced: { chat: { type, salt, hash } }. */
    var all: JSONObject
        get() = try { JSONObject(prefs().getString("chat_locks", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        set(v) { prefs().edit().putString("chat_locks", v.toString()).apply() }

    private fun entry(id: String): JSONObject? = all.optJSONObject(id)

    fun type(id: String): String = entry(id)?.optString("type", "none")?.ifBlank { "none" } ?: "none"

    fun isLocked(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val e = entry(id) ?: return false
        val t = e.optString("type", "none")
        return t != "none" && e.optString("hash").isNotBlank() && e.optString("salt").isNotBlank()
    }

    fun label(id: String): String = when (if (isLocked(id)) type(id) else "none") {
        "pin" -> "PIN"; "password" -> "Password"; "biometric" -> "Fingerprint / face"; else -> "Off"
    }

    private fun hash(secret: String, saltB64: String): String {
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return Base64.encodeToString(f.generateSecret(PBEKeySpec(secret.toCharArray(), salt, ITER, 256)).encoded, Base64.NO_WRAP)
    }

    private fun save(id: String, newType: String, secret: String?) {
        val a = all
        if (newType == "none" || secret.isNullOrBlank()) a.remove(id)
        else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val s = Base64.encodeToString(salt, Base64.NO_WRAP)
            a.put(id, JSONObject().put("type", newType).put("salt", s).put("hash", hash(secret, s)))
            openedAt[id] = 0L
        }
        all = a
        // the web app and other phones get it too
        try { com.silvershireinn.app.network.WebSocketManager.sendSettings(JSONObject().put("chatLocks", a)) } catch (_: Throwable) {}
    }

    fun check(id: String, secret: String): Boolean {
        val e = entry(id) ?: return false
        return try { hash(secret, e.optString("salt")) == e.optString("hash") } catch (_: Throwable) { false }
    }

    /** The chat was left (screen closed, app in the background, another chat). */
    fun left(id: String) { if (openedAt[id] == 0L) openedAt[id] = System.currentTimeMillis() }

    fun needsUnlock(id: String?): Boolean {
        if (id == null || !isLocked(id)) return false
        val t = openedAt[id] ?: return true
        if (t == 0L || System.currentTimeMillis() - t < 60_000L) return false
        openedAt.remove(id)
        return true
    }

    /** Ask for the chat's lock; [onDone] gets true when opened. */
    fun prompt(a: FragmentActivity, id: String, name: String, onDone: (Boolean) -> Unit) {
        val ok: () -> Unit = { openedAt[id] = 0L; onDone(true) }
        val t = type(id)
        if (t == "biometric" && BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) {
            BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { ok() }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) askSecret(a, id, name, true, ok, onDone) else onDone(false)
                }
            }).authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Open chat with $name")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("Use PIN")
                .build())
            return
        }
        askSecret(a, id, name, t != "password", ok, onDone)
    }

    /** Choose this chat's lock (asks for the current one first when there is one). */
    fun configure(a: FragmentActivity, id: String, name: String, onChanged: () -> Unit) {
        val go = {
            val labels = arrayOf("Off", "PIN", "Password", "Fingerprint / face (with a backup PIN)")
            val keys = arrayOf("none", "pin", "password", "biometric")
            AlertDialog.Builder(a).setTitle("Lock the chat with $name")
                .setSingleChoiceItems(labels, keys.indexOf(if (isLocked(id)) type(id) else "none").coerceAtLeast(0)) { dlg, i ->
                    dlg.dismiss()
                    val k = keys[i]
                    if (k == "none") { save(id, "none", null); onChanged(); return@setSingleChoiceItems }
                    if (k == "biometric" && BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) {
                        android.widget.Toast.makeText(a, "No fingerprint or face is set up on this phone", android.widget.Toast.LENGTH_LONG).show()
                        return@setSingleChoiceItems
                    }
                    newSecret(a, k != "password", if (k == "biometric") "Backup PIN" else null) { s -> save(id, k, s); onChanged() }
                }
                .setNegativeButton("Cancel", null).show()
        }
        if (isLocked(id) && needsUnlock(id)) prompt(a, id, name) { ok -> if (ok) go() } else go()
    }

    private fun field(a: FragmentActivity, pin: Boolean, h: String) = EditText(a).apply {
        inputType = if (pin) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = h
    }

    private fun newSecret(a: FragmentActivity, pin: Boolean, title: String?, onSet: (String) -> Unit) {
        val e1 = field(a, pin, if (pin) "New PIN (4 or more digits)" else "New password (6 or more)")
        val e2 = field(a, pin, "Again")
        val box = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * a.resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, 0)
            addView(e1); addView(e2)
        }
        val d = AlertDialog.Builder(a).setTitle(title ?: if (pin) "Chat PIN" else "Chat password").setView(box)
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

    private fun askSecret(a: FragmentActivity, id: String, name: String, pin: Boolean, ok: () -> Unit, onDone: (Boolean) -> Unit) {
        val et = field(a, pin, if (pin) "PIN" else "Password")
        val box = FrameLayout(a).apply {
            val p = (20 * a.resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, 0); addView(et)
        }
        val d = AlertDialog.Builder(a)
            .setTitle("Open chat with $name")
            .setView(box)
            .setPositiveButton("Open", null)
            .setNegativeButton("Cancel") { _, _ -> onDone(false) }
            .setOnCancelListener { onDone(false) }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (check(id, et.text.toString())) { d.dismiss(); ok() }
                else { et.text = null; et.error = if (pin) "Wrong PIN" else "Wrong password" }
            }
        }
        d.show()
    }
}
