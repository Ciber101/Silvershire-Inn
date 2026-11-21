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
        attachRoot(root)
    }

    /** Theme a window that is not an activity (the floating chat), and whatever is added to it later. */
    fun attachRoot(root: View) {
        val a = root.context
        if (!isOn(a)) return
        decorate(root)
        if (listening.add(root)) {
            var busy = false
            // Right after layout and BEFORE the frame is drawn: new tabs, rows and sheets never
            // show the plain look for a moment (it used to be applied 250 ms later: a blink).
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (busy || !isOn(a)) return@addOnGlobalLayoutListener
                busy = true
                try { decorate(root) } finally { busy = false }
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

    /** Grey, white and gold icon tints become engraved gold; anything else (red, on-gold) stays. */
    private val iconTints by lazy {
        val c = com.silvershireinn.app.SilvershireApp.instance
        setOf(ContextCompat.getColor(c, R.color.color_text_secondary), ContextCompat.getColor(c, R.color.color_text_primary),
              ContextCompat.getColor(c, R.color.color_gold), ContextCompat.getColor(c, R.color.color_gold_dark),
              Color.WHITE, 0xFFC9A84C.toInt(), 0xFFFFD700.toInt())
    }

    /** Camera, photo editor, viewer and video call keep white icons over the picture. */
    private fun overMedia(v: View): Boolean {
        var c = v.context
        while (c is android.content.ContextWrapper && c !is Activity) c = c.baseContext
        val n = (c as? Activity)?.javaClass?.simpleName ?: return false
        return listOf("Camera", "Editor", "Viewer", "VideoCall", "Crop", "Media").any { n.contains(it) }
    }

    /** Bottom bar icons keep their selected / not selected colours. */
    private fun inNavBar(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p is BottomNavigationView || p is com.google.android.material.navigation.NavigationBarView) return true; p = p.parent }
        return false
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
                // Dark lettering meant for a silver button reads as hollow, smudged letters on the
                // dark iron one: use the light gold text (and icon) of the theme instead.
                val tc = v.currentTextColor
                val lum = (Color.red(tc) * 299 + Color.green(tc) * 587 + Color.blue(tc) * 114) / 1000
                if (lum < 110) {
                    v.setTextColor(0xFFF0D78C.toInt())
                    v.iconTint = android.content.res.ColorStateList.valueOf(0xFFF0D78C.toInt())
                }
                v.setShadowLayer(0f, 0f, 0f, 0)
                return true
            }
            is android.widget.ImageView -> {
                // Line icons (vector drawables) in engraved gold; photos and pictures are left alone.
                val d = v.drawable ?: return false
                if (d !is android.graphics.drawable.VectorDrawable && d.javaClass.simpleName != "VectorDrawableCompat") return v.width != 0 || v.height != 0
                if (overMedia(v) || inNavBar(v)) return true
                val t = v.imageTintList?.defaultColor
                if (t == null || t in iconTints) {
                    v.imageTintList = android.content.res.ColorStateList.valueOf(ThemeIcons.GOLD)
                    v.imageTintMode = android.graphics.PorterDuff.Mode.MULTIPLY
                }
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
