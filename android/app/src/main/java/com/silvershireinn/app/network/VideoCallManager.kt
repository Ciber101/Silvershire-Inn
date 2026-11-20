package com.silvershireinn.app.network

import android.os.Handler
import android.os.Looper
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.utils.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Video calls over WebRTC ([RtcCall]): smooth adaptive video + clear audio straight between
 * the two devices. The old version sent a JPEG per frame through the server (laggy, choppy).
 * Same signaling shape as voice calls, with video_call_* message types.
 */
object VideoCallManager {

    enum class State { IDLE, OUTGOING, INCOMING, ACTIVE }
    /** DECLINED = I turned down an incoming call (it used to be logged as missed). */
    enum class EndReason { ENDED, MISSED, REJECTED, CANCELLED, DECLINED }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var remoteUser: String = ""
        private set
    @Volatile var useFrontCamera: Boolean = true
    @Volatile var isMuted: Boolean = false
        private set
    @Volatile var isSpeaker: Boolean = true
        private set
    @Volatile var cameraOn: Boolean = true
        private set
    @Volatile var remoteVideoOn: Boolean = true
        private set
    @Volatile var isConnected: Boolean = false
        private set
    @Volatile var statusText: String = ""
        private set

    var onStateChanged: ((State) -> Unit)? = null
    var onCallEnded: ((remoteUser: String, durationSecs: Int, reason: EndReason, incoming: Boolean) -> Unit)? = null
    /** True when the current / last call was started by the other person. */
    @Volatile private var incomingCall = false
    var onStatusChanged: (() -> Unit)? = null

    private var callStartTime = 0L
    private var remoteDevice: String? = null
    private var iceServers: JSONArray? = null
    private val main = Handler(Looper.getMainLooper())

    private val ringTimeout = Runnable {
        when (state) {
            State.OUTGOING -> { send("video_call_end", remoteUser); resetInternal(EndReason.CANCELLED) }
            State.INCOMING -> resetInternal(EndReason.MISSED)
            else -> {}
        }
    }

    private fun setState(s: State) { state = s; main.post { onStateChanged?.invoke(s) } }
    private fun setStatus(t: String, connected: Boolean = isConnected) {
        statusText = t; isConnected = connected; main.post { onStatusChanged?.invoke() }
    }

    fun isBusy(): Boolean = state != State.IDLE

    fun placeCall(to: String) {
        if (state != State.IDLE) return
        incomingCall = false
        remoteUser = to; remoteDevice = null; iceServers = null
        isMuted = false; isSpeaker = true; cameraOn = true; remoteVideoOn = true
        setStatus("Calling...", false)
        setState(State.OUTGOING)
        send("video_call_offer", to)
        main.removeCallbacks(ringTimeout); main.postDelayed(ringTimeout, 45_000)
    }

    fun acceptCall() {
        if (state != State.INCOMING) return
        main.removeCallbacks(ringTimeout)
        setState(State.ACTIVE)
        setStatus("Connecting...", false)
        send("video_call_accept", remoteUser, remoteDevice)
        startMedia(caller = false)
    }

    fun rejectCall() {
        val u = remoteUser
        if (u.isNotBlank()) send("video_call_reject", u, remoteDevice)
        resetInternal(EndReason.CANCELLED)
    }

    fun endCall() {
        val u = remoteUser
        if (u.isNotBlank()) send("video_call_end", u, remoteDevice)
        resetInternal(EndReason.ENDED)
    }

    fun flipCamera() { RtcCall.switchCamera { useFrontCamera = it } }
    fun toggleMute() { isMuted = !isMuted; RtcCall.setMuted(isMuted) }
    fun toggleSpeaker() { isSpeaker = !isSpeaker; RtcCall.setSpeaker(isSpeaker) }
    fun toggleCamera() { cameraOn = !cameraOn; RtcCall.setCameraOn(cameraOn) }

