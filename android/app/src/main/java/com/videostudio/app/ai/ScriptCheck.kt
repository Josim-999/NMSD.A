package com.videostudio.app.ai

import com.videostudio.app.data.Scene
import java.util.Locale

/**
 * Scores a script against the playbook (0-100) and says exactly what to fix. Runs on the phone,
 * costs nothing; a low score triggers one targeted rewrite by the AI.
 */
object ScriptCheck {
    data class Result(val score: Int, val issues: List<String>) {
        val good: Boolean get() = score >= PASS
    }

    const val PASS = 80

    val BANNED = listOf(
        "basically", "essentially", "in today's world", "game-changer", "game changer", "revolutionary", "unlock",
        "unleash", "delve", "dive into", "let's dive", "it's important to note", "in conclusion", "whether you're",
        "the world of", "landscape", "leverage", "seamless", "cutting-edge", "cutting edge", "harness", "elevate", "supercharge",
    )
    val BAD_OPENERS = listOf("hey guys", "in this video", "did you know", "have you ever wondered", "today we", "let's talk about", "welcome")
    private val REHOOK = Regex("\\b(but|wait|here's|here is|the catch|nobody|most people|it gets|plot twist|turns out|until)\\b", RegexOption.IGNORE_CASE)

    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotBlank() }

    fun check(scenes: List<Scene>, lengthSec: Int, wordsPerSecond: Double = Prompts.WORDS_PER_SECOND): Result {
        if (scenes.isEmpty()) return Result(0, listOf("The script is empty."))
        val issues = ArrayList<String>()
        var score = 100
        fun fail(points: Int, why: String) { score -= points; issues += why }
        val all = scenes.joinToString(" ") { it.say }
        val lower = all.lowercase(Locale.ROOT)
        val hook = scenes.first().say.trim()
        val hookWords = words(hook).size

        if (hookWords > 12) fail(15, "The hook has $hookWords words; make it 5-10 punchy words.")
        if (hookWords < 3) fail(10, "The hook is too short to say anything; 5-10 words.")
        BAD_OPENERS.firstOrNull { hook.lowercase(Locale.ROOT).startsWith(it) }?.let { fail(15, "The hook starts with \"$it\": open with the claim or question itself.") }
        val hookSpecific = Regex("\\d").containsMatchIn(hook) || hook.split(" ").drop(1).any { it.firstOrNull()?.isUpperCase() == true } ||
            Regex("\\b(one|two|three|four|five|ten|hundred|thousand|million|billion|free|never|nobody|stop|wrong|secret)\\b", RegexOption.IGNORE_CASE).containsMatchIn(hook)
        if (!hookSpecific) fail(8, "The hook is vague: add a number, a named tool or a strong word (wrong, stop, nobody, free).")

        val banned = BANNED.filter { lower.contains(it) }
        if (banned.isNotEmpty()) fail(minOf(20, 5 * banned.size), "Remove cliché AI words: ${banned.joinToString()}.")

        val sentences = all.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val avg = if (sentences.isEmpty()) 0.0 else sentences.sumOf { words(it).size }.toDouble() / sentences.size
        if (avg > 14) fail(10, String.format(Locale.US, "Sentences average %.0f words; keep them at 4-12.", avg))

        val specifics = Regex("\\b\\d[\\d,.%]*\\b").findAll(all).count() +
            Regex("\\b(percent|million|billion|thousand|hundred)\\b", RegexOption.IGNORE_CASE).findAll(all).count() +
            scenes.count { it.brand.isNotBlank() || it.wiki.isNotBlank() }
        if (specifics < 3) fail(12, "Only $specifics specific numbers or names; use at least 3 (numbers, named tools, real examples).")

        val target = (lengthSec * wordsPerSecond).toInt()
        val total = words(all).size
        if (total < target * 0.85) fail(12, "Only $total words; ${lengthSec} s needs about $target. Add 2-4 concrete scenes.")
        if (total > target * 1.2) fail(6, "$total words is too long for ${lengthSec} s (about $target).")

        val middle = scenes.drop(2).dropLast(2)
        if (middle.size >= 4 && middle.none { REHOOK.containsMatchIn(it.say) }) fail(8, "No re-hook in the middle: add a line like \"But here's the part nobody mentions.\"")

        val last = scenes.last().say.trim()
        if (!last.contains('?') && scenes.takeLast(2).none { it.say.contains('?') }) fail(8, "End with a question that invites comments.")

        val you = Regex("\\byou(r|'re)?\\b", RegexOption.IGNORE_CASE).findAll(all).count()
        if (you < 2) fail(6, "Talk to the viewer: use \"you\" and \"your\".")

        val openers = scenes.map { words(it.say).firstOrNull()?.lowercase(Locale.ROOT) ?: "" }
        if (openers.zipWithNext().count { (a, b) -> a.isNotBlank() && a == b } >= 2) fail(4, "Several lines in a row start with the same word.")

        return Result(score.coerceIn(0, 100), issues)
    }
}
