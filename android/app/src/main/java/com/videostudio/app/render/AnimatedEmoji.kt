package com.videostudio.app.render

import android.graphics.Canvas
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Google's Noto Animated Emoji (free, CC BY 4.0): every emoji in a video moves (the fire flickers,
 * the bulb glows) instead of standing still. Files are Lottie animations, fetched once and cached.
 */
object AnimatedEmoji {
    var base = "https://fonts.gstatic.com/s/e/notoemoji/latest/%s/lottie.json"
    const val CREDIT = "Animated emoji: Google Noto Emoji Animation (CC BY 4.0)"

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** "❤️" -> ["2764_fe0f", "2764"]: the names Google uses, with and without the variation selector. */
    fun names(emoji: String): List<String> {
        val all = emoji.codePoints().toArray().map { Integer.toHexString(it) }
        if (all.isEmpty()) return emptyList()
        val plain = all.filter { it != "fe0f" }
        return listOf(all.joinToString("_"), plain.joinToString("_")).distinct().filter { it.isNotEmpty() }
    }

    fun parse(json: String, key: String): LottieComposition? =
        runCatching { LottieCompositionFactory.fromJsonStringSync(json, key).value }.getOrNull()

    /** The animation for [emoji], or null (then the normal emoji is drawn). */
    fun load(emoji: String, cacheDir: File): LottieComposition? {
        cacheDir.mkdirs()
        for (n in names(emoji)) {
            val f = File(cacheDir, "noto_$n.json")
            if (!f.exists() || f.length() < 100) {
                val ok = runCatching {
                    http.newCall(Request.Builder().url(base.format(n)).header("User-Agent", "VideoStudioApp/1.9 (Android)").build())
                        .execute().use { r ->
                            if (!r.isSuccessful) return@use false
                            val body = r.body?.string() ?: return@use false
                            if (!body.trimStart().startsWith("{")) return@use false
                            f.writeText(body); true
                        }
                }.getOrDefault(false)
                if (!ok) continue
            }
            parse(f.readText(), "noto_$n")?.let { return it }
        }
        return null
    }
}

/** Draws one Lottie animation at any time t (seconds), looping. */
class Animoji(composition: LottieComposition) {
    private val drawable = LottieDrawable().apply { setComposition(composition) }
    private val seconds = (composition.duration / 1000f).coerceAtLeast(0.1f)

    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, t: Double, alpha: Float = 1f) {
        drawable.progress = ((t % seconds) / seconds).toFloat()
        drawable.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val h = size / 2
        drawable.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
        drawable.draw(c)
    }
}