    fun handleSignal(type: String, from: String) = handleSignal(JSONObject().put("type", type).put("from", from))

    fun handleSignal(json: JSONObject) {
        val type = json.optString("type")
        val from = json.optString("from")
        val fromDevice = json.optString("fromDevice").ifBlank { null }
        json.optJSONArray("iceServers")?.let { iceServers = it }
        when (type) {
            "video_call_offer" -> {
                if (state == State.IDLE && !CallManager.isBusy()) {
                    incomingCall = true
                    remoteUser = from; remoteDevice = fromDevice
                    isMuted = false; isSpeaker = true; cameraOn = true; remoteVideoOn = true
                    setStatus("Incoming video call", false)
                    setState(State.INCOMING)
                    main.removeCallbacks(ringTimeout); main.postDelayed(ringTimeout, 45_000)
                    // Warm up the engine so the self-view appears instantly on answer.
                    RtcCall.prepare(SilvershireApp.instance)
                } else if (from != remoteUser) send("video_call_reject", from, fromDevice)
            }
            "video_call_accept" -> if (state == State.OUTGOING && from == remoteUser) {
                main.removeCallbacks(ringTimeout)
                remoteDevice = fromDevice
                setState(State.ACTIVE)
                setStatus("Connecting...", false)
                startMedia(caller = true)
            }
            "video_call_reject" -> if (from == remoteUser && state == State.OUTGOING) resetInternal(EndReason.REJECTED)
            "video_call_end" -> if (from == remoteUser) resetInternal(if (state == State.INCOMING) EndReason.MISSED else EndReason.ENDED)
            "call_answered_elsewhere" -> if (state == State.INCOMING && from == remoteUser) {
                main.removeCallbacks(ringTimeout)
                state = State.IDLE; remoteUser = ""
                main.post { onStateChanged?.invoke(State.IDLE) }
            }
        }
    }

    fun handleRtcSignal(json: JSONObject) {
        if (json.optString("from") != remoteUser) return
        RtcCall.onSignal(json)
    }

    private fun startMedia(caller: Boolean) {
        callStartTime = 0L
        RtcCall.start(SilvershireApp.instance, remoteUser, remoteDevice, video = true, caller = caller,
            iceServersJson = iceServers, l = object : RtcCall.Listener {
                override fun onConnected() {
                    if (callStartTime == 0L) callStartTime = System.currentTimeMillis()
                    RtcCall.setMuted(isMuted); RtcCall.setSpeaker(isSpeaker)
                    setStatus("", true)
                }
                override fun onReconnecting() { setStatus("Reconnecting...", false) }
                override fun onFailed(reason: String) {
                    setStatus(reason, false)
                    val u = remoteUser
                    if (u.isNotBlank()) send("video_call_end", u, remoteDevice)
                    main.postDelayed({ resetInternal(EndReason.ENDED) }, 1500)
                }
                override fun onRemoteVideoChanged(hasVideo: Boolean) {
                    remoteVideoOn = hasVideo; main.post { onStatusChanged?.invoke() }
                }
            })
    }

    fun callDurationSecs(): Int = if (callStartTime > 0) ((System.currentTimeMillis() - callStartTime) / 1000).toInt() else 0

    /** Old frame relay messages from outdated clients are ignored. */
    @Suppress("UNUSED_PARAMETER") fun handleVideoFrame(base64Jpeg: String) {}
    @Suppress("UNUSED_PARAMETER") fun handleAudioFrame(base64Pcm: String) {}

    private fun resetInternal(reason: EndReason) {
        main.removeCallbacks(ringTimeout)
        if (state == State.IDLE && remoteUser.isBlank()) return
        val dur = callDurationSecs()
        val prevUser = remoteUser
        val prevState = state
        RtcCall.stop()
        state = State.IDLE; remoteUser = ""; remoteDevice = null; callStartTime = 0L
        isMuted = false; isSpeaker = true; cameraOn = true; isConnected = false; statusText = ""
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
