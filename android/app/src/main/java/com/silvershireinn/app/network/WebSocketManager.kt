package com.silvershireinn.app.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object WebSocketManager {

    private const val TAG = "WS"

    private var webSocket: WebSocket? = null
    /**
     * One client for the app's lifetime. A new client per connect left its thread pool and
     * connection pool alive after every reconnect (dozens of idle threads on a cheap phone
     * after a day of network changes).
     */
    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // Ping every 30 s so routers / NAT don't silently kill idle connections. No pong
            // within the interval fails the socket, which schedules a reconnect.
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + com.silvershireinn.app.utils.CrashGuard.coroutineHandler)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 1024)   // room for history bursts
    val events: SharedFlow<JSONObject> = _events

    private var reconnectJob: Job? = null
    /** Next retry wait: 1 s, 2 s, 4 s, then every 8 s. Reset only once logged in, so a server
     *  that accepts the socket and then rejects us is not hammered every second. */
    private var reconnectDelay = 1000L
    private var lastConnectParams: ConnectParams? = null

    sealed class ConnectionState {
        object Connected : ConnectionState()
        object Connecting : ConnectionState()
        object Disconnected : ConnectionState()
    }

    data class ConnectParams(
        val serverUrl: String,
        val serverPassword: String,
        val username: String,
        val isRegistering: Boolean,
        val publicKey: String
    )

    /** Bumped on every connect()/disconnect(); callbacks from older sockets are ignored. */
    @Volatile private var generation = 0
    /** Socket of the latest attempt (still handshaking or open). */
    @Volatile private var attemptSocket: WebSocket? = null
    @Volatile private var connectStartedAt = 0L
    /** True once the server confirmed login on the CURRENT socket (set by WebSocketService). */
    @Volatile var isAuthenticated = false
        set(v) { field = v; if (v) reconnectDelay = 1000L }

    fun connect(params: ConnectParams) {
        // A handshake for the same account is already in flight: don't open a second socket.
        // App resume, the watchdog and the network callback can all ask at once. Before this
        // guard each call opened its own authenticated socket and the extras stayed alive,
        // so the server treated this phone as several devices and echoed its own events back
        // (its own "chat read" dismissed the chat head right after opening it).
        if (_state.value == ConnectionState.Connecting && lastConnectParams == params &&
            System.currentTimeMillis() - connectStartedAt < 15_000L) return
        lastConnectParams = params
        reconnectJob?.cancel()
        isAuthenticated = false
        val gen = ++generation
        // Exactly one connection at a time: drop whatever we had before.
        val old = attemptSocket ?: webSocket
        attemptSocket = null
        webSocket = null
        try { old?.cancel() } catch (_: Exception) {}
        connectStartedAt = System.currentTimeMillis()
        _state.value = ConnectionState.Connecting

        scope.launch {
            if (gen != generation) return@launch
            val client = okClient
            // http(s):// -> ws(s):// (only the scheme; the old replace could hit the path too)
            val raw = params.serverUrl.trim()
            val wsUrl = when {
                raw.startsWith("https://", true) -> "wss://" + raw.substring(8)
                raw.startsWith("http://", true) -> "ws://" + raw.substring(7)
                raw.startsWith("ws://", true) || raw.startsWith("wss://", true) -> raw
                else -> "ws://$raw"
            }
            val request = try { Request.Builder().url(wsUrl).build() } catch (_: Exception) {
                // Malformed saved address: stay disconnected (retry later) instead of crashing.
                if (gen == generation) { dropCurrent(); scheduleReconnect() }
                return@launch
            }

            val attempt = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(ws: WebSocket, response: Response) {
                    if (gen != generation) {
                        // A newer attempt superseded this one: never keep two sockets.
                        try { ws.close(1000, "superseded") } catch (_: Exception) {}
                        return
                    }
                    webSocket = ws
                    _state.value = ConnectionState.Connected

                    // Auth sent synchronously and immediately — FIRST thing on open
                    ws.send(JSONObject().apply {
                        put("type", "auth")
                        put("password", params.serverPassword)
                    }.toString())

                    // Then login or register — always include the current public key so
                    // the server can detect key rotation (e.g. after app reinstall) and
                    // broadcast the updated key to all online contacts.
                    if (params.isRegistering) {
                        ws.send(JSONObject().apply {
                            put("type", "register")
                            put("name", params.username)
                            put("publicKey", params.publicKey)
                        }.toString())
                    } else {
                        ws.send(JSONObject().apply {
                            put("type", "login")
                            put("name", params.username)
                            put("publicKey", params.publicKey)
                            // Keep this device's HTTP token across reconnects.
                            com.silvershireinn.app.utils.PreferenceManager.httpToken.takeIf { it.isNotBlank() }?.let { put("httpToken", it) }
                            // Only show as online while the app is actually in use.
                            put("active", com.silvershireinn.app.SilvershireApp.isInUse)
                            // Which app version this phone runs: an older one is told about the update
                            put("appVersion", com.silvershireinn.app.utils.AppUpdateHelper.installedCode(com.silvershireinn.app.SilvershireApp.instance))
                        }.toString())
                    }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    // Events from a superseded socket would be processed twice (or be our
                    // own echoes); only the current attempt's messages count.
                    if (gen != generation) return
                    // Emitted in arrival order on OkHttp's reader thread. Launching a coroutine
                    // per message (the old way) let them run in parallel and out of order:
                    // a read receipt could beat its message, a delete could beat the insert.
                    val obj = try { JSONObject(text) } catch (_: Exception) { return }
                    if (!_events.tryEmit(obj)) runBlocking { _events.emit(obj) }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    // Complete the close handshake so onClosed fires promptly.
                    try { ws.close(code, null) } catch (_: Exception) {}
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    // Only the current attempt may change state. Keyed on the attempt
                    // generation (not `webSocket`) so an attempt that fails BEFORE onOpen
                    // still schedules a reconnect instead of leaving us stuck "Connecting".
                    if (gen != generation) return
                    dropCurrent()
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (gen != generation) return
                    dropCurrent()
                    Log.d(TAG, "Connection lost, will retry")
                    scheduleReconnect()
                }
            })
            if (gen == generation) attemptSocket = attempt
            else try { attempt.cancel() } catch (_: Exception) {}
        }
    }

    private fun dropCurrent() {
        webSocket = null
        attemptSocket = null
        isAuthenticated = false
        _state.value = ConnectionState.Disconnected
    }

    fun send(json: JSONObject): Boolean {
        return webSocket?.send(json.toString()) ?: false
    }

    /** Tell the server whether the app is in use right now (drives "online" for friends). */
    fun sendAppState(active: Boolean) {
        if (!isAuthenticated) return
        sendRaw(JSONObject().put("type", "app_state").put("active", active).toString())
    }

    /** Send a raw JSON string (used by the call managers). */
    fun sendRaw(jsonString: String): Boolean {
        return webSocket?.send(jsonString) ?: false
    }

    fun sendPrivateMessage(from: String, to: String, payload: String, messageId: String, timestamp: String): Boolean {
        val json = JSONObject().apply {
            put("type", "private_message")
            put("from", from)
            put("to", to)
            put("payload", payload)
            put("timestamp", timestamp)
            put("messageId", messageId)
        }
        return send(json)
    }

    fun sendGroupMessage(groupId: String, payload: String, messageId: String, timestamp: String): Boolean {
        val json = JSONObject().apply {
            put("type", "group_message")
            put("groupId", groupId)
            put("payload", payload)
            put("timestamp", timestamp)
            put("messageId", messageId)
        }
        return send(json)
    }

    fun createGroup(name: String, members: List<String>): Boolean {
        val arr = org.json.JSONArray(members)
        val json = JSONObject().apply {
            put("type", "create_group")
            put("name", name)
            put("members", arr)
        }
        return send(json)
    }

    fun requestGroupKeys(groupId: String): Boolean =
        send(JSONObject().put("type", "group_keys_request").put("groupId", groupId))

    fun requestGroupHistory(groupId: String, since: String? = null): Boolean {
        val json = JSONObject().apply {
            put("type", "group_history_request")
            put("groupId", groupId)
            if (since != null) put("since", since)
        }
        return send(json)
    }

    fun leaveGroup(groupId: String): Boolean {
        val json = JSONObject().apply {
            put("type", "leave_group")
            put("groupId", groupId)
        }
        return send(json)
    }

    fun sendTyping(from: String, to: String) {
        send(JSONObject().apply {
            put("type", "typing")
            put("from", from)
            put("to", to)
            // The server forwards `typing === true`; without it the web client always
            // saw the phone as "not typing".
            put("typing", true)
        })
    }

    fun sendConversationRead(from: String, to: String) {
        sendRaw(JSONObject().apply {
            put("type", "conversation_read")
            put("from", from)
            put("to", to)
        }.toString())
    }

    fun sendReadReceipt(from: String, to: String, messageId: String) {
        send(JSONObject().apply {
            put("type", "read_receipt")
            put("from", from)
            put("to", to)
            put("messageId", messageId)
        })
    }

    fun sendDeliveryReceipt(from: String, to: String, messageId: String) {
        send(JSONObject().apply {
            put("type", "message_delivered")
            put("from", from)
            put("to", to)
            put("messageId", messageId)
        })
    }



    fun sendDiscoverable(username: String, discoverable: Boolean) {
        send(JSONObject().apply {
            put("type", "set_discoverable")
            put("from", username)
            put("discoverable", discoverable)
        })
    }

    fun sendFriendRequest(from: String, to: String) {
        send(JSONObject().apply {
            put("type", "friend_request")
            put("from", from)
            put("to", to)
        })
    }

    fun sendFriendResponse(from: String, to: String, accepted: Boolean) {
        send(JSONObject().apply {
            put("type", "friend_response")
            put("from", from)
            put("to", to)
            put("accepted", accepted)
        })
    }

    fun searchUser(from: String, query: String) {
        send(JSONObject().apply {
            put("type", "search")
            put("from", from)
            put("query", query)
        })
    }

    /**
     * Tell the server to remove [target] from both users' friends lists.
     * This ensures the contact won't reappear when the user logs in on a new device.
     */
    fun sendRemoveContact(target: String) {
        send(JSONObject().apply {
            put("type",   "remove_contact")
            put("target", target)
        })
    }

    /**
     * Push [settings] to the server for storage.
     * The server persists them in users.json so they can be restored on any device.
     */
    fun sendSettings(settings: JSONObject) {
        send(JSONObject().apply {
            put("type",     "save_settings")
            put("settings", settings)
        })
    }

    /** Sync chat-head / avatar chrome shape to server (circle|rounded|squircle|diamond). */
    fun sendAvatarShape(avatarShape: String) {
        send(JSONObject().apply {
            put("type", "set_avatar_shape")
            put("avatarShape", avatarShape)
        })
    }

    /** Profile frame ("gen:<kind>:<seed>" or "none"): friends see it. */
    fun sendProfileFrame(frame: String) {
        send(JSONObject().apply { put("type", "set_profile_frame"); put("frame", frame) })
    }
    fun sendSaveFrame(frame: String, remove: Boolean = false) {
        send(JSONObject().apply { put("type", if (remove) "remove_saved_frame" else "save_frame"); put("frame", frame) })
    }

    /** Set own presence mode (online/afk/dnd/busy/brb/invisible). */
    fun sendPresence(mode: String) {
        send(JSONObject().apply {
            put("type", "set_presence")
            put("presence", mode)
        })
    }

    private fun scheduleReconnect() {
        val params = lastConnectParams ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val wait = reconnectDelay
            reconnectDelay = minOf(reconnectDelay * 2, 8_000L)
            delay(wait)
            connect(params)
        }
    }

    /**
     * Reconnect immediately. Pass [force]=true when a send just failed so we do not
     * no-op on a stale Connected state (zombie socket) — that was causing ~30s send lag.
     */
    fun reconnectNow(force: Boolean = false) {
        val params = lastConnectParams ?: return
        if (!force && _state.value == ConnectionState.Connected) return
        reconnectJob?.cancel()
        reconnectDelay = 1000L
        if (force) {
            // connect() cancels the old socket(s); just make sure its in-flight guard
            // does not swallow this forced attempt.
            _state.value = ConnectionState.Disconnected
        }
        connect(params)
    }

    /** Returns the number of bytes enqueued but not yet sent. 0 if disconnected. */
    fun queueSize(): Long = webSocket?.queueSize() ?: 0L

    /**
     * Update the cached connection params with the new username after a successful
     * username change. This ensures reconnects log in with the updated identity.
     */
    fun updateUsername(newUsername: String) {
        lastConnectParams = lastConnectParams?.copy(username = newUsername)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        lastConnectParams = null
        generation++                       // ignore any late callbacks from the old sockets
        val ws = webSocket
        val attempt = attemptSocket
        webSocket = null
        attemptSocket = null
        isAuthenticated = false
        try { ws?.close(1000, "Disconnecting") } catch (_: Exception) {}
        if (attempt != null && attempt !== ws) try { attempt.cancel() } catch (_: Exception) {}
        _state.value = ConnectionState.Disconnected
    }
}