package com.videostudio.app.upload

import com.videostudio.app.compliance.YouTubeCheck
import com.videostudio.app.data.Project
import com.videostudio.app.data.Settings

/**
 * A caption made for each app's rules and habits, from the script's title, description, comment
 * question and hashtags (no extra AI call). Instagram and TikTok now allow at most 5 hashtags.
 */
object Captions {
    data class Rules(val maxChars: Int, val maxTags: Int, val extraTags: List<String> = emptyList(), val long: Boolean = true)

    val RULES = mapOf(
        "tiktok" to Rules(2200, 5, listOf("fyp")),
        "instagram" to Rules(2200, 5, listOf("reels")),
        "facebook" to Rules(5000, 3, listOf("reels")),
        "youtube" to Rules(100, 2, listOf("shorts"), long = false),
        "x" to Rules(280, 2, long = false),
        "threads" to Rules(500, 1),
        "linkedin" to Rules(3000, 3),
        "whatsapp" to Rules(700, 0, long = false),
    )

    private fun firstSentences(text: String, n: Int) =
        text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.take(n).joinToString(" ")

    /** Tags that belong to one platform only: never put #shorts on TikTok or #fyp on Instagram. */
    private val PLATFORM_TAGS = setOf("shorts", "youtubeshorts", "ytshorts", "fyp", "foryou", "foryoupage", "reels", "reel", "instareels", "tiktok")

    fun tags(p: Project, extra: List<String>, max: Int): List<String> =
        (extra + p.hashtags.filter { it.removePrefix("#").lowercase() !in PLATFORM_TAGS })
            .map { it.removePrefix("#").replace(Regex("[^\\p{L}\\p{N}_]"), "") }
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            .let { all -> if (extra.isEmpty()) all.take(max) else (all.drop(extra.size).take(max - extra.size) + all.take(extra.size)) }
            .take(max)

    /** The caption for [key] ("tiktok", "instagram", ...). */
    fun forPlatform(key: String, p: Project, s: Settings, credits: List<String> = emptyList()): String {
        val r = RULES[key] ?: RULES.getValue("tiktok")
        val title = p.ytTitle.ifBlank { p.displayTitle() }.trim()
        val hashtags = tags(p, r.extraTags, r.maxTags).joinToString(" ") { "#$it" }
        if (key == "youtube") {
            // The YouTube app's Shorts caption is the title: keep it within 100 characters.
            val withTags = "$title $hashtags".trim()
            return if (withTags.length <= r.maxChars) withTags else title.take(r.maxChars)
        }
        val parts = ArrayList<String>()
        parts += title
        when {
            r.long -> {
                val body = listOf(firstSentences(p.description, if (key == "facebook") 1 else 2), p.commentQuestion.trim()).filter { it.isNotBlank() }
                if (body.isNotEmpty()) parts += body.joinToString(" ")
            }
            p.commentQuestion.isNotBlank() -> parts += p.commentQuestion.trim()
        }
        val notes = ArrayList<String>()
        if (p.sensitive == "health") notes += "For information only, not medical advice."
        if (p.sensitive == "finance") notes += "For information only, not financial advice."
        if (p.paidPromotion) notes += "Includes paid promotion."
        if (r.long && credits.isNotEmpty()) notes += YouTubeCheck.shortCredits(credits, 300)
        if (r.long && s.aiLabel && s.aiLabelText.isNotBlank()) notes += s.aiLabelText.trim()
        if (notes.isNotEmpty()) parts += notes.joinToString("\n")
        if (hashtags.isNotBlank()) parts += hashtags
        var text = parts.joinToString("\n\n")
        if (text.length > r.maxChars) {
            // Too long (X): keep the title, the question and the tags.
            text = listOf(title, p.commentQuestion.trim(), hashtags).filter { it.isNotBlank() }.joinToString("\n\n").take(r.maxChars)
        }
        return text
    }

    fun all(keys: List<String>, p: Project, s: Settings, credits: List<String>) = keys.associateWith { forPlatform(it, p, s, credits) }
}
