package com.silvershireinn.app.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.silvershireinn.app.R
import kotlin.math.max
import kotlin.math.sqrt

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
    private var sealOffset = 0f
    private var rimColor = 0
    private var rimWidth = 0f
    /** The picture this bubble's cut follows: the cut is drawn from where (and how big) the
     *  picture really is, so the arc always lines up with it, at any size or border scale. */
    var sealAvatar: View? = null
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

    /** Cut a circle of [radius], centred [offset] outside the bubble's top [corner]; [corner] 0 removes it. */
    fun setSeal(corner: Int, radius: Float, rim: Int, rimW: Float, offset: Float = 0f) {
        if (corner == sealCorner && radius == sealRadius && rim == rimColor && rimW == rimWidth && offset == sealOffset) return
        sealCorner = corner; sealRadius = radius; rimColor = rim; rimWidth = rimW; sealOffset = offset
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
        var cx = if (sealCorner == LEFT) -sealOffset else width + sealOffset
        var cy = -sealOffset
        var rad = sealRadius
        var shapeOf: com.silvershireinn.app.utils.AvatarShape = com.silvershireinn.app.utils.AvatarShape.CIRCLE
        val gap = GAP_DP * resources.displayMetrics.density
        val av = sealAvatar
        if (av != null && av.parent === parent && av.width > 0) {
            cx = av.left + av.translationX + av.width / 2f - (left + translationX)
            cy = av.top + av.translationY + av.height / 2f - (top + translationY)
            // The edge of the picture, or of its border's ring when it has one (measured from the
            // border itself), then ONE fixed gap, the same at every picture size.
            val k = av.scaleX
            val ringFrac = av.getTag(R.id.seal_ring) as? Float
            val framed = av.getTag(R.id.profile_frame_float) != null && ringFrac != null
            val edge = if (framed) ringFrac!! * (av.width / com.silvershireinn.app.utils.ProfileFrames.PIC) / 2f * k else av.width / 2f * k
            rad = edge
            shapeOf = (av.getTag(R.id.seal_shape) as? com.silvershireinn.app.utils.AvatarShape) ?: com.silvershireinn.app.utils.AvatarShape.CIRCLE
        } else rad = sealRadius - gap
        outline(cut, shapeOf, cx, cy, rad, gap)
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
            outline(rimPath, shapeOf, cx, cy, rad, gap + rimWidth / 2f)
            c.drawPath(rimPath, ring)
            c.restore()
        }
    }

    private val rimPath = Path()

    /** The picture's own shape around ([cx],[cy]) with half size [h], grown by [grow] all round:
     *  a circle stays a circle, a square gets a square corner, a diamond a diamond point. */
    private fun outline(p: Path, s: com.silvershireinn.app.utils.AvatarShape, cx: Float, cy: Float, h: Float, grow: Float) {
        p.reset()
        when (s) {
            com.silvershireinn.app.utils.AvatarShape.CIRCLE -> p.addCircle(cx, cy, h + grow, Path.Direction.CW)
            com.silvershireinn.app.utils.AvatarShape.ROUNDED_SQUARE, com.silvershireinn.app.utils.AvatarShape.SQUIRCLE -> {
                val cr = (if (s == com.silvershireinn.app.utils.AvatarShape.SQUIRCLE) 0.34f else 0.22f) * 2 * h + grow
                val H = h + grow
                p.addRoundRect(cx - H, cy - H, cx + H, cy + H, cr, cr, Path.Direction.CW)
            }
            com.silvershireinn.app.utils.AvatarShape.DIAMOND -> {
                if (Build.VERSION.SDK_INT < 30) { p.addCircle(cx, cy, h + grow, Path.Direction.CW); return }
                val v = h + grow * 1.4142f
                p.moveTo(cx, cy - v); p.lineTo(cx + v, cy); p.lineTo(cx, cy + v); p.lineTo(cx - v, cy); p.close()
            }
        }
    }

    companion object {
        /** The gap between the picture (or its border) and the bubble, at every size. */
        const val GAP_DP = 3.5f
        const val LEFT = 1; const val RIGHT = 2
        const val NONE = 0; const val ALIGNED = 1; const val SEAL = 2

        /**
         * Places a message's picture [frame] (in a FrameLayout row) and its [bubble]: set into the
         * bubble's top corner ([SEAL]), its room kept but nothing shown ([ALIGNED], the rest of a
         * run) or none at all ([NONE]). [sizeDp] is the picture size. Big pictures sit a little
         * further out of the corner, and the bubble's content always stays clear of the cut.
         */
        fun place(frame: View, bubble: SealBubble?, mine: Boolean, state: Int, sizeDp: Int, ornate: Boolean, below: Boolean = false) {
            val b = bubble ?: return
            val d = frame.resources.displayMetrics.density
            val a = (sizeDp * d).toInt()
            val half = a / 2f
            val o = 0f                  // the picture sits right on the corner (the cut follows the real picture)
            val roomTop = max(6 * d, a * 0.15f + 2 * d).toInt()       // above the picture, for its border
            (frame.layoutParams as? FrameLayout.LayoutParams)?.let { flp ->
                if (flp.width != a || flp.height != a || flp.topMargin != roomTop) {
                    flp.width = a; flp.height = a; flp.topMargin = roomTop; frame.layoutParams = flp
                }
            }
            // room beside the picture for its border
            (frame.parent as? ViewGroup)?.let { row ->
                val side = if (state == NONE) (10 * d).toInt() else max((10 * d).toInt(), (a * 0.25f).toInt())
                if (mine && row.paddingEnd != side) row.setPaddingRelative(row.paddingStart, row.paddingTop, side, row.paddingBottom)
                if (!mine && row.paddingStart != side) row.setPaddingRelative(side, row.paddingTop, row.paddingEnd, row.paddingBottom)
            }
            val lp = b.layoutParams as? FrameLayout.LayoutParams ?: return
            val sideM = if (state == NONE) 0 else (half - o).toInt()
            val top = if (state == SEAL) (roomTop + half + o).toInt() else 0      // centre sits o above and o outside the corner
            val changed = (if (mine) lp.marginEnd else lp.marginStart) != sideM || lp.topMargin != top
            if (mine) lp.marginEnd = sideM else lp.marginStart = sideM
            lp.topMargin = top
            if (changed) b.layoutParams = lp
            val orig = (b.getTag(R.id.seal_padding) as? IntArray)
                ?: intArrayOf(b.paddingLeft, b.paddingTop, b.paddingRight, b.paddingBottom).also { b.setTag(R.id.seal_padding, it) }
            val radius = half + 2.5f * d
            b.sealAvatar = frame
            if (state == SEAL) {
                // Nothing of the picture or its border ever sits on the text, and the bubble does
                // not grow taller: the content keeps clear sideways (its border may reach 0.3 x the
                // size past the picture), so the bubble just gets a little wider on that side.
                if (below) {
                    // Pictures, videos, files and voice messages are wide blocks: they go BELOW the
                    // picture's corner, at the bubble's full width. Pushed sideways they stuck out
                    // past the bubble's edge. The border reaches at most 0.15 x the size below it.
                    val clearY = (half - o + a * 0.15f + (GAP_DP + 2f) * d).toInt()
                    b.setPadding(orig[0], max(orig[1], clearY), orig[2], orig[3])
                } else {
                    val clearX = (half - o + a * 0.3f + 2 * d).toInt()
                    if (mine) b.setPadding(orig[0], orig[1], max(orig[2], clearX), orig[3])
                    else b.setPadding(max(orig[0], clearX), orig[1], orig[2], orig[3])
                }
                val rim = if (!ornate) 0xFF3C3C42.toInt() else if (mine) 0xFF8C6A2A.toInt() else 0xFF6E7078.toInt()
                b.setSeal(if (mine) RIGHT else LEFT, radius, rim, 1.5f * d, o)
            } else {
                b.setPadding(orig[0], orig[1], orig[2], orig[3])
                b.setSeal(0, 0f, 0, 0f)
            }
        }
    }
}
