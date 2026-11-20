package com.silvershireinn.app.utils

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Panic phrase (a word, words or a whole sentence), set once for the ACCOUNT and shared by
 * every device through the server.
 *
 *  - Lock phrase: typed in any text box, or said out loud (PanicVoice), locks the account on
 *    the server at once. Every device of the account locks, and a locked account gets nothing
 *    from the server (no chats, contacts, pictures, calls), so a fresh install, a new browser
 *    or a reinstalled server all stay locked.
 *  - Unlock phrase: only it opens the account again (PIN / password / fingerprint can't). It is
 *    checked by the SERVER: devices send a PBKDF2 proof, never the phrase, and devices never
 *    hold the unlock hash, so a stolen phone can't be used to guess it offline.
 *
 * Devices keep the server's public config (salted SHA-256 of the lock phrase + its length and
 * word count, unlock salt / iterations / word count) to spot the lock phrase instantly, even
 * offline. A lock made offline is remembered and sent on the next connection.
 * Matching: case, punctuation and extra spaces don't matter; the phrase can be the whole text
 * or its last words (typing), or anywhere in what was said (voice).
 */
object PanicLock {
    private const val P_CFG = "cfg"
    private const val P_LOCKED = "locked"
    private const val P_PENDING = "pending_lock"
    private const val P_LISTEN = "listen_voice"
    private const val P_LISTEN_BG = "listen_voice_bg"
    const val ITER = 150_000

    private fun prefs() = com.silvershireinn.app.SilvershireApp.instance
        .getSharedPreferences("silvershire_panic", Context.MODE_PRIVATE)

