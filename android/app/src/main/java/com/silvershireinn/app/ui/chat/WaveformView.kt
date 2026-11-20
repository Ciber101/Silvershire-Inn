package com.silvershireinn.app.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.silvershireinn.app.R
import kotlin.math.max

/**
 * Sleek WhatsApp/Telegram-style waveform scrubber.
 * Deterministic bar heights from [seed] so the same message looks stable across binds.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var heights: FloatArray = DEFAULT_HEIGHTS.copyOf()
    private var progress = 0f // 0..1
    private var dragging = false
    var onSeek: ((fraction: Float) -> Unit)? = null
    var onSeekStart: (() -> Unit)? = null
    var onSeekStop: ((fraction: Float) -> Unit)? = null

    init {
        val gold = ContextCompat.getColor(context, R.color.color_gold)
        val dim = ContextCompat.getColor(context, R.color.color_divider)
        playedPaint.color = gold
        barPaint.color = (gold and 0x00FFFFFF) or 0x55000000
        dimPaint.color = dim
        isClickable = true
    }

    fun setSeed(seed: String) {
        heights = FloatArray(BAR_COUNT) { i ->
            val h = pseudo(seed, i)
            0.22f + h * 0.78f
        }
        invalidate()
    }

    /** Real loudness levels (0..1) from the audio; resampled to the bar count. */
    fun setLevels(levels: FloatArray?) {
        if (levels == null || levels.isEmpty()) return
        heights = FloatArray(BAR_COUNT) { i ->
            val from = i * levels.size / BAR_COUNT
            val to = ((i + 1) * levels.size / BAR_COUNT).coerceAtLeast(from + 1).coerceAtMost(levels.size)
            var m = 0f; for (j in from until to) m = maxOf(m, levels[j]); m.coerceIn(0.12f, 1f)
        }
        invalidate()
    }

    fun setProgressFraction(fraction: Float) {
        if (dragging) return
        val f = fraction.coerceIn(0f, 1f)
        if (kotlin.math.abs(f - progress) > 0.001f) {
            progress = f
            invalidate()
        }
    }

    fun getProgressFraction(): Float = progress

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w / (BAR_COUNT * 2.2f)
        val barW = gap * 1.15f
        val totalBarsW = BAR_COUNT * barW + (BAR_COUNT - 1) * gap
        var x = (w - totalBarsW) / 2f
        val playedUntil = x + totalBarsW * progress
        for (i in 0 until BAR_COUNT) {
            val bh = heights[i] * h * 0.92f
            val top = (h - bh) / 2f
            val paint = if (x + barW / 2f <= playedUntil) playedPaint else dimPaint
            canvas.drawRoundRect(x, top, x + barW, top + bh, barW / 2f, barW / 2f, paint)
            x += barW + gap
        }
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var pressed = false
    private var progressBeforeDrag = 0f

    /**
     * Tap = seek there; sideways drag = scrub. A mostly vertical move is left to the list so
     * the chat still scrolls when your finger starts on a voice note (it used to grab every
     * touch, and a scroll that got cancelled also seeked and started playback).
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                pressed = true; dragging = false
                progressBeforeDrag = progress
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed) return false
                val dx = kotlin.math.abs(event.x - downX); val dy = kotlin.math.abs(event.y - downY)
                if (!dragging && dx > touchSlop && dx > dy) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onSeekStart?.invoke()
                } else if (!dragging && dy > touchSlop) {
                    pressed = false            // a scroll: let the list have it
                    return false
                }
                if (dragging) updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressed) {
                    dragging = true            // so updateFromTouch is not ignored
                    updateFromTouch(event.x)
                    dragging = false
                    onSeekStop?.invoke(progress)
                }
                pressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { progress = progressBeforeDrag; invalidate() }
                pressed = false; dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float) {
        val f = (x / max(1f, width.toFloat())).coerceIn(0f, 1f)
        progress = f
        onSeek?.invoke(f)
        invalidate()
    }

    companion object {
        private const val BAR_COUNT = 28
        private val DEFAULT_HEIGHTS = FloatArray(BAR_COUNT) { 0.35f + (it % 5) * 0.1f }

        private fun pseudo(seed: String, i: Int): Float {
            var h = 2166136261u
            for (c in seed) {
                h = (h xor c.code.toUInt()) * 16777619u
            }
            h = (h xor i.toUInt()) * 16777619u
            return ((h % 1000u).toInt()) / 1000f
        }
    }
}
