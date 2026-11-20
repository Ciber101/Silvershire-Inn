package com.silvershireinn.app.ui.media

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.silvershireinn.app.R
import com.silvershireinn.app.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Photo editor: crop (free / 1:1 / 4:3 / 16:9 / 9:16), rotate, flip, filters, brightness /
 * contrast / saturation, drawing (colors, sizes, undo) and movable text.
 *
 * Returns a file:// Uri of a JPEG saved at quality 100. Only edited photos are re-encoded;
 * anything sent without editing goes out as the untouched original.
 */
class PhotoEditorActivity : AppCompatActivity() {

    private lateinit var editor: EditorView
    private lateinit var toolPanel: LinearLayout
    private lateinit var progress: ProgressBar
    private var tool = Tool.NONE
    private enum class Tool { NONE, CROP, FILTER, ADJUST, DRAW, TEXT }

    private val palette = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(),
        0xFF34C759.toInt(), 0xFF00C7BE.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(),
        0xFFD4AF37.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        // fitsSystemWindows: keep the tool bars clear of the status bar / gesture bar.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); fitsSystemWindows = true }
        editor = EditorView(this)
        root.addView(editor, FrameLayout.LayoutParams(-1, -1).apply { topMargin = (56 * d).toInt(); bottomMargin = (150 * d).toInt() })

