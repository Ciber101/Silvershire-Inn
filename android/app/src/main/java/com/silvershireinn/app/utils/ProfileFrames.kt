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
     * chat header. Nothing may overlap anything, so the picture's slot gets extra room around it
     * (margins) and the picture and its border shrink together until the border's drawn area fits
     * inside that room, with a little to spare. Drawn on the row's overlay.
     */
    /** Returns the room reserved on each side (0 when there is no border). */
    fun applyFloating(avatar: View?, user: String?): Int {
        avatar ?: return 0
        val first = avatar.parent as? ViewGroup ?: return 0
        // The slot is the picture itself, or the small box holding it (picture + online dot)
        val slot: View = if (first is FrameLayout && first.layoutParams?.width?.let { it > 0 && it <= (avatar.layoutParams?.width ?: 0) * 1.4 } == true) first else avatar
        val host = slot.parent as? ViewGroup ?: return 0
        if (avatar.foreground is FrameDrawable) avatar.foreground = null      // older compact band
        val id = frameFor(user)
        val old = avatar.getTag(R_FLOAT) as? FloatingFrame
        val framed = !(id == "none" || id.startsWith("gen:none"))
        reserveRoom(slot, avatar, framed)
        if (!framed) {
            if (old != null) { old.host.overlay.remove(old); avatar.setTag(R_FLOAT, null); old.host.invalidate() }
            avatar.scaleX = 1f; avatar.scaleY = 1f
            return 0
        }
        val shape = shapeName(AvatarShapeHelper.forUser(user))
        val room = (slot.getTag(R_ROOM) as? Int) ?: 0
        if (old != null && old.id == id && old.shape == shape && old.host === host) { host.invalidate(); return room }
        if (old != null) old.host.overlay.remove(old)
        val f = FloatingFrame(avatar.context.applicationContext, id, shape, avatar, slot, host)
        host.overlay.add(f)
        avatar.setTag(R_FLOAT, f)
        host.invalidate()
        return room
    }

    /** Margins around a framed picture's slot (the room the border may use); restored when unframed. */
    private fun reserveRoom(slot: View, avatar: View, framed: Boolean) {
        val lp = slot.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val orig = (slot.getTag(R_MARGINS) as? IntArray) ?: intArrayOf(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin).also { slot.setTag(R_MARGINS, it) }
        val a = (avatar.layoutParams?.width?.takeIf { it > 0 } ?: avatar.width).coerceAtLeast(1)
        val m = if (framed) (a * 0.3f).toInt() else 0
        val want = intArrayOf(orig[0] + m, orig[1] + m / 2, orig[2] + m, orig[3] + m / 2)
        if (lp.leftMargin != want[0] || lp.topMargin != want[1] || lp.rightMargin != want[2] || lp.bottomMargin != want[3]) {
            lp.setMargins(want[0], want[1], want[2], want[3]); slot.layoutParams = lp
        }
        slot.setTag(R_ROOM, m)
    }

    /** Overlay drawable that follows its picture (position, size, visibility) every time it draws. */
    class FloatingFrame(private val app: Context, val id: String, val shape: String, avatar: View, slot: View, val host: ViewGroup) : Drawable() {
        private val ref = java.lang.ref.WeakReference(avatar)
        private val slotRef = java.lang.ref.WeakReference(slot)
        private var bmp: Bitmap? = null
        private var drawn: FloatArray? = null       // drawn area of the bitmap: x0, y0, x1, y1 (0..1)
        private var asked = 0
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dst = Rect()
        private val tmp = Rect()
        init { setBounds(-4000, -4000, 4000, 4000) }   // invalidation area: anywhere near the host
        override fun draw(c: Canvas) {
            val v = ref.get() ?: return
            val slot = slotRef.get() ?: return
            if (v.getTag(R_FLOAT) !== this || slot.parent !== host || v.width == 0) return
            if (v.visibility != View.VISIBLE || slot.visibility != View.VISIBLE) return
            val a = minOf(v.width, v.height)
            val full = (a / PIC)                                   // border size at full scale
            tmp.set(0, 0, v.width, v.height)
            try { host.offsetDescendantRectToMyCoords(v, tmp) } catch (_: Exception) { return }
            // moved pictures (stacked heads, dragged chips) carry their border along
            val tx = v.translationX + (if (slot !== v) slot.translationX else 0f)
            val ty = v.translationY + (if (slot !== v) slot.translationY else 0f)
            val cx = tmp.exactCenterX() + tx; val cy = tmp.exactCenterY() + ty
            val s = bucket(full.toInt()).coerceAtLeast(128)
            if (s != asked) {
                asked = s
                FrameRenderer.get(app, id, shape, s, "full") { b -> if (b != null && asked == s) { bmp = b; drawn = drawnArea(b); host.invalidate() } }
            }
            val b = bmp ?: return
            val d = drawn ?: floatArrayOf(0f, 0f, 1f, 1f)
            // the room: the slot plus its reserved margins, inside the row
            val m = (slot.getTag(R_ROOM) as? Int) ?: 0
            val tol = 2f * v.resources.displayMetrics.density
            val left = maxOf(0f, slot.left + tx - m.toFloat()) + tol
            val top = maxOf(0f, slot.top + ty - m / 2f) + tol
            val right = minOf(host.width.toFloat(), slot.right + tx + m.toFloat()) - tol
            val bottom = minOf(host.height.toFloat(), slot.bottom + ty + m / 2f) - tol
            var k = 1f
            val nl = (0.5f - d[0]) * full; val nt = (0.5f - d[1]) * full; val nr = (d[2] - 0.5f) * full; val nb = (d[3] - 0.5f) * full
            if (nl > 0) k = minOf(k, (cx - left) / nl)
            if (nr > 0) k = minOf(k, (right - cx) / nr)
            if (nt > 0) k = minOf(k, (cy - top) / nt)
            if (nb > 0) k = minOf(k, (bottom - cy) / nb)
            k = k.coerceIn(0.3f, 1f)
            if (kotlin.math.abs(v.scaleX - k) > 0.01f) { v.post { v.scaleX = k; v.scaleY = k } }   // picture shrinks with its border
            val size = full * k
            dst.set((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            paint.alpha = (v.alpha * 255).toInt()
            c.drawBitmap(b, null, dst, paint)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** The part of a border bitmap that is actually drawn (alpha > 20), as fractions. */
    fun drawnArea(b: Bitmap): FloatArray {
        val w = b.width; val h = b.height
        val row = IntArray(w)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        var y = 0
        while (y < h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                if ((row[x] ushr 24) > 20) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
                x += 2
            }
            y += 2
        }
        return if (x1 < 0) floatArrayOf(0f, 0f, 1f, 1f)
               else floatArrayOf(x0.toFloat() / w, y0.toFloat() / h, minOf(1f, (x1 + 2f) / w), minOf(1f, (y1 + 2f) / h))
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
    private val R_MARGINS = com.silvershireinn.app.R.id.profile_frame_margins
    private val R_ROOM = com.silvershireinn.app.R.id.profile_frame_room
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
    private const val CACHE_VERSION = 8
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
