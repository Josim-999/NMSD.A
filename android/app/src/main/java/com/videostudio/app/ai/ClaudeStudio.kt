package com.videostudio.app.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20260209
import com.videostudio.app.data.Project
import java.time.Duration
import java.time.LocalDate

/**
 * Premium script studio (paid, the user's own Anthropic key). Three passes:
 *  1. Research: Claude searches the live web for the newest checkable facts, a real story and the angle.
 *  2. Writer: drafts the script from that fact sheet with the full playbook.
 *  3. Editor: scores the draft like a top Shorts editor, fact-checks every number against the
 *     fact sheet and rewrites it. The result is the same JSON scene format as the free writers.
 */
class ClaudeStudio(
    private val apiKey: String,
    val model: String = DEFAULT_MODEL,
    private val notice: (String) -> Unit = {},
) {
    data class Reply(val text: String, val sources: List<Pair<String, String>>)

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofMinutes(6)).maxRetries(2).build()
    }

    /** One request (with live web search when [search]); resumes `pause_turn` until Claude is done. */
    fun ask(system: String, user: String, search: Boolean = false, effort: OutputConfig.Effort = OutputConfig.Effort.HIGH): Reply {
        val history = ArrayList<MessageParam>()
        val sources = LinkedHashMap<String, String>()
        val text = StringBuilder()
        var reply: Message? = null
        for (round in 0 until 5) {
            val r = client.messages().create(params(system, user, search, effort, history))
            reply = r
            for (block in r.content()) {
                block.text().ifPresent { t ->
                    text.append(t.text())
                    t.citations().ifPresent { cs -> cs.forEach { c -> c.webSearchResultLocation().ifPresent { w -> sources.putIfAbsent(w.url(), w.title().orElse(w.url())) } } }
                }
                block.webSearchToolResult().ifPresent { res ->
                    res.content().resultBlocks().ifPresent { list -> list.forEach { sources.putIfAbsent(it.url(), it.title()) } }
                }
            }
            if (r.stopReason().orElse(null) != StopReason.PAUSE_TURN) break
            // The server paused a long search loop: send the turn back and it carries on.
            history.add(r.toParam())
        }
        if (reply?.stopReason()?.orElse(null) == StopReason.REFUSAL) throw IllegalStateException("Claude declined this topic.")
        if (text.isBlank()) throw IllegalStateException("Claude returned no text.")
        return Reply(text.toString(), sources.map { it.value to it.key })
    }

    /** The request for one round (exposed for tests). */
    fun params(system: String, user: String, search: Boolean, effort: OutputConfig.Effort, history: List<MessageParam> = emptyList()): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .outputConfig(OutputConfig.builder().effort(effort).build())
            // If a safety classifier declines, the server retries on a fallback model by itself.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessage(user)
        if (search) b.addTool(
            WebSearchTool20260209.builder()
                .maxUses(8L)
                .userLocation(UserLocation.builder().country("US").timezone("America/New_York").build())
                .build(),
        )
        history.forEach { b.addMessage(it) }
        return b.build()
    }

    /** Pass 1: live research into a dated fact sheet. */
    fun research(p: Project, today: LocalDate = LocalDate.now()): Reply {
        notice("Researching the live web with ${label(model)}…")
        return ask(RESEARCH_SYSTEM, researchPrompt(p, today), search = true)
    }

    /** Pass 2: the draft, from the standard script prompt (which already carries the fact sheet). */
    fun write(scriptPrompt: String, today: LocalDate = LocalDate.now()): Reply {
        notice("Writing the draft with ${label(model)}…")
        return ask(WRITER_SYSTEM, "Today is $today.\n\n$scriptPrompt\n\n$WRITER_ADDENDUM", effort = OutputConfig.Effort.HIGH)
    }

    /** Pass 3: editor and fact-checker; returns the improved script JSON. */
    fun edit(draftJson: String, factSheet: String, issues: List<String>, words: IntRange, today: LocalDate = LocalDate.now()): Reply {
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
        return ask(EDITOR_SYSTEM, prompt, effort = OutputConfig.Effort.HIGH)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
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

        private const val RESEARCH_SYSTEM = "You are the head researcher for a fact-checked educational Shorts channel. " +
            "You verify every number against its source, prefer the newest primary data, and say plainly when something could not be confirmed."

        private const val WRITER_SYSTEM = "You are a top YouTube Shorts scriptwriter for faceless educational channels with US audiences. " +
            "Your scripts hold attention for the full minute because every line either raises a question or pays one off, " +
            "and every number is true and sourced. You write the way people talk: short words, short sentences, concrete images."

        private const val WRITER_ADDENDUM = """
Before writing, privately draft five hooks with different techniques and keep the strongest; do not output the others.
Craft standards for this script:
- Hook (first scene, under 2.5 seconds): a concrete, surprising claim or question tied to the viewer. No greetings, no "did you know", no channel name.
- Story spine: hook -> why it matters to "you" -> a real named moment or example -> the key data reveal -> a twist or the reason behind it -> the payoff -> a last line that loops back into the first.
- Keep an open question running: answer each small question only after raising the next one.
- Every number comes from the research with its source in "sources"; say how recent it is when that matters ("as of 2026", "last year").
- Make big numbers feel real with one comparison the research supports.
- One idea per scene; vary sentence length; no filler, no hype words ("insane", "mind-blowing"), no clichés.
"""

        private const val EDITOR_SYSTEM = """You are the executive editor and fact-checker of a large educational Shorts channel. You get a draft script as JSON and the fact sheet it was written from.

Do this privately, then output only the final JSON:
1. Fact-check every claim, number, date, name and quote against the fact sheet. Anything not supported is cut or rewritten to what the sources say. Keep each "sources" entry matched to a real claim and URL from the fact sheet.
2. Score the draft from 1 to 10 on: hook strength in the first 2 seconds, curiosity running through the whole video, a real story or example, specific data made vivid, pacing (no line wasted), clarity for a 12-year-old, and an ending that loops into the start.
3. Rewrite every part that scored under 9. Keep the same schema and field names, the same scene types where they still fit, and the length asked for.
Never add a fact that is not in the fact sheet."""
    }
}
