package com.silvershireinn.app.utils

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.io.File

/**
 * Manages a looping video played behind other content as a live wallpaper effect.
 *
 * Usage:
 *   1. Add a [SurfaceView] with id `svVideoBg` as the FIRST child of your root layout.
 *      Set its layout to match_parent, visibility GONE, z-order to below everything else.
 *   2. Call [attach] in onViewCreated / onCreate.
 *   3. Call [applyPath] whenever the video path changes (pass null to hide).
 *   4. Call [release] in onDestroyView / onDestroy.
 */
class VideoBackgroundHelper(
    private val context: Context,
    private val surfaceView: SurfaceView
) {
    private var mediaPlayer: MediaPlayer? = null
    private var pendingPath: String? = null
    /** The video that should be showing; replayed when the surface comes back (app resumed). */
    private var currentPath: String? = null
    private var surfaceReady = false

    init {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                // Resume after returning to the app: the surface is destroyed in the background
                // and the background video used to stay blank afterwards.
                (pendingPath ?: currentPath)?.let { startVideo(it, holder) }
                pendingPath = null
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfaceReady = false
                stopVideo()
            }
        })
    }

    /** Apply a video path (or null to clear). Call any time after attach. */
    fun applyPath(path: String?) {
        stopVideo()
        currentPath = path?.takeIf { File(it).exists() }
        if (path == null || !File(path).exists()) {
            surfaceView.visibility = View.GONE
            return
        }
        surfaceView.visibility = View.VISIBLE
        if (surfaceReady) {
            startVideo(path, surfaceView.holder)
        } else {
            pendingPath = path
        }
    }

    private fun startVideo(path: String, holder: SurfaceHolder) {
        stopVideo()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(context, Uri.fromFile(File(path)))
            mp.setDisplay(holder)
            mp.isLooping = true
            mp.setVolume(0f, 0f)        // silent — it's a visual background
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            mp.setOnPreparedListener { player ->
                // Guard: don't start if the surface was destroyed while prepareAsync() ran
                if (surfaceReady) {
                    try { player.start() } catch (_: Exception) {}
                } else {
                    stopVideo()
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                // Release quietly so MediaPlayer doesn't remain in an error state
                stopVideo()
                surfaceView.visibility = View.GONE
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            surfaceView.visibility = View.GONE
        }
    }

    private fun stopVideo() {
        val mp = mediaPlayer
        mediaPlayer = null          // null first so re-entrant calls are safe
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.reset() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
    }

    fun release() {
        stopVideo()
        pendingPath = null
        currentPath = null
    }
}
