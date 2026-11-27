package com.videostudio.app.render

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File

/** The picture behind a photo scene: a still photo, or frames of a stock video clip. */
sealed class SceneMedia {
    abstract val width: Int
    abstract val height: Int

    /** Frame to show [t] seconds into the scene. */
    abstract fun frameAt(t: Double): Bitmap

    open fun release() {}

    class Photo(private val bmp: Bitmap) : SceneMedia() {
        override val width get() = bmp.width
        override val height get() = bmp.height
        override fun frameAt(t: Double) = bmp
        override fun release() = bmp.recycle()
    }

    /** Several photos for one long scene, changing every [EVERY] seconds. */
    class Slideshow(private val photos: List<Bitmap>) : SceneMedia() {
        override val width get() = photos[0].width
        override val height get() = photos[0].height
        private fun at(t: Double) = ((t.coerceAtLeast(0.0) / EVERY).toInt()) % photos.size
        override fun frameAt(t: Double) = photos[at(t)]

        /** The picture being faded out just after a change, with how far the fade has gone (0..1). */
        fun previousFrame(t: Double): Pair<Bitmap, Float>? {
            val since = t - (t / EVERY).toInt() * EVERY
            if (t < EVERY || since >= FADE) return null
            return photos[(at(t) - 1 + photos.size) % photos.size] to (since / FADE).toFloat()
        }

        override fun release() = photos.forEach { it.recycle() }

        companion object {
            const val EVERY = 1.9
            const val FADE = 0.25
        }
    }

    /**
     * A clip decoded to frames on the phone ([FPS] a second, about 540 px wide, RGB 565 to keep
     * memory low). Plays forward then backward so a short clip never jumps.
     */
    class Clip(internal val frames: List<Bitmap>) : SceneMedia() {
        override val width get() = frames[0].width
        override val height get() = frames[0].height
        override fun frameAt(t: Double): Bitmap {
            if (frames.size == 1) return frames[0]
            val i = (t.coerceAtLeast(0.0) * FPS).toInt()
            val period = 2 * (frames.size - 1)
            val k = i % period
            return frames[if (k < frames.size) k else period - k]
        }

        override fun release() = frames.forEach { it.recycle() }

        companion object {
            const val FPS = 12
            /** 3 seconds at 12 fps; a clip plays forward then backward, so longer scenes never jump. */
            private const val MAX_FRAMES = 36

            /**
             * Decodes up to [seconds] of [file] once, before rendering, into small JPEG frames in [dir]
             * (decoding a video frame by frame is slow on phones; reading JPEGs back is fast).
             * Returns how many frames were written (0 when the phone cannot read the clip).
             */
            fun prepare(file: File, seconds: Double, dir: File, maxWidth: Int = 540): Int {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val clip = load(file, seconds, maxWidth) ?: return 0
                var n = 0
                clip.frames.forEachIndexed { i, b ->
                    runCatching {
                        File(dir, "f%03d.jpg".format(i)).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        n++
                    }
                }
                clip.release()
                return n
            }

            /** Reads frames written by [prepare]. */
            fun loadFrames(dir: File): Clip? {
                val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val frames = dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }
                    ?.mapNotNull { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath, opts) }.getOrNull() }.orEmpty()
                return if (frames.isEmpty()) null else Clip(frames)
            }

            /** Decodes up to [seconds] of [file]. Null when the phone cannot read it. */
            fun load(file: File, seconds: Double, maxWidth: Int = 540): Clip? = runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
                    val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val (vw, vh) = if (rotation % 180 == 0) w to h else h to w
                    val tw = minOf(maxWidth, vw)
                    val th = (vh.toLong() * tw / vw).toInt()
                    val count = minOf(MAX_FRAMES, ((minOf(seconds, durMs / 1000.0)) * FPS).toInt().coerceAtLeast(1))
                    val frames = ArrayList<Bitmap>(count)
                    // Android 9+: decode the frames in one pass (one seek), much faster than seeking per frame.
                    if (Build.VERSION.SDK_INT >= 28) runCatching { sequential(r, durMs, count, tw, th, frames) }.onFailure { frames.forEach { it.recycle() }; frames.clear() }
                    if (frames.isNotEmpty()) return Clip(frames)
                    for (i in 0 until count) {
                        val us = i * 1_000_000L / FPS
                        val raw = if (Build.VERSION.SDK_INT >= 27) {
                            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, tw, th)
                        } else {
                            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)?.let { Bitmap.createScaledBitmap(it, tw, th, true) }
                        } ?: continue
                        frames.add(raw.copy(Bitmap.Config.RGB_565, false).also { if (it !== raw) raw.recycle() })
                    }
                    if (frames.isEmpty()) null else Clip(frames)
                } finally {
                    r.release()
                }
            }.getOrNull()
        }
    }

    private companion object Sequential {
        /** Frames [count] at [Clip.FPS] from the start, read in short consecutive batches. */
        @android.annotation.TargetApi(28)
        fun sequential(r: MediaMetadataRetriever, durMs: Long, count: Int, tw: Int, th: Int, out: MutableList<Bitmap>) {
            val total = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: return
            if (total <= 0 || durMs <= 0) return
            val srcFps = total * 1000.0 / durMs
            val wanted = (0 until count).map { k -> (k * srcFps / Clip.FPS).toInt().coerceAtMost(total - 1) }
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
            var i = 0
            while (i < wanted.size) {
                // A batch of about 8 source frames, decoded in one go from the nearest keyframe.
                val start = wanted[i]
                var j = i
                while (j + 1 < wanted.size && wanted[j + 1] - start < 8) j++
                val batch = r.getFramesAtIndex(start, wanted[j] - start + 1, params)
                for (k in i..j) {
                    val src = batch.getOrNull(wanted[k] - start) ?: continue
                    out.add(if (src.width == tw && src.height == th) src.copy(Bitmap.Config.RGB_565, false) else Bitmap.createScaledBitmap(src, tw, th, true))
                }
                batch.forEach { it.recycle() }
                i = j + 1
            }
        }
    }

    /**
     * Loads on first use and can be dropped again, so a 60-second video never holds more than the
     * clips of the two scenes on screen during a transition.
     */
    class Lazy(private val loader: () -> SceneMedia?) {
        private var media: SceneMedia? = null
        private var tried = false

        fun get(): SceneMedia? {
            if (!tried) {
                tried = true
                media = runCatching { loader() }.getOrNull()
            }
            return media
        }

        fun release() {
            media?.release()
            media = null
            tried = false
        }
    }
}
