package com.silvershireinn.app.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.silvershireinn.app.R
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

/**
 * The "Silvershire" look (forged iron, dark stone) over the existing screens, with "Classic"
 * (the original look) one switch away. Instead of a second copy of every layout, a decorator
 * walks each screen and swaps in the forged pieces (art from tools/gen_theme_assets.py):
 *   window: tiled dark stone          toolbars / tabs / bottom bars: forged iron bar
 *   panels (cards): iron frame with corner brackets and rivets
 *   buttons: forged plates            chat bubbles: bronze / steel rims (MessageAdapter)
 * Views it changed are tagged so it never does the same view twice.
 */
object OrnateTheme {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ui_theme", Context.MODE_PRIVATE)

    /** "ornate" (default) or "classic". */
    fun isOn(c: Context): Boolean = prefs(c).getString("theme", "ornate") != "classic"
    fun set(c: Context, ornate: Boolean) { prefs(c).edit().putString("theme", if (ornate) "ornate" else "classic").apply() }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                if (!isOn(a)) return
                // before the screen is built: stone behind everything
                a.window?.setBackgroundDrawableResource(R.drawable.ornate_window_bg)
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) { if (isOn(a)) attach(a) }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun attach(a: Activity) {
        val root = a.window?.decorView ?: return
        decorate(root)
        if (listening.add(root)) {
            var pending = false
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (pending || !isOn(a)) return@addOnGlobalLayoutListener
                pending = true
                root.postDelayed({ pending = false; decorate(root) }, 250)   // new rows, fragments, sheets
            }
        }
    }

    private val listening = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private fun dp(v: View, d: Int) = (d * v.resources.displayMetrics.density).toInt()

    private val bgColor by lazy { ContextCompat.getColor(com.silvershireinn.app.SilvershireApp.instance, R.color.color_background) }
    private val surfaceColors by lazy {
        val c = com.silvershireinn.app.SilvershireApp.instance
        setOf(ContextCompat.getColor(c, R.color.color_surface), ContextCompat.getColor(c, R.color.color_surface_variant))
    }

    fun decorate(v: View) {
        if (v.getTag(R.id.ornate_done) == null || v === v.rootView) {
            if (styleOne(v)) v.setTag(R.id.ornate_done, true)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) decorate(v.getChildAt(i))
    }

    private fun idName(v: View): String = if (v.id == View.NO_ID) "" else try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { "" }

    /** Style one view; returns true once it is final (so it is skipped later). */
    private fun styleOne(v: View): Boolean {
        val ctx = v.context
        when (v) {
            is Toolbar, is AppBarLayout, is TabLayout -> {
                if (v is Toolbar && v.parent is AppBarLayout) { v.background = null; return true }
                v.background = ContextCompat.getDrawable(ctx, R.drawable.ornate_bar)
                return true
            }
            is BottomNavigationView -> {
                v.background = ContextCompat.getDrawable(ctx, R.drawable.ornate_bar_bottom)
                return true
            }
            is MaterialButton -> {
                if (v.width == 0 && v.height == 0) return false            // not laid out yet
                // icon-only and text-style buttons stay as they are
                if (v.text.isNullOrBlank() || v.backgroundTintList?.defaultColor == Color.TRANSPARENT) return true
                val sl = StateListDrawable()
                sl.addState(intArrayOf(android.R.attr.state_pressed), ContextCompat.getDrawable(ctx, R.drawable.ornate_button_pressed))
                sl.addState(intArrayOf(), ContextCompat.getDrawable(ctx, R.drawable.ornate_button))
                val pl = v.paddingLeft; val pt = v.paddingTop; val pr = v.paddingRight; val pb = v.paddingBottom
                v.backgroundTintList = null
                v.background = sl
                v.setPadding(pl, pt, pr, pb)
                v.insetTop = 0; v.insetBottom = 0
                return true
            }
            is CardView -> {
                if (v.width == 0 && v.height == 0) return false
                val small = minOf(v.width, v.height) < dp(v, 76)
                val round = v.radius >= minOf(v.width, v.height) / 2f - dp(v, 2)
                val name = idName(v).lowercase()
                val skip = small || round || listOf("avatar", "pic", "badge", "indicator", "btn", "dot", "chip", "head", "fab", "send").any { name.contains(it) }
                if (skip) return true
                val c = v.cardBackgroundColor.defaultColor
                if (c !in surfaceColors && Color.alpha(c) > 0 && c != bgColor) return true   // custom coloured card: leave it
                v.setCardBackgroundColor(Color.TRANSPARENT)
                v.background = ContextCompat.getDrawable(ctx, R.drawable.ornate_panel)
                return true
            }
        }
        // plain background colour: dark stone instead (still opaque, so nothing shows through)
        val bg = v.background
        if (bg is ColorDrawable && bg.color == bgColor) { v.background = ContextCompat.getDrawable(ctx, R.drawable.ornate_window_bg); return true }
        return v.width != 0 || v.height != 0
    }
}
