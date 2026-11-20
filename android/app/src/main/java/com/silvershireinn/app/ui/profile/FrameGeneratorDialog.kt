package com.silvershireinn.app.ui.profile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.silvershireinn.app.R
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.utils.AvatarShapeHelper
import com.silvershireinn.app.utils.FrameRenderer
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.ProfileFrames
import com.silvershireinn.app.utils.ProfileMediaHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Border generator: pick a style, Generate (every press draws a new one of a kind border),
 * then Use it and / or Save it to "My borders" so it can be picked again later. Friends see the
 * border in use; the saved collection follows the account to every device.
 */
object FrameGeneratorDialog {

    fun show(ctx: Context, onChanged: () -> Unit) {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt() }
        val gold = ContextCompat.getColor(ctx, R.color.color_gold)
        val dim = ContextCompat.getColor(ctx, R.color.color_text_secondary)
        val kinds = ProfileFrames.KINDS.keys.toList()
        var kind = "random"
        var current: String? = ProfileFrames.myFrame.takeIf { it != "none" && !it.startsWith("gen:none") }
        val shapeName = ProfileFrames.shapeName(AvatarShapeHelper.current())

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }

        // preview: own picture with the frame image around it
        val P = dp(240)
        val preview = FrameLayout(ctx)
        val picBox = FrameLayout(ctx)
        val pic = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val initials = TextView(ctx).apply {
            gravity = Gravity.CENTER; textSize = 30f; setTextColor(gold)
            text = PreferenceManager.username.take(1).uppercase()
        }
        picBox.setBackgroundColor(ContextCompat.getColor(ctx, R.color.color_surface))
        picBox.addView(initials, FrameLayout.LayoutParams(-1, -1))
        picBox.addView(pic, FrameLayout.LayoutParams(-1, -1))
        AvatarShapeHelper.applyClip(picBox, AvatarShapeHelper.current())
        val picSize = (P * ProfileFrames.PIC).toInt()
        preview.addView(picBox, FrameLayout.LayoutParams(picSize, picSize, Gravity.CENTER))
        val frameIv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
        preview.addView(frameIv, FrameLayout.LayoutParams(P, P, Gravity.CENTER))
        root.addView(preview, LinearLayout.LayoutParams(P, P).apply { gravity = Gravity.CENTER_HORIZONTAL })
        try {
            val path = PreferenceManager.profilePicturePath
            if (path.isNotBlank() && java.io.File(path).exists()) {
                val bmp = if (ProfileMediaHelper.isVideoPath(path)) ProfileMediaHelper.videoFrameBitmap(path)
                          else BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
                if (bmp != null) { pic.setImageBitmap(bmp); initials.visibility = View.GONE }
            }
        } catch (_: Throwable) {}

        val note = TextView(ctx).apply { textSize = 12f; setTextColor(dim); gravity = Gravity.CENTER }
        fun refreshPreview() {
            val id = current
            if (id == null) { frameIv.setImageDrawable(null); note.text = "Pick a style and press Generate. Every border is one of a kind."; return }
            frameIv.tag = id
            FrameRenderer.get(ctx, id, shapeName, 640, "full") { b -> if (frameIv.tag == id) frameIv.setImageBitmap(b) }
            note.text = if (id == ProfileFrames.myFrame) "This is your border now." else "Not in use yet. Use it, save it, or generate another."
        }

        // style + Generate
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val spinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, ProfileFrames.KINDS.values.toList())
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { kind = kinds[pos] }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val gen = Button(ctx).apply { text = "Generate"; isAllCaps = false }
        row1.addView(spinner, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(gen, LinearLayout.LayoutParams(-2, -2))
        root.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // Use / Save / None
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val use = Button(ctx).apply { text = "Use this"; isAllCaps = false }
        val save = Button(ctx).apply { text = "Save"; isAllCaps = false }
        val none = Button(ctx).apply { text = "No border"; isAllCaps = false }
        for (b in listOf(use, save, none)) row2.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row2, LinearLayout.LayoutParams(-1, -2))
        root.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // saved collection
        root.addView(TextView(ctx).apply { text = "My borders"; textSize = 15f; setTextColor(gold) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16); bottomMargin = dp(6) })
        val grid = GridLayout(ctx).apply { columnCount = 3 }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
        val emptyHint = TextView(ctx).apply { textSize = 12f; setTextColor(dim); text = "Borders you save show up here, so you can switch back to them any time." }
        root.addView(emptyHint, LinearLayout.LayoutParams(-1, -2))

        lateinit var renderSaved: () -> Unit
        renderSaved = {
            grid.removeAllViews()
            val list = ProfileFrames.savedFrames
            emptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            val cell = dp(86)
            for (id in list) {
                val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(2), dp(2), dp(6)) }
                val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; tag = id }
                FrameRenderer.get(ctx, id, shapeName, 256, "full") { b -> if (iv.tag == id) iv.setImageBitmap(b) }
                iv.setOnClickListener { current = id; refreshPreview() }
                iv.setOnLongClickListener {
                    MaterialAlertDialogBuilder(ctx).setTitle("Remove this border from My borders?")
                        .setPositiveButton("Remove") { _, _ -> WebSocketManager.sendSaveFrame(id, remove = true)
                            ProfileFrames.savedFrames = ProfileFrames.savedFrames - id; renderSaved() }
                        .setNegativeButton("Cancel", null).show(); true
                }
                val lbl = TextView(ctx).apply {
                    textSize = 11f; gravity = Gravity.CENTER
                    text = if (id == ProfileFrames.myFrame) "In use" else (ProfileFrames.KINDS[id.split(':').getOrNull(1)] ?: "")
                    setTextColor(if (id == ProfileFrames.myFrame) gold else dim)
                }
                box.addView(iv, LinearLayout.LayoutParams(cell, cell))
                box.addView(lbl, LinearLayout.LayoutParams(-2, -2))
                grid.addView(box, GridLayout.LayoutParams().apply {
                    width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                })
            }
        }
        renderSaved()
        refreshPreview()

        gen.setOnClickListener { current = ProfileFrames.newId(kind); refreshPreview() }
        use.setOnClickListener {
            val id = current ?: run { note.text = "Generate a border first."; return@setOnClickListener }
            ProfileFrames.myFrame = id
            WebSocketManager.sendProfileFrame(id)
            refreshPreview(); renderSaved(); onChanged()
        }
        save.setOnClickListener {
            val id = current ?: run { note.text = "Generate a border first."; return@setOnClickListener }
            if (id !in ProfileFrames.savedFrames) {
                ProfileFrames.savedFrames = listOf(id) + ProfileFrames.savedFrames
                WebSocketManager.sendSaveFrame(id)
                renderSaved()
            }
            note.text = "Saved to My borders."
        }
        none.setOnClickListener {
            ProfileFrames.myFrame = "none"
            WebSocketManager.sendProfileFrame("none")
            current = null; refreshPreview(); renderSaved(); onChanged()
        }

        val scroll = ScrollView(ctx).apply { addView(root) }
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { renderSaved() } }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Profile border")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .create()
        dialog.setOnShowListener {
            val f = IntentFilter("com.silvershireinn.FRAMES_CHANGED")
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(receiver, f)
        }
        dialog.setOnDismissListener { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {} }
        dialog.show()
    }
}
