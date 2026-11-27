package com.videostudio.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The subject of a photo (person, animal, object) cut out of its background, so the headline can
 * sit *behind* it: the "pop-out" magazine-cover look. [bounds] is where the subject is, as fractions
 * of the photo (0..1).
 */
class Cutout(val bitmap: Bitmap, val shadow: Bitmap, val bounds: RectF) {
    companion object {
        /** Pixels with this much alpha count as subject. */
        private const val SOLID = 128

        /** Fraction of the picture the subject covers, and where it is. */
        fun measure(fg: Bitmap): Pair<Float, RectF> {
            val step = maxOf(1, fg.width / 160)
            var n = 0; var hit = 0
            var l = fg.width; var t = fg.height; var r = 0; var b = 0
            var y = 0
            while (y < fg.height) {
                var x = 0
                while (x < fg.width) {
                    n++
                    if (Color.alpha(fg.getPixel(x, y)) >= SOLID) {
                        hit++
                        if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                    }
                    x += step
                }
                y += step
            }
            val box = if (hit == 0) RectF() else RectF(l / fg.width.toFloat(), t / fg.height.toFloat(), r / fg.width.toFloat(), b / fg.height.toFloat())
            return (if (n == 0) 0f else hit / n.toFloat()) to box
        }

        /** A pop-out only looks right when the subject is clear and does not fill the whole picture. */
        fun usable(coverage: Float, box: RectF) = coverage in 0.05f..0.65f && box.height() >= 0.25f

        fun fromForeground(fg: Bitmap): Cutout? {
            val (coverage, box) = measure(fg)
            if (!usable(coverage, box)) return null
            // A soft shadow under the subject so it lifts off the picture.
            val small = Bitmap.createScaledBitmap(fg, maxOf(1, fg.width / 4), maxOf(1, fg.height / 4), true)
            val alpha = small.extractAlpha()
            val shadow = Bitmap.createBitmap(small.width + 16, small.height + 16, Bitmap.Config.ARGB_8888)
            Canvas(shadow).drawBitmap(alpha, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
            })
            return Cutout(fg, shadow, box)
        }

        fun load(f: File): Cutout? = runCatching {
            BitmapFactory.decodeFile(f.absolutePath)?.let { fromForeground(it) }
        }.getOrNull()
    }
}

/** Runs Google's free on-device subject segmentation (ML Kit, via Google Play services). */
object Segmenter {
    /**
     * Writes the cut-out of [photo] to [out] as a transparent PNG. False when there is no clear
     * subject, or the model is not on the phone yet (its download is then started for next time).
     */
    fun cut(context: Context, photo: File, out: File): Boolean {
        val client = runCatching {
            SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
        }.getOrNull() ?: return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
            val bmp = BitmapFactory.decodeFile(photo.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
            val result = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)), 15, TimeUnit.SECONDS)
            val fg = result.foregroundBitmap ?: return false
            val (coverage, box) = Cutout.measure(fg)
            if (!Cutout.usable(coverage, box)) return false
            out.outputStream().use { fg.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            // Usually "waiting for the optional module to be downloaded": ask Play services for it.
            runCatching {
                ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder().addApi(client).build())
            }
            false
        } finally {
            runCatching { client.close() }
        }
    }
}
