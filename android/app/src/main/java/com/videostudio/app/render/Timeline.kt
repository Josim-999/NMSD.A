package com.videostudio.app.render

import android.graphics.Color
import com.videostudio.app.data.Scene
import com.videostudio.app.voice.Word
import java.util.Locale

class RenderError(message: String) : Exception(message)

data class Palette(
    val id: String,
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val accent: Int,
    val accent2: Int,
    val text: Int = Color.WHITE,
    val muted: Int = Color.argb(190, 255, 255, 255),
    val card: Int = Color.argb(46, 255, 255, 255),
    val bad: Int = Color.rgb(255, 82, 102),
    val good: Int = Color.rgb(61, 255, 160),
)

object Palettes {
    val all = listOf(
        Palette("electric", "Electric", Color.rgb(11, 15, 42), Color.rgb(46, 15, 84), Color.rgb(255, 210, 63), Color.rgb(0, 229, 255)),
        Palette("sunset", "Sunset", Color.rgb(26, 11, 31), Color.rgb(84, 21, 48), Color.rgb(255, 138, 61), Color.rgb(255, 61, 127)),
        Palette("mint", "Mint", Color.rgb(6, 32, 28), Color.rgb(11, 66, 56), Color.rgb(61, 255, 176), Color.rgb(182, 255, 61)),
        Palette("ocean", "Ocean", Color.rgb(3, 20, 46), Color.rgb(6, 64, 107), Color.rgb(79, 209, 255), Color.rgb(255, 224, 102)),
        Palette("mono", "Mono", Color.rgb(10, 10, 10), Color.rgb(34, 34, 34), Color.WHITE, Color.rgb(255, 59, 59)),
        Palette("noir", "Noir Gold", Color.rgb(8, 8, 10), Color.rgb(28, 24, 18), Color.rgb(232, 190, 92), Color.rgb(255, 244, 214)),
        Palette("aurora", "Aurora", Color.rgb(4, 18, 30), Color.rgb(38, 14, 64), Color.rgb(94, 255, 214), Color.rgb(176, 120, 255)),
        Palette("crimson", "Crimson", Color.rgb(24, 4, 8), Color.rgb(70, 10, 22), Color.rgb(255, 92, 92), Color.rgb(255, 214, 165)),
    )

    fun find(id: String) = all.firstOrNull { it.id == id } ?: all.first()

    /** Liquid-glass light theme: pastel mesh, charcoal text, purple → coral accents. */
    val GLASS_LIGHT = Palette(
        "glass", "Glass light", Color.rgb(247, 245, 251), Color.rgb(239, 234, 248),
        Color.rgb(124, 77, 255), Color.rgb(255, 92, 138), text = Color.rgb(31, 26, 46),
        muted = Color.argb(170, 31, 26, 46), card = Color.argb(150, 255, 255, 255),
        bad = Color.rgb(255, 77, 109), good = Color.rgb(34, 197, 94),
    )

    /** Liquid-glass dark theme for problems, myths and questions: midnight violet with neon accents. */
    val GLASS_DARK = Palette(
        "glassdark", "Glass dark", Color.rgb(14, 11, 26), Color.rgb(22, 17, 46),
        Color.rgb(179, 136, 255), Color.rgb(255, 111, 168), card = Color.argb(40, 255, 255, 255),
    )
}

data class TimedWord(val text: String, val start: Double, val end: Double, val scene: Int)
data class SceneSpan(val index: Int, val start: Double, val end: Double)
data class CaptionGroup(val words: List<TimedWord>, val start: Double, val end: Double)

/** Lines up the voice's word timings with the scenes, and groups words into captions. */
class Timeline(scenes: List<Scene>, words: List<Word>, audioSeconds: Double) {
    val words: List<TimedWord>
    val duration: Double
    val spans: List<SceneSpan>
    val captions: List<CaptionGroup>

    init {
        // Where each scene's spoken text sits in the full script.
        val full = StringBuilder()
        val ranges = scenes.map { s ->
            val from = full.length
            full.append(s.say.trim()).append(' ')
            from until full.length
        }
        val lower = full.toString().lowercase(Locale.ROOT)

        val timed = ArrayList<TimedWord>()
        var cursor = 0
        var scene = 0
        for (w in words) {
            val needle = w.text.lowercase(Locale.ROOT).trim()
            var at = if (needle.isEmpty()) -1 else lower.indexOf(needle, cursor)
            if (at < 0 || at - cursor > 60) at = -1
            if (at >= 0) {
                cursor = at + needle.length
                val idx = ranges.indexOfFirst { at in it }
                if (idx >= 0) scene = maxOf(scene, idx)
            }
            timed.add(TimedWord(w.text, w.start, w.end, scene))
        }
        this.words = timed
        duration = maxOf(audioSeconds, timed.lastOrNull()?.end ?: 0.0) + 0.6

        // Scene start = its first word; scenes without a matched word share the gap evenly.
        val n = scenes.size
        val starts = DoubleArray(n) { -1.0 }
        for (t in timed) if (starts[t.scene] < 0) starts[t.scene] = t.start
        if (n > 0) starts[0] = 0.0
        var i = 0
        while (i < n) {
            if (starts[i] < 0) {
                var j = i
                while (j < n && starts[j] < 0) j++
                val from = starts[i - 1]
                val step = ((if (j < n) starts[j] else duration) - from) / (j - i + 1)
                for (k in i until j) starts[k] = from + (k - i + 1) * step
                i = j
            } else {
                i++
            }
        }
        spans = scenes.indices.map { SceneSpan(it, starts[it], if (it + 1 < n) starts[it + 1] else duration) }

        // Captions: up to 3 words, same scene, no long pause, break after punctuation.
        val groups = ArrayList<CaptionGroup>()
        var current = ArrayList<TimedWord>()
        fun flush() {
            if (current.isNotEmpty()) groups.add(CaptionGroup(current.toList(), current.first().start, current.last().end))
            current = ArrayList()
        }
        for (t in timed) {
            val prev = current.lastOrNull()
            if (prev != null) {
                val keep = current.size < 3 && prev.scene == t.scene && t.start - prev.end <= 0.45 &&
                    !prev.text.endsWith(".") && !prev.text.endsWith("?") && !prev.text.endsWith("!") && !prev.text.endsWith(",")
                if (!keep) flush()
            }
            current.add(t)
        }
        flush()
        captions = groups.mapIndexed { idx, g ->
            var end = groups.getOrNull(idx + 1)?.start ?: (g.end + 0.4)
            if (end - g.end >= 0.6) end = g.end + 0.3
            CaptionGroup(g.words, g.start, end)
        }
    }

    fun sceneAt(t: Double): SceneSpan = spans.lastOrNull { t >= it.start } ?: spans.first()

    fun captionAt(t: Double): CaptionGroup? = captions.firstOrNull { t >= it.start && t < it.end }
}
