package com.silvershireinn.app.utils

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Walks an entire view hierarchy and applies [fontFamily] to every [TextView].
 * Used to apply the global app font setting to all screens instantly without
 * requiring an Activity recreate.
 *
 * Font availability: only "sans-serif" (DEFAULT), "serif" (SERIF), and "monospace"
 * (MONOSPACE) are guaranteed Android SDK Typeface constants that work on every device
 * including Samsung/Xiaomi OEM builds.  Other families (e.g. Roboto Condensed) are
 * only available on stock AOSP devices.  Use [isFamilyAvailable] before showing a
 * font option in the picker so OEM users don't see choices that do nothing.
 */
object FontHelper {

    /**
     * Apply [fontFamily] to every [TextView] found under [root].
     * Call from [android.app.Activity.onResume] or [androidx.fragment.app.Fragment.onResume].
     */
    fun apply(root: View, fontFamily: String) {
        val typeface = resolveTypeface(fontFamily)
        walk(root, typeface)
    }

    /**
     * Resolve a font family name to a [Typeface].
     *
     * Uses hardcoded SDK constants for the three guaranteed families so they always work
     * on OEM devices (Samsung, Xiaomi, etc.).  For other named families, falls back to
     * [Typeface.DEFAULT] if the family is not installed on this device.
     */
    fun resolveTypeface(family: String): Typeface {
        return when {
            family.isBlank() || family == "sans-serif" -> Typeface.DEFAULT
            family == "serif"     -> Typeface.SERIF
            family == "monospace" -> Typeface.MONOSPACE
            else -> {
                return try {
                    val tf = Typeface.create(family, Typeface.NORMAL) ?: return Typeface.DEFAULT
                    // On OEM devices Typeface.create() silently returns DEFAULT for missing
                    // families.  We detect this by reference equality so we don't silently
                    // apply a no-op typeface change.
                    if (tf === Typeface.DEFAULT) Typeface.DEFAULT else tf
                } catch (_: Exception) {
                    Typeface.DEFAULT
                }
            }
        }
    }

    /**
     * Returns true if [family] produces a Typeface that is visually distinct from
     * the device default (i.e. the font is actually installed on this device).
     * Use this to filter out picker entries that would have no effect.
     */
    fun isFamilyAvailable(family: String): Boolean {
        return when {
            family.isBlank() || family == "sans-serif" -> true
            family == "serif"     -> Typeface.SERIF !== Typeface.DEFAULT
            family == "monospace" -> Typeface.MONOSPACE !== Typeface.DEFAULT
            else -> {
                try {
                    val tf = Typeface.create(family, Typeface.NORMAL) ?: return false
                    tf !== Typeface.DEFAULT
                } catch (_: Exception) { false }
            }
        }
    }

    // ── Internal tree walker ──────────────────────────────────────────────────

    private fun walk(view: View, typeface: Typeface) {
        // Keep each view's bold / italic (assigning the bare typeface used to drop bold titles).
        if (view is TextView) view.setTypeface(typeface, view.typeface?.style ?: Typeface.NORMAL)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), typeface)
            }
        }
    }
}
