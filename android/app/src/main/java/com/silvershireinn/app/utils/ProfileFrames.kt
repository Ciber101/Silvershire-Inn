package com.silvershireinn.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Profile frames ("borders"). A frame is just an id, "gen:<kind>:<seed>"; every device draws the
 * identical picture from it with the same drawing code the web client uses (assets/frames.js),
 * run in a hidden WebView and cached as PNG files.
 *
 *  - Small pictures (chat list, contacts, beside messages, chat header, chat heads): the compact
 *    band, drawn over the rim of the picture as the view's foreground.
 *  - Profiles (own profile, a friend's profile): the full frame with dragons / weapons / wings,
 *    an image centred on the picture and larger than it.
 */
object ProfileFrames {
    val KINDS = linkedMapOf(
        "random" to "Random", "dragon" to "Dragon", "weapons" to "Weapons", "basic" to "Basic",
        "stone" to "Stone", "iron" to "Iron", "silver" to "Silver", "gold" to "Gold",
        "bronze" to "Bronze", "winged" to "Winged", "spiked" to "Spiked"
    )
    private val ID_RE = Regex("^gen:(random|dragon|weapons|basic|stone|iron|silver|gold|bronze|winged|spiked|none):\\d{1,10}$")
    fun isValid(id: String?) = id != null && ID_RE.matches(id)
    fun newId(kind: String): String = "gen:$kind:${(1..4294967295L).random()}"

    private fun prefs() = com.silvershireinn.app.SilvershireApp.instance.getSharedPreferences("profile_frames", Context.MODE_PRIVATE)

    // ── stored state ──
    var myFrame: String
        get() = prefs().getString("mine", "none") ?: "none"
        set(v) { prefs().edit().putString("mine", if (isValid(v)) v else "none").apply() }

    var savedFrames: List<String>
        get() = try { val a = JSONArray(prefs().getString("saved", "[]")); (0 until a.length()).map { a.optString(it) }.filter { isValid(it) } } catch (_: Exception) { emptyList() }
        set(v) { prefs().edit().putString("saved", JSONArray(v.filter { isValid(it) }.take(60)).toString()).apply() }

    fun contactFrame(user: String): String = prefs().getString("c_$user", "none") ?: "none"
    fun setContactFrame(user: String, id: String?) {
        val e = prefs().edit()
        if (isValid(id) && !id!!.startsWith("gen:none")) e.putString("c_$user", id) else e.remove("c_$user")
        e.apply()
    }

    fun frameFor(user: String?): String {
        if (user.isNullOrBlank() || user.startsWith("group_")) return "none"
        return if (user == PreferenceManager.username) myFrame else contactFrame(user)
    }

    /** Shape name the drawing code uses; diamond clips need API 30 (older phones show a circle). */
    fun shapeName(shape: AvatarShape): String = when (shape) {
        AvatarShape.CIRCLE -> "circle"
        AvatarShape.ROUNDED_SQUARE -> "rounded"
        AvatarShape.SQUIRCLE -> "squircle"
        AvatarShape.DIAMOND -> if (Build.VERSION.SDK_INT >= 30) "diamond" else "circle"
    }

    // ── showing frames ──

    /** Compact band over a small picture. [view] is the clipped picture container. */
    fun applyCompact(view: View?, user: String?) {
        view ?: return
        val id = frameFor(user)
        if (id == "none" || id.startsWith("gen:none")) {
            if (view.foreground is FrameDrawable) view.foreground = null
            return
        }
        val shape = shapeName(AvatarShapeHelper.forUser(user))
        val cur = view.foreground as? FrameDrawable
        if (cur != null && cur.id == id && cur.shape == shape) return
        view.foreground = FrameDrawable(view.context.applicationContext, id, shape, "compact")
    }

    /**
     * The whole border (dragons and all) around a small picture: list rows, beside messages, the
     * chat header. It is drawn on the picture's parent overlay, centred on the picture and larger
     * than it, so it spills past the row like the profile one does (ancestors stop clipping).
     */
    fun applyFloating(avatar: View?, user: String?) {
        avatar ?: return
        val host = avatar.parent as? ViewGroup ?: return
        if (avatar.foreground is FrameDrawable) avatar.foreground = null      // older compact band
        val id = frameFor(user)
        val old = avatar.getTag(R_FLOAT) as? FloatingFrame
        if (id == "none" || id.startsWith("gen:none")) {
            if (old != null) { old.host.overlay.remove(old); avatar.setTag(R_FLOAT, null); old.host.invalidate() }
            return
        }
        val shape = shapeName(AvatarShapeHelper.forUser(user))
        if (old != null && old.id == id && old.shape == shape && old.host === host) { host.invalidate(); return }
        if (old != null) old.host.overlay.remove(old)
        val f = FloatingFrame(avatar.context.applicationContext, id, shape, avatar, host)
        host.overlay.add(f)
        avatar.setTag(R_FLOAT, f)
        var p: android.view.ViewParent? = host
        for (i in 0 until 9) {
            val g = p as? ViewGroup ?: break
            g.clipChildren = false
            if (g is androidx.recyclerview.widget.RecyclerView) break
            p = g.parent
        }
        host.invalidate()
    }

    /** Overlay drawable that follows its picture (position, size, visibility) every time it draws. */
    class FloatingFrame(private val app: Context, val id: String, val shape: String, avatar: View, val host: ViewGroup) : Drawable() {
        private val ref = java.lang.ref.WeakReference(avatar)
        private var bmp: Bitmap? = null
        private var asked = 0
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dst = Rect()
        init { setBounds(-4000, -4000, 4000, 4000) }   // invalidation area: anywhere near the host
        override fun draw(c: Canvas) {
            val v = ref.get() ?: return
            if (v.parent !== host || v.visibility != View.VISIBLE || v.width == 0) return
            if (v.getTag(R_FLOAT) !== this) return
            val size = (minOf(v.width, v.height) / PIC).toInt()
            val cx = (v.left + v.translationX + v.width / 2f).toInt()
            val cy = (v.top + v.translationY + v.height / 2f).toInt()
            val s = bucket(size).coerceAtLeast(128)
            if (s != asked) {
                asked = s
                FrameRenderer.get(app, id, shape, s, "full") { b -> if (b != null && asked == s) { bmp = b; host.invalidate() } }
            }
            val b = bmp ?: return
            dst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            paint.alpha = (v.alpha * 255).toInt()
            c.drawBitmap(b, null, dst, paint)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * Full frame around a profile picture. [avatar]'s parent must be a FrameLayout the size of the
     * picture; the frame image is added to it, centred and ~2.3x larger (ancestors stop clipping).
     * Returns true when a frame is shown (callers can leave room under it).
     */
    fun applyFull(avatar: View?, user: String?): Boolean {
        val parent = avatar?.parent as? FrameLayout ?: return false
        val id = frameFor(user)
        var iv = parent.findViewWithTag<ImageView>(FULL_TAG)
        if (id == "none" || id.startsWith("gen:none")) { iv?.let { parent.removeView(it) }; return false }
        val ctx = parent.context
        val pic = avatar.layoutParams?.width?.takeIf { it > 0 } ?: avatar.width.takeIf { it > 0 } ?: (88 * ctx.resources.displayMetrics.density).toInt()
        val size = (pic / PIC).toInt()
        if (iv == null) {
            iv = ImageView(ctx).apply {
                tag = FULL_TAG
                scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false; isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            parent.addView(iv, parent.indexOfChild(avatar) + 1, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        } else {
            iv.layoutParams = (iv.layoutParams as FrameLayout.LayoutParams).apply { width = size; height = size; gravity = Gravity.CENTER }
        }
        // above the picture card's shadow, below nothing else that matters
        iv.translationZ = avatar.elevation + avatar.translationZ + 1f
        var p: ViewGroup? = parent
        for (i in 0 until 4) { p?.clipChildren = false; p?.clipToPadding = false; p = p?.parent as? ViewGroup }
        val shape = shapeName(AvatarShapeHelper.forUser(user))
        val want = "$id|$shape"
        iv.setTag(R_KEY, want)
        val px = bucket((size * 1.0).toInt()).coerceAtLeast(320)
        FrameRenderer.get(ctx.applicationContext, id, shape, px, "full") { bmp ->
            if (iv.getTag(R_KEY) == want) iv.setImageBitmap(bmp)
        }
        return true
    }

    private const val FULL_TAG = "profile_frame_full"
    private val R_KEY = com.silvershireinn.app.R.id.profile_frame_key
    private val R_FLOAT = com.silvershireinn.app.R.id.profile_frame_float
    const val PIC = 0.44f          // picture diameter / full frame size (frames.js draws r = 0.22 S)

    fun bucket(px: Int): Int {
        for (b in intArrayOf(48, 64, 96, 128, 192, 256, 384, 512)) if (px <= b) return b
        return 640
    }

    /** Drawable for the compact band; renders on demand at the size it is shown. */
    class FrameDrawable(private val app: Context, val id: String, val shape: String, private val mode: String) : Drawable() {
        private var bmp: Bitmap? = null
        private var asked = 0
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        override fun onBoundsChange(b: Rect) { request() }
        private fun request() {
            val s = bucket(maxOf(bounds.width(), bounds.height()))
            if (s <= 0 || s == asked) return
            asked = s
            FrameRenderer.get(app, id, shape, s, mode) { b -> if (b != null && asked == s) { bmp = b; invalidateSelf() } }
        }
        override fun draw(c: Canvas) { bmp?.let { c.drawBitmap(it, null, bounds, paint) } }
        override fun setAlpha(a: Int) { paint.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

/**
 * Draws frames with assets/frames.js in a hidden WebView (one at a time) and caches the results:
 * memory first, then PNG files in the app's cache folder, so each frame is drawn once per size.
 */
@SuppressLint("SetJavaScriptEnabled")
object FrameRenderer {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "frame-io").apply { isDaemon = true } }
    private val mem = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val queue = ArrayDeque<Array<String>>()
    private var web: WebView? = null
    private var ready = false
    private var busyKey: String? = null

    /** Callback runs on the main thread (null if it could not be drawn). */
    fun get(ctx: Context, id: String, shape: String, size: Int, mode: String, cb: (Bitmap?) -> Unit) {
        val key = "$id|$shape|$size|$mode"
        mem.get(key)?.let { cb(it); return }
        waiting[key]?.let { it.add(cb); return }
        waiting[key] = mutableListOf(cb)
        val app = ctx.applicationContext
        io.execute {
            val f = file(app, key)
            val b = if (f.exists()) try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null } else null
            main.post {
                if (b != null) deliver(key, b)
                else { queue.addLast(arrayOf(key, id, shape, size.toString(), mode)); pump(app) }
            }
        }
    }

    /** Bump whenever assets/frames.js changes how frames look: old cached pictures are dropped. */
    private const val CACHE_VERSION = 3
    @Volatile private var cleaned = false

    private fun file(ctx: Context, key: String): File {
        if (!cleaned) {
            cleaned = true
            ctx.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("frames") && it.name != "frames_v$CACHE_VERSION" }
                ?.forEach { it.deleteRecursively() }
        }
        val dir = File(ctx.cacheDir, "frames_v$CACHE_VERSION").apply { mkdirs() }
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.png")
    }

    private fun deliver(key: String, b: Bitmap?) {
        if (b != null) mem.put(key, b)
        waiting.remove(key)?.forEach { try { it(b) } catch (_: Throwable) {} }
    }

    private fun q(s: String) = org.json.JSONObject.quote(s)

    private fun pump(app: Context) {
        if (busyKey != null || queue.isEmpty()) return
        val w = web ?: create(app)
        if (!ready) return
        val job = queue.removeFirst()
        busyKey = job[0]
        w.evaluateJavascript("R(${q(job[0])},${q(job[1])},${q(job[2])},${job[3]},${q(job[4])})", null)
        val k = job[0]
        main.postDelayed({ if (busyKey == k) { busyKey = null; deliver(k, null); pump(app) } }, 20000)
    }

    private fun create(app: Context): WebView {
        val js = try { app.assets.open("frames.js").bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.blockNetworkLoads = true
        w.addJavascriptInterface(Bridge(app), "Bridge")
        w.layout(0, 0, 1, 1)
        w.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { ready = true; pump(app) }
        }
        // App pictures use these corner radii (AvatarShapeHelper): match them so no corner pokes out.
        val html = "<!doctype html><html><body><script>$js</script><script>" +
            "SilvershireFrames.setCorners({rounded:0.22,squircle:0.34});" +
            "function R(k,id,sh,S,mode){try{Bridge.done(k,SilvershireFrames.render(id,sh,S,mode).toDataURL('image/png'))}catch(e){Bridge.done(k,'')}}" +
            "</script></body></html>"
        w.loadDataWithBaseURL("https://frames.local/", html, "text/html", "utf-8", null)
        web = w
        return w
    }

    private class Bridge(val app: Context) {
        @JavascriptInterface
        fun done(key: String, dataUrl: String) {
            io.execute {
                var b: Bitmap? = null
                try {
                    val comma = dataUrl.indexOf(',')
                    if (comma > 0) {
                        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                        b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (b != null) file(app, key).writeBytes(bytes)
                    }
                } catch (_: Throwable) {}
                main.post {
                    if (busyKey == key) busyKey = null
                    deliver(key, b)
                    pump(app)
                }
            }
        }
    }
}
