package com.videostudio.app.render

/**
 * The visual style of one video. Channels whose videos all look the same risk being treated as
 * "repetitive / mass-produced" by YouTube's monetization review, so every new video gets a look
 * that differs from the previous one in captions, transition and background.
 */
data class Look(val captions: Int, val transition: Int, val background: Int) {
    val id: Int get() = captions + CAPTIONS * (transition + TRANSITIONS * background)

    val glass: Boolean get() = background == GLASS
    val paper: Boolean get() = background == PAPER
    val kinetic: Boolean get() = background == KINETIC

    val description: String
        get() = if (glass) "Liquid glass (light frosted UI, ${TRANSITION_NAMES[transition]} transitions)"
        else if (paper) "Paper sketch (marker lettering, highlighter captions, ${TRANSITION_NAMES[transition]} transitions)"
        else if (kinetic) "Kinetic type (spoken words slam in, ${CAPTION_NAMES[captions]} captions, ${TRANSITION_NAMES[transition]} transitions)"
        else "${CAPTION_NAMES[captions]} captions, ${TRANSITION_NAMES[transition]} transitions, ${BACKGROUND_NAMES[background]} background"

    companion object {
        const val CAPTIONS = 4
        const val TRANSITIONS = 4
        const val BACKGROUNDS = 6
        const val COUNT = CAPTIONS * TRANSITIONS * BACKGROUNDS

        val CAPTION_NAMES = listOf("chip", "colour-pop", "boxed", "glow")
        val TRANSITION_NAMES = listOf("3D push-blur", "slide-up", "whip-pan", "iris")
        val BACKGROUND_NAMES = listOf("soft-light", "grid", "aurora", "liquid-glass", "paper-sketch", "kinetic-type")

        /** The "liquid glass" family: light frosted UI, theme switches, blur-in captions. */
        const val GLASS = 3
        /** Whiteboard / paper explainer: cream paper, marker lettering, highlighter captions. */
        const val PAPER = 4
        /** Kinetic typography: huge spoken words slam in on a dark screen. */
        const val KINETIC = 5

        fun of(id: Int): Look {
            val i = ((id % COUNT) + COUNT) % COUNT
            return Look(i % CAPTIONS, (i / CAPTIONS) % TRANSITIONS, i / (CAPTIONS * TRANSITIONS))
        }

        /** The family a look belongs to: its background (soft-light, grid, aurora, glass, paper, kinetic). */
        fun family(id: Int) = of(id).background

        /**
         * A look for a new video that never repeats recent ones:
         * - the background family is the one used LEAST recently (all 6 families come round before any repeats);
         * - captions and transition differ from the last video;
         * - the exact look is not one of the recent ones ([history], newest first).
         */
        fun pickFresh(seed: Int, history: List<Int>, mode: String = "mix"): Int {
            val recent = history.filter { it in 0 until COUNT }
            val families = (0 until BACKGROUNDS).filter { b ->
                when (mode) {
                    "glass" -> b == GLASS; "paper" -> b == PAPER; "kinetic" -> b == KINETIC
                    "classic" -> b < GLASS
                    else -> true
                }
            }
            // Least recently used family: never used first, else the one whose last use is oldest.
            val lastUse = families.associateWith { f -> recent.indexOfFirst { family(it) == f }.let { if (it < 0) Int.MAX_VALUE else it } }
            val oldest = lastUse.values.maxOrNull() ?: 0
            val candidates = families.filter { lastUse[it] == oldest }
            val fam = candidates[Math.floorMod(seed, candidates.size)]
            val prev = recent.firstOrNull()?.let { of(it) }
            val options = (0 until COUNT).map { of(it) }.filter { l ->
                l.background == fam && l.id !in recent &&
                    (prev == null || (l.captions != prev.captions && l.transition != prev.transition))
            }.ifEmpty { (0 until COUNT).map { of(it) }.filter { it.background == fam && it.id != recent.firstOrNull() } }
            return options[Math.floorMod(seed / 7, options.size)].id
        }

        /** A look for a new video: every part differs from [last] (when there is one); [seed] picks among them. */
        fun pick(seed: Int, last: Int, mode: String = "mix"): Int {
            val prev = if (last in 0 until COUNT) of(last) else null
            val allowed = (0 until COUNT).map { of(it) }.filter { l ->
                when (mode) {
                    "glass" -> l.background == GLASS
                    "paper" -> l.background == PAPER
                    "kinetic" -> l.background == KINETIC
                    "classic" -> l.background < GLASS
                    else -> true
                }
            }
            val options = allowed.filter { l ->
                prev == null || (l.captions != prev.captions && l.transition != prev.transition &&
                    (l.background != prev.background || mode in setOf("glass", "paper", "kinetic")))
            }.ifEmpty { allowed }
            return options[((seed % options.size) + options.size) % options.size].id
        }
    }
}
