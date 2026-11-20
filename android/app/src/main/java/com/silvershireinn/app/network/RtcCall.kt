@file:Suppress("DEPRECATION")

package com.silvershireinn.app.network

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.silvershireinn.app.utils.DeviceTier
import com.silvershireinn.app.utils.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Real-time call engine (WebRTC): Opus audio with echo cancellation / noise suppression /
 * jitter buffering, and adaptive H.264/VP8 video. Media flows directly between the two
 * devices (STUN, or the server's TURN relay when one is set up); the server only relays
 * the small offer / answer / network-candidate messages ("rtc_signal").
 *
 * One call at a time. CallManager (voice) and VideoCallManager (video) drive it.
 */
object RtcCall {

    interface Listener {
        fun onConnected()
        fun onReconnecting() {}
        fun onFailed(reason: String)
        fun onRemoteVideoChanged(hasVideo: Boolean) {}
    }

    private const val TAG = "RtcCall"
    private val main = Handler(Looper.getMainLooper())

    // ── Factory (created once) ────────────────────────────────────────────────
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var egl: EglBase? = null
    val eglContext: EglBase.Context? get() = egl?.eglBaseContext

    // ── Current call ──────────────────────────────────────────────────────────
    private var pc: PeerConnection? = null
    private var listener: Listener? = null
    private var remoteUser = ""
    var remoteDevice: String? = null
    private var isCaller = false
    private var withVideo = false
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val earlySignals = mutableListOf<JSONObject>()
    private var remoteDescSet = false
    private var connectedOnce = false
    private var restartAttempts = 0
    var frontCamera = true; private set
    var cameraOn = true; private set
    private var appContext: Context? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL

    /** Sinks are swappable so screens can attach / detach renderers at any time. */
    class ProxySink : VideoSink {
        @Volatile var target: VideoSink? = null
        override fun onFrame(frame: VideoFrame) { target?.onFrame(frame) }
    }
    val localSink = ProxySink()
    val remoteSink = ProxySink()

    val isActive: Boolean get() = pc != null

    private fun ensureFactory(ctx: Context) {
        if (factory != null) return
        val app = ctx.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        egl = EglBase.create()
        adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** Prepare the factory early (e.g. when the call screen opens) so the camera preview can show. */
    fun prepare(ctx: Context) {
        try { ensureFactory(ctx) } catch (e: Throwable) { Log.e(TAG, "factory", e) }
    }

    private fun iceServers(json: JSONArray?): List<PeerConnection.IceServer> {
        val out = mutableListOf<PeerConnection.IceServer>()
        try {
            if (json != null) for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                val urls = o.opt("urls")
                val list = when (urls) {
                    is JSONArray -> (0 until urls.length()).map { urls.getString(it) }
                    is String -> listOf(urls)
                    else -> emptyList()
                }
                if (list.isEmpty()) continue
                val b = PeerConnection.IceServer.builder(list)
                if (o.has("username")) b.setUsername(o.optString("username"))
                if (o.has("credential")) b.setPassword(o.optString("credential"))
                out += b.createIceServer()
            }
        } catch (_: Throwable) {}
        if (out.isEmpty()) out += PeerConnection.IceServer.builder(
            listOf("stun:stun.l.google.com:19302", "stun:stun.cloudflare.com:3478")).createIceServer()
        return out
    }

    /**
     * Start the media connection. The caller makes the offer; the callee waits for it.
     * [iceServersJson] comes from the server (STUN + optional TURN).
     */
    fun start(ctx: Context, remote: String, device: String?, video: Boolean, caller: Boolean,
              iceServersJson: JSONArray?, l: Listener) {
        // Everything in here runs on the main thread. Calls used to be started from the
        // socket thread while signals arrived on others: racy lists and half-set state.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(ctx, remote, device, video, caller, iceServersJson, l) }; return
        }
        // Offer / candidates that arrived before we were ready, from THIS person only
        // (left-overs from an earlier, unanswered call used to be fed into the new one).
        val early = earlySignals.filter { it.optString("from") == remote }
        stop(silent = true)
        appContext = ctx.applicationContext
        try { ensureFactory(ctx) } catch (e: Throwable) { l.onFailed("Calling isn't available on this phone"); return }
        val f = factory ?: run { l.onFailed("Calling isn't available on this phone"); return }
        listener = l; remoteUser = remote; remoteDevice = device; isCaller = caller; withVideo = video
        remoteDescSet = false; connectedOnce = false; restartAttempts = 0
        pendingCandidates.clear()
        cameraOn = video

        val cfg = PeerConnection.RTCConfiguration(iceServers(iceServersJson)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        pc = f.createPeerConnection(cfg, observer)
        val p = pc ?: run { l.onFailed("Could not start the call"); return }

        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("audio0", audioSource).also { p.addTrack(it, listOf("s0")) }
        if (video) startCamera(ctx, f, p)

        startAudioRouting(speaker = video)
        WebSocketService.setCallMedia(mic = true, camera = video)   // keep the mic alive off-screen
        if (caller) makeOffer(iceRestart = false)
        // Anything that arrived before we were ready (offer / candidates).
        early.forEach { onSignal(it) }
        // Give up if we never connect.
        main.postDelayed(connectTimeout, 25_000)
    }

    private fun startCamera(ctx: Context, f: PeerConnectionFactory, p: PeerConnection) {
        try {
            val en = Camera2Enumerator(ctx)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) == frontCamera } ?: en.deviceNames.firstOrNull() ?: return
            frontCamera = en.isFrontFacing(name)
            val cap = en.createCapturer(name, null) ?: return
            surfaceHelper = SurfaceTextureHelper.create("cam", egl!!.eglBaseContext)
            videoSource = f.createVideoSource(false)
            cap.initialize(surfaceHelper, ctx.applicationContext, videoSource!!.capturerObserver)
            val low = DeviceTier.isLowEnd
            cap.startCapture(if (low) 640 else 1280, if (low) 480 else 720, if (low) 24 else 30)
            capturer = cap
            videoTrack = f.createVideoTrack("video0", videoSource).also {
                it.addSink(localSink)
                val sender = p.addTrack(it, listOf("s0"))
                try {
                    val params = sender.parameters
                    params.encodings.firstOrNull()?.maxBitrateBps = if (low) 600_000 else 1_800_000
                    sender.parameters = params
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) { Log.e(TAG, "camera", e) }
    }

    private var focusRequest: android.media.AudioFocusRequest? = null

    /**
     * Call audio: communication mode, audio focus (music / videos pause for the call and
     * resume after - they used to keep playing underneath), and the right output: a
     * Bluetooth or wired headset when one is connected (calls always used the phone's own
     * earpiece / speaker before, even with earbuds in), otherwise earpiece or speaker.
     */
    private fun startAudioRouting(speaker: Boolean) {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            savedAudioMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Throwable) {}
        try {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        } catch (_: Throwable) {}
        setSpeaker(speaker)
    }

    /** Speaker on, or the best private output (headset > earpiece). */
    fun setSpeaker(on: Boolean) {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Throwable) {}
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                val devs = am.availableCommunicationDevices
                fun pick(vararg types: Int): android.media.AudioDeviceInfo? =
                    types.asSequence().mapNotNull { t -> devs.firstOrNull { it.type == t } }.firstOrNull()
                val target = if (on) pick(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    else pick(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                              android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                              android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                if (target != null && am.setCommunicationDevice(target)) return
            } catch (_: Throwable) {}
        }
        try {
            if (!on && am.isBluetoothScoAvailableOffCall && isBluetoothHeadsetConnected(am)) {
                am.isSpeakerphoneOn = false
                am.startBluetoothSco(); am.isBluetoothScoOn = true
            } else {
                if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
                am.isSpeakerphoneOn = on && !am.isWiredHeadsetOn
            }
        } catch (_: Throwable) {}
    }

    private fun isBluetoothHeadsetConnected(am: AudioManager): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 23)
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        else false
    } catch (_: Throwable) { false }

    private fun stopAudioRouting() {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try { if (android.os.Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() } catch (_: Throwable) {}
        try { if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() } } catch (_: Throwable) {}
        try { am.isSpeakerphoneOn = false; am.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
            else am.abandonAudioFocus(null)
        } catch (_: Throwable) {}
        focusRequest = null
    }

    fun setMuted(muted: Boolean) { try { audioTrack?.setEnabled(!muted) } catch (_: Throwable) {} }

    fun setCameraOn(on: Boolean) {
        cameraOn = on
        try {
            videoTrack?.setEnabled(on)
            if (on) capturer?.startCapture(if (DeviceTier.isLowEnd) 640 else 1280, if (DeviceTier.isLowEnd) 480 else 720, if (DeviceTier.isLowEnd) 24 else 30)
            else capturer?.stopCapture()
        } catch (_: Throwable) {}
        sendSignal(JSONObject().put("kind", "video_state").put("video", on))
    }

    fun switchCamera(done: (Boolean) -> Unit = {}) {
        try {
            capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) { frontCamera = isFront; main.post { done(isFront) } }
                override fun onCameraSwitchError(error: String?) {}
            })
        } catch (_: Throwable) {}
    }

    // ── Signaling ─────────────────────────────────────────────────────────────
    private fun sendSignal(o: JSONObject) {
        if (remoteUser.isBlank()) return
        o.put("type", "rtc_signal").put("to", remoteUser).put("from", PreferenceManager.username)
        remoteDevice?.let { o.put("toDevice", it) }
        WebSocketManager.sendRaw(o.toString())
    }

    private val noop = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.w(TAG, "sdp create $p0") }
        override fun onSetFailure(p0: String?) { Log.w(TAG, "sdp set $p0") }
    }

    private fun mediaConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (withVideo) "true" else "false"))
    }

    private fun makeOffer(iceRestart: Boolean) {
        val p = pc ?: return
        if (iceRestart) try { p.restartIce() } catch (_: Throwable) {}
        p.createOffer(object : SdpObserver by noop {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                p.setLocalDescription(noop, sdp)
                sendSignal(JSONObject().put("kind", "offer").put("sdp", sdp.description).put("video", withVideo))
            }
        }, mediaConstraints())
    }

    /** rtc_signal from the other device. */
    fun onSignal(json: JSONObject) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onSignal(json) }; return }
        val p = pc
        if (p == null) { if (earlySignals.size < 200) earlySignals.add(json); return }
        json.optString("fromDevice").takeIf { it.isNotBlank() }?.let { remoteDevice = it }
        when (json.optString("kind")) {
            "offer" -> {
                val sdp = SessionDescription(SessionDescription.Type.OFFER, json.optString("sdp"))
                p.setRemoteDescription(object : SdpObserver by noop {
                    override fun onSetSuccess() {
                        remoteDescSet = true; drainCandidates()
                        p.createAnswer(object : SdpObserver by noop {
                            override fun onCreateSuccess(ans: SessionDescription?) {
                                ans ?: return
                                p.setLocalDescription(noop, ans)
                                sendSignal(JSONObject().put("kind", "answer").put("sdp", ans.description))
                            }
                        }, mediaConstraints())
                    }
                }, sdp)
            }
            "answer" -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, json.optString("sdp"))
                p.setRemoteDescription(object : SdpObserver by noop {
                    override fun onSetSuccess() { remoteDescSet = true; drainCandidates() }
                }, sdp)
            }
            "ice" -> {
                val c = json.optJSONObject("candidate") ?: return
                val ice = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
                if (remoteDescSet) p.addIceCandidate(ice) else pendingCandidates.add(ice)
            }
            "video_state" -> main.post { listener?.onRemoteVideoChanged(json.optBoolean("video", true)) }
        }
    }

    private fun drainCandidates() {
        val p = pc ?: return
        pendingCandidates.forEach { try { p.addIceCandidate(it) } catch (_: Throwable) {} }
        pendingCandidates.clear()
    }

    private val connectTimeout = Runnable {
        if (!connectedOnce && pc != null) listener?.onFailed("Couldn't connect the call. The network may be blocking it.")
    }
    private val reconnectRunnable = Runnable {
        if (pc == null) return@Runnable
        if (restartAttempts >= 3) { listener?.onFailed("Connection lost"); return@Runnable }
        restartAttempts++
        if (isCaller) makeOffer(iceRestart = true)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate?) {
            c ?: return
            sendSignal(JSONObject().put("kind", "ice").put("candidate", JSONObject()
                .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp)))
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            main.post {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        main.removeCallbacks(connectTimeout); main.removeCallbacks(reconnectRunnable)
                        restartAttempts = 0
                        if (!connectedOnce) { connectedOnce = true; listener?.onConnected() } else listener?.onConnected()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        listener?.onReconnecting()
                        main.removeCallbacks(reconnectRunnable)
                        main.postDelayed(reconnectRunnable, 4000)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        listener?.onReconnecting()
                        main.removeCallbacks(reconnectRunnable)
                        main.post(reconnectRunnable)
                    }
                    else -> {}
                }
            }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            val t = transceiver?.receiver?.track() as? VideoTrack ?: return
            remoteVideoTrack = t
            t.addSink(remoteSink)
            main.post { listener?.onRemoteVideoChanged(true) }
        }
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    /** Hang up the media side. Safe to call any time. */
    fun stop(silent: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop(silent) }; return }
        main.removeCallbacks(connectTimeout); main.removeCallbacks(reconnectRunnable)
        earlySignals.clear(); pendingCandidates.clear()
        try { remoteVideoTrack?.removeSink(remoteSink) } catch (_: Throwable) {}
        try { videoTrack?.removeSink(localSink) } catch (_: Throwable) {}
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        try { surfaceHelper?.dispose() } catch (_: Throwable) {}
        try { videoSource?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        try { pc?.dispose() } catch (_: Throwable) {}
        capturer = null; surfaceHelper = null; videoSource = null; videoTrack = null
        audioSource = null; audioTrack = null; remoteVideoTrack = null; pc = null
        if (!silent) { stopAudioRouting(); WebSocketService.setCallMedia(mic = false, camera = false) }
        listener = null; remoteUser = ""; remoteDevice = null
        frontCamera = true; cameraOn = true
    }
}
