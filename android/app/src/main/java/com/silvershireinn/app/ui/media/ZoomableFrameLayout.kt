package com.silvershireinn.app.ui.media

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * FrameLayout that pinch-zooms / pans its single child (used to wrap PlayerView).
 * Pinch zooms toward the focus point; drag pans while zoomed.
 * Double-tap left/right and single-tap are forwarded via callbacks.
 */
class ZoomableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onSingleTap: ((MotionEvent) -> Unit)? = null
    var onDoubleTap: ((MotionEvent) -> Unit)? = null

    private var scale = 1f
    private var transX = 0f
    private var transY = 0f
    private val minScale = 1f
    private val maxScale = 4f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prev = scale
            val next = (prev * detector.scaleFactor).coerceIn(minScale, maxScale)
            if (next == prev) return true
            // Keep the focus point stable in parent coords (pivot at view center).
            val focusX = detector.focusX - width / 2f
            val focusY = detector.focusY - height / 2f
            val ratio = next / prev
            transX = focusX - (focusX - transX) * ratio
            transY = focusY - (focusY - transY) * ratio
            scale = next
            if (scale <= 1.01f) {
                scale = 1f; transX = 0f; transY = 0f
            }
            clampTranslation()
            applyTransform()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke(e); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke(e); return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scale > 1.01f) {
                transX -= distanceX
                transY -= distanceY
                clampTranslation()
                applyTransform()
                return true
            }
            return false
        }
    })

    val isZoomed: Boolean get() = scale > 1.01f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.pointerCount > 1 || scale > 1.01f)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun applyTransform() {
        val child = getChildAt(0) ?: return
        child.pivotX = width / 2f
        child.pivotY = height / 2f
        child.scaleX = scale
        child.scaleY = scale
        child.translationX = transX
        child.translationY = transY
    }

    private fun clampTranslation() {
        val child = getChildAt(0) ?: return
        val maxX = max(0f, (child.width * scale - width) / 2f)
        val maxY = max(0f, (child.height * scale - height) / 2f)
        transX = min(maxX, max(-maxX, transX))
        transY = min(maxY, max(-maxY, transY))
    }

    fun resetZoom() {
        scale = 1f; transX = 0f; transY = 0f
        applyTransform()
    }
}
