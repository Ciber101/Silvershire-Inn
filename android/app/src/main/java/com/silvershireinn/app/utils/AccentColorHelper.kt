package com.silvershireinn.app.utils

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout

/**
 * Walks an entire view hierarchy and re-tints every element that currently uses
 * either the app's default gold color (#D4AF37) OR a previously-applied accent color,
 * replacing it with the user's latest chosen accent color.
 *
 * Pass [oldAccent] when the user is actively changing their accent so that elements
 * that were already tinted with a previous accent (not gold) also get updated.
 *
 * Covered element types:
 *   - FloatingActionButton  (backgroundTint and imageTint)
 *   - SwitchMaterial / SwitchCompat  (thumbTint + trackTint)
 *   - SeekBar               (progressTint + thumbTint)
 *   - ImageButton / ImageView (imageTint)
 *   - MaterialButton        (textColor when gold; backgroundTint when gold)
 *   - TabLayout             (indicator color + selected text color)
 *   - CardView              (background color when gold/accent)
 *   - TextView              (section headers colored gold/accent)
 */
object AccentColorHelper {

    /** Resolved ARGB value of the app's default accent color (cozy gold). */
    val GOLD: Int = Color.parseColor("#D4AF37")

    /**
     * Apply [accentColor] to every accent-tinted view inside [root].
     *
     * @param root        The root of the view hierarchy to walk.
     * @param accentColor The new accent color to apply.
     * @param oldAccent   The accent color that was previously applied (defaults to GOLD).
     *                    Supply this when changing the accent so elements already tinted
     *                    with the OLD accent are also re-tinted to the new one.
     */
    fun apply(root: View, accentColor: Int, oldAccent: Int = GOLD) {
        val csl = ColorStateList.valueOf(accentColor)
        walk(root, accentColor, csl, oldAccent)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun walk(view: View, color: Int, csl: ColorStateList, old: Int) {
        tint(view, color, csl, old)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), color, csl, old)
        }
    }

    private fun tint(view: View, color: Int, csl: ColorStateList, old: Int) {
        when (view) {

            // TabLayout — update indicator bar and selected-tab text color.
            // Must come before other ViewGroup checks so the type is matched first.
            is TabLayout -> {
                view.setSelectedTabIndicatorColor(color)
                // Preserve the unselected text color; only replace the selected color.
                val normal = view.tabTextColors?.defaultColor ?: Color.GRAY
                view.setTabTextColors(normal, color)
            }

            // CardView — update background when it is the gold/accent badge circle.
            is CardView -> {
                if (isAccentColor(view.cardBackgroundColor, old)) view.setCardBackgroundColor(color)
            }

            is FloatingActionButton -> {
                if (isAccentColor(view.backgroundTintList, old)) view.backgroundTintList = csl
                if (isAccentColor(view.imageTintList, old))      view.imageTintList      = csl
            }

            is SwitchCompat -> {
                // SwitchMaterial extends SwitchCompat — covers all switches in the app.
                // Apply unconditionally: switches always follow the accent color.
                val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
                val thumbOn  = color
                val thumbOff = Color.argb(160, r, g, b)
                val trackOn  = Color.argb(120, r, g, b)
                val trackOff = Color.argb(60,  r, g, b)

                val checked   = intArrayOf( android.R.attr.state_checked)
                val unchecked = intArrayOf(-android.R.attr.state_checked)

                view.thumbTintList = ColorStateList(arrayOf(checked, unchecked), intArrayOf(thumbOn,  thumbOff))
                view.trackTintList = ColorStateList(arrayOf(checked, unchecked), intArrayOf(trackOn,  trackOff))
            }

            is SeekBar -> {
                // All seekbars in the app follow the accent color — apply unconditionally.
                view.progressTintList = csl
                view.thumbTintList    = csl
            }

            // ImageButton extends ImageView, so this covers toolbar icon buttons too.
            is ImageView -> {
                if (isAccentColor(view.imageTintList, old)) view.imageTintList = csl
            }

            is MaterialButton -> {
                if (isAccentText(view, old)) view.setTextColor(csl)
                if (isAccentColor(view.backgroundTintList, old)) view.backgroundTintList = csl
            }

            // Plain TextViews used as section headers (e.g. "PRIVACY", "NOTIFICATIONS").
            // Must come AFTER MaterialButton since MaterialButton extends TextView.
            is TextView -> {
                if (isAccentTextColor(view.currentTextColor, old)) view.setTextColor(color)
            }
        }
    }

    // ── UI text color ─────────────────────────────────────────────────────────

    /**
     * The app's "danger" text color (panic wipe, clear history, log out).
     * Views already painted this color are NEVER overridden by [applyTextColor].
     */
    private val DANGER: Int = Color.parseColor("#CF6679")

    /**
     * Walk [root] and set all general-purpose [TextView] text colors to [textColor].
     * Danger-colored TextViews (panic wipe / clear / log out) are left untouched so
     * they always remain clearly distinguishable regardless of the user's chosen color.
     *
     * Pass null to clear the override (reverts to the theme color_text_primary).
     */
    private val PRIMARY: Int = Color.parseColor("#F0F0F5")   // color_text_primary
    /** Last custom UI text color applied, so a later change / reset finds those views again. */
    @Volatile private var lastApplied: Int? = null

    fun applyTextColor(root: View, textColor: Int?) {
        val prev = lastApplied
        walkText(root, textColor ?: PRIMARY, prev)
        lastApplied = textColor
    }

    /**
     * Only text currently in the main text color (or the previous custom color) changes.
     * It used to repaint EVERY label: grey secondary text, gold headers and button text all
     * turned the same color, and resetting made all secondary text bright white. Message
     * lists are skipped (they have their own per-chat text color and recycle their rows).
     */
    private fun walkText(view: View, color: Int, prev: Int?) {
        if (view is androidx.recyclerview.widget.RecyclerView) return
        if (view is TextView) {
            val cur = view.currentTextColor or 0xFF000000.toInt()
            val isPrimary = cur == (PRIMARY or 0xFF000000.toInt()) || (prev != null && cur == (prev or 0xFF000000.toInt()))
            if (isPrimary && !isDangerColor(view.currentTextColor)) view.setTextColor(color)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walkText(view.getChildAt(i), color, prev)
        }
    }

    /** True when [c] is close enough to the danger red to leave alone. */
    private fun isDangerColor(c: Int): Boolean {
        // Strip alpha, compare to DANGER within a tolerance of ±30 per channel
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val dr = Color.red(DANGER); val dg = Color.green(DANGER); val db = Color.blue(DANGER)
        return Math.abs(r - dr) < 40 && Math.abs(g - dg) < 40 && Math.abs(b - db) < 40
    }

    /**
     * Returns the color that "danger" UI elements (Panic Wipe, Clear History) should use,
     * given the user's current [accentColor].
     *
     * When the accent is close to the standard danger red, the danger elements would become
     * invisible (red text blending into a red-tinted UI). In that case we switch to a bright
     * amber/orange that stands out clearly on both dark backgrounds and red-accented UIs.
     */
    fun safeDangerColor(accentColor: Int): Int {
        // Tolerance: if any component of the accent is within 60 of the danger color,
        // consider it "too close" and use the alternate danger color.
        val r = Color.red(accentColor); val g = Color.green(accentColor); val b = Color.blue(accentColor)
        val dr = Color.red(DANGER); val dg = Color.green(DANGER); val db = Color.blue(DANGER)
        val tooClose = Math.abs(r - dr) < 60 && Math.abs(g - dg) < 60 && Math.abs(b - db) < 60
        return if (tooClose) Color.parseColor("#FF8C00") else DANGER   // bright amber vs standard danger red
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * True if [csl]'s default color matches GOLD or [old] (both ±alpha).
     * This catches elements tinted gold on first launch AND elements that were
     * already re-tinted to a previous custom accent on a subsequent change.
     */
    private fun isAccentColor(csl: ColorStateList?, old: Int): Boolean {
        csl ?: return false
        val stripped = csl.defaultColor or 0xFF000000.toInt()
        return stripped == (GOLD or 0xFF000000.toInt()) ||
               stripped == (old  or 0xFF000000.toInt())
    }

    /** True if [tv]'s current text color is gold or the previous accent. */
    private fun isAccentText(tv: MaterialButton, old: Int): Boolean =
        isAccentTextColor(tv.currentTextColor, old)

    private fun isAccentTextColor(textColor: Int, old: Int): Boolean {
        val stripped = textColor or 0xFF000000.toInt()
        return stripped == (GOLD or 0xFF000000.toInt()) ||
               stripped == (old  or 0xFF000000.toInt())
    }
}

