package com.silvershireinn.app.utils

import android.content.Context
import org.json.JSONObject

/**
 * Public keys of group members (they are not always our friends, so the contact list doesn't
 * have them). Filled from the server's group list / group_created / group_updated / group_keys.
 */
object GroupKeys {
    private fun prefs() = com.silvershireinn.app.SilvershireApp.instance
        .getSharedPreferences("silvershire_group_keys", Context.MODE_PRIVATE)

    @Volatile private var cache: MutableMap<String, String>? = null
    private val fetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun load(): MutableMap<String, String> {
        cache?.let { return it }
        val m = HashMap<String, String>()
        try {
            val j = JSONObject(prefs().getString("keys", "{}") ?: "{}")
            j.keys().forEach { k -> j.optString(k).takeIf { it.isNotBlank() }?.let { m[k] = it } }
        } catch (_: Exception) {}
        cache = m
        return m
    }

    @Synchronized
    fun update(groupId: String?, keys: JSONObject?) {
        if (keys == null) return
        val m = load()
        var changed = false
        keys.keys().forEach { u ->
            val k = keys.optString(u)
            if (k.isNotBlank() && m[u] != k) { m[u] = k; changed = true }
        }
        if (groupId != null) fetchedAt[groupId] = System.currentTimeMillis()
        if (changed) prefs().edit().putString("keys", JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun get(username: String): String? = load()[username]

    /** Keys were refreshed for this group in the last [maxAgeMs]. */
    fun fresh(groupId: String, maxAgeMs: Long = 10 * 60_000L): Boolean =
        System.currentTimeMillis() - (fetchedAt[groupId] ?: 0L) < maxAgeMs

    fun clear() { cache = HashMap(); fetchedAt.clear(); prefs().edit().clear().apply() }
}
