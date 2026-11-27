package com.videostudio.app.ai

import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import java.util.Locale

/**
 * The last check before a video is made (3.9, from the specialist's audit). Every rule here protects
 * the channel's reputation; a script that breaks one is never rendered or posted. The AI studio gets the
 * list and fixes it; if it still fails, the video is stopped with the reasons.
 */
object ContentGate {
    /** Words a complete phrase never ends on ("This period is sometimes", "Scale AI, Inc. is"). */
    private val DANGLING = setOf(
        "is", "are", "was", "were", "be", "been", "being", "include", "includes", "including", "the", "a", "an", "of", "to",
        "and", "or", "but", "nor", "sometimes", "with", "for", "by", "in", "on", "at", "that", "which", "who", "whose", "its",
        "their", "his", "her", "our", "your", "my", "as", "from", "into", "than", "because", "so", "very", "when", "while", "if",
        "has", "have", "had", "can", "will", "would", "could", "should", "may", "might", "must", "about", "such", "also", "then",
        "where", "whether", "although", "though", "since", "until", "unless", "via", "per", "like", "just", "only", "both", "each",
    )

    /** Endings that leave the viewer hanging ("And that is exactly why."). */
    private val DANGLING_END = Regex("(?i)\\b(exactly why|that'?s why|that is why|which is why|this is why|because|which means|and|but|so|or)\\W*$")

    /** Encyclopedia / corporate-bio sentences ("Scale AI, Inc. is an American ... company based in San Francisco"). */
    private val BIO = Regex("(?i)\\b(inc\\.?|corp\\.?|ltd\\.?|llc)\\s+is\\b|\\bis an? (?:[a-z]+ ){0,6}(?:company|corporation|organization|organisation|startup|firm) (?:based|headquartered|founded)\\b")

    private val GENERIC = setOf(
        "what", "when", "where", "which", "about", "your", "this", "that", "they", "them", "will", "with", "from", "have", "make", "makes",
        "take", "takes", "know", "next", "happens", "happen", "real", "really", "truth", "story", "thing", "things", "people", "most",
        "more", "than", "very", "just", "here", "there", "every", "into", "over", "under", "after", "before", "today", "becoming", "become",
        "secret", "secrets", "nobody", "everyone", "actually", "change", "changing", "changes", "forever", "future", "world", "life",
    )

    fun words(s: String) = s.split(Regex("[^\\p{L}\\p{N}'%]+")).filter { it.isNotBlank() }

    /** Topic words that identify the subject ("Gemini", "agents", "cybersecurity"), singular-ish. */
    fun topicWords(topic: String): List<String> = words(topic).map { it.lowercase(Locale.ROOT) }
        .filter { (it.length >= 4 || it.uppercase(Locale.ROOT) == it) && it !in GENERIC }.map { it.removeSuffix("s") }.distinct()

    private fun norm(s: String) = words(s.lowercase(Locale.ROOT)).joinToString(" ")

    /** Word stems that identify the subject, from the topic and the title ("subscriptions" -> "subscr"). */
    fun topicStems(p: Project): List<String> = (topicWords(p.topic) + topicWords(p.title)).map { if (it.length > 6) it.take(6) else it }.distinct()

    private fun mentions(s: Scene, stems: List<String>): Boolean {
        val t = (s.say + " " + s.text + " " + s.brand + " " + s.items.joinToString(" ")).lowercase(Locale.ROOT)
        return stems.any { t.contains(it) }
    }

    /** How many scenes mention the topic. */
    fun topicHits(p: Project): Int { val st = topicStems(p); return if (st.isEmpty()) p.scenes.size else p.scenes.count { mentions(it, st) } }

    /** Things the editor should improve that do not block the video (topic focus). */
    fun advice(p: Project): List<String> {
        val st = topicStems(p)
        if (st.isEmpty() || p.scenes.size < 3) return emptyList()
        val out = ArrayList<String>()
        val hits = topicHits(p)
        if (hits < kotlin.math.ceil(p.scenes.size * 0.3)) out += "Bring the topic (${st.take(3).joinToString("/")}) into more scenes: only $hits of ${p.scenes.size} mention it."
        if (p.scenes.drop(p.scenes.size * 2 / 3).none { mentions(it, st) }) out += "Bring the ending back to the topic (${st.take(3).joinToString("/")})."
        return out
    }

