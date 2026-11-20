package com.silvershireinn.app.crypto

import android.content.Context
import android.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.spec.MGF1ParameterSpec
import javax.crypto.spec.PSource

object CryptoManager {

    private const val PBKDF2_ITERATIONS = 600_000
    private const val PBKDF2_KEY_LENGTH = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    // RSA key storage — SharedPreferences (PKCS8 private, X509 public), Base64-encoded
    private const val CRYPTO_PREFS_NAME = "crypto_prefs"
    private const val KEY_PRIVATE_RSA   = "rsa_private_key_pkcs8"
    private const val KEY_PUBLIC_RSA    = "rsa_public_key_x509"

    private lateinit var appContext: Context

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Must be called once in Application.onCreate() before any crypto operations.
     * Stores the application context needed for SharedPreferences access.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // Parsing a 4096-bit private key costs real time on cheap phones; it used to happen for
    // EVERY message decrypted. Parsed once and reused (reset whenever the key pair changes).
    @Volatile private var cachedPrivate: Pair<String, PrivateKey>? = null

    /**
     * The identity private key (base64 PKCS#8). Stored wrapped by the Android Keystore
     * (DeviceVault); an older plain copy is wrapped on first read. Null if missing or the
     * keystore can no longer open it.
     */
    private fun storedPrivB64(): String? {
        val prefs = appContext.getSharedPreferences(CRYPTO_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PRIVATE_RSA, null) ?: return null
        if (com.silvershireinn.app.utils.DeviceVault.isWrapped(raw)) {
            return try { com.silvershireinn.app.utils.DeviceVault.openString(raw) }
            catch (e: Exception) { android.util.Log.e("CryptoManager", "identity key: keystore can't open it (${e.javaClass.simpleName})"); null }
        }
        try { prefs.edit().putString(KEY_PRIVATE_RSA, com.silvershireinn.app.utils.DeviceVault.wrapString(raw)).apply() }
        catch (e: Exception) { android.util.Log.w("CryptoManager", "identity key not wrapped yet: ${e.message}") }
        return raw
    }

    private fun privateKey(): PrivateKey {
        val b64 = storedPrivB64()
            ?: throw IllegalStateException("RSA private key not found in SharedPreferences")
        cachedPrivate?.let { if (it.first == b64) return it.second }
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val key = try {
            KeyFactory.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME).generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (_: Exception) {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }
        cachedPrivate = b64 to key
        return key
    }

    // ── RSA Key-pair (BouncyCastle + SharedPreferences) ──────────────────────

