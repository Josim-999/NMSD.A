package com.videostudio.app.voice

/** Text tweaks for the voice only (the captions keep the original words). */
object Speech {
    private val ACRONYM = Regex("\\b[B-DF-HJ-NP-TV-XZ]{2,6}s?\\b")

    /**
     * Acronyms without a vowel can only be spelled ("RLHF" is never a word), so they are written with hyphens
     * ("R-L-H-F") and every voice reads them letter by letter instead of guessing.
     */
    fun forVoice(text: String): String = ACRONYM.replace(text) { m ->
        val v = m.value
        val plural = v.length > 2 && v.endsWith("s")
        val core = if (plural) v.dropLast(1) else v
        core.toCharArray().joinToString("-") + if (plural) "s" else ""
    }
}
