package com.silvershireinn.app.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The preview under a link: picture on top, then site, title and a line about it, in a rounded
 * card (same look as the web app). Always the same comfortable width (narrower only when the
 * bubble can't fit it), so the picture isn't squeezed down to the width of a short link.
 */
class LinkCard @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val pref = (272 * ctx.resources.displayMetrics.density).toInt()
    init { clipToOutline = true }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (mode == MeasureSpec.UNSPECIFIED) pref else minOf(pref, size)
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}

/** A picture that is always 16:9 of its width. */
class AspectImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ImageView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 9 / 16)
    }
}
