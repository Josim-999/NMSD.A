package com.videostudio.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Looks at a picture before it goes into the video (3.9): Gemini Flash says whether it really shows what
 * the scene is about. Stops museum guitars, sarcophagi and trail signs behind a line about AI.
 */
class VisionCheck(apiKey: String, private val notice: (String) -> Unit = {}) {
    private val gemini = Gemini(apiKey, notice = {}, strict = true, maxWaitMs = 8_000)
    private val chain: List<String> by lazy { Gemini.visionChain(runCatching { gemini.available() }.getOrDefault(emptyList())) }

    /** true = fits, false = unrelated, null = could not check (no quota or no internet). */
    fun fits(image: File, query: String, line: String): Boolean? {
        val jpeg = small(image) ?: return null
        return runCatching {
            val r = gemini.generate(prompt(query, line), chain.first(), fallbacks = chain.drop(1).take(2), image = jpeg)
            answer(r.text)
        }.getOrNull()
    }

    companion object {
        fun prompt(query: String, line: String) = """
You check background pictures for a short educational video, so viewers never see something unrelated.
The narrator says: "${line.take(300)}"
The picture should show: "$query"
Answer with exactly one word.
YES: the picture clearly shows that subject, or something directly about it (the same kind of object, place, technology or activity).
NO: it shows something unrelated or misleading (a different object, place, era or topic; a random sign, map, artwork or document).
""".trim()

        /** Reads YES/NO; anything unclear counts as NO (a wrong picture is worse than none). */
        fun answer(text: String): Boolean = text.trim().uppercase().let { it.startsWith("YES") && !it.startsWith("YES, BUT") }

        /** A 384 px JPEG: enough to judge, quick to send. */
        fun small(f: File): ByteArray? = runCatching {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, b)
            var sample = 1
            while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= 384) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            ByteArrayOutputStream().use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 80, o); bmp.recycle(); o.toByteArray() }
        }.getOrNull()
    }
}
