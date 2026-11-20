package com.silvershireinn.app.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.os.Build
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * A message bubble that can carry its sender's picture on a top corner like a wax seal: the
 * bubble is cut away in a circle around the picture and its edge follows the cut, so the picture
 * looks set into the bubble. Also honours android:maxWidth (plain LinearLayout ignores it).
 */
class SealBubble @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {

    /** 0 = no seal, [LEFT] = top-left corner, [RIGHT] = top-right corner. */
    var sealCorner = 0
        private set
    private var sealRadius = 0f
    private var rimColor = 0
    private var rimWidth = 0f
    private val maxW: Int
    private val cut = Path()
    private val shape = Path()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        val a = ctx.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxWidth))
        maxW = a.getDimensionPixelSize(0, 0)
        a.recycle()
        setWillNotDraw(false)
    }

    /** Cut a circle of [radius] around the bubble's top [corner]; [corner] 0 removes it. */
    fun setSeal(corner: Int, radius: Float, rim: Int, rimW: Float) {
        if (corner == sealCorner && radius == sealRadius && rim == rimColor && rimW == rimWidth) return
        sealCorner = corner; sealRadius = radius; rimColor = rim; rimWidth = rimW
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var ws = widthMeasureSpec
        if (maxW > 0) {
            val size = MeasureSpec.getSize(ws)
            if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED || size > maxW)
                ws = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
        }
        super.onMeasure(ws, heightMeasureSpec)
    }

    override fun draw(c: Canvas) {
        if (sealCorner == 0 || sealRadius <= 0f || width == 0) { super.draw(c); return }
        val cx = if (sealCorner == LEFT) 0f else width.toFloat()
        cut.reset(); cut.addCircle(cx, 0f, sealRadius, Path.Direction.CW)
        c.save()
        if (Build.VERSION.SDK_INT >= 26) c.clipOutPath(cut)
        else @Suppress("DEPRECATION") c.clipPath(cut, Region.Op.DIFFERENCE)
        super.draw(c)
        c.restore()
        // The bubble's edge follows the cut (only the part inside the bubble's rounded shape)
        if (rimWidth > 0f) {
            val r = 18f * resources.displayMetrics.density
            shape.reset(); shape.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, Path.Direction.CW)
            c.save()
            c.clipPath(shape)
            ring.color = rimColor; ring.strokeWidth = rimWidth
            c.drawCircle(cx, 0f, sealRadius + rimWidth / 2f, ring)
            c.restore()
        }
    }

    companion object { const val LEFT = 1; const val RIGHT = 2 }
}
