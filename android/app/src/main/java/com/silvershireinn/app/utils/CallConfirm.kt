package com.silvershireinn.app.utils

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Confirm before placing an outgoing voice/video call.
 * Incoming calls are never gated by this.
 */
object CallConfirm {
    fun voice(context: Context, who: String, onConfirm: () -> Unit) {
        val name = PreferenceManager.displayNameFor(who)
        MaterialAlertDialogBuilder(context)
            .setTitle("Call $name?")
            .setMessage("Place a voice call?")
            .setPositiveButton("Call") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun video(context: Context, who: String, onConfirm: () -> Unit) {
        val name = PreferenceManager.displayNameFor(who)
        MaterialAlertDialogBuilder(context)
            .setTitle("Video call $name?")
            .setMessage("Place a video call?")
            .setPositiveButton("Video") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
