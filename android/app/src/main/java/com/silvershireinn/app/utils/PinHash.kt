package com.silvershireinn.app.utils

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Lock-screen PIN storage. PINs used to be saved as a plain SHA-256, which anyone holding a
 * copy of the app's data can reverse for every 4-6 digit PIN in well under a second. Now:
 * random salt + PBKDF2 ("v2$salt$hash"). Old hashes still unlock and are upgraded on success.
 */
object PinHash {
    private const val ITER = 60_000

    fun hash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "v2$" + b64(salt) + "$" + b64(derive(pin, salt))
    }

    /** True if [pin] matches [stored] (new or legacy format). Call off the main thread. */
    fun verify(pin: String, stored: String): Boolean {
        if (stored.startsWith("v2$")) {
            val parts = stored.split('$')
            if (parts.size != 3) return false
            val salt = try { Base64.decode(parts[1], Base64.NO_WRAP) } catch (_: Exception) { return false }
            val want = try { Base64.decode(parts[2], Base64.NO_WRAP) } catch (_: Exception) { return false }
            return MessageDigest.isEqual(derive(pin, salt), want)
        }
        return MessageDigest.isEqual(legacy(pin).toByteArray(), stored.toByteArray())
    }

    fun isLegacy(stored: String) = stored.isNotBlank() && !stored.startsWith("v2$")

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded

    private fun legacy(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
