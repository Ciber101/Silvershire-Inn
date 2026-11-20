package com.silvershireinn.app.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.silvershireinn.app.R

/**
 * The app's own line icons (the same set the web app uses) wherever a symbol used to be an
 * emoji: file types, menus, lock notes, previews. Gold, shaded light to dark like engraved
 * metal; always centered on the text line.
 */
object ThemeIcons {
    /** Engraved gold: the icons are drawn white-to-grey, so a MULTIPLY tint shades them. */
    const val GOLD = 0xFFE6C25A.toInt()

    private val EMOJI = linkedMapOf(
        "✓✓" to R.drawable.ic_seen, "👤" to R.drawable.ic_profile, "💬" to R.drawable.ic_chat,
        "📄" to R.drawable.ic_file, "📝" to R.drawable.ic_file, "🗑" to R.drawable.ic_delete,
        "🚫" to R.drawable.ic_block, "🔒" to R.drawable.ic_lock, "📎" to R.drawable.ic_attach,
        "📞" to R.drawable.ic_call, "📹" to R.drawable.ic_video_call, "✕" to R.drawable.ic_close,
        "↩" to R.drawable.ic_reply, "📋" to R.drawable.ic_copy, "🖼" to R.drawable.ic_gallery,
        "⬇" to R.drawable.ic_download, "👥" to R.drawable.ic_group, "🎬" to R.drawable.ic_film,
        "🎵" to R.drawable.ic_music, "📦" to R.drawable.ic_package, "📱" to R.drawable.ic_phone_app,
        "↪" to R.drawable.ic_forward
    )

    fun forMime(mime: String?): Int = when {
        mime == null -> R.drawable.ic_attach
        mime.startsWith("image/") -> R.drawable.ic_gallery
        mime.startsWith("video/") -> R.drawable.ic_film
        mime.startsWith("audio/") -> R.drawable.ic_music
        mime == "application/pdf" || mime.startsWith("text/") -> R.drawable.ic_file
        mime.contains("zip") || mime.contains("rar") || mime.contains("7z") -> R.drawable.ic_package
        mime.contains("apk") -> R.drawable.ic_phone_app
        else -> R.drawable.ic_attach
    }

    fun drawable(ctx: Context, res: Int, sizePx: Int, color: Int = GOLD): Drawable? {
        val d = AppCompatResources.getDrawable(ctx, res)?.mutate() ?: return null
        d.setTintList(ColorStateList.valueOf(color))
        d.setTintMode(PorterDuff.Mode.MULTIPLY)
        d.setBounds(0, 0, sizePx, sizePx)
        return d
    }

    /** A TextView that only shows a symbol: the icon instead, as its only "character", so it is
     *  centered exactly like the symbol was ([sizeDp] square). */
    fun setIcon(tv: TextView, res: Int, sizeDp: Int = 26, color: Int = GOLD) {
        val px = (sizeDp * tv.resources.displayMetrics.density).toInt()
        tv.setCompoundDrawables(null, null, null, null)
        val d = drawable(tv.context, res, px, color) ?: return
        val sb = SpannableStringBuilder("\u00A0")
        val align = if (Build.VERSION.SDK_INT >= 29) ImageSpan.ALIGN_CENTER else ImageSpan.ALIGN_BOTTOM
        sb.setSpan(ImageSpan(d, align), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.includeFontPadding = false
        tv.text = sb
    }

    /** Back to plain text (recycled rows): the next setText replaces the icon. */
    fun clearIcon(tv: TextView) { tv.setCompoundDrawables(null, null, null, null) }

    /** "🔒 text" becomes [lock icon] text; text without a leading symbol is returned as it is. */
    fun iconize(ctx: Context, text: CharSequence?, sizePx: Int = (18 * ctx.resources.displayMetrics.density).toInt(), color: Int = GOLD): CharSequence {
        val s = text ?: return ""
        val str = s.toString()
        for ((emo, res) in EMOJI) {
            if (!str.startsWith(emo)) continue
            var i = emo.length
            while (i < str.length && (str[i] == '️' || str[i].isWhitespace())) i++
            val d = drawable(ctx, res, sizePx, color) ?: return s
            val sb = SpannableStringBuilder("  ")
            val align = if (Build.VERSION.SDK_INT >= 29) ImageSpan.ALIGN_CENTER else ImageSpan.ALIGN_BASELINE
            sb.setSpan(ImageSpan(d, align), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(s.subSequence(i, s.length))
            return sb
        }
        return s
    }
}