    /**
     * Generate a fresh 4096-bit RSA key-pair using BouncyCastle and persist it to
     * SharedPreferences.  Using BouncyCastle (rather than AndroidKeyStore) gives us
     * full control over the cipher parameters so that encrypt and decrypt always use
     * identical OAEPParameterSpec values — the AndroidKeyStore approach caused
     * cross-provider parameter mismatches that silently broke decryption.
     */
    fun generateRsaKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(4096, SecureRandom())
        val keyPair = kpg.generateKeyPair()
        appContext.getSharedPreferences(CRYPTO_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRIVATE_RSA, com.silvershireinn.app.utils.DeviceVault.wrap(
                Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP).toByteArray(Charsets.UTF_8)))
            .putString(KEY_PUBLIC_RSA,  Base64.encodeToString(keyPair.public.encoded,  Base64.NO_WRAP))
            .apply()
        return keyPair
    }

    /** Returns true if a persisted RSA key-pair exists in SharedPreferences. */
    fun hasRsaKeyPair(): Boolean {
        val prefs = appContext.getSharedPreferences(CRYPTO_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_PRIVATE_RSA) && prefs.contains(KEY_PUBLIC_RSA)
    }

    /**
     * Validates that the stored RSA key pair is internally consistent — i.e. the public and
     * private keys are a genuine matching pair — by doing a SHA256withRSA sign + verify
     * round-trip with a random 32-byte probe.
     *
     * **Why signing instead of OAEP encrypt/decrypt:**
     * The previous version used `Cipher.getInstance("RSA/ECB/OAEPPadding", "BC")`.  On
     * Android API 28+, Google deprecated the built-in "BC" Cipher provider.  Because our
     * app's full BouncyCastle JAR uses the same provider name ("BC") as the built-in one,
     * `Security.getProvider("BC") != null` prevents our full BC from being added.  So all
     * "BC" Cipher operations silently fall back to the deprecated system version, which
     * throws `NoSuchAlgorithmException` on newer Android.  The catch block then CLEARS
     * both keys and returns false — causing silent key regeneration on every reconnect,
     * breaking the public/private match between reconnects.
     *
     * SHA256withRSA (PKCS#1 v1.5 signature) uses the default JCA provider (Conscrypt),
     * has no OAEP parameter ambiguity, and works on all Android API levels.
     *
     * If both keys exist and form a valid pair → returns true.
     * If either key is missing, corrupt, or the pair doesn't match → deletes BOTH keys
     * from SharedPreferences and returns false, so the caller can generate a fresh pair.
     */
    fun ensureConsistentRsaKeyPair(): Boolean {
        val prefs   = appContext.getSharedPreferences(CRYPTO_PREFS_NAME, Context.MODE_PRIVATE)
        val privB64 = storedPrivB64() ?: return false
        val pubB64  = prefs.getString(KEY_PUBLIC_RSA,  null) ?: return false

        return try {
            // Use default JCA provider — same reason as signRsaChallenge (see below).
            val factory    = KeyFactory.getInstance("RSA")
            val publicKey  = factory.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64,  Base64.NO_WRAP)))
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))

            // Sign a random 32-byte probe with the private key, verify with the public key.
            val probe   = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val signer  = Signature.getInstance("SHA256withRSA")
            signer.initSign(privateKey)
            signer.update(probe)
            val signature = signer.sign()

            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(probe)

            if (verifier.verify(signature)) {
                true
            } else {
                android.util.Log.w("CryptoManager", "RSA key pair probe mismatch — clearing stale keys")
                prefs.edit().remove(KEY_PRIVATE_RSA).remove(KEY_PUBLIC_RSA).apply()
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("CryptoManager", "RSA key pair validation failed (${e.javaClass.simpleName}: ${e.message}) — clearing stale keys")
            prefs.edit().remove(KEY_PRIVATE_RSA).remove(KEY_PUBLIC_RSA).apply()
            false
        }
    }

    /** Load our own RSA public key from SharedPreferences. */
    fun getRsaPublicKey(): PublicKey? {
        val b64 = appContext.getSharedPreferences(CRYPTO_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PUBLIC_RSA, null) ?: return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return try {
            KeyFactory.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
                .generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: Exception) {
            try {
                KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
            } catch (e2: Exception) {
                android.util.Log.e("CryptoManager", "getRsaPublicKey failed: ${e2.message}")
                null
            }
        }
    }

    fun publicKeyToBase64(publicKey: PublicKey): String =
        Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    fun base64ToPublicKey(base64: String): PublicKey {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        return try {
            KeyFactory.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
                .generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: Exception) {
            // FALLBACK — same deprecated-BC issue as the OAEP ciphers. This function is
            // the FIRST step of encryptE2E; if it throws, encryptForContact silently
            // falls back to PLAINTEXT. The default provider parses the same X509 bytes.
            android.util.Log.w("CryptoManager", "BC KeyFactory unavailable (${e.javaClass.simpleName}) — using default provider")
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
        }
    }

    // ── Password hashing (PBKDF2) ─────────────────────────────────────────────

    fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun generateSalt(): ByteArray {
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun hashPassword(password: String, salt: ByteArray): String {
        val key = deriveKeyFromPassword(password, salt)
        return Base64.encodeToString(key.encoded, Base64.NO_WRAP)
    }

    // ── AES-256-GCM symmetric encryption ─────────────────────────────────────

    fun encryptAesGcm(plaintext: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun decryptAesGcm(ciphertext: ByteArray, key: SecretKey): ByteArray {
        val iv = ciphertext.sliceArray(0 until GCM_IV_LENGTH)
        val data = ciphertext.sliceArray(GCM_IV_LENGTH until ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(data)
    }

    fun encryptMessage(plaintext: String, key: SecretKey): String {
        val encrypted = encryptAesGcm(plaintext.toByteArray(Charsets.UTF_8), key)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptMessage(ciphertext: String, key: SecretKey): String {
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        return String(decryptAesGcm(bytes, key), Charsets.UTF_8)
    }

    fun saltToBase64(salt: ByteArray): String = Base64.encodeToString(salt, Base64.NO_WRAP)
    fun base64ToSalt(base64: String): ByteArray = Base64.decode(base64, Base64.NO_WRAP)

    // ── E2E Hybrid Encryption (RSA-OAEP + AES-256-GCM) ──────────────────────
    //
    // Wire format (JSON string, version 1):
    //   { "v": 1, "enc": "<base64 AES-GCM ciphertext>", "key": "<base64 RSA-wrapped AES key>" }
    //
    // "enc"  = AES-GCM(plaintext), prefixed with 12-byte IV (same as encryptAesGcm)
    // "key"  = RSA-OAEP-SHA256(aesKey.encoded), using RECIPIENT's public key
    //
    // Both encrypt and decrypt use BouncyCastle explicitly with the same OAEPParameterSpec,
    // ensuring identical SHA-256 / MGF1-SHA-256 parameters on both sides with no
    // provider-specific ambiguity.

    private const val E2E_VERSION = 1
    private const val RSA_CIPHER  = "RSA/ECB/OAEPPadding"
    private val OAEP_SPEC = OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
    )

    /** Generate a fresh random AES-256 key for one message. */
    fun generateAesKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256, SecureRandom())
        return kg.generateKey()
    }

    /**
     * RSA-OAEP encrypt [data] with the given [publicKey] using BouncyCastle.
     * Used to wrap the per-message AES key.
     */
    fun encryptWithRsaPublicKey(data: ByteArray, publicKey: PublicKey): ByteArray {
        fun viaDefault(): ByteArray {
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey.encoded))
            val c = Cipher.getInstance(RSA_CIPHER)
            c.init(Cipher.ENCRYPT_MODE, pub, OAEP_SPEC)
            return c.doFinal(data)
        }
        fun viaBc(): ByteArray {
            val c = Cipher.getInstance(RSA_CIPHER, BouncyCastleProvider.PROVIDER_NAME)
            c.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SPEC)
            return c.doFinal(data)
        }
        // Same provider order as decrypt: the bundled BC cipher fails on Android 9+.
        return if (defaultFirst) { try { viaDefault() } catch (_: Exception) { viaBc() } }
               else { try { viaBc() } catch (_: Exception) { viaDefault() } }
    }

    /**
     * RSA-OAEP decrypt [data] using our own private key from SharedPreferences.
     * Uses BouncyCastle explicitly — the same provider used for encryption — so
     * OAEP parameters are interpreted identically on both sides.
     */
    /** Built-in provider (Conscrypt) first on Android 9+, where the bundled BC cipher fails. */
    @Volatile private var defaultFirst = android.os.Build.VERSION.SDK_INT >= 28
    @Volatile private var cachedDefaultPrivate: Pair<String, PrivateKey>? = null

    private fun defaultProviderPrivateKey(): PrivateKey {
        val b64 = storedPrivB64() ?: throw IllegalStateException("RSA private key not found")
        cachedDefaultPrivate?.let { if (it.first == b64) return it.second }
        val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(b64, Base64.NO_WRAP)))
        cachedDefaultPrivate = b64 to k
        return k
    }

    private fun decryptDefault(data: ByteArray): ByteArray {
        val c = Cipher.getInstance(RSA_CIPHER)
        c.init(Cipher.DECRYPT_MODE, defaultProviderPrivateKey(), OAEP_SPEC)
        return c.doFinal(data)
    }

    private fun decryptBc(data: ByteArray): ByteArray {
        val c = Cipher.getInstance(RSA_CIPHER, BouncyCastleProvider.PROVIDER_NAME)
        c.init(Cipher.DECRYPT_MODE, privateKey(), OAEP_SPEC)
        return c.doFinal(data)
    }

    /**
     * RSA-OAEP(SHA-256) unwrap with our private key. Uses the provider that works on this
     * phone first (keys parsed once and cached); if that provider can't do it at all, the
     * other one becomes the default from then on.
     */
    fun decryptWithRsaPrivateKey(data: ByteArray): ByteArray {
        val firstDefault = defaultFirst
        return try {
            if (firstDefault) decryptDefault(data) else decryptBc(data)
        } catch (e: javax.crypto.BadPaddingException) {
            throw e   // wrong key (e.g. trying the recipient's copy): no point retrying elsewhere
        } catch (e: Exception) {
            val unsupported = e is NoSuchAlgorithmException || e is NoSuchProviderException ||
                e is java.security.InvalidAlgorithmParameterException || e is javax.crypto.NoSuchPaddingException
            val r = if (firstDefault) decryptBc(data) else decryptDefault(data)
            if (unsupported) defaultFirst = !firstDefault
            r
        }
    }

    /**
     * Encrypt [plaintext] for [recipientPublicKeyBase64] using hybrid RSA+AES encryption.
     * Returns a JSON string that can be sent as the message payload.
     *
     * @throws Exception if the recipient public key is invalid or blank.
     */
    fun encryptE2E(plaintext: String, recipientPublicKeyBase64: String): String {
        val recipientKey = base64ToPublicKey(recipientPublicKeyBase64)
        val aesKey       = generateAesKey()
        val encBytes     = encryptAesGcm(plaintext.toByteArray(Charsets.UTF_8), aesKey)
        val wrappedKey   = encryptWithRsaPublicKey(aesKey.encoded, recipientKey)
        return org.json.JSONObject().apply {
            put("v",   E2E_VERSION)
            put("enc", Base64.encodeToString(encBytes,   Base64.NO_WRAP))
            put("key", Base64.encodeToString(wrappedKey, Base64.NO_WRAP))
            // ADDITIVE — "keySelf": the AES key also wrapped with OUR OWN public key.
            // This lets our other devices (web client) decrypt our sent messages from
            // server history. Receivers ignore unknown JSON fields, so this is safe.
            try {
                getRsaPublicKey()?.let { ownPub ->
                    put("keySelf", Base64.encodeToString(encryptWithRsaPublicKey(aesKey.encoded, ownPub), Base64.NO_WRAP))
                }
            } catch (_: Exception) { /* never block sending because of keySelf */ }
        }.toString()
    }

    /**
     * Short id of a public key: first 16 bytes of SHA-256 over the key (same bytes on the web).
     * Group messages address each member's copy of the message key by this, so a renamed
     * member can still read old messages.
     */
    fun keyFingerprint(publicKeyBase64: String): String {
        val raw = Base64.decode(publicKeyBase64.trim(), Base64.NO_WRAP)
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
        return Base64.encodeToString(h.copyOf(16), Base64.NO_WRAP)
    }

    /**
     * Group end-to-end encryption: the message is encrypted once (AES-GCM) and its key is
     * wrapped for every member's public key (and ours). {"v":2,"enc":..,"keys":{fp: wrapped}}.
     */
    fun encryptGroupE2E(plaintext: String, memberPublicKeys: Collection<String>): String {
        val aesKey   = generateAesKey()
        val encBytes = encryptAesGcm(plaintext.toByteArray(Charsets.UTF_8), aesKey)
        val keys = org.json.JSONObject()
        val all = LinkedHashSet<String>()
        memberPublicKeys.forEach { if (it.isNotBlank()) all += it.trim() }
        getRsaPublicKey()?.let { all += publicKeyToBase64(it) }
        for (pk in all) {
            try {
                keys.put(keyFingerprint(pk), Base64.encodeToString(encryptWithRsaPublicKey(aesKey.encoded, base64ToPublicKey(pk)), Base64.NO_WRAP))
            } catch (e: Exception) { android.util.Log.w("CryptoManager", "group key skipped: ${e.javaClass.simpleName}") }
        }
        return org.json.JSONObject().apply {
            put("v", 2)
            put("enc", Base64.encodeToString(encBytes, Base64.NO_WRAP))
            put("keys", keys)
        }.toString()
    }

    /** True if [payload] is a group-encrypted (v2) message. */
    fun isGroupE2E(payload: String): Boolean =
        payload.startsWith("{") && payload.contains("\"v\":2") && payload.contains("\"keys\"")

    /**
     * Export this device's identity (username + RSA key pair) as a JSON string for
     * logging into the WEB CLIENT with the same account. The web client imports this
     * and signs the same login challenge with the same private key.
     *
     * SECURITY: this string contains the PRIVATE key — the user should only paste it
     * into their own browser and never send it through a chat.
     */
    fun exportIdentityJson(username: String): String? {
        return try {
            val prefs = appContext.getSharedPreferences(CRYPTO_PREFS_NAME, Context.MODE_PRIVATE)
            val priv  = storedPrivB64() ?: return null
            val pub   = prefs.getString(KEY_PUBLIC_RSA,  null) ?: return null
            org.json.JSONObject().apply {
                put("username",   username)
                put("privateKey", priv)
                put("publicKey",  pub)
            }.toString()
        } catch (e: Exception) {
            android.util.Log.e("CryptoManager", "exportIdentityJson failed: ${e.message}")
            null
        }
    }

    /**
     * Sign a server challenge nonce with our RSA private key using SHA256withRSA.
     *
     * The server sends a random 32-byte nonce as a Base64 string.  We sign the raw
     * bytes with SHA256withRSA (PKCS#1 v1.5) using BouncyCastle and return the
     * signature as Base64 so the server can verify it with the stored public key.
     *
     * This replaces the old OAEP encrypt/decrypt approach and eliminates all
     * cross-provider OAEP parameter ambiguity (Node.js vs BouncyCastle).
     *
     * Returns null if signing fails (missing private key / corrupt key).
     */
    fun signRsaChallenge(nonceBase64: String): String? {
        return try {
            val prefs = appContext.getSharedPreferences(CRYPTO_PREFS_NAME, Context.MODE_PRIVATE)
            val b64 = storedPrivB64()
                ?: throw IllegalStateException("RSA private key not found in SharedPreferences")
            // Use the default JCA provider (AndroidOpenSSL / Conscrypt) — not BouncyCastle.
            // On Android API 28+, the built-in "BC" provider is deprecated and may not expose
            // SHA256withRSA; Android's Conscrypt provider handles it reliably.  SHA256withRSA
            // (PKCS#1 v1.5) has zero parameter ambiguity, so no specific provider is needed.
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(b64, Base64.NO_WRAP)))
            val nonce = Base64.decode(nonceBase64, Base64.NO_WRAP)
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(privateKey)
            sig.update(nonce)
            Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("CryptoManager", "signRsaChallenge failed [${e.javaClass.simpleName}]: ${e.message}")
            null
        }
    }

    /**
     * Decrypt an RSA-OAEP challenge nonce sent by the server during login.
     * DEPRECATED — superseded by signRsaChallenge(). Kept for reference.
     *
     * Returns null if decryption fails (wrong private key / corrupted ciphertext).
     */
    fun decryptRsaChallenge(encryptedBase64: String): String? {
        return try {
            val cipherBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val decrypted   = decryptWithRsaPrivateKey(cipherBytes)
            Base64.encodeToString(decrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("CryptoManager", "decryptRsaChallenge failed [${e.javaClass.simpleName}]: ${e.message}")
            null
        }
    }

    /**
     * Attempt to decrypt a message payload.
     *
     * If [payload] is a recognised E2E JSON blob (`{"v":1,"enc":...,"key":...}`), decrypt it.
     * If it is plaintext (legacy or unencrypted), return it unchanged.
     * Returns null if the payload is E2E-formatted but decryption fails.
     */
    fun decryptIfE2E(payload: String): String? = decryptIfE2E(payload, preferSelf = false)

    /**
     * [preferSelf] = this is a message WE sent: try our own copy of the key (keySelf) first.
     * Trying the recipient's key first always failed for our own messages and wasted a slow
     * 4096-bit operation per message when loading history.
     */
    fun decryptIfE2E(payload: String, preferSelf: Boolean): String? {
        return try {
            val json = org.json.JSONObject(payload)
            if (json.optInt("v") == 2 && json.has("keys")) {
                // Group message: find our copy of the key by our key's fingerprint.
                val own = getRsaPublicKey() ?: return null
                val wrapped = json.getJSONObject("keys").optString(keyFingerprint(publicKeyToBase64(own)), "")
                if (wrapped.isBlank()) return null          // not addressed to us (joined later / old key)
                val k = SecretKeySpec(decryptWithRsaPrivateKey(Base64.decode(wrapped, Base64.NO_WRAP)), "AES")
                return String(decryptAesGcm(Base64.decode(json.getString("enc"), Base64.NO_WRAP), k), Charsets.UTF_8)
            }
            if (!json.has("v") || json.optInt("v") != E2E_VERSION) return payload   // not E2E
            val encBytes    = Base64.decode(json.getString("enc"), Base64.NO_WRAP)
            // "keySelf" = the message key wrapped for US (on messages we sent). Try the right
            // one first; fall back to the other.
            val selfKey = json.optString("keySelf", "")
            val first = if (preferSelf && selfKey.isNotBlank()) selfKey else json.getString("key")
            val second = if (first == selfKey) json.optString("key", "") else selfKey
            val aesKeyBytes = try {
                decryptWithRsaPrivateKey(Base64.decode(first, Base64.NO_WRAP))
            } catch (primaryEx: Exception) {
                if (second.isNotBlank()) decryptWithRsaPrivateKey(Base64.decode(second, Base64.NO_WRAP))
                else throw primaryEx
            }
            val aesKey      = SecretKeySpec(aesKeyBytes, "AES")
            String(decryptAesGcm(encBytes, aesKey), Charsets.UTF_8)
        } catch (jsonEx: org.json.JSONException) {
            payload   // not JSON at all — treat as legacy plaintext
        } catch (e: Exception) {
            android.util.Log.e("CryptoManager", "E2E decrypt failed: ${e.javaClass.simpleName}: ${e.message}")
            android.util.Log.e("CryptoManager", "Stack: ${e.stackTraceToString().take(800)}")
            null   // decryption error — don't display garbled text
        }
    }
}