    /** Words, phrase or sentence: case, punctuation and extra spaces don't matter (voice typing adds them). */
    fun normalize(s: CharSequence?): String = s?.toString()?.lowercase()
        ?.replace(Regex("[\\p{P}\\p{S}]"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private fun words(s: CharSequence?): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    @Volatile private var cfgCache: JSONObject? = null
    private fun cfg(): JSONObject? {
        cfgCache?.let { return it }
        val s = prefs().getString(P_CFG, null) ?: return null
        return try { JSONObject(s).also { cfgCache = it } } catch (_: Exception) { null }
    }

    fun isSet(): Boolean = cfg() != null

    @JvmStatic
    val locked: Boolean get() = prefs().getBoolean(P_LOCKED, false)

    var listenOutLoud: Boolean
        get() = prefs().getBoolean(P_LISTEN, false)
        set(v) { prefs().edit().putBoolean(P_LISTEN, v).apply() }

    /** Keep listening while the app is in the background (Android shows the microphone in use). */
    var listenInBackground: Boolean
        get() = prefs().getBoolean(P_LISTEN_BG, false)
        set(v) { prefs().edit().putBoolean(P_LISTEN_BG, v).apply() }

    /** From the server (login / panic_config). Null = phrases turned off for the account. */
    fun applyServerConfig(json: JSONObject?) {
        cfgCache = json
        if (json == null) prefs().edit().remove(P_CFG).apply()
        else prefs().edit().putString(P_CFG, json.toString()).apply()
    }

    // ── spotting the lock phrase ──

    private fun sha(saltB64: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(Base64.decode(saltB64, Base64.NO_WRAP)); md.update(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }

    private fun isLockPhrase(candidate: String, c: JSONObject): Boolean =
        candidate.length == c.optInt("lockLen", -1) &&
        MessageDigest.isEqual(sha(c.optString("lockSalt"), candidate).toByteArray(), c.optString("lockHash").toByteArray())

    /** Typing: the whole text, or its last words, is the lock phrase. Cheap for normal typing. */
    fun matchesLockWord(text: CharSequence?): Boolean {
        if (locked) return false
        val c = cfg() ?: return false
        val w = words(text); val n = c.optInt("lockWords", 0)
        if (n <= 0 || w.size < n) return false
        return isLockPhrase(w.takeLast(n).joinToString(" "), c)
    }

    /** Speech: the lock phrase anywhere in what was said. */
    fun heardLockPhrase(utterance: CharSequence?): Boolean {
        if (locked) return false
        val c = cfg() ?: return false
        val w = words(utterance); val n = c.optInt("lockWords", 0)
        if (n <= 0 || w.size < n) return false
        for (i in 0..(w.size - n)) if (isLockPhrase(w.subList(i, i + n).joinToString(" "), c)) return true
        return false
    }

    // ── unlock proofs (checked by the server) ──

    private fun pbkdf2(text: String, saltB64: String, iter: Int): String {
        val gen = org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator(org.bouncycastle.crypto.digests.SHA256Digest())
        gen.init(text.toByteArray(Charsets.UTF_8), Base64.decode(saltB64, Base64.NO_WRAP), iter)
        val key = (gen.generateDerivedParameters(256) as org.bouncycastle.crypto.params.KeyParameter).key
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    /** Proofs for every run of [unlockWords] words in [text] (at most 16). Slow: off the main thread. */
    fun unlockProofs(text: CharSequence?, c: JSONObject? = cfg()): List<String> {
        c ?: return emptyList()
        val w = words(text); val n = c.optInt("unlockWords", 0)
        if (n <= 0 || w.size < n) return emptyList()
        val cands = LinkedHashSet<String>()
        for (i in (w.size - n) downTo 0) { cands += w.subList(i, i + n).joinToString(" "); if (cands.size >= 16) break }
        return cands.map { pbkdf2(it, c.optString("unlockSalt"), c.optInt("unlockIter", ITER)) }
    }

    private val http by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /**
     * Ask the server to unlock (plain HTTP, so it works even while the connection is refused
     * because the account, or the whole server, is locked). Blocking: call off the main thread.
     * Returns false if the server couldn't be reached; the result goes to [unlockListener] /
     * [onAccountUnlocked].
     */
    fun requestUnlock(proofs: List<String>): Boolean {
        if (proofs.isEmpty()) return false
        val base = com.silvershireinn.app.network.MediaAuth.httpBase()
        val body = JSONObject().put("name", PreferenceManager.username).put("proofs", JSONArray(proofs)).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        return try {
            http.newCall(okhttp3.Request.Builder().url("$base/panic-unlock").post(body).build()).execute().use { r ->
                val ok = r.isSuccessful && (try { JSONObject(r.body?.string() ?: "{}").optBoolean("ok") } catch (_: Exception) { false })
                val app = com.silvershireinn.app.SilvershireApp.instance
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (ok) onAccountUnlocked(app) else unlockListener?.invoke(false)
                }
                true
            }
        } catch (e: Exception) {
            android.util.Log.w("PanicLock", "unlock: server not reachable (${e.javaClass.simpleName})")
            false
        }
    }

    /** New phrases for the server (hashes only). [currentProofs] needed when phrases already exist. */
    fun buildSetMessage(lockPhrase: String, unlockPhrase: String, currentProofs: List<String>): JSONObject {
        val r = SecureRandom()
        val lockSalt = Base64.encodeToString(ByteArray(16).also { r.nextBytes(it) }, Base64.NO_WRAP)
        val unlockSalt = Base64.encodeToString(ByteArray(16).also { r.nextBytes(it) }, Base64.NO_WRAP)
        val l = normalize(lockPhrase); val u = normalize(unlockPhrase)
        val panic = JSONObject()
            .put("lockSalt", lockSalt).put("lockHash", sha(lockSalt, l))
            .put("lockLen", l.length).put("lockWords", words(l).size)
            .put("unlockSalt", unlockSalt).put("unlockHash", pbkdf2(u, unlockSalt, ITER))
            .put("unlockIter", ITER).put("unlockWords", words(u).size)
        return JSONObject().put("type", "panic_set").put("panic", panic).put("proofs", JSONArray(currentProofs))
    }

    fun buildClearMessage(currentProofs: List<String>): JSONObject =
        JSONObject().put("type", "panic_set").put("clear", true).put("proofs", JSONArray(currentProofs))

    // ── locking ──

    /** Lock now (this device at once; the account through the server, now or when back online). */
    fun lockNow(context: Context, fromServer: Boolean = false) {
        if (!isSet() && !fromServer) return
        val wasLocked = locked
        prefs().edit().putBoolean(P_LOCKED, true).putBoolean(P_PENDING, !fromServer).commit()
        if (!fromServer) sendPendingLock()
        if (wasLocked && fromServer) return
        val app = context.applicationContext
        try { NotificationManagerCompat.from(app).cancelAll() } catch (_: Exception) {}
        try { app.stopService(Intent(app, com.silvershireinn.app.ui.chathead.ChatHeadService::class.java)) } catch (_: Exception) {}
        try {
            app.startActivity(Intent(app, com.silvershireinn.app.ui.lock.PanicLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        } catch (_: Exception) {}
        PanicVoice.refresh(app)
    }

    /** A lock made while offline reaches the server as soon as we're connected. */
    fun sendPendingLock() {
        if (!prefs().getBoolean(P_PENDING, false)) return
        if (com.silvershireinn.app.network.WebSocketManager.sendRaw(JSONObject().put("type", "panic_lock").toString()))
            prefs().edit().putBoolean(P_PENDING, false).apply()
    }

    fun hasPendingLock(): Boolean = prefs().getBoolean(P_PENDING, false)

    /** The server unlocked the account (here or on another device). */
    fun onAccountUnlocked(context: Context, alreadySignedIn: Boolean = false) {
        prefs().edit().putBoolean(P_LOCKED, false).putBoolean(P_PENDING, false).commit()
        // Sign in again so everything the server held back while locked comes in.
        if (!alreadySignedIn) try { com.silvershireinn.app.network.WebSocketManager.reconnectNow(force = true) } catch (_: Exception) {}
        unlockListener?.invoke(true)
        PanicVoice.refresh(context.applicationContext)
    }

    @Volatile var unlockListener: ((Boolean) -> Unit)? = null
    @Volatile var setListener: ((Boolean, String?) -> Unit)? = null

    fun clearLocal() { cfgCache = null; prefs().edit().clear().apply() }
}
