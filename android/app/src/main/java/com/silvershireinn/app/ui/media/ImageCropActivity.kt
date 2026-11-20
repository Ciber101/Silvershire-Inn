package com.silvershireinn.app.ui.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.silvershireinn.app.R
import com.silvershireinn.app.utils.toast
import java.io.File
import java.io.FileOutputStream

/**
 * Pinch-zoom cropper. The overlay frame is the region that will be saved/uploaded
 * so friends see the same crop. Banner uses a wide frame; avatar is 1:1.
 */
class ImageCropActivity : AppCompatActivity() {

    private lateinit var image: ZoomableImageView
    private lateinit var frame: View
    private var source: Uri? = null
    private var aspectW = 16
    private var aspectH = 6
    private var maxEdge = 1600
    private var srcBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_crop)
        image = findViewById(R.id.ivCrop)
        frame = findViewById(R.id.cropFrame)
        source = intent.data
        aspectW = intent.getIntExtra(EXTRA_ASPECT_W, 16).coerceAtLeast(1)
        aspectH = intent.getIntExtra(EXTRA_ASPECT_H, 6).coerceAtLeast(1)
        maxEdge = intent.getIntExtra(EXTRA_MAX_EDGE, 1600).coerceIn(256, 4096)

        findViewById<View>(R.id.btnCropCancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCropApply).setOnClickListener { applyCrop() }

        val uri = source
        if (uri == null) { toast("No image"); finish(); return }
        image.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                image.viewTreeObserver.removeOnGlobalLayoutListener(this)
                sizeFrame()
            }
        })
        // Decoded in the background (it was on the main thread), only as large as the result
        // can use (the output is at most maxEdge), and turned upright: phone photos are often
        // stored sideways with an EXIF rotation tag, so avatars / banners came out sideways.
        Thread {
            val bmp = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val cap = minOf(4096, maxOf(maxEdge * 2, 1600))
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                while (longest / sample > cap) sample *= 2
                val raw = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                val rot = try { contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0 } catch (_: Throwable) { 0 }
                if (raw != null && rot != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(rot.toFloat()) }, true).also { if (it !== raw) raw.recycle() } else raw
            } catch (_: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) { bmp?.recycle(); return@runOnUiThread }
                if (bmp == null) { toast("Could not load image"); finish(); return@runOnUiThread }
                srcBitmap = bmp
                image.setImageBitmap(bmp)
            }
        }.start()
    }

    private fun sizeFrame() {
        val vw = image.width
        val vh = image.height
        if (vw <= 0 || vh <= 0) return
        val margin = (12 * resources.displayMetrics.density).toInt()
        val maxW = vw - margin * 2
        val maxH = vh - margin * 2
        val target = aspectW.toFloat() / aspectH.toFloat()
        var fw = maxW
        var fh = (fw / target).toInt()
        if (fh > maxH) {
            fh = maxH
            fw = (fh * target).toInt()
        }
        val lp = frame.layoutParams
        lp.width = fw
        lp.height = fh
        frame.layoutParams = lp
    }

    private fun applyCrop() {
        val bmp = srcBitmap ?: return
        val m = image.copyDisplayMatrix()
        val inv = Matrix()
        if (!m.invert(inv)) { toast("Could not crop"); return }

        val locImg = IntArray(2)
        val locFrame = IntArray(2)
        image.getLocationOnScreen(locImg)
        frame.getLocationOnScreen(locFrame)
        val left = (locFrame[0] - locImg[0]).toFloat()
        val top = (locFrame[1] - locImg[1]).toFloat()
        val pts = floatArrayOf(
            left, top,
            left + frame.width, top,
            left + frame.width, top + frame.height,
            left, top + frame.height
        )
        inv.mapPoints(pts)
        var minX = pts[0]; var maxX = pts[0]; var minY = pts[1]; var maxY = pts[1]
        for (i in 0 until 4) {
            minX = minOf(minX, pts[i * 2]); maxX = maxOf(maxX, pts[i * 2])
            minY = minOf(minY, pts[i * 2 + 1]); maxY = maxOf(maxY, pts[i * 2 + 1])
        }
        val x = minX.toInt().coerceIn(0, bmp.width - 1)
        val y = minY.toInt().coerceIn(0, bmp.height - 1)
        val w = (maxX.toInt() - x).coerceIn(1, bmp.width - x)
        val h = (maxY.toInt() - y).coerceIn(1, bmp.height - y)
        var cropped = try { Bitmap.createBitmap(bmp, x, y, w, h) } catch (_: Exception) { bmp }
        val longest = maxOf(cropped.width, cropped.height)
        if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest
            cropped = Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).toInt().coerceAtLeast(1),
                (cropped.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        val out = File(cacheDir, "crop_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(out).use { fos -> cropped.compress(Bitmap.CompressFormat.JPEG, 90, fos) }
        } catch (e: Exception) {
            toast("Save failed"); return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", out)
        setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        srcBitmap?.recycle()
        srcBitmap = null
    }

    companion object {
        const val EXTRA_ASPECT_W = "aspect_w"
        const val EXTRA_ASPECT_H = "aspect_h"
        const val EXTRA_MAX_EDGE = "max_edge"
        const val ASPECT_BANNER_W = 16
        const val ASPECT_BANNER_H = 6
        const val ASPECT_AVATAR_W = 1
        const val ASPECT_AVATAR_H = 1

        fun intent(ctx: Context, src: Uri, aspectW: Int, aspectH: Int, maxEdge: Int = 1600): Intent =
            Intent(ctx, ImageCropActivity::class.java).apply {
                data = src
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_ASPECT_W, aspectW)
                putExtra(EXTRA_ASPECT_H, aspectH)
                putExtra(EXTRA_MAX_EDGE, maxEdge)
            }
    }
}
