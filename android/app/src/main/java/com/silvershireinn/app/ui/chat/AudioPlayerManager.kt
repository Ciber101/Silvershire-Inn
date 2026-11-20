package com.silvershireinn.app.ui.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.model.Message
import com.silvershireinn.app.repository.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.sqrt

/**
 * Voice-message player.
 *
 *  - Downloads each voice note once to the cache and plays the local file (instant,
 *    reliable seeking, works offline, no repeat downloads). Streaming over HTTP with a
 *    moov-at-the-end m4a was why seeking and starting were flaky on some phones.
 *  - Real waveform (decoded from the audio) and the real length shown before playing.
 *  - Playback speed 1x / 1.5x / 2x (remembered), seek anywhere even before playing,
 *    auto-plays the next voice message in a row, pauses other audio (audio focus) and
 *    pauses when headphones are unplugged.
 */
object AudioPlayerManager {

    interface Listener {
        fun onProgress(messageId: String, positionMs: Int, durationMs: Int, fraction: Float)
        fun onStateChanged(messageId: String, playing: Boolean, paused: Boolean)
        fun onCompleted(messageId: String)
        fun onLoading(messageId: String, loading: Boolean) {}
        fun onInfoReady(messageId: String) {}
    }

    private val app get() = SilvershireApp.instance
    private var player: MediaPlayer? = null
    private var currentId: String = ""
    private var prepared = false
    private var lastPositionMs = 0
    private var lastDurationMs = 0
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val loadingIds = ConcurrentHashMap.newKeySet<String>()

