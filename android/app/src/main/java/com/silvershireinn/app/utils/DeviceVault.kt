package com.silvershireinn.app.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets kept on the phone (identity private key, database key, file keys) are wrapped with
 * an AES key that lives in the Android Keystore: it never leaves the phone's secure hardware
 * and can't be copied out, so a copy of the app's files alone is useless.
 * Wrapped strings look like "ks1:<base64(iv + ciphertext)>"; anything else is legacy plain.
 */
object DeviceVault {
    private const val ALIAS = "silvershire_device_vault_v1"
    private const val PREFIX = "ks1:"

    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val k = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                generateKey()
            }
            cached = k
            return k
        }
    }

    fun isWrapped(s: String?): Boolean = s != null && s.startsWith(PREFIX)

    fun wrap(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain)
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Plain bytes of a wrapped string; throws if it can't be opened (keystore key gone). */
    fun unwrap(s: String): ByteArray {
        require(isWrapped(s)) { "not wrapped" }
        val all = Base64.decode(s.substring(PREFIX.length), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return c.doFinal(all, 12, all.size - 12)
    }

    fun wrapString(plain: String): String = wrap(plain.toByteArray(Charsets.UTF_8))

    /** Wrapped -> plain text; legacy plain strings pass through unchanged. */
    fun openString(s: String?): String? {
        if (s == null) return null
        if (!isWrapped(s)) return s
        return String(unwrap(s), Charsets.UTF_8)
    }
}
