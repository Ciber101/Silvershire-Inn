package com.silvershireinn.app.ui.media

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView with pinch-to-zoom and double-tap-to-zoom.
 *
 * Features:
 *  • Pinch to zoom (min = fit-center scale, max 6×)
 *  • Double-tap toggles between fit and 2.5× at tap point
 *  • Pan / drag while zoomed, clamped to image bounds
 *  • Fling with momentum after panning
 *  • [onSingleTap] callback — invoked on confirmed single-tap (use to toggle toolbar)
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val mMatrix = Matrix()
    private val vals   = FloatArray(9)

    private var scale    = 1f
    /** Fit-center scale — updated every time the image or view size changes. */
    private var minScale = 1f
    private val maxScale = 6f

    /** Optional callback invoked on a confirmed single tap (toolbar show/hide). */
    var onSingleTap: (() -> Unit)? = null

    private var viewW = 0; private var viewH = 0
    private var drawW = 0f; private var drawH = 0f

    // For fling
    private val scroller = OverScroller(context, DecelerateInterpolator())
    private var flingRunnable: FlingRunnable? = null

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    // ── Sizing ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        viewW = w; viewH = h
        fitImage()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        if (drawable != null) {
            drawW = drawable.intrinsicWidth.toFloat()
            drawH = drawable.intrinsicHeight.toFloat()
            if (viewW > 0) fitImage()
        }
    }

    /** Reset to fit-center. Updates [minScale] so zoom clamping is always relative to fit size. */
    private fun fitImage() {
        if (drawW <= 0 || drawH <= 0 || viewW <= 0 || viewH <= 0) return
        scale    = minOf(viewW / drawW, viewH / drawH)
        minScale = scale   // ← dynamic: large images get minScale < 1, small images > 1
        val tx = (viewW - drawW * scale) / 2f
        val ty = (viewH - drawH * scale) / 2f
        mMatrix.setScale(scale, scale)
        mMatrix.postTranslate(tx, ty)
        imageMatrix = mMatrix
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Only keep the touch when zoomed or pinching, so a pager can swipe to the next photo.
        parent?.requestDisallowInterceptTouchEvent(ev.pointerCount > 1 || curScale() > minScale + 0.05f)
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Current translation from the matrix. */
    private fun tx(): Float { mMatrix.getValues(vals); return vals[Matrix.MTRANS_X] }
    private fun ty(): Float { mMatrix.getValues(vals); return vals[Matrix.MTRANS_Y] }
    private fun curScale(): Float { mMatrix.getValues(vals); return vals[Matrix.MSCALE_X] }

    /** Clamp translation so image can't be panned off-screen. */
    private fun clamp() {
        mMatrix.getValues(vals)
        var tx = vals[Matrix.MTRANS_X]
        var ty = vals[Matrix.MTRANS_Y]
        val s  = vals[Matrix.MSCALE_X]

        val scaledW = drawW * s
        val scaledH = drawH * s

        tx = if (scaledW <= viewW) (viewW - scaledW) / 2f
             else tx.coerceIn(viewW - scaledW, 0f)
        ty = if (scaledH <= viewH) (viewH - scaledH) / 2f
             else ty.coerceIn(viewH - scaledH, 0f)

        vals[Matrix.MTRANS_X] = tx
        vals[Matrix.MTRANS_Y] = ty
        mMatrix.setValues(vals)
        imageMatrix = mMatrix
    }

    /** Animate toward a target matrix. */
    private fun animateTo(targetScale: Float, focusX: Float, focusY: Float) {
        val startScale = curScale()
        val startTx    = tx()
        val startTy    = ty()

        // Where should (focusX, focusY) map to after scaling?
        // We want the pixel under the focus point to stay put.
        val factor = targetScale / startScale
        val endTx  = focusX - factor * (focusX - startTx)
        val endTy  = focusY - factor * (focusY - startTy)

        val startMs = android.os.SystemClock.uptimeMillis()
        val durMs = 220f
        postOnAnimation(object : Runnable {
            override fun run() {
                val t = ((android.os.SystemClock.uptimeMillis() - startMs) / durMs).coerceIn(0f, 1f)
                if (t >= 1f) {
                    // final snap
                    mMatrix.getValues(vals)
                    vals[Matrix.MSCALE_X] = targetScale
                    vals[Matrix.MSCALE_Y] = targetScale
                    vals[Matrix.MTRANS_X] = endTx
                    vals[Matrix.MTRANS_Y] = endTy
                    mMatrix.setValues(vals)
                    clamp()
                    return
                }
                val ease = 1f - (1f - t) * (1f - t)  // ease-out quad, timed (smooth at 60 or 120 Hz)
                val s = startScale + (targetScale - startScale) * ease
                val tx = startTx + (endTx - startTx) * ease
                val ty = startTy + (endTy - startTy) * ease
                mMatrix.getValues(vals)
                vals[Matrix.MSCALE_X] = s
                vals[Matrix.MSCALE_Y] = s
                vals[Matrix.MTRANS_X] = tx
                vals[Matrix.MTRANS_Y] = ty
                mMatrix.setValues(vals)
                imageMatrix = mMatrix
                postOnAnimation(this)          // in step with the screen's refresh (not a fixed 16 ms)
            }
        })
    }

    // ── Scale gesture ─────────────────────────────────────────────────────────

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val s = curScale()
            var factor = det.scaleFactor
            val newScale = (s * factor).coerceIn(minScale, maxScale)
            factor = newScale / s

            mMatrix.postScale(factor, factor, det.focusX, det.focusY)
            clamp()
            return true
        }
    }

    // ── Gesture listener ──────────────────────────────────────────────────────

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val s = curScale()
            val target = if (s > minScale * 1.5f) {
                minScale   // already zoomed — reset all the way to fit
            } else {
                minScale * 2.5f   // zoom in to 2.5× fit size
            }
            // When zooming out to fit, use the view center as focus so the image
            // always snaps to center cleanly; when zooming in, use the tap point.
            val focusX = if (target <= minScale) viewW / 2f else e.x
            val focusY = if (target <= minScale) viewH / 2f else e.y
            animateTo(target.coerceIn(minScale, maxScale), focusX, focusY)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (curScale() > minScale + 0.05f) {
                mMatrix.postTranslate(-dx, -dy)
                clamp()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (curScale() <= minScale + 0.05f) return false
            flingRunnable?.cancel()
            val fling = FlingRunnable(tx().toInt(), ty().toInt(), vx.toInt(), vy.toInt())
            flingRunnable = fling
            post(fling)
            return true
        }

        override fun onDown(e: MotionEvent) = true
    }

    // ── Fling ─────────────────────────────────────────────────────────────────

    inner class FlingRunnable(startX: Int, startY: Int, vx: Int, vy: Int) : Runnable {
        private var cancelled = false

        init {
            val s = curScale()
            val scaledW = (drawW * s).toInt()
            val scaledH = (drawH * s).toInt()
            val minX = if (scaledW > viewW) viewW - scaledW else 0
            val minY = if (scaledH > viewH) viewH - scaledH else 0
            scroller.fling(startX, startY, vx, vy, minX, 0, minY, 0)
        }

        fun cancel() { cancelled = true; scroller.forceFinished(true) }

        override fun run() {
            if (cancelled || !scroller.computeScrollOffset()) return
            val nx = scroller.currX.toFloat()
            val ny = scroller.currY.toFloat()
            val dx = nx - tx()
            val dy = ny - ty()
            mMatrix.postTranslate(dx, dy)
            clamp()
            postOnAnimation(this)
        }
    }

    fun copyDisplayMatrix(): Matrix {
        val out = Matrix()
        out.set(mMatrix)
        return out
    }
}
