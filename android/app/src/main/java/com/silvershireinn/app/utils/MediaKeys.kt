package com.silvershireinn.app.utils

import android.content.Context
import android.util.Base64
import com.silvershireinn.app.crypto.MediaCrypto
import org.json.JSONObject
import java.io.File

/**
 * Keys of end-to-end encrypted chat files, by file id, learned from every file message that
 * passes through (live, history, echo, own uploads). Also keeps the real name / type / size
 * (the server only stores "file" / octet-stream for encrypted uploads) and the small preview
 * picture a video message carries ("th"), so videos have a thumbnail without downloading.
 */
object MediaKeys {
    private fun prefs() = com.silvershireinn.app.SilvershireApp.instance
        .getSharedPreferences("silvershire_media_keys", Context.MODE_PRIVATE)

    data class Info(val key: MediaCrypto.Key, val name: String, val mime: String, val size: Long)

    private val mem = java.util.concurrent.ConcurrentHashMap<String, Info>()

    /** Remember the key (and preview) from an "_att" object. Safe to call repeatedly. */
    fun learn(att: JSONObject?) {
        if (att == null) return
        val fileId = att.optString("fileId", "")
        if (fileId.isBlank()) return
        val key = MediaCrypto.Key.from(att) ?: return
        val info = Info(key, att.optString("name", "file"), att.optString("mime", "application/octet-stream"), att.optLong("size", -1L))
        mem[fileId] = info
        if (!prefs().contains(fileId)) {
            // File keys are wrapped by the Android Keystore (a copy of the app's files can't open them).
            val j = JSONObject(key.encode()).put("name", info.name).put("mime", info.mime).put("size", info.size)
            val v = try { DeviceVault.wrapString(j.toString()) } catch (_: Exception) { j.toString() }
            prefs().edit().putString(fileId, v).apply()
        }
        val th = att.optString("th", "")
        if (th.isNotBlank()) {
            val f = thumbFile(fileId)
            if (!f.exists()) try {
                f.parentFile?.mkdirs()
                f.writeBytes(Base64.decode(th, Base64.NO_WRAP))
            } catch (_: Exception) {}
        }
    }

    fun get(fileId: String?): Info? {
        if (fileId.isNullOrBlank()) return null
        mem[fileId]?.let { return it }
        val raw = prefs().getString(fileId, null) ?: return null
        return try {
            val s = DeviceVault.openString(raw) ?: return null
            val j = JSONObject(s)
            val k = MediaCrypto.Key.from(j) ?: return null
            Info(k, j.optString("name", "file"), j.optString("mime", "application/octet-stream"), j.optLong("size", -1L))
                .also { mem[fileId] = it }
        } catch (_: Exception) { null }
    }

    fun isEncrypted(fileId: String?): Boolean = get(fileId) != null

    /** Add the key fields to an "_att" object being rebuilt (resends, forwards). */
    fun decorate(att: JSONObject): JSONObject {
        get(att.optString("fileId", ""))?.key?.putInto(att)
        return att
    }

    /** An "_att" object that adds the file's key as soon as its fileId is set (resends). */
    fun decorateNew(): JSONObject = object : JSONObject() {
        override fun put(name: String, value: Any?): JSONObject {
            super.put(name, value)
            if (name == "fileId" && value is String) MediaKeys.get(value)?.key?.putInto(this)
            return this
        }
    }

    fun thumbFile(fileId: String): File =
        File(com.silvershireinn.app.SilvershireApp.instance.filesDir, "media_thumbs/" + fileId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")

    /** File id inside a ".../file/<id>" URL, if any. */
    fun fileIdFromUrl(url: String?): String? {
        if (url == null) return null
        val i = url.indexOf("/file/"); if (i < 0) return null
        val rest = url.substring(i + 6)
        val end = rest.indexOfFirst { it == '?' || it == '#' || it == '/' }
        return (if (end < 0) rest else rest.substring(0, end)).takeIf { it.isNotBlank() }
    }

    fun clear() {
        mem.clear(); prefs().edit().clear().apply()
        try { File(com.silvershireinn.app.SilvershireApp.instance.filesDir, "media_thumbs").deleteRecursively() } catch (_: Exception) {}
    }
}