    private val prefs by lazy { app.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE) }
    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        private set(v) { prefs.edit().putFloat("speed", v).apply() }

    /** Given the message that just finished, return the next voice message to auto-play. */
    var nextProvider: ((String) -> Message?)? = null

    private val durations = ConcurrentHashMap<String, Int>()
    private val levels = LruCache<String, FloatArray>(300)
    private val infoGate = Semaphore(2)
    private val infoRequested = ConcurrentHashMap.newKeySet<String>()

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            try {
                if (p.isPlaying) {
                    val dur = p.duration.coerceAtLeast(1)
                    val pos = p.currentPosition.coerceIn(0, dur)
                    lastDurationMs = dur; lastPositionMs = pos
                    dispatchProgress(currentId, pos, dur, pos.toFloat() / dur)
                    handler.postDelayed(this, 50)
                }
            } catch (_: Throwable) {}
        }
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    // ── Info (length + waveform), fetched in the background ─────────────────
    fun knownDuration(id: String): Int = durations[id] ?: 0
    fun knownLevels(id: String): FloatArray? = levels.get(id)

    /** Download (once) and read length + waveform so the bubble shows them before playing. */
    fun prefetchInfo(msg: Message) {
        val id = msg.messageId
        if (levels.get(id) != null && durations.containsKey(id)) return
        if (!infoRequested.add(id)) return
        val fileId = msg.attachmentId ?: return
        SilvershireApp.appScope.launch {
            infoGate.withPermit {
                try {
                    val f = FileRepository.ensureCached(app, fileId, msg.attachmentMime ?: "audio/mp4", msg.attachmentName)
                        ?: return@withPermit
                    if (!durations.containsKey(id)) readDuration(f)?.let { durations[id] = it }
                    if (levels.get(id) == null) (readCachedLevels(f) ?: computeLevels(f)?.also { saveLevels(f, it) })?.let { levels.put(id, it) }
                    withContext(Dispatchers.Main) { listeners.forEach { l -> try { l.onInfoReady(id) } catch (_: Throwable) {} } }
                } catch (_: Throwable) {
                } finally { infoRequested.remove(id) }
            }
        }
    }

    private fun readDuration(f: File): Int? {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(f.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() }
        catch (_: Throwable) { null } finally { try { r.release() } catch (_: Throwable) {} }
    }

    private fun levelsFile(f: File) = File(f.parentFile, f.name + ".wave")
    private fun readCachedLevels(f: File): FloatArray? = try {
        levelsFile(f).takeIf { it.exists() }?.readText()?.split(',')?.map { it.toFloat() }?.toFloatArray()
    } catch (_: Throwable) { null }
    private fun saveLevels(f: File, l: FloatArray) { try { levelsFile(f).writeText(l.joinToString(",") { "%.3f".format(java.util.Locale.US, it) }) } catch (_: Throwable) {} }

    /** Waveform for a local file (e.g. a recording being reviewed). Call off the main thread. */
    fun levelsForFile(f: File): FloatArray? = computeLevels(f)

    /** Decode the audio to PCM and turn loudness into [bars] bar heights (0.12..1). */
    private fun computeLevels(f: File, bars: Int = 40): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val chunks = ArrayList<Float>(4096)
            var acc = 0.0; var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            val started = System.currentTimeMillis()
            while (!outputDone && System.currentTimeMillis() - started < 8000) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o >= 0) {
                    val out = codec.getOutputBuffer(o)
                    if (out != null && info.size > 0) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val sb = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var k = 0
                        while (sb.hasRemaining()) {
                            val s = sb.get().toDouble()
                            if (k++ % 4 == 0) { acc += s * s; n++ }   // every 4th sample is plenty
                            if (n >= 512) { chunks.add(sqrt(acc / n).toFloat()); acc = 0.0; n = 0 }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (n > 0) chunks.add(sqrt(acc / n).toFloat())
            if (chunks.isEmpty()) return null
            val out = FloatArray(bars)
            for (b in 0 until bars) {
                val from = b * chunks.size / bars; val to = ((b + 1) * chunks.size / bars).coerceAtLeast(from + 1).coerceAtMost(chunks.size)
                var m = 0f; for (j in from until to) m = maxOf(m, chunks[j]); out[b] = m
            }
            val peak = out.maxOrNull()?.takeIf { it > 0f } ?: return null
            for (b in out.indices) out[b] = (0.12f + 0.88f * sqrt(out[b] / peak)).coerceIn(0.12f, 1f)
            return out
        } catch (_: Throwable) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────────
    fun toggle(msg: Message) {
        val id = msg.messageId
        when {
            isPlaying(id) -> pause()
            isPaused(id) -> resume()
            loadingIds.contains(id) -> {}
            else -> play(msg, 0f)
        }
    }

    /** Seek; if this message isn't the one playing, start it from that point. */
    fun seek(msg: Message, fraction: Float) {
        val id = msg.messageId
        val f = fraction.coerceIn(0f, 1f)
        val p = player
        if (currentId == id && p != null && prepared) {
            val dur = p.duration.coerceAtLeast(1)
            val ms = (dur * f).toInt()
            try { p.seekTo(ms); lastPositionMs = ms; lastDurationMs = dur; dispatchProgress(id, ms, dur, f) } catch (_: Throwable) {}
        } else play(msg, f)
    }

    fun play(msg: Message, startFraction: Float = 0f) {
        val id = msg.messageId
        val fileId = msg.attachmentId ?: return
        stopInternal(notifyComplete = currentId.isNotBlank() && currentId != id)
        currentId = id
        loadingIds.add(id)
        dispatchLoading(id, true)
        SilvershireApp.appScope.launch {
            val f = FileRepository.ensureCached(app, fileId, msg.attachmentMime ?: "audio/mp4", msg.attachmentName)
            withContext(Dispatchers.Main) {
                loadingIds.remove(id)
                dispatchLoading(id, false)
                if (currentId != id) return@withContext   // user moved on meanwhile
                if (f == null) { currentId = ""; dispatchState(id, false, false); toastSafe("Could not load voice message"); return@withContext }
                startLocal(id, f, startFraction)
            }
        }
        prefetchInfo(msg)
    }

    private fun startLocal(id: String, f: File, startFraction: Float) {
        val mp = MediaPlayer()
        player = mp; prepared = false
        try {
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath)
            mp.setOnPreparedListener {
                prepared = true
                lastDurationMs = it.duration.coerceAtLeast(0)
                durations[id] = lastDurationMs
                if (startFraction > 0f) { val ms = (lastDurationMs * startFraction).toInt(); it.seekTo(ms); lastPositionMs = ms }
                applySpeed(it)
                requestFocus()
                registerNoisy()
                try { it.start() } catch (_: Throwable) {}
                dispatchState(id, playing = true, paused = false)
                handler.post(ticker)
            }
            mp.setOnCompletionListener {
                handler.removeCallbacks(ticker)
                dispatchProgress(id, lastDurationMs, lastDurationMs, 1f)
                dispatchCompleted(id)
                releasePlayer()
                currentId = ""
                dispatchState(id, playing = false, paused = false)
                // Auto-play the next voice message in a row (like WhatsApp).
                val next = try { nextProvider?.invoke(id) } catch (_: Throwable) { null }
                if (next != null) handler.postDelayed({ if (currentId.isBlank()) play(next) }, 350)
                else abandonFocus()
            }
            mp.setOnErrorListener { _, _, _ ->
                handler.removeCallbacks(ticker)
                releasePlayer(); abandonFocus(); currentId = ""
                dispatchState(id, playing = false, paused = false)
                toastSafe("Could not play voice message")
                true
            }
            mp.prepareAsync()
        } catch (_: Throwable) {
            releasePlayer(); currentId = ""
            dispatchState(id, false, false)
        }
    }

    private fun applySpeed(p: MediaPlayer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val wasPlaying = p.isPlaying
                p.playbackParams = p.playbackParams.setSpeed(speed)
                if (!wasPlaying && prepared && currentId.isNotBlank() && isPaused(currentId)) p.pause()
            } catch (_: Throwable) {}
        }
    }

    /** 1x -> 1.5x -> 2x -> 1x. Returns the new speed. */
    fun cycleSpeed(): Float {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player?.let { if (prepared) applySpeed(it) }
        return speed
    }

    fun speedLabel(): String = when (speed) { 1.5f -> "1.5x"; 2f -> "2x"; else -> "1x" }

    fun pause() {
        val id = currentId
        try { player?.pause(); lastPositionMs = player?.currentPosition ?: lastPositionMs } catch (_: Throwable) {}
        handler.removeCallbacks(ticker)
        // Give audio focus back so music / podcasts paused for the voice note resume.
        abandonFocus()
        if (id.isNotBlank()) dispatchState(id, playing = false, paused = true)
    }

    fun resume() {
        val id = currentId
        val p = player ?: return
        if (!prepared) return
        try { requestFocus(); p.start(); applySpeed(p); handler.post(ticker); dispatchState(id, playing = true, paused = false) } catch (_: Throwable) {}
    }

    fun isPlaying(id: String) = currentId == id && try { player?.isPlaying == true } catch (_: Throwable) { false }
    fun isPaused(id: String) = currentId == id && player != null && prepared && !isPlaying(id)
    fun isActive(id: String) = currentId == id && (player != null || loadingIds.contains(id))
    fun isLoading(id: String) = loadingIds.contains(id)

    fun stopCurrent() { stopInternal(notifyComplete = currentId.isNotBlank()) }

    /** Message id of the voice note playing / paused / loading, or null. */
    fun currentMessageId(): String? = currentId.takeIf { it.isNotBlank() }

    fun positionMs(id: String) = if (currentId == id) lastPositionMs else 0
    fun durationMs(id: String) = if (currentId == id && lastDurationMs > 0) lastDurationMs else knownDuration(id)
    fun progressFraction(id: String): Float {
        if (currentId != id) return 0f
        val d = lastDurationMs; if (d <= 0) return 0f
        return (lastPositionMs.toFloat() / d).coerceIn(0f, 1f)
    }

    fun formatMs(ms: Int): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

    // ── Internals ─────────────────────────────────────────────────────────────
    private fun stopInternal(notifyComplete: Boolean) {
        handler.removeCallbacks(ticker)
        val prev = currentId
        try { player?.stop() } catch (_: Throwable) {}
        releasePlayer()
        abandonFocus()
        currentId = ""
        if (notifyComplete && prev.isNotBlank()) { dispatchCompleted(prev); dispatchState(prev, false, false) }
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Throwable) {}
        player = null; prepared = false; lastPositionMs = 0
        unregisterNoisy()
    }

    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) handler.post { pause() }
    }

    private fun requestFocus() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(focusListener).build().also { focusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (_: Throwable) {}
    }

    private fun abandonFocus() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { am.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
        } catch (_: Throwable) {}
    }

    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }
    private fun registerNoisy() {
        if (noisyRegistered) return
        try {
            val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(noisyReceiver, f)
            noisyRegistered = true
        } catch (_: Throwable) {}
    }
    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { app.unregisterReceiver(noisyReceiver) } catch (_: Throwable) {}
        noisyRegistered = false
    }

    private fun toastSafe(t: String) = handler.post {
        try { android.widget.Toast.makeText(app, t, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    private fun dispatchProgress(id: String, pos: Int, dur: Int, frac: Float) =
        listeners.forEach { try { it.onProgress(id, pos, dur, frac) } catch (_: Throwable) {} }
    private fun dispatchState(id: String, playing: Boolean, paused: Boolean) =
        listeners.forEach { try { it.onStateChanged(id, playing, paused) } catch (_: Throwable) {} }
    private fun dispatchCompleted(id: String) =
        listeners.forEach { try { it.onCompleted(id) } catch (_: Throwable) {} }
    private fun dispatchLoading(id: String, loading: Boolean) =
        listeners.forEach { try { it.onLoading(id, loading) } catch (_: Throwable) {} }
}
