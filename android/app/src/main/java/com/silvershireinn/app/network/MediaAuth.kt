package com.silvershireinn.app.network

import com.silvershireinn.app.utils.PreferenceManager
import okhttp3.Request

/**
 * Media / file auth without putting the server password in URLs.
 * OkHttp, Coil, ExoPlayer, MediaPlayer, and VideoView all send these headers.
 */
object MediaAuth {
    fun httpBase(): String {
        val u = PreferenceManager.serverUrl.trim().trimEnd('/')
        return when {
            u.startsWith("wss://", true) -> "https://" + u.substring(6)
            u.startsWith("ws://", true) -> "http://" + u.substring(5)
            else -> u
        }
    }

    fun fileUrl(fileId: String): String = "${httpBase()}/file/$fileId"

    /**
     * Our own per-device token (x-auth-token) once the server has given us one; before that
     * (or with an older server) the shared password + username. The token is what stops
     * other users from acting as you over HTTP.
     */
    fun headers(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val user = PreferenceManager.username
        val tok = PreferenceManager.httpToken
        if (tok.isNotBlank()) {
            out["x-auth-token"] = tok
            if (user.isNotBlank()) out["x-username"] = user   // logs only; the token decides
            return out
        }
        val pwd = PreferenceManager.serverPassword
        if (user.isNotBlank()) out["x-username"] = user
        if (pwd.isNotBlank()) out["x-password"] = pwd
        return out
    }

    fun apply(builder: Request.Builder): Request.Builder {
        headers().forEach { (k, v) -> builder.header(k, v) }
        return builder
    }
}
