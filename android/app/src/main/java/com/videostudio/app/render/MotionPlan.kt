package com.videostudio.app.render

/**
 * The motion "vocabulary" of the app (about 115 named techniques) and the plan that picks from it
 * for one video: per scene a text entrance, a camera move, a transition in and maybe an overlay;
 * per emphasis word a hit effect; per video a background motif and a caption variant.
 * Rules: never the same choice on two scenes in a row, least-used first inside a video, and the
 * video-level choices rotate across videos (via [history]) so no two videos feel the same.
 */
class MotionPlan(
    seed: Int,
    val sceneTypes: List<String>,
    val look: Look,
    /** Recent videos' signatures ("motif:caption"), newest first. */
    history: List<String> = emptyList(),
) {
    val entrance = IntArray(sceneTypes.size)
    val camera = IntArray(sceneTypes.size)
    val transition = IntArray(sceneTypes.size)
    val overlay = IntArray(sceneTypes.size)
    val motif: Int
    val captionVariant: Int
    private val rnd = java.util.Random(seed.toLong() * 31 + 7)

    init {
        // Video-level: background motif and caption variant, least recently used across videos.
        val motifs = if (look.paper) PAPER_MOTIFS else if (look.glass) GLASS_MOTIFS else (1 until MOTIFS.size).toList()
        val usedMotifs = history.mapNotNull { it.substringBefore(':').toIntOrNull() }
        motif = leastRecent(motifs, usedMotifs)
        val captionChoices = if (look.glass || look.paper) listOf(look.captions) else (0 until CAPTIONS.size - 2).toList()
        val usedCaps = history.mapNotNull { it.substringAfter(':', "").toIntOrNull() }
        captionVariant = leastRecent(captionChoices, usedCaps)

        val entrances = (1 until ENTRANCES.size).toList()
        val cameras = (1 until CAMERAS.size).toList()
        val overlays = (1 until OVERLAYS.size).toList()
        val transitions = (0 until TRANSITIONS.size).toList()
        val counts = HashMap<String, Int>()
        fun pick(group: String, options: List<Int>, avoid: Int): Int {
            val pool = options.filter { it != avoid }.ifEmpty { options }
            val min = pool.minOf { counts["$group$it"] ?: 0 }
            val least = pool.filter { (counts["$group$it"] ?: 0) == min }
            val v = least[rnd.nextInt(least.size)]
            counts["$group$v"] = (counts["$group$v"] ?: 0) + 1
            return v
        }
        for (i in sceneTypes.indices) {
            val type = sceneTypes[i]
            // Scene 1 must be fully readable on its first frame (the feed preview): no entrance, no camera trick.
            entrance[i] = if (i == 0 || type in OWN_MOTION) 0 else pick("e", entrances, entrance[i - 1])
            camera[i] = if (i == 0 || type == "logo") 0 else pick("c", cameras, if (i > 0) camera[i - 1] else -1)
            // Every third cut uses the look's signature transition, the rest rotate through the library.
            transition[i] = when {
                i == 0 -> 0
                i % 3 == 0 && look.transition != transition[i - 1] -> look.transition
                else -> pick("t", transitions, transition[i - 1])
            }
            overlay[i] = if (i == 0 || type in BUSY || rnd.nextInt(10) < 5) 0 else pick("o", overlays, if (i > 0) overlay[i - 1] else -1)
        }
    }

    /** The hit effect for the n-th emphasis word of the video (a different one each time). */
    fun emphasis(n: Int): Int = 1 + Math.floorMod(n * 5 + motif * 3, EMPHASIS.size - 1)

    val signature: String get() = "$motif:$captionVariant"

    /** Every technique this video uses, by name (for the log and the Script tab). */
    fun describe(): String {
        val e = entrance.filter { it > 0 }.distinct().joinToString { ENTRANCES[it] }
        val t = transition.drop(1).distinct().joinToString { TRANSITIONS[it] }
        return "motif ${MOTIFS[motif]}, captions ${CAPTIONS[captionVariant]}, entrances: $e; transitions: $t"
    }

    companion object {
        val ENTRANCES = listOf(
            "settle", "slide-up", "slide-down", "slide-in-right", "slide-in-left", "slam", "giant-zoom-out", "pop",
            "typewriter", "mask-reveal-up", "mask-reveal-left", "split-open", "flip-x", "flip-y", "elastic-stretch",
            "bounce-drop", "blur-in", "glitch-in", "tracking-in", "rotate-in",
        )
        val EMPHASIS = listOf(
            "zoom-punch", "shake", "color-flash", "glow-pulse", "sparkle-burst", "shine-sweep", "jelly", "wiggle",
            "ring-burst", "speed-lines", "starburst", "mini-confetti", "screen-bounce", "rgb-flash", "letterbox-slam",
        )
        val TRANSITIONS = listOf(
            "3D push-blur", "slide-up", "whip-pan", "iris", "swipe-left", "swipe-right", "zoom-blur-out", "dip-to-black",
            "flash-white", "doors-vertical", "doors-horizontal", "blinds", "clock-wipe", "diagonal-wipe", "corner-circle",
            "glitch-cut", "cube-rotate", "card-flip", "slide-over", "spin-zoom", "ink-drop",
        )
        val MOTIFS = listOf(
            "none", "starfield", "drifting-particles", "bokeh", "moving-stripes", "halftone", "pulse-rings",
            "topographic", "light-sweep", "wave-lines", "floating-shapes", "scanlines", "light-leaks",
        )
        val OVERLAYS = listOf(
            "none", "corner-brackets", "sparkles", "lens-flare", "floating-shapes", "timer-ring", "arrow-pointer",
            "highlight-ring", "light-leak-flash", "scan-band", "orbit-dots", "mini-confetti",
        )
        val CAMERAS = listOf(
            "push-in", "pull-out", "pan-left", "pan-right", "dutch-tilt", "handheld", "orbit-sway", "crash-zoom",
            "float-bob", "rotate-settle",
        )
        val CAPTIONS = listOf("chip", "colour-pop", "boxed", "glow", "karaoke-fill", "bounce-word", "glass-blur-in", "marker")
        val BACKGROUND_FAMILIES = Look.BACKGROUND_NAMES
        val DATA_GRAPHICS = listOf(
            "bar-chart", "donut-chart", "line-graph", "counter", "progress-ring", "list", "steps", "myth-vs-fact",
            "timeline", "tier-list", "map", "document",
        )

        /** Scenes with their own built-in motion: no extra text entrance on top. */
        val OWN_MOTION = setOf("hook", "chart", "tier", "map", "document", "timeline", "logo")
        /** Scenes already full of graphics: no extra overlay. */
        val BUSY = setOf("chart", "tier", "map", "document", "photo", "compare", "list", "steps", "timeline")
        val PAPER_MOTIFS = listOf(0, 5, 7, 9)
        val GLASS_MOTIFS = listOf(0, 3, 6, 8, 10)

        /** Total named techniques in the library. */
        val TOTAL: Int get() = (ENTRANCES.size) + EMPHASIS.size + TRANSITIONS.size + BACKGROUND_FAMILIES.size * 1 + (MOTIFS.size - 1) +
            (OVERLAYS.size - 1) + CAMERAS.size + DATA_GRAPHICS.size + CAPTIONS.size

        private fun leastRecent(options: List<Int>, used: List<Int>): Int {
            val lastUse = options.associateWith { o -> used.indexOf(o).let { if (it < 0) Int.MAX_VALUE else it } }
            val oldest = lastUse.values.maxOrNull() ?: 0
            return options.first { lastUse[it] == oldest }
        }
    }
}