    /** Every rule the script breaks, in plain words (empty = ready to make). */
    fun check(p: Project, writer: String = p.writer, builtIn: String = "built-in writer"): List<String> {
        val issues = ArrayList<String>()
        val scenes = p.scenes
        if (writer == builtIn) issues += "The script was written without AI (Wikipedia sentences); it is never made into a video."
        if (scenes.size < 3) { issues += "The script has only ${scenes.size} scenes."; return issues }

        // 1. On-screen text and list items must be complete phrases, short enough to read.
        scenes.forEachIndexed { i, s ->
            val shown = listOf(s.text) + s.items
            for (t in shown.filter { it.isNotBlank() }) {
                // A full question or sentence ("What AI tools do you pay for?") is complete, whatever its last word.
                if (t.trimEnd().endsWith("?") || t.trimEnd().endsWith("!") || t.trimEnd().endsWith(".")) continue
                val last = words(t).lastOrNull()?.lowercase(Locale.ROOT)?.trim('\'') ?: continue
                if (last in DANGLING || t.trimEnd().endsWith(":") || t.trimEnd().endsWith(",")) issues += "Scene ${i + 1}: on-screen text is cut off (\"$t\"). Use a complete 2-6 word phrase."
            }
            if (s.type !in setOf("quote", "document") && words(s.text).size > 9) issues += "Scene ${i + 1}: on-screen text is too long (${words(s.text).size} words: \"${s.text.take(60)}\"). Use 2-6 words."
            if (BIO.containsMatchIn(s.say)) issues += "Scene ${i + 1}: encyclopedia/company-profile sentence (\"${s.say.take(70)}\"). Tell it as part of the story instead."
        }

        // 2. The last line is a complete sentence, not a dangling clause.
        val end = scenes.last().say.trim()
        if (end.isNotEmpty() && (!end.endsWith(".") && !end.endsWith("!") && !end.endsWith("?") || DANGLING_END.containsMatchIn(end.trimEnd('.', '!', '?', ' '))))
            issues += "The last line is cut off (\"${end.takeLast(60)}\"). End with a complete sentence that leads back into the hook."

        // 3. No repeated lines or cards.
        for (i in scenes.indices) for (j in i + 1 until scenes.size) {
            val a = scenes[i]; val b = scenes[j]
            val sameText = a.text.isNotBlank() && norm(a.text) == norm(b.text)
            val wa = words(a.say.lowercase(Locale.ROOT)).toSet(); val wb = words(b.say.lowercase(Locale.ROOT)).toSet()
            val overlap = if (wa.size >= 5 && wb.size >= 5) wa.intersect(wb).size.toDouble() / wa.union(wb).size else 0.0
            if (sameText || overlap >= 0.8) issues += "Scenes ${i + 1} and ${j + 1} repeat the same line or card. Every scene must say something new."
        }

        // 4. Blatant topic drift only (a script that never mentions its subject). Softer drift is advice for the editor.
        if (topicHits(p) == 0) issues += "The script never mentions its topic (${topicStems(p).take(3).joinToString("/")}). Keep every scene about the topic."

        // 5. Facts only: every number must be in the research or the sources.
        val nums = ScriptCheck.numbers(scenes)
        if (nums.isNotEmpty()) {
            val evidence = (p.researchNotes + " " + p.sources.joinToString(" ") { it.claim + " " + it.url }).replace(",", "")
            if (evidence.isBlank()) issues += "Numbers without any research or source (${nums.take(4).joinToString()})."
            else nums.filter { n -> !evidence.contains(n.replace(",", "").removeSuffix("%")) }.takeIf { it.isNotEmpty() }
                ?.let { issues += "Numbers not found in the research or sources: ${it.take(4).joinToString()}. Use only sourced numbers." }
        }
        return issues.distinct()
    }
}
