package com.silvershireinn.app.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.utils.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Voice calls. Signaling (ring / accept / decline / hang up) goes through the server; the
 * audio itself is a real-time WebRTC connection ([RtcCall]) straight between the two devices.
 *
 *   Caller  -> call_offer  -> every device of the callee rings
 *   Callee  -> call_accept -> only the caller's device that rang them (toDevice)
 *   Caller makes the WebRTC offer; both sides exchange rtc_signal until connected.
 *
 * The old version streamed raw audio through the server as text, which is why it lagged.
 */
object CallManager {

    enum class State { IDLE, OUTGOING, INCOMING, ACTIVE }
    /** DECLINED = I turned down an incoming call (it used to be logged as missed). */
    enum class EndReason { ENDED, MISSED, REJECTED, CANCELLED, DECLINED }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var remoteUser: String = ""
        private set
    @Volatile var isMuted: Boolean = false
        private set
    @Volatile var isSpeaker: Boolean = false
        private set
    /** Connected and audio flowing (vs. still connecting / reconnecting). */
    @Volatile var isConnected: Boolean = false
        private set
    /** Short status line for the call screen ("Connecting...", "Reconnecting..."). */
    @Volatile var statusText: String = ""
        private set

    private var callStartTime = 0L
    private var remoteDevice: String? = null
    private var iceServers: JSONArray? = null
    private val main = Handler(Looper.getMainLooper())

    var onStateChanged: ((State) -> Unit)? = null
    var onCallEnded: ((remoteUser: String, durationSeconds: Int, reason: EndReason, incoming: Boolean) -> Unit)? = null
    /** True when the current / last call was started by the other person. */
    @Volatile private var incomingCall = false
    /** Fired when [statusText] / [isConnected] change. */
    var onStatusChanged: (() -> Unit)? = null

    private val ringTimeout = Runnable {
        when (state) {
            State.OUTGOING -> { send("call_end", remoteUser); resetInternal(EndReason.CANCELLED) }   // no answer
            State.INCOMING -> resetInternal(EndReason.MISSED)
            else -> {}
        }
    }

    private fun setState(s: State) {
        state = s
        main.post { onStateChanged?.invoke(s) }
    }

    private fun setStatus(t: String, connected: Boolean = isConnected) {
        statusText = t; isConnected = connected
        main.post { onStatusChanged?.invoke() }
    }

    fun placeCall(to: String) {
        if (state != State.IDLE) return
        incomingCall = false
        remoteUser = to; remoteDevice = null; iceServers = null
        isMuted = false; isSpeaker = false
        setStatus("Calling...", false)
        setState(State.OUTGOING)
        send("call_offer", to)
        main.removeCallbacks(ringTimeout); main.postDelayed(ringTimeout, 45_000)
    }

    fun acceptCall() {
        if (state != State.INCOMING) return
        main.removeCallbacks(ringTimeout)
        setState(State.ACTIVE)
        setStatus("Connecting...", false)
        send("call_accept", remoteUser, remoteDevice)
        startMedia(caller = false)
    }

    fun rejectCall() {
        val user = remoteUser
        if (user.isNotBlank()) send("call_reject", user, remoteDevice)
        resetInternal(EndReason.CANCELLED)
    }

    fun endCall() {
        val user = remoteUser
        if (user.isNotBlank()) send("call_end", user, remoteDevice)
        resetInternal(EndReason.ENDED)
    }

    fun toggleMute() {
        isMuted = !isMuted
        RtcCall.setMuted(isMuted)
    }

    fun toggleSpeaker(@Suppress("UNUSED_PARAMETER") context: Context) {
        isSpeaker = !isSpeaker
        RtcCall.setSpeaker(isSpeaker)
    }

    /** Called by WebSocketService for call_offer / call_accept / call_reject / call_end. */
    fun handleSignal(type: String, from: String) = handleSignal(JSONObject().put("type", type).put("from", from))

