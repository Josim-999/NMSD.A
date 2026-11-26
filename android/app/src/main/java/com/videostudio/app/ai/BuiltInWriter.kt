package com.videostudio.app.ai

import com.videostudio.app.data.Project
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The script writer that cannot run out: no AI, no key, no quota. It looks the topic up on
 * Wikipedia (free, no key) and builds a full Shorts script from the facts with the same beats the
 * AI uses (hook, promise, proof, payoff, comment question). Offline it still writes a structured
 * script from the topic alone. Used only when every free AI service is busy.
 */
class BuiltInWriter(
    private val wikiRest: String = "https://en.wikipedia.org/w/rest.php/v1/search/page",
    private val wikiSummary: String = "https://en.wikipedia.org/api/rest_v1/page/summary/",
    private val ddg: String = "https://api.duckduckgo.com/",
) {
    data class Fact(val text: String, val url: String, val page: String)

    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "VideoStudioApp/2.2 (Android)").build()).execute()
            .use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    /** Up to ~12 short, speakable fact sentences about [topic] from the top Wikipedia pages. */
    fun facts(topic: String): List<Fact> {
        val q = searchTerms(topic)
        if (q.isBlank()) return emptyList()
        val out = ArrayList<Fact>()
        val search = get(wikiRest.toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("limit", "3").build().toString())
        val pages = search?.let { runCatching { JSONObject(it).getJSONArray("pages") }.getOrNull() } ?: JSONArray()
        for (i in 0 until minOf(pages.length(), 3)) {
            val key = pages.optJSONObject(i)?.optString("key").orEmpty()
            if (key.isBlank()) continue
            val sum = get(wikiSummary + java.net.URLEncoder.encode(key, "UTF-8").replace("+", "_")) ?: continue
            val o = runCatching { JSONObject(sum) }.getOrNull() ?: continue
            if (o.optString("type") == "disambiguation") continue
            val url = o.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty()
            val title = o.optString("title")
            for (sentence in sentences(o.optString("extract"))) out.add(Fact(sentence, url, title))
        }
        // Second keyless source: DuckDuckGo's instant answer (a Wikipedia abstract) if Wikipedia refused.
        if (out.isEmpty()) runCatching {
            val o = JSONObject(get(ddg.toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("format", "json")
                .addQueryParameter("no_html", "1").addQueryParameter("skip_disambig", "1").build().toString()) ?: "{}")
            val url = o.optString("AbstractURL")
            for (sentence in sentences(o.optString("AbstractText"))) out.add(Fact(sentence, url, o.optString("Heading")))
        }
        return out.distinctBy { it.text.lowercase(Locale.ROOT) }.take(12)
    }

    /** A full script in the same JSON shape the AI returns. Never throws. */
    fun write(p: Project, extraHashtags: String, facts: List<Fact> = runCatching { facts(p.topic) }.getOrDefault(emptyList())): JSONObject {
        val topic = p.topic.trim().trimEnd('?', '.', '!')
        val isQuestion = Regex("^(how|why|what|when|where|who|is|are|can|do|does|did|should|will)\\b", RegexOption.IGNORE_CASE).containsMatchIn(topic)
        val howTo = Regex("^how\\b", RegexOption.IGNORE_CASE).containsMatchIn(topic)
        // What the video is about, as a noun: "Why do cats sleep so much" -> "cats".
        val subject = facts.firstOrNull()?.page?.ifBlank { null }
            ?: if (isQuestion) searchTerms(topic).split(" ").let { if (howTo) it.takeLast(2) else it.take(1) }.joinToString(" ").ifBlank { topic }
            else topic
        val maxWords = (p.lengthSec * 2.9).toInt().coerceAtLeast(60)
        val scenes = JSONArray()
        var words = 0
        fun add(say: String, visual: JSONObject, emphasis: String = "") {
            scenes.put(JSONObject().put("say", say).put("visual", visual).put("emphasis", emphasis.ifBlank { longestWord(say) }))
            words += say.split(Regex("\\s+")).size
        }
        val seed = topic.hashCode()
        // HOOK
        // Concrete-promise / bold-claim hook when a fact has a number (specific hooks beat vague ones).
        val numeric = facts.map { speakable(it.text) }.firstOrNull { Regex("\\d").containsMatchIn(it) && it.split(" ").size <= 18 }
        val hookSay = when {
            numeric != null -> shortText(numeric, 10).trimEnd('.') + ". Here's why."
            Regex("^(how|why|what|when|where|who|is|are|can|do|does)\\b", RegexOption.IGNORE_CASE).containsMatchIn(topic) -> "$topic?"
            else -> listOf(
                "What nobody tells you about $subject.",
                "$subject is not what you think.",
                "This changes how you see $subject.",
            )[Math.floorMod(seed, 3)]
        }
        add(hookSay, JSONObject().put("type", "hook").put("text", shortText(hookSay, 7)).put("emoji", emojiFor(topic)))
        // PROMISE
        add(
            "In ${p.lengthSec} seconds, you'll know the part most people miss.",
            JSONObject().put("type", "statement").put("text", "The part most people miss").put("highlight", "miss"),
        )
        // PROOF: the facts, each with a different visual
        val usable = facts.map { it.copy(text = speakable(it.text)) }.filter { it.text.split(" ").size in 5..26 }
        var photoUsed = 0
        var listDone = false
        for ((i, f) in usable.withIndex()) {
            if (words >= maxWords - 22) break
            val number = Regex("(\\d[\\d,.]*\\s*(%|percent|million|billion|thousand|years?|km|kg|°C)?)").find(f.text)?.value?.trim()
            val year = Regex("\\b(1[5-9]\\d\\d|20[0-4]\\d)\\b").find(f.text)?.value
            val visual = when {
                number != null && year == null && number.length <= 14 -> JSONObject().put("type", "stat")
                    .put("value", number.replace(" percent", "%")).put("label", shortText(f.text.replace(number, "").trim(), 6))
                photoUsed < 2 && i % 2 == 1 -> { photoUsed++; JSONObject().put("type", "photo").put("query", searchTerms(topic).take(40))
                    .put("wiki", f.page).put("text", shortText(f.text, 5)) }
                !listDone && usable.size - i >= 3 && i >= 2 -> { listDone = true
                    val items = usable.drop(i).take(3).map { shortText(it.text, 4) }
                    JSONObject().put("type", "list").put("text", "Quick facts").put("items", JSONArray(items)) }
                else -> JSONObject().put("type", "statement").put("text", shortText(f.text, 9)).put("highlight", longestWord(shortText(f.text, 9)))
            }
            add(f.text, visual)
            // A re-hook after the second fact keeps the middle from sagging.
            if (i == 1 && usable.size > 3) add("But here's the part most people miss.", JSONObject().put("type", "statement").put("text", "The part most people miss").put("highlight", "miss"))
        }
        if (usable.isEmpty()) {
            // Offline or nothing found: a clear, honest structure built from the topic itself.
            for ((say, v) in genericBeats(subject, howTo)) {
                if (words >= maxWords - 20) break
                add(say, v)
            }
        }
        // The creator's take (originality).
        if (p.take.isNotBlank()) add("Here's my take: ${p.take.trim().trimEnd('.')}.", JSONObject().put("type", "statement").put("text", shortText(p.take, 8)))
        // PAYOFF + comment question, looping back to the hook.
        add("So now you know the real story behind $subject.", JSONObject().put("type", "emoji").put("emoji", "💡").put("text", "Now you know"))
        val question = if (howTo) "What's stopping you from trying it today?" else "What surprised you most about $subject?"
        add(question, JSONObject().put("type", "outro").put("text", shortText(question, 8)).put("emoji", "💬"))

        val tags = (searchTerms(topic).lowercase(Locale.ROOT).split(" ").filter { it.length > 3 }.take(3) +
            extraHashtags.split(Regex("[\\s,#]+")).filter { it.isNotBlank() }.take(2)).distinct()
        val titleBase = titleCase(topic)
        return JSONObject()
            .put("title", titleBase)
            .put("one_idea", "The real story behind $subject")
            .put("comment_question", question)
            .put("mood", "energetic")
            .put("compliance", JSONObject().put("sensitive", "none").put("made_for_kids", false))
            .put("scenes", scenes)
            .put("sources", JSONArray(facts.map { it.url }.filter { it.isNotBlank() }.distinct().map { JSONObject().put("claim", "Facts about $subject").put("url", it) }))
            .put("publish", JSONObject()
                .put("titles", JSONArray(listOf("$titleBase: What You Missed", "The Truth About $titleBase", "$titleBase in ${p.lengthSec} Seconds").map { it.take(60) }))
                .put("description", (usable.firstOrNull()?.text ?: "Everything you need to know about $topic, fast.") + " Facts from Wikipedia.")
                .put("hashtags", JSONArray(tags)))
    }

    companion object {
        private val STOP = setOf("how", "why", "what", "when", "where", "who", "is", "are", "the", "a", "an", "to", "of", "in", "on",
            "for", "and", "your", "you", "do", "does", "can", "make", "makes", "my", "first", "best", "ways", "way", "really")

        fun searchTerms(topic: String): String = topic.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s-]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() && it !in STOP }.take(5).joinToString(" ")

        /** Sentences of an extract, without pronunciation brackets and stray spaces. */
        fun sentences(text: String): List<String> = speakable(text)
            .split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9])")).map { it.trim() }.filter { it.length in 25..220 }

        fun speakable(s: String): String = s.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s*\\[[^]]*]"), "")
            .replace(Regex("\\s+"), " ").trim()

        /** First [n] words, cut at a comma if one comes early. */
        fun shortText(s: String, n: Int): String {
            val clause = s.split(Regex("[,;:—–]")).first().trim()
            val w = (if (clause.split(" ").size >= 3) clause else s).trim().trimEnd('.', '?', '!').split(Regex("\\s+"))
            return w.take(n).joinToString(" ")
        }

        fun longestWord(s: String) = s.split(Regex("[^\\p{L}\\p{N}%]+")).maxByOrNull { it.length }.orEmpty()

        fun titleCase(s: String) = s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

        fun emojiFor(topic: String): String {
            val t = topic.lowercase(Locale.ROOT)
            return when {
                listOf("ai", "robot", "chatgpt", "gemini", "claude", "tech").any { Regex("\\b$it\\b").containsMatchIn(t) } -> "🤖"
                listOf("money", "earn", "rich", "invest", "business").any { t.contains(it) } -> "💰"
                listOf("health", "sleep", "food", "body", "brain").any { t.contains(it) } -> "🧠"
                listOf("space", "planet", "star", "moon").any { t.contains(it) } -> "🚀"
                listOf("video", "youtube", "shorts", "edit").any { t.contains(it) } -> "🎬"
                else -> "🤯"
            }
        }

        private fun genericBeats(subject: String, howTo: Boolean): List<Pair<String, JSONObject>> = if (howTo) listOf(
            "Most people get $subject wrong at the very first step." to JSONObject().put("type", "compare").put("left", "MYTH")
                .put("leftText", "You need to be an expert").put("right", "FACT").put("rightText", "You need a simple system"),
            "Here is the simple version, in three steps." to JSONObject().put("type", "steps").put("text", "The simple version")
                .put("items", JSONArray(listOf("Start small", "Repeat daily", "Improve one thing"))),
            "Start with the smallest version you can finish today." to JSONObject().put("type", "statement").put("text", "Start with the smallest version").put("highlight", "smallest"),
            "Then repeat it, and fix one thing every time." to JSONObject().put("type", "statement").put("text", "Repeat and fix one thing").put("highlight", "Repeat"),
            "That is how beginners quietly beat people who only plan." to JSONObject().put("type", "emoji").put("emoji", "🏆").put("text", "Doers beat planners"),
        ) else listOf(
            "Most people think it's random. It isn't." to JSONObject().put("type", "compare").put("left", "MYTH")
                .put("leftText", "It just happens").put("right", "FACT").put("rightText", "There is a clear reason"),
            "The real reason is simpler than you think." to JSONObject().put("type", "statement").put("text", "Simpler than you think").put("highlight", "Simpler"),
            "Once you see it, you notice it everywhere." to JSONObject().put("type", "emoji").put("emoji", "👀").put("text", "You'll see it everywhere"),
            "And it says more about $subject than most people realise." to JSONObject().put("type", "statement").put("text", "It says more than you think").put("highlight", "more"),
        )
    }
}
