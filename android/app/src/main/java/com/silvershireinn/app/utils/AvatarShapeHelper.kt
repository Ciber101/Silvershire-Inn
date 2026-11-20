package com.silvershireinn.app.utils

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.min

/**
 * Chat-head / avatar chrome shapes. Size is unchanged — only the clip path differs.
 */
enum class AvatarShape {
    CIRCLE, ROUNDED_SQUARE, SQUIRCLE, DIAMOND;

    companion object {
        fun fromPref(raw: String?): AvatarShape = when (raw) {
            "rounded_square", "rounded" -> ROUNDED_SQUARE
            "squircle" -> SQUIRCLE
            "diamond" -> DIAMOND
            else -> CIRCLE
        }

        /** Map server avatarShape (rounded) to local pref key. */
        fun fromServer(raw: String?): AvatarShape = fromPref(raw)
    }

    fun prefKey(): String = when (this) {
        CIRCLE -> "circle"
        ROUNDED_SQUARE -> "rounded_square"
        SQUIRCLE -> "squircle"
        DIAMOND -> "diamond"
    }

    /** Server AVATAR_SHAPES value. */
    fun toServer(): String = when (this) {
        CIRCLE -> "circle"
        ROUNDED_SQUARE -> "rounded"
        SQUIRCLE -> "squircle"
        DIAMOND -> "diamond"
    }

    fun label(): String = when (this) {
        CIRCLE -> "Circle"
        ROUNDED_SQUARE -> "Rounded square"
        SQUIRCLE -> "Squircle"
        DIAMOND -> "Diamond"
    }
}

object AvatarShapeHelper {

    fun current(): AvatarShape = AvatarShape.fromPref(PreferenceManager.chatHeadShape)

    /** Own shape or a contact's synced shape. */
    fun forUser(username: String?): AvatarShape {
        if (username.isNullOrBlank()) return current()
        val me = PreferenceManager.username
        if (username == me) return current()
        val stored = PreferenceManager.getContactAvatarShape(username)
        return if (stored.isNotBlank()) AvatarShape.fromServer(stored) else AvatarShape.CIRCLE
    }

    fun applyClip(view: View, shape: AvatarShape = current()) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val w = v.width.coerceAtLeast(1)
                val h = v.height.coerceAtLeast(1)
                when (shape) {
                    AvatarShape.CIRCLE -> outline.setOval(0, 0, w, h)
                    AvatarShape.ROUNDED_SQUARE -> {
                        val r = min(w, h) * 0.22f
                        outline.setRoundRect(0, 0, w, h, r)
                    }
                    AvatarShape.SQUIRCLE -> {
                        val r = min(w, h) * 0.34f
                        outline.setRoundRect(0, 0, w, h, r)
                    }
                    AvatarShape.DIAMOND -> {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val path = Path()
                            path.moveTo(w / 2f, 0f)
                            path.lineTo(w.toFloat(), h / 2f)
                            path.lineTo(w / 2f, h.toFloat())
                            path.lineTo(0f, h / 2f)
                            path.close()
                            outline.setPath(path)
                        } else {
                            outline.setOval(0, 0, w, h)
                        }
                    }
                }
            }
        }
        view.invalidateOutline()
    }

    fun backgroundDrawable(fillColor: Int, shape: AvatarShape = current()): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            when (shape) {
                AvatarShape.CIRCLE -> this.shape = GradientDrawable.OVAL
                AvatarShape.ROUNDED_SQUARE -> {
                    this.shape = GradientDrawable.RECTANGLE
                    cornerRadius = 18f
                }
                AvatarShape.SQUIRCLE -> {
                    this.shape = GradientDrawable.RECTANGLE
                    cornerRadius = 28f
                }
                AvatarShape.DIAMOND -> this.shape = GradientDrawable.OVAL
            }
        }
    }

    fun needsSelectionRing(shape: AvatarShape = current()): Boolean = shape != AvatarShape.CIRCLE
}
