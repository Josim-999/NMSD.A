package com.videostudio.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Memory-safe picture loading for the render. A phone gives the app a few hundred MB; a single
 * 4000x6000 photo decoded in full is ~96 MB. Every picture is brought down to just cover the
 * 1080x1920 frame (at most ~8 MB), which is all the video can show anyway.
 */
object MediaLoad {
    const val FRAME_W = 1080
    const val FRAME_H = 1920
    /** How much wider or taller than the frame a picture may stay (room for the slow pan). */
    const val MAX_EXTRA = 1.5

    /** [file] decoded and scaled down to cover [maxW]x[maxH], or null. */
    fun fit(file: File, maxW: Int = FRAME_W, maxH: Int = FRAME_H): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Largest power-of-two step that still leaves the picture covering the frame.
        val cw = coverW(bounds.outWidth, bounds.outHeight, maxW, maxH)
        val ch = coverH(bounds.outWidth, bounds.outHeight, maxW, maxH)
        var sample = 1
        while (sample < 64 && bounds.outWidth / (sample * 2) >= cw && bounds.outHeight / (sample * 2) >= ch) sample *= 2
        var bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Panoramas and very tall strips: keep the middle part the frame (plus pan room) can show.
        val aspect = bmp.width.toDouble() / bmp.height
        val wideMax = maxW * MAX_EXTRA / maxH
        val tallMin = maxW / (maxH * MAX_EXTRA)
        if (aspect > wideMax || aspect < tallMin) {
            val cw2 = if (aspect > wideMax) (bmp.height * wideMax).toInt() else bmp.width
            val ch2 = if (aspect < tallMin) (bmp.width / tallMin).toInt() else bmp.height
            val cropped = Bitmap.createBitmap(bmp, (bmp.width - cw2) / 2, (bmp.height - ch2) / 2, cw2, ch2)
            if (cropped !== bmp) bmp.recycle()
            bmp = cropped
        }
        val tw = coverW(bmp.width, bmp.height, maxW, maxH)
        val th = coverH(bmp.width, bmp.height, maxW, maxH)
        if (bmp.width <= tw + 1 && bmp.height <= th + 1) return bmp
        Bitmap.createScaledBitmap(bmp, tw, th, true).also { if (it !== bmp) bmp.recycle() }
    }.getOrNull()

    /** Width of a w x h picture scaled to just cover maxW x maxH (never enlarged). */
    fun coverW(w: Int, h: Int, maxW: Int, maxH: Int): Int = (w * scale(w, h, maxW, maxH)).toInt().coerceAtLeast(1)
    fun coverH(w: Int, h: Int, maxW: Int, maxH: Int): Int = (h * scale(w, h, maxW, maxH)).toInt().coerceAtLeast(1)
    private fun scale(w: Int, h: Int, maxW: Int, maxH: Int) = maxOf(maxW.toDouble() / w, maxH.toDouble() / h).coerceAtMost(1.0)
}
