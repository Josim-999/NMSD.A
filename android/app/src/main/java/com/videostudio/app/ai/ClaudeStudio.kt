package com.videostudio.app.ai

import com.videostudio.app.data.Project
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Premium script studio (paid, the user's own Anthropic key). Three passes:
 *  1. Research: Claude searches the live web for the newest checkable facts, a real story and the angle.
 *  2. Writer: drafts the script from that fact sheet with the full playbook.
 *  3. Editor: scores the draft like a top Shorts editor, fact-checks every number against the
 *     fact sheet and rewrites it. The result is the same JSON scene format as the free writers.
 * Plain HTTPS with OkHttp, like every other AI service in the app (3.6: the SDK added ~10 MB and a
 * newer Kotlin runtime to the APK).
 */
/** A premium script writer: live research, a writer pass and an editor/fact-checker pass. */
interface ScriptStudio {
    /** Shown in the log and on the Script tab. */
    val name: String
    fun research(p: Project, today: LocalDate = LocalDate.now()): ClaudeStudio.Reply
    fun write(scriptPrompt: String, today: LocalDate = LocalDate.now()): ClaudeStudio.Reply
    fun edit(draftJson: String, factSheet: String, issues: List<String>, words: IntRange, today: LocalDate = LocalDate.now()): ClaudeStudio.Reply
}

class ClaudeStudio(
    private val apiKey: String,
    val model: String = DEFAULT_MODEL,
    private val notice: (String) -> Unit = {},
) : ScriptStudio {
    override val name: String get() = label(model)
    data class Reply(val text: String, val sources: List<Pair<String, String>>)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.MINUTES)
        .callTimeout(8, TimeUnit.MINUTES)
        .build()

    /** The request body for one round (exposed for tests). */
    fun body(system: String, user: String, search: Boolean, effort: String = "high", history: JSONArray = JSONArray()): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", user))
        for (i in 0 until history.length()) messages.put(history.get(i))
        val o = JSONObject()
            .put("model", model)
            .put("max_tokens", 16000)
            .put("system", system)
            .put("output_config", JSONObject().put("effort", effort))
            // If a safety classifier declines, the server retries on a fallback model by itself.
            .put("fallbacks", "default")
            .put("messages", messages)
        if (search) o.put("tools", JSONArray().put(
            JSONObject().put("type", "web_search_20260209").put("name", "web_search").put("max_uses", 8)
                .put("user_location", JSONObject().put("type", "approximate").put("country", "US").put("timezone", "America/New_York")),
        ))
        return o
    }

    /** Headers sent with every request (exposed for tests; the key itself is never logged). */
    fun headers(): Map<String, String> = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
        "anthropic-beta" to "server-side-fallback-2026-07-01",
        "content-type" to "application/json",
    )

    /** One request (with live web search when [search]); resumes `pause_turn` until Claude is done. */
    fun ask(system: String, user: String, search: Boolean = false, effort: String = "high"): Reply {
        val history = JSONArray()
        val sources = LinkedHashMap<String, String>()
        val text = StringBuilder()
        var stop = ""
        for (round in 0 until 5) {
            val req = Request.Builder().url(ENDPOINT).apply { headers().forEach { (k, v) -> header(k, v) } }
                .post(body(system, user, search, effort, history).toString().toRequestBody("application/json".toMediaType())).build()
            val r = http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                    throw IllegalStateException("Claude HTTP ${resp.code}${if (msg.isNotBlank()) ": " + msg.take(160) else ""}")
                }
                JSONObject(raw)
            }
            val content = r.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> {
                        text.append(block.optString("text"))
                        block.optJSONArray("citations")?.let { cs ->
                            for (k in 0 until cs.length()) cs.optJSONObject(k)?.let { c ->
                                val url = c.optString("url"); if (url.isNotBlank()) sources.putIfAbsent(url, c.optString("title").ifBlank { url })
                            }
                        }
                    }
                    "web_search_tool_result" -> block.optJSONArray("content")?.let { list ->
                        for (k in 0 until list.length()) list.optJSONObject(k)?.let { w ->
                            val url = w.optString("url"); if (url.isNotBlank()) sources.putIfAbsent(url, w.optString("title").ifBlank { url })
                        }
                    }
                }
            }
            stop = r.optString("stop_reason")
            if (stop != "pause_turn") break
            // The server paused a long search loop: send the turn back unchanged and it carries on.
            history.put(JSONObject().put("role", "assistant").put("content", content))
        }
        if (stop == "refusal") throw IllegalStateException("Claude declined this topic.")
        if (text.isBlank()) throw IllegalStateException("Claude returned no text.")
        return Reply(text.toString(), sources.map { it.value to it.key })
    }

    /** Pass 1: live research into a dated fact sheet. */
    override fun research(p: Project, today: LocalDate): Reply {
        notice("Researching the live web with ${label(model)}…")
        return ask(RESEARCH_SYSTEM, researchPrompt(p, today), search = true)
    }

    /** Pass 2: the draft, from the standard script prompt (which already carries the fact sheet). */
    override fun write(scriptPrompt: String, today: LocalDate): Reply {
        notice("Writing the draft with ${label(model)}…")
        return ask(WRITER_SYSTEM, "Today is $today.\n\n$scriptPrompt\n\n$WRITER_ADDENDUM", effort = "high")
    }

    /** Pass 3: editor and fact-checker; returns the improved script JSON. */
    override fun edit(draftJson: String, factSheet: String, issues: List<String>, words: IntRange, today: LocalDate): Reply {
        notice("Editing and fact-checking with ${label(model)}…")
        val prompt = """
Today is $today.
<fact_sheet>
$factSheet
</fact_sheet>
<draft>
$draftJson
</draft>
${if (issues.isEmpty()) "" else "The automatic checker also flagged: " + issues.joinToString(" ")}
Length: ${words.first}-${words.last} spoken words in total across all "say" fields.
Return the complete improved script as one JSON object in exactly the same schema as the draft, nothing else.
""".trim()
        return ask(EDITOR_SYSTEM, prompt, effort = "high")
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        val MODELS = listOf("claude-opus-5-5" to "Claude Opus 5.5 (best, ~US$0.45 a script)", "claude-sonnet-5-5" to "Claude Sonnet 5.5 (great, ~US$0.25 a script)")
        fun label(id: String) = when (id) { "claude-opus-5-5" -> "Claude Opus 5.5"; "claude-sonnet-5-5" -> "Claude Sonnet 5.5"; else -> id }

        fun researchPrompt(p: Project, today: LocalDate): String = """
Today is $today. Topic for a 50-60 second YouTube Short (US audience): ${p.topic}
${if (p.audience.isBlank()) "" else "Audience: ${p.audience}"}
${if (p.take.isBlank()) "" else "The creator's own take: ${p.take}"}

Search the web now. Prefer sources from the last 12 months and primary sources (official data, the study itself, government, the company, major outlets). Then write this fact sheet in plain text:

ANGLE: the one surprising, specific angle that would stop a scroll, in one sentence.
NEW: what changed most recently about this topic (with the date), or "nothing new" if nothing did.
STORY: one real, named moment (a person, place, date, event) that makes the topic human. Only if you found a source.
FACTS: 8 to 12 lines, each exactly: FACT | as-of date | source title | URL
  - A fact is one checkable claim with its number, unit and who measured it. Round nothing you can quote exactly.
  - If two good sources disagree, give both and say so.
COMPARISONS: 2-3 ways to make the biggest numbers feel real (e.g. "that is every person in Texas"), each computed from the facts above.
MYTHS: 1-3 things people wrongly believe here, with the fact that corrects each.
HOOK SEEDS: 5 opening lines of under 12 words, each using a different technique (shocking number, contradiction, "you" stakes, mystery question, mini-story).

Never invent a fact, number, quote, name or date. If you could not verify something, leave it out.
""".trim()

        const val RESEARCH_SYSTEM = "You are the head researcher for a fact-checked educational Shorts channel. " +
            "You verify every number against its source, prefer the newest primary data, and say plainly when something could not be confirmed."

        const val WRITER_SYSTEM = "You are a top YouTube Shorts scriptwriter for faceless educational channels with US audiences. " +
            "Your scripts hold attention for the full minute because every line either raises a question or pays one off, " +
            "and every number is true and sourced. You write the way people talk: short words, short sentences, concrete images."

        const val WRITER_ADDENDUM = """
Before writing, privately draft five hooks with different techniques and keep the strongest; do not output the others.
Craft standards for this script:
- Hook (first scene, under 2.5 seconds): a concrete, surprising claim or question tied to the viewer. No greetings, no "did you know", no channel name.
- Story spine: hook -> why it matters to "you" -> a real named moment or example -> the key data reveal -> a twist or the reason behind it -> the payoff -> a last line that loops back into the first.
- Keep an open question running: answer each small question only after raising the next one.
- Every number comes from the research with its source in "sources"; say how recent it is when that matters ("as of 2026", "last year").
- Make big numbers feel real with one comparison the research supports.
- One idea per scene; vary sentence length; no filler, no hype words ("insane", "mind-blowing"), no clichés.
- Every scene stays on the exact topic; the ending too. No encyclopedia sentences, no corporate bios.
- On-screen "text" is a complete 2-6 word phrase; list items are complete 1-4 word phrases; nothing is cut off.
- The last line is a complete sentence that leads back into the hook (never "and that is exactly why").
- Correct grammar, plurals and spelling of every name and term; no repeated lines or cards.
- Every scene has a "query": 2-4 concrete, filmable words for its background picture.
"""

        const val EDITOR_SYSTEM = """You are the executive editor and fact-checker of a large educational Shorts channel. You get a draft script as JSON and the fact sheet it was written from.

Do this privately, then output only the final JSON:
1. Fact-check every claim, number, date, name and quote against the fact sheet. Anything not supported is cut or rewritten to what the sources say. Keep each "sources" entry matched to a real claim and URL from the fact sheet.
2. Check the craft rules: every scene on the exact topic (no drift to other subjects); no encyclopedia or corporate-bio sentences; on-screen "text" and items are complete short phrases (never cut off); no repeated lines; correct grammar, plurals and spelling (e.g. "AI winters", not "AI winners"); the last line is a complete sentence; "stat" only for quantities, not years; every scene has a concrete filmable "query". Fix every break.
3. Score the draft from 1 to 10 on: hook strength in the first 2 seconds, curiosity running through the whole video, a real story or example, specific data made vivid, pacing (no line wasted), clarity for a 12-year-old, and an ending that loops into the start.
4. Rewrite every part that scored under 9. Keep the same schema and field names, the same scene types where they still fit, and the length asked for.
Never add a fact that is not in the fact sheet."""
    }
}
