package com.silvershireinn.app.utils

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

/** Puts a file (a picture, usually) on the clipboard so it can be pasted into any app. */
object ClipCopy {
    /** True when it worked. The type is given outright: files without an extension were
     *  offered as "unknown data" and apps would not paste them as a picture. */
    fun file(ctx: Context, f: File, name: String, mime: String): Boolean = try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
        val type = mime.ifBlank { ctx.contentResolver.getType(uri) ?: "application/octet-stream" }
        val clip = ClipData(ClipDescription(name, arrayOf(type)), ClipData.Item(uri))
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        true
    } catch (_: Throwable) { false }

    fun label(mime: String?): String = when {
        mime == null -> "Copy"
        mime.startsWith("image/") -> "Copy picture"
        mime.startsWith("video/") -> "Copy video"
        else -> "Copy file"
    }
}