    fun handleSignal(json: JSONObject) {
        val type = json.optString("type")
        val from = json.optString("from")
        val fromDevice = json.optString("fromDevice").ifBlank { null }
        json.optJSONArray("iceServers")?.let { iceServers = it }
        when (type) {
            "call_offer" -> {
                if (state == State.IDLE && !VideoCallManager.isBusy()) {
                    incomingCall = true
                    remoteUser = from; remoteDevice = fromDevice
                    isMuted = false; isSpeaker = false
                    setStatus("Incoming call", false)
                    setState(State.INCOMING)
                    main.removeCallbacks(ringTimeout); main.postDelayed(ringTimeout, 45_000)
                } else if (from != remoteUser) {
                    send("call_reject", from, fromDevice)   // busy
                }
            }
            "call_accept" -> if (state == State.OUTGOING && from == remoteUser) {
                main.removeCallbacks(ringTimeout)
                remoteDevice = fromDevice
                setState(State.ACTIVE)
                setStatus("Connecting...", false)
                startMedia(caller = true)
            }
            "call_reject" -> if (from == remoteUser && state == State.OUTGOING) resetInternal(EndReason.REJECTED)
            "call_end" -> if (from == remoteUser) {
                resetInternal(if (state == State.INCOMING) EndReason.MISSED else EndReason.ENDED)
            }
            "call_answered_elsewhere" -> if (state == State.INCOMING && from == remoteUser) {
                // Picked up / declined on another of my devices: stop ringing here quietly.
                main.removeCallbacks(ringTimeout)
                state = State.IDLE; remoteUser = ""
                main.post { onStateChanged?.invoke(State.IDLE) }
            }
            "call_config" -> {}
        }
    }

    /** WebRTC offer / answer / candidates for the current voice call. */
    fun handleRtcSignal(json: JSONObject) {
        if (json.optString("from") != remoteUser) return
        RtcCall.onSignal(json)
    }

    private fun startMedia(caller: Boolean) {
        callStartTime = 0L
        RtcCall.start(SilvershireApp.instance, remoteUser, remoteDevice, video = false, caller = caller,
            iceServersJson = iceServers, l = object : RtcCall.Listener {
                override fun onConnected() {
                    if (callStartTime == 0L) callStartTime = System.currentTimeMillis()
                    RtcCall.setMuted(isMuted); RtcCall.setSpeaker(isSpeaker)
                    setStatus("", true)
                }
                override fun onReconnecting() { setStatus("Reconnecting...", false) }
                // Their screen, shared into this voice call
                override fun onRemoteScreenShare(on: Boolean) { main.post { onStatusChanged?.invoke() } }
                override fun onFailed(reason: String) {
                    setStatus(reason, false)
                    val user = remoteUser
                    if (user.isNotBlank()) send("call_end", user, remoteDevice)
                    main.postDelayed({ resetInternal(EndReason.ENDED) }, 1500)
                }
            })
    }

    /** Legacy audio relay is gone; kept so old messages from outdated clients are ignored. */
    @Suppress("UNUSED_PARAMETER") fun handleAudioFrame(base64Pcm: String) {}

    fun isBusy(): Boolean = state != State.IDLE

    /** Talk time so far (0 until connected). The call screen shows this, so leaving and
     *  coming back no longer restarts the timer at 0:00. */
    fun callDurationSecs(): Int = if (callStartTime > 0L) ((System.currentTimeMillis() - callStartTime) / 1000).toInt() else 0

    private fun resetInternal(reason: EndReason) {
        main.removeCallbacks(ringTimeout)
        if (state == State.IDLE && remoteUser.isBlank()) return
        val dur = if (callStartTime > 0L) ((System.currentTimeMillis() - callStartTime) / 1000).toInt() else 0
        val prevUser = remoteUser
        val prevState = state
        RtcCall.stop()
        state = State.IDLE; remoteUser = ""; remoteDevice = null
        callStartTime = 0L; isMuted = false; isSpeaker = false; isConnected = false; statusText = ""
        main.post { onStateChanged?.invoke(State.IDLE) }
        val effective = when {
            dur > 0 -> EndReason.ENDED
            prevState == State.INCOMING && reason == EndReason.CANCELLED -> EndReason.DECLINED
            prevState == State.INCOMING -> EndReason.MISSED
            prevState == State.OUTGOING && reason == EndReason.REJECTED -> EndReason.REJECTED
            else -> reason
        }
        if (prevUser.isNotBlank()) onCallEnded?.invoke(prevUser, dur, effective, incomingCall)
    }

    fun reset() = resetInternal(EndReason.ENDED)

    private fun send(type: String, to: String, toDevice: String? = null) {
        WebSocketManager.sendRaw(JSONObject().apply {
            put("type", type)
            put("from", PreferenceManager.username)
            put("to", to)
            put("rtc", true)
            if (toDevice != null) put("toDevice", toDevice)
        }.toString())
    }
}
