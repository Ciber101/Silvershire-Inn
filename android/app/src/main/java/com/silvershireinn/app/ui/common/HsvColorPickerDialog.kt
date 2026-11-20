package com.silvershireinn.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.silvershireinn.app.R
import kotlin.math.*

/**
 * Full-spectrum HSV color picker dialog.
 *
 * Usage:
 *   HsvColorPickerDialog(context, initialColor) { selectedColor ->
 *       // use selectedColor (ARGB int)
 *   }.show()
 */
class HsvColorPickerDialog(
    context: Context,
    initialColor: Int,
    private val onColorSelected: (Int) -> Unit
) {
    private val dialog: AlertDialog

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_hsv_color_picker, null)

        val hsvWheel   = view.findViewById<HsvWheelView>(R.id.hsvWheel)
        val sbBright   = view.findViewById<SeekBar>(R.id.sbBrightness)
        val tvPreview  = view.findViewById<View>(R.id.viewColorPreview)
        val tvHex      = view.findViewById<TextView>(R.id.tvHexValue)

        // Initialise from the provided color
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)

        hsvWheel.setHsv(hsv[0], hsv[1])
        sbBright.progress = (hsv[2] * 100).toInt()

        fun updatePreview() {
            val h = hsvWheel.hue
            val s = hsvWheel.saturation
            val v = sbBright.progress / 100f
            val color = Color.HSVToColor(floatArrayOf(h, s, v))
            tvPreview.setBackgroundColor(color)
            tvHex.text = String.format("#%06X", color and 0xFFFFFF)
        }

        hsvWheel.onHsvChanged = { updatePreview() }
        // Tap the code to type an exact color (e.g. #D4AF37).
        tvHex.setOnClickListener {
            val et = android.widget.EditText(context).apply {
                setText(tvHex.text); setSelection(text.length)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(android.text.InputFilter.LengthFilter(7))
            }
            MaterialAlertDialogBuilder(context)
                .setTitle("Color code")
                .setView(android.widget.FrameLayout(context).apply {
                    val pad = (20 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, 0); addView(et)
                })
                .setPositiveButton("Use") { _, _ ->
                    val raw = et.text.toString().trim().removePrefix("#")
                    val c = try { Color.parseColor("#" + raw) } catch (_: Exception) { null }
                    if (c != null) {
                        val x = FloatArray(3); Color.colorToHSV(c, x)
                        hsvWheel.setHsv(x[0], x[1]); sbBright.progress = (x[2] * 100).toInt()
                        updatePreview()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        sbBright.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = updatePreview()
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        updatePreview()

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Pick a color")
            .setView(view)
            .setPositiveButton("OK") { _, _ ->
                val h = hsvWheel.hue
                val s = hsvWheel.saturation
                val v = sbBright.progress / 100f
                onColorSelected(Color.HSVToColor(floatArrayOf(h, s, v)))
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    fun show() = dialog.show()
}

/**
 * Custom view that renders an HSV color wheel (hue around the ring,
 * saturation from centre to edge) and lets the user tap/drag to pick H+S.
 */
class HsvWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var hue: Float = 0f
        private set
    var saturation: Float = 1f
        private set

    var onHsvChanged: (() -> Unit)? = null

    private val huePaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.BLACK
        alpha = 100
    }

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = minOf(cx, cy) - 4f
        buildHueShader()
        buildSatShader()
    }

    private fun buildHueShader() {
        val colors = IntArray(361) { i -> Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f)) }
        huePaint.shader = SweepGradient(cx, cy, colors, null)
    }

    private fun buildSatShader() {
        satPaint.shader = RadialGradient(
            cx, cy, radius,
            Color.WHITE, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        // Draw hue ring
        canvas.drawCircle(cx, cy, radius, huePaint)
        // Draw saturation overlay (white in centre → transparent at edge)
        canvas.drawCircle(cx, cy, radius, satPaint)

        // Draw thumb
        val (tx, ty) = hsvToXy(hue, saturation)
        // Thumb sized for the screen density (it was a tiny 12 px dot on sharp screens).
        val r = 10f * resources.displayMetrics.density
        canvas.drawCircle(tx, ty, r, thumbShadow)
        canvas.drawCircle(tx, ty, r, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Keep the drag on the wheel (a scrolling dialog used to steal it mid-pick).
        if (event.action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)
            parent?.requestDisallowInterceptTouchEvent(false)
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val dx = event.x - cx
            val dy = event.y - cy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= radius + 20f) {
                hue = (atan2(dy, dx) * 180f / PI.toFloat() + 360f) % 360f
                saturation = (dist / radius).coerceIn(0f, 1f)
                invalidate()
                onHsvChanged?.invoke()
            }
        }
        return true
    }

    fun setHsv(h: Float, s: Float) {
        hue = h; saturation = s; invalidate()
    }

    private fun hsvToXy(h: Float, s: Float): Pair<Float, Float> {
        val angle = h * PI.toFloat() / 180f
        return Pair(cx + cos(angle) * s * radius, cy + sin(angle) * s * radius)
    }
}
