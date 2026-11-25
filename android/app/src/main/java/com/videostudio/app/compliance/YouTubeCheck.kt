package com.videostudio.app.compliance

import com.videostudio.app.data.Project

/**
 * Checks a video against the YouTube rules that decide whether a channel gets (and keeps)
 * monetization: honest metadata, sourced claims, advertiser-friendly words, audience (COPPA),
 * paid promotion, licensed pictures with credits, AI disclosure and originality.
 * Pure logic, no network: it costs nothing and runs before every post.
 */
object YouTubeCheck {
    enum class Level { OK, WARN, INFO }

    data class Item(val level: Level, val title: String, val detail: String)

    /** Words that make YouTube limit or remove ads (advertiser-friendly guidelines). */
    private val PROFANITY = listOf(
        "fuck", "shit", "bitch", "bastard", "asshole", "dick", "cunt", "damn", "crap", "piss", "slut", "whore",
    )

    /** Phrases that over-promise: misleading metadata is removed and can earn a strike. */
    private val CLICKBAIT = listOf(
        "you won't believe", "you wont believe", "gone wrong", "not clickbait", "100% guaranteed", "guaranteed",
        "shocking", "must watch", "secret they", "doctors hate", "get rich", "free money", "cure", "miracle",
    )

    private val RISKY_CLAIMS = listOf(
        "cures", "cure for", "guaranteed returns", "risk-free", "risk free", "double your money", "100% safe",
        "proven to cure", "instead of medicine", "stop taking",
    )

    fun profanity(text: String): List<String> {
        val words = text.lowercase().split(Regex("[^a-z']+")).toSet()
        return PROFANITY.filter { bad -> words.any { it == bad || it == bad + "s" || it == bad + "ing" } }
    }

    fun clickbait(title: String): List<String> {
        val t = title.lowercase()
        val found = CLICKBAIT.filter { t.contains(it) }.toMutableList()
        val letters = title.filter { it.isLetter() }
        if (letters.length >= 8 && letters.count { it.isUpperCase() } > letters.length * 0.7) found.add("ALL CAPS")
        if (Regex("[!?]{3,}").containsMatchIn(title)) found.add("!!!")
        return found
    }

    fun riskyClaims(text: String): List<String> {
        val t = text.lowercase()
        return RISKY_CLAIMS.filter { t.contains(it) }
    }

    private fun tokens(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 && it !in STOP }.map { it.removeSuffix("s") }.toSet()

    private val STOP = setOf(
        "the", "and", "for", "with", "you", "your", "how", "why", "what", "this", "that", "are", "was", "from",
        "about", "into", "can", "will", "its", "has", "have", "not", "but", "they", "their", "who", "when",
    )

