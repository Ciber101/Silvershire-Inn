package com.silvershireinn.app.ui.profile

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.load
import com.silvershireinn.app.R
import com.silvershireinn.app.ui.chat.SealBubble
import com.silvershireinn.app.utils.AvatarShapeHelper
import com.silvershireinn.app.utils.OrnateTheme
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.ProfileFrames
import com.silvershireinn.app.utils.ProfileMediaHelper
import com.silvershireinn.app.utils.toAvatarColor
import com.silvershireinn.app.utils.toInitials

/**
 * Picture sizes: how big pictures (and their borders) are in the Chats list and on message
 * bubbles. Each slider has a live preview with your own picture and border.
 */
object PictureSizeDialog {

    fun show(a: FragmentActivity) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val me = PreferenceManager.username

        fun picture(sizePx: Int): FrameLayout = FrameLayout(a).apply {
            val shape = AvatarShapeHelper.forUser(me)
            background = AvatarShapeHelper.backgroundDrawable(me.toAvatarColor(), shape)
            AvatarShapeHelper.applyClip(this, shape)
            addView(TextView(a).apply {
                text = PreferenceManager.displayNameFor(me).toInitials(); gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(-1, -1))
            val f = ProfileMediaHelper.findOwnProfilePic(a)
            if (f != null && f.exists()) {
                val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(iv, FrameLayout.LayoutParams(-1, -1))
                if (ProfileMediaHelper.isVideoPath(f.absolutePath)) {
                    Thread { ProfileMediaHelper.videoFrameBitmap(f.absolutePath)?.let { b -> iv.post { iv.setImageBitmap(b) } } }.start()
                } else iv.load(f)
            }
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }

        fun label(t: String, sub: String) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(4))
            addView(TextView(a).apply { text = t; textSize = 16f; setTextColor(ContextCompat.getColor(a, R.color.color_text_primary)) })
            addView(TextView(a).apply { text = sub; textSize = 12f; setTextColor(ContextCompat.getColor(a, R.color.color_text_secondary)) })
        }

        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(8)) }

        // ── Chats list ──
        root.addView(label("Chats list", "Pictures in your list of chats"))
        val listRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10)); clipChildren = false; clipToPadding = false
        }
        val listBox = FrameLayout(a).apply { clipChildren = false }
        var listPic = picture(dp(PreferenceManager.listAvatarSize))
        listBox.addView(listPic)
        listRow.addView(listBox, LinearLayout.LayoutParams(dp(PreferenceManager.listAvatarSize), dp(PreferenceManager.listAvatarSize)))
        listRow.addView(TextView(a).apply {
            text = PreferenceManager.displayNameFor(me); textSize = 16f
            setTextColor(ContextCompat.getColor(a, R.color.color_text_primary)); setPadding(dp(14), 0, 0, 0)
        })
        root.addView(listRow, LinearLayout.LayoutParams(-1, -2))
        fun refreshList() {
            val px = dp(PreferenceManager.listAvatarSize)
            listBox.layoutParams = (listBox.layoutParams as LinearLayout.LayoutParams).apply { width = px; height = px }
            listPic.layoutParams = FrameLayout.LayoutParams(px, px)
            ProfileFrames.applyFloating(listPic, me)
            listRow.invalidate()
        }
        root.addView(SeekBar(a).apply {
            max = 120 - 48; progress = PreferenceManager.listAvatarSize - 48
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { PreferenceManager.listAvatarSize = p + 48; refreshList() } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        // ── Messages ──
        root.addView(label("On messages", "Pictures set into the corner of message bubbles"))
        val msgRow = FrameLayout(a).apply { setPadding(dp(10), dp(4), dp(48), dp(8)); clipChildren = false; clipToPadding = false }
        val bubble = SealBubble(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(a, R.drawable.bg_message_received)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(TextView(a).apply {
                text = "This is how your picture and border look on messages"
                textSize = 15f; setTextColor(ContextCompat.getColor(a, R.color.color_text_primary))
            })
            if (OrnateTheme.isOn(a)) foreground = ContextCompat.getDrawable(a, R.drawable.ornate_rim_recv)
        }
        msgRow.addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.START))
        val msgPic = picture(dp(PreferenceManager.msgAvatarSize)).apply {
            layoutParams = FrameLayout.LayoutParams(dp(PreferenceManager.msgAvatarSize), dp(PreferenceManager.msgAvatarSize), Gravity.TOP or Gravity.START)
        }
        msgRow.addView(msgPic)
        root.addView(msgRow, LinearLayout.LayoutParams(-1, -2))
        fun refreshMsg() {
            SealBubble.place(msgPic, bubble, false, SealBubble.SEAL, PreferenceManager.msgAvatarSize, OrnateTheme.isOn(a))
            ProfileFrames.applyFloating(msgPic, me, reserve = false)
            msgRow.invalidate()
        }
        root.addView(SeekBar(a).apply {
            max = 110 - 30; progress = PreferenceManager.msgAvatarSize - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { PreferenceManager.msgAvatarSize = p + 30; refreshMsg() } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        val scroll = ScrollView(a).apply { addView(root) }
        val dlg = AlertDialog.Builder(a)
            .setTitle("Picture sizes")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ ->
                PreferenceManager.listAvatarSize = 48; PreferenceManager.msgAvatarSize = 30
                a.sendBroadcast(Intent("com.silvershireinn.PROFILE_UPDATED").setPackage(a.packageName))
            }
            .setOnDismissListener {
                // Chats list and open chats redraw at the new sizes
                a.sendBroadcast(Intent("com.silvershireinn.PROFILE_UPDATED").setPackage(a.packageName))
            }
            .create()
        dlg.show()
        root.post { refreshList(); refreshMsg() }
        @Suppress("UNUSED_VARIABLE") val unused = View.VISIBLE
    }
}