        // Top bar: cancel / undo / reset / done
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt()); setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        }
        top.addView(iconBtn(R.drawable.ic_close, "Cancel") { finish() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(textBtn("Reset") { confirmReset() })
        top.addView(iconBtn(R.drawable.ic_undo, "Undo") { if (!editor.undo()) toast("Nothing to undo") })
        top.addView(iconBtn(R.drawable.ic_check, "Done") { save() }.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFC107.toInt()) }
            setColorFilter(0xFF1A1A1E.toInt())
        })
        root.addView(top, FrameLayout.LayoutParams(-1, (56 * d).toInt(), Gravity.TOP))

        // Bottom: tool options panel + tool row
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xCC000000.toInt()) }
        toolPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), 0) }
        bottom.addView(toolPanel, LinearLayout.LayoutParams(-1, (88 * d).toInt()))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(
            Triple(R.drawable.ic_crop, "Crop", Tool.CROP),
            Triple(R.drawable.ic_rotate_right, "Rotate", null),
            Triple(R.drawable.ic_filter, "Filters", Tool.FILTER),
            Triple(R.drawable.ic_settings, "Adjust", Tool.ADJUST),
            Triple(R.drawable.ic_brush, "Draw", Tool.DRAW),
            Triple(R.drawable.ic_text, "Text", Tool.TEXT)
        ).forEach { (icon, label, t) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                setOnClickListener { if (t == null) editor.rotate90() else selectTool(t) }
            }
            col.addView(android.widget.ImageView(this).apply { setImageResource(icon); setColorFilter(Color.WHITE) },
                LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
            col.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 11f })
            tools.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bottom.addView(tools, LinearLayout.LayoutParams(-1, (62 * d).toInt()))
        root.addView(bottom, FrameLayout.LayoutParams(-1, (150 * d).toInt(), Gravity.BOTTOM))

        progress = ProgressBar(this).apply { visibility = View.VISIBLE }
        root.addView(progress, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)

        val src = intent.data ?: run { toast("No photo"); finish(); return }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { loadBitmap(src) }
            progress.visibility = View.GONE
            if (bmp == null) { toast("Could not open photo"); finish(); return@launch }
            editor.setImage(bmp)
            selectTool(Tool.NONE)
        }
    }

    /** Decode capped by available memory (cheap phones get a smaller working copy), EXIF-upright. */
    private fun loadBitmap(uri: Uri): Bitmap? = try {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val cap = when {
            am.isLowRamDevice || Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024 -> 2560
            Runtime.getRuntime().maxMemory() < 400L * 1024 * 1024 -> 4096
            else -> 6000
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > cap) sample *= 2
        val raw = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true })
        }
        val rot = try { contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 } catch (_: Throwable) { 0 }
        when {
            raw == null -> null
            rot == 0 -> raw
            else -> Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                .also { if (it !== raw) raw.recycle() }
        }
    } catch (_: Throwable) { null }

    private fun confirmReset() {
        AlertDialog.Builder(this).setMessage("Undo all edits?")
            .setPositiveButton("Reset") { _, _ ->
                progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { intent.data?.let { loadBitmap(it) } }
                    progress.visibility = View.GONE
                    if (bmp != null) { editor.setImage(bmp); selectTool(Tool.NONE) }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private var saving = false

    private fun save() {
        if (!editor.hasImage() || saving) return   // a second tap used to start a second save
        saving = true
        if (tool == Tool.CROP) editor.applyCrop()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            // The final image is put together here on the main thread (the editor's drawing
            // state isn't safe to read from another thread); only the JPEG writing is in the
            // background. With no drawings / text / filter it's saved without an extra copy,
            // which is what ran cheap phones out of memory.
            val out = try { editor.finalImage() } catch (_: Throwable) { null }
            val file = if (out == null) null else withContext(Dispatchers.IO) {
                try {
                    val f = File(cacheDir, "edited_${System.currentTimeMillis()}.jpg")
                    f.outputStream().use { out.first.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                    if (out.second) out.first.recycle()
                    f
                } catch (_: Throwable) { null }
            }
            saving = false
            progress.visibility = View.GONE
            if (file == null) { toast("Could not save, try a smaller edit"); return@launch }
            setResult(RESULT_OK, Intent().setData(Uri.fromFile(file)))
            finish()
        }
    }

    // ── Tool panels ───────────────────────────────────────────────────────────
    private fun selectTool(t: Tool) {
        if (tool == Tool.CROP && t != Tool.CROP) editor.applyCrop()
        tool = if (tool == t && t != Tool.NONE) Tool.NONE else t
        editor.mode = tool.name
        toolPanel.removeAllViews()
        when (tool) {
            Tool.CROP -> {
                editor.startCrop()
                chips(listOf("Free" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f), 0f) { editor.setCropAspect(it) }
                toolPanel.addView(row(textBtn("Flip") { editor.flipH() }, textBtn("Apply crop") { editor.applyCrop(); selectTool(Tool.NONE) }))
            }
            Tool.FILTER -> chips(EditorView.FILTERS.mapIndexed { i, n -> n to i }, editor.filter) { editor.filter = it; editor.invalidate() }
            Tool.ADJUST -> {
                slider("Brightness", editor.brightness) { editor.brightness = it; editor.invalidate() }
                slider("Contrast", editor.contrast) { editor.contrast = it; editor.invalidate() }
                slider("Saturation", editor.saturation) { editor.saturation = it; editor.invalidate() }
            }
            Tool.DRAW -> {
                colorRow(editor.brushColor) { editor.brushColor = it }
                slider("Size", editor.brushSize) { editor.brushSize = it }
            }
            Tool.TEXT -> {
                colorRow(editor.textColor) { editor.textColor = it; editor.recolorSelectedText(it) }
                toolPanel.addView(row(textBtn("Add text") { promptText(null) },
                    TextView(this).apply { text = "Drag to move, tap to edit"; setTextColor(0x99FFFFFF.toInt()); textSize = 11f }))
                editor.onTextTapped = { idx -> promptText(idx) }
            }
            Tool.NONE -> toolPanel.addView(TextView(this).apply {
                text = "Pick a tool below. Photos you don't edit are sent untouched at full quality."
                setTextColor(0x99FFFFFF.toInt()); textSize = 12f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -1))
        }
    }

    private fun promptText(index: Int?) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(index?.let { editor.textAt(it) } ?: "")
            setSelection(text.length)
        }
        val b = AlertDialog.Builder(this).setTitle(if (index == null) "Add text" else "Edit text").setView(et)
            .setPositiveButton("OK") { _, _ ->
                val s = et.text.toString()
                if (index == null) { if (s.isNotBlank()) editor.addText(s) } else editor.updateText(index, s)
            }
            .setNegativeButton("Cancel", null)
        if (index != null) b.setNeutralButton("Delete") { _, _ -> editor.updateText(index, "") }
        b.show()
    }

    private fun <T> chips(items: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
        val d = resources.displayMetrics.density
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun paint(sel: T) {
            for (i in 0 until row.childCount) {
                val tv = row.getChildAt(i) as TextView
                val on = items[i].second == sel
                tv.setTextColor(if (on) 0xFF1A1A1E.toInt() else Color.WHITE)
                (tv.background as GradientDrawable).setColor(if (on) 0xFFFFC107.toInt() else 0x33FFFFFF)
            }
        }
        items.forEach { (label, v) ->
            row.addView(TextView(this).apply {
                text = label; textSize = 13f
                setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 16 * d }
                setOnClickListener { onPick(v); paint(v) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * d).toInt() })
        }
        paint(selected)
        hs.addView(row)
        toolPanel.addView(hs)
    }

    private fun colorRow(selected: Int, onPick: (Int) -> Unit) {
        val d = resources.displayMetrics.density
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun paint(sel: Int) {
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i)
                (v.background as GradientDrawable).setStroke((if (palette[i] == sel) 3 else 1) * d.toInt().coerceAtLeast(1),
                    if (palette[i] == sel) 0xFFFFC107.toInt() else 0x66FFFFFF)
            }
        }
        palette.forEach { c ->
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener { onPick(c); paint(c) }
            }, LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
        }
        paint(selected)
        hs.addView(row)
        toolPanel.addView(hs)
    }

    /** value range 0..200, 100 = neutral. */
    private fun slider(label: String, value: Int, onChange: (Int) -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 11f },
            LinearLayout.LayoutParams((78 * resources.displayMetrics.density).toInt(), -2))
        r.addView(SeekBar(this).apply {
            max = 200; progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolPanel.addView(r)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (12 * resources.displayMetrics.density).toInt() }) }
    }

    private fun iconBtn(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); contentDescription = desc
        setBackgroundResource(android.R.color.transparent)
        setColorFilter(Color.WHITE)
        val s = (44 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s)
        setOnClickListener { onClick() }
    }

    private fun textBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(0xFFFFD54F.toInt()); textSize = 14f
        val p = (10 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
        setOnClickListener { onClick() }
    }

    // ═════════════════════════════════════════════════════════════════════════
    /** Canvas that shows the photo and collects edits in image coordinates. */
    @SuppressLint("ViewConstructor")
    class EditorView(ctx: Context) : View(ctx) {
        companion object {
            val FILTERS = listOf("Original", "Vivid", "Warm", "Cool", "B&W", "Noir", "Sepia", "Fade")
        }

        private var bmp: Bitmap? = null
        var mode = "NONE"
        var filter = 0
        var brightness = 100
        var contrast = 100
        var saturation = 100
        var brushColor = 0xFFFF3B30.toInt()
        var brushSize = 60
        var textColor = 0xFFFFFFFF.toInt()
        var onTextTapped: ((Int) -> Unit)? = null

        private class Stroke(val path: Path, val color: Int, val width: Float)
        private class TextItem(var text: String, var x: Float, var y: Float, var color: Int, val size: Float)
        private val strokes = mutableListOf<Stroke>()
        private val texts = mutableListOf<TextItem>()
        private val history = mutableListOf<Any>()   // undo order
        private var current: Stroke? = null
        private var selectedText = -1

        private val toView = Matrix()
        private val toImage = Matrix()
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
        }

        // crop (image coords)
        private val crop = RectF()
        private var cropAspect = 0f
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }

        fun hasImage() = bmp != null

        fun setImage(b: Bitmap) {
            bmp?.takeIf { it !== b }?.recycle()
            bmp = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }
            strokes.clear(); texts.clear(); history.clear()
            filter = 0; brightness = 100; contrast = 100; saturation = 100
            requestLayout(); computeMatrix(); invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { computeMatrix() }

        private fun computeMatrix() {
            val b = bmp ?: return
            if (width == 0 || height == 0) return
            val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
            toView.reset()
            toView.postScale(s, s)
            toView.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
            toView.invert(toImage)
        }

        private fun scale(): Float { val v = FloatArray(9); toView.getValues(v); return v[Matrix.MSCALE_X] }

        private fun colorFilter(): ColorMatrixColorFilter? {
            if (filter == 0 && brightness == 100 && contrast == 100 && saturation == 100) return null
            val cm = ColorMatrix()
            when (FILTERS[filter]) {
                "Vivid" -> cm.setSaturation(1.45f)
                "Warm"  -> cm.set(floatArrayOf(1.1f,0f,0f,0f,12f, 0f,1.02f,0f,0f,4f, 0f,0f,0.88f,0f,-8f, 0f,0f,0f,1f,0f))
                "Cool"  -> cm.set(floatArrayOf(0.9f,0f,0f,0f,-6f, 0f,1.0f,0f,0f,2f, 0f,0f,1.12f,0f,14f, 0f,0f,0f,1f,0f))
                "B&W"   -> cm.setSaturation(0f)
                "Noir"  -> { cm.setSaturation(0f); cm.postConcat(contrastMatrix(1.5f)) }
                "Sepia" -> { cm.setSaturation(0f); cm.postConcat(ColorMatrix(floatArrayOf(1f,0f,0f,0f,40f, 0f,1f,0f,0f,20f, 0f,0f,1f,0f,-12f, 0f,0f,0f,1f,0f))) }
                "Fade"  -> { cm.setSaturation(0.75f); cm.postConcat(ColorMatrix(floatArrayOf(0.85f,0f,0f,0f,30f, 0f,0.85f,0f,0f,30f, 0f,0f,0.85f,0f,30f, 0f,0f,0f,1f,0f))) }
            }
            if (saturation != 100) cm.postConcat(ColorMatrix().apply { setSaturation(saturation / 100f) })
            if (contrast != 100) cm.postConcat(contrastMatrix(contrast / 100f))
            if (brightness != 100) {
                val off = (brightness - 100) * 1.2f
                cm.postConcat(ColorMatrix(floatArrayOf(1f,0f,0f,0f,off, 0f,1f,0f,0f,off, 0f,0f,1f,0f,off, 0f,0f,0f,1f,0f)))
            }
            return ColorMatrixColorFilter(cm)
        }

        private fun contrastMatrix(c: Float): ColorMatrix {
            val t = (1f - c) * 128f
            return ColorMatrix(floatArrayOf(c,0f,0f,0f,t, 0f,c,0f,0f,t, 0f,0f,c,0f,t, 0f,0f,0f,1f,0f))
        }

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            bmpPaint.colorFilter = colorFilter()
            canvas.drawBitmap(b, toView, bmpPaint)
            canvas.save()
            canvas.concat(toView)
            drawOverlays(canvas)
            canvas.restore()
            if (mode == "CROP") drawCrop(canvas)
        }

        private fun drawOverlays(c: Canvas) {
            strokes.forEach { s -> strokePaint.color = s.color; strokePaint.strokeWidth = s.width; c.drawPath(s.path, strokePaint) }
            texts.forEachIndexed { i, t ->
                textPaint.color = t.color; textPaint.textSize = t.size
                val lines = t.text.split("\n")
                lines.forEachIndexed { li, line ->
                    val w = textPaint.measureText(line)
                    c.drawText(line, t.x - w / 2f, t.y + li * t.size * 1.15f, textPaint)
                }
                if (i == selectedText && mode == "TEXT") {
                    val r = textBounds(t)
                    c.drawRect(r, Paint().apply { style = Paint.Style.STROKE; color = 0xAAFFC107.toInt(); strokeWidth = t.size / 20f })
                }
            }
        }

        private fun textBounds(t: TextItem): RectF {
            textPaint.textSize = t.size
            val lines = t.text.split("\n")
            val w = lines.maxOf { textPaint.measureText(it) }
            return RectF(t.x - w / 2f - t.size * 0.2f, t.y - t.size, t.x + w / 2f + t.size * 0.2f, t.y + (lines.size - 1) * t.size * 1.15f + t.size * 0.3f)
        }

        private fun drawCrop(c: Canvas) {
            val r = RectF(crop); toView.mapRect(r)
            c.drawRect(0f, 0f, width.toFloat(), r.top, dim)
            c.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dim)
            c.drawRect(0f, r.top, r.left, r.bottom, dim)
            c.drawRect(r.right, r.top, width.toFloat(), r.bottom, dim)
            c.drawRect(r, frame)
            val gw = r.width() / 3f; val gh = r.height() / 3f
            val g = Paint().apply { color = 0x55FFFFFF; strokeWidth = 1.5f }
            for (i in 1..2) { c.drawLine(r.left + gw * i, r.top, r.left + gw * i, r.bottom, g); c.drawLine(r.left, r.top + gh * i, r.right, r.top + gh * i, g) }
            val hr = 14f * resources.displayMetrics.density
            listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).forEach { (x, y) -> c.drawCircle(x, y, hr / 1.6f, handle) }
        }

        // ── Edits ─────────────────────────────────────────────────────────────
        fun undo(): Boolean {
            val last = history.removeLastOrNull() ?: return false
            strokes.remove(last); texts.remove(last)
            selectedText = -1
            invalidate(); return true
        }

        fun addText(s: String) {
            val b = bmp ?: return
            val t = TextItem(s, b.width / 2f, b.height / 2f, textColor, max(b.width, b.height) / 14f)
            texts.add(t); history.add(t); selectedText = texts.lastIndex; invalidate()
        }
        fun textAt(i: Int) = texts.getOrNull(i)?.text ?: ""
        fun updateText(i: Int, s: String) {
            val t = texts.getOrNull(i) ?: return
            if (s.isBlank()) { texts.removeAt(i); history.remove(t); selectedText = -1 } else t.text = s
            invalidate()
        }
        fun recolorSelectedText(c: Int) { texts.getOrNull(selectedText)?.color = c; invalidate() }

        /** Bake drawings/text/filters into the bitmap (before geometry changes). */
        private fun flatten() {
            val b = bmp ?: return
            if (strokes.isEmpty() && texts.isEmpty() && colorFilter() == null) return
            val out = render()
            if (out !== b) { b.recycle(); bmp = out }
            strokes.clear(); texts.clear(); history.clear()
            filter = 0; brightness = 100; contrast = 100; saturation = 100
        }

        fun rotate90() {
            val b0 = bmp ?: return
            flatten()
            val b = bmp ?: b0
            try {
                val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)
                if (r !== b) b.recycle()
                bmp = if (r.isMutable) r else r.copy(Bitmap.Config.ARGB_8888, true).also { r.recycle() }
            } catch (_: Throwable) { }
            computeMatrix(); if (mode == "CROP") startCrop(); invalidate()
        }

        fun flipH() {
            val b0 = bmp ?: return
            flatten()
            val b = bmp ?: b0
            try {
                val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postScale(-1f, 1f) }, true)
                if (r !== b) b.recycle()
                bmp = if (r.isMutable) r else r.copy(Bitmap.Config.ARGB_8888, true).also { r.recycle() }
            } catch (_: Throwable) { }
            invalidate()
        }

        fun startCrop() {
            val b = bmp ?: return
            crop.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
            if (cropAspect > 0f) fitAspect()
            invalidate()
        }

        fun setCropAspect(a: Float) { cropAspect = a; startCrop() }

        private fun fitAspect() {
            val b = bmp ?: return
            var w = b.width.toFloat(); var h = w / cropAspect
            if (h > b.height) { h = b.height.toFloat(); w = h * cropAspect }
            crop.set((b.width - w) / 2f, (b.height - h) / 2f, (b.width + w) / 2f, (b.height + h) / 2f)
        }

        fun applyCrop() {
            val b0 = bmp ?: return
            if (crop.isEmpty || (crop.left <= 0.5f && crop.top <= 0.5f && crop.right >= b0.width - 0.5f && crop.bottom >= b0.height - 0.5f)) return
            flatten()
            val b = bmp ?: b0
            try {
                val l = crop.left.toInt().coerceIn(0, b.width - 1); val t = crop.top.toInt().coerceIn(0, b.height - 1)
                val w = crop.width().toInt().coerceIn(1, b.width - l); val h = crop.height().toInt().coerceIn(1, b.height - t)
                val r = Bitmap.createBitmap(b, l, t, w, h)
                if (r !== b) b.recycle()
                bmp = if (r.isMutable) r else r.copy(Bitmap.Config.ARGB_8888, true).also { r.recycle() }
            } catch (_: Throwable) { }
            crop.setEmpty()
            computeMatrix(); invalidate()
        }

        /** (image, isACopy): the bitmap itself when there is nothing to draw on top of it. */
        fun finalImage(): Pair<Bitmap, Boolean> {
            val b = bmp ?: throw IllegalStateException()
            return if (strokes.isEmpty() && texts.isEmpty() && colorFilter() == null) b to false else render() to true
        }

        /** Final image with every edit applied. */
        fun render(): Bitmap {
            val b = bmp ?: throw IllegalStateException()
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = colorFilter() })
            val wasMode = mode; mode = "EXPORT"; val sel = selectedText; selectedText = -1
            drawOverlays(c)
            mode = wasMode; selectedText = sel
            return out
        }

        // ── Touch ─────────────────────────────────────────────────────────────
        private var dragCorner = -1
        private var dragInside = false
        private var lastX = 0f; private var lastY = 0f
        private var downX = 0f; private var downY = 0f
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bmp == null) return false
            val p = floatArrayOf(e.x, e.y); toImage.mapPoints(p)
            val ix = p[0]; val iy = p[1]
            when (mode) {
                "DRAW" -> when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val w = (brushSize.coerceAtLeast(5) / 100f) * 8f * resources.displayMetrics.density / scale()
                        current = Stroke(Path().apply { moveTo(ix, iy) }, brushColor, w).also { strokes.add(it); history.add(it) }
                    }
                    MotionEvent.ACTION_MOVE -> { current?.path?.lineTo(ix, iy); invalidate() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { current?.path?.lineTo(ix + 0.1f, iy + 0.1f); current = null; invalidate() }
                }
                "TEXT" -> when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x; downY = e.y; moved = false; lastX = ix; lastY = iy
                        selectedText = texts.indexOfLast { textBounds(it).contains(ix, iy) }
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.x - downX) + abs(e.y - downY) > 12) moved = true
                        texts.getOrNull(selectedText)?.let { it.x += ix - lastX; it.y += iy - lastY; invalidate() }
                        lastX = ix; lastY = iy
                    }
                    MotionEvent.ACTION_UP -> if (!moved && selectedText >= 0) onTextTapped?.invoke(selectedText)
                }
                "CROP" -> handleCropTouch(e, ix, iy)
                else -> return false
            }
            return true
        }

        private fun handleCropTouch(e: MotionEvent, ix: Float, iy: Float) {
            val b = bmp ?: return
            val tol = 28f * resources.displayMetrics.density / scale()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val corners = listOf(crop.left to crop.top, crop.right to crop.top, crop.left to crop.bottom, crop.right to crop.bottom)
                    dragCorner = corners.indexOfFirst { (x, y) -> abs(x - ix) < tol && abs(y - iy) < tol }
                    dragInside = dragCorner < 0 && crop.contains(ix, iy)
                    lastX = ix; lastY = iy
                }
                MotionEvent.ACTION_MOVE -> {
                    val minSide = 64f
                    if (dragCorner >= 0) {
                        when (dragCorner) {
                            0 -> { crop.left = ix.coerceIn(0f, crop.right - minSide); crop.top = iy.coerceIn(0f, crop.bottom - minSide) }
                            1 -> { crop.right = ix.coerceIn(crop.left + minSide, b.width.toFloat()); crop.top = iy.coerceIn(0f, crop.bottom - minSide) }
                            2 -> { crop.left = ix.coerceIn(0f, crop.right - minSide); crop.bottom = iy.coerceIn(crop.top + minSide, b.height.toFloat()) }
                            3 -> { crop.right = ix.coerceIn(crop.left + minSide, b.width.toFloat()); crop.bottom = iy.coerceIn(crop.top + minSide, b.height.toFloat()) }
                        }
                        if (cropAspect > 0f) {
                            // keep the ratio: adjust height from width, anchored at the opposite corner
                            val h = crop.width() / cropAspect
                            if (dragCorner <= 1) crop.top = (crop.bottom - h).coerceAtLeast(0f) else crop.bottom = (crop.top + h).coerceAtMost(b.height.toFloat())
                            val w = crop.height() * cropAspect
                            if (dragCorner == 0 || dragCorner == 2) crop.left = crop.right - w else crop.right = crop.left + w
                        }
                    } else if (dragInside) {
                        val dx = (ix - lastX).coerceIn(-crop.left, b.width - crop.right)
                        val dy = (iy - lastY).coerceIn(-crop.top, b.height - crop.bottom)
                        crop.offset(dx, dy)
                    }
                    lastX = ix; lastY = iy
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragCorner = -1; dragInside = false }
            }
        }
    }

    companion object {
        fun intent(ctx: Context, src: Uri): Intent =
            Intent(ctx, PhotoEditorActivity::class.java).apply {
                data = src
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }
}