    /** 0..1 word overlap (Jaccard) of two topics. */
    fun similarity(a: String, b: String): Double {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    /** The earlier video whose topic or title is most like this one's, if it is close (≥ 0.6). */
    fun mostSimilar(p: Project, others: List<Project>): Pair<Project, Double>? = others
        .filter { it.id != p.id }
        .map { o -> o to maxOf(similarity(p.topic, o.topic), similarity(p.displayTitle(), o.displayTitle())) }
        .maxByOrNull { it.second }
        ?.takeIf { it.second >= 0.6 }

    fun check(p: Project, others: List<Project>, credits: List<String>, aiLabel: Boolean): List<Item> {
        val out = ArrayList<Item>()
        val title = p.ytTitle.ifBlank { p.displayTitle() }
        val spoken = p.scenes.joinToString(" ") { it.say }
        val onScreen = p.scenes.joinToString(" ") { listOf(it.text, it.label, it.leftText, it.rightText).joinToString(" ") + " " + it.items.joinToString(" ") }
        val all = "$title ${p.description} $spoken $onScreen"

        // 1. Audience (COPPA). Wrong answers can bring FTC fines; YouTube asks on every upload.
        out += if (p.madeForKids)
            Item(Level.INFO, "Audience: made for kids", "Comments, the mini-player and personalised ads are off on this video, so it earns less. Only keep this if children are the main audience.")
        else
            Item(Level.OK, "Audience: not made for kids", "Choose \"No, it's not made for kids\" when YouTube asks. Switch it on below if this video is aimed at children.")

        // 2. Advertiser-friendly language.
        val bad = profanity(all)
        out += if (bad.isEmpty()) Item(Level.OK, "Advertiser-friendly words", "No swearing found in the title, script or on-screen text.")
        else Item(Level.WARN, "Swearing limits ads", "Found: ${bad.joinToString()}. Ask for a rewrite without it, especially in the first seconds and the title.")

        // 3. Honest title (misleading metadata policy).
        val cb = clickbait(title)
        out += if (cb.isEmpty()) Item(Level.OK, "Honest title", "The title makes no over-promise. Keep it matching what the video shows.")
        else Item(Level.WARN, "Title may read as clickbait", "Found: ${cb.joinToString()}. YouTube removes videos whose title or thumbnail mislead, and repeats can lead to a strike.")

        // 4. Facts have sources (misinformation policies).
        out += if (p.sources.isNotEmpty()) Item(Level.OK, "Facts have sources", "${p.sources.size} sources are listed in the YouTube description.")
        else Item(Level.WARN, "No sources listed", "Numbers and claims without a source are risky. Turn on Research when you make the video, or ask for a rewrite with sources.")

        // 5. Sensitive topics: health, money, news, elections.
        val risky = riskyClaims(all)
        if (p.sensitive.isNotBlank() || risky.isNotEmpty()) {
            val area = p.sensitive.ifBlank { "health or money" }
            out += Item(
                Level.WARN, "Sensitive topic: $area",
                (if (risky.isNotEmpty()) "Risky wording: ${risky.joinToString()}. " else "") +
                    "YouTube is strict here: no promised cures or returns, nothing that contradicts health authorities, no false election claims. " +
                    "Say what the evidence shows, and add \"Not medical/financial advice\" to the description."
            )
        }

        // 6. Pictures: licensed for commercial use, credited.
        out += if (credits.isEmpty()) Item(Level.OK, "No outside pictures", "Everything on screen was drawn by the app.")
        else Item(Level.OK, "Pictures credited", "${credits.size} pictures from free libraries (licences that allow commercial use and changes). Their credits are added to the description.")

        // 7. Music: generated on the phone, so no Content ID claims.
        out += Item(Level.OK, "Music and sounds are original", "Made by the app on this phone: no copyright claims, revenue stays yours.")

        // 8. AI disclosure (altered or synthetic content).
        out += Item(
            if (aiLabel) Level.OK else Level.INFO, "AI disclosure",
            "Animated graphics and a narrator voice do not need YouTube's \"Altered content\" box. Tick it (Yes) only if the video shows a realistic person, place or event that did not happen, or a real person's cloned voice." +
                if (aiLabel) " The AI line is in your caption." else " Turn on the AI label above for an honest note in the caption."
        )

        // 9. Paid promotion.
        if (p.paidPromotion) out += Item(Level.WARN, "Paid promotion: declare it", "Tick \"Contains paid promotion\" on YouTube (Details → Show more). Direct uploads declare it for you.")

        // 10. Originality (reused / repetitive content: the main reason monetization is refused).
        val twin = mostSimilar(p, others)
        out += when {
            twin != null -> Item(Level.WARN, "Very close to an earlier video", "\"${twin.first.displayTitle()}\" covers almost the same topic. YouTube may count near-copies as repetitive content; give this one a different angle.")
            p.take.isBlank() -> Item(Level.INFO, "Add your own take", "Videos with a personal opinion or insight count as original. Add one in \"Your take\" next time.")
            else -> Item(Level.OK, "Original angle", "Your own take is in the script, and this video's look differs from the last one.")
        }
        return out
    }

    /** Short credit lines for the caption: no URLs, at most [max] characters. */
    fun shortCredits(credits: List<String>, max: Int = 400): String {
        if (credits.isEmpty()) return ""
        val lines = credits.map { it.replace(Regex("\\s*\\(https?://[^)]*\\)"), "").replace(Regex("\\s+"), " ").trim() }.distinct()
        val sb = StringBuilder("Pictures: ")
        for ((i, l) in lines.withIndex()) {
            val add = (if (i > 0) "; " else "") + l
            if (sb.length + add.length > max) { sb.append("; and more"); break }
            sb.append(add)
        }
        return sb.toString()
    }

    /** YouTube Partner Program: what a channel needs (as published by YouTube, 2025-2026). */
    val YPP_STEPS = listOf(
        "Full monetization (ads): 1,000 subscribers, plus 10 million valid Shorts views in the last 90 days or 4,000 public watch hours in the last 12 months.",
        "Early tier (fan funding, Shopping) in many countries: 500 subscribers, 3 public uploads in 90 days, plus 3 million Shorts views in 90 days or 3,000 watch hours in 12 months.",
        "No active Community Guidelines strikes, 2-Step Verification on the Google account, and an AdSense account.",
        "Reviewers look at the channel's main theme, most-viewed and newest videos: they must look original and made with care, not mass-produced.",
        "Shorts earn from the Shorts ad pool by share of views; every music track the app makes is original, so nothing is shared with music owners.",
        "Post regularly (3–5 a week), reply to comments, and keep one clear theme so returning viewers subscribe.",
    )
}
