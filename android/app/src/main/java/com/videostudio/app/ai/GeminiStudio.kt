package com.videostudio.app.ai

import com.videostudio.app.data.Project
import java.time.LocalDate

/**
 * Gemini premium script studio (the owner's choice, 3.9): the best Gemini Pro model on the user's key
 * (paid tier for full use), with Gemini Flash as the backup. Same three passes as [ClaudeStudio]:
 * live Google Search research into a dated fact sheet, the writer, then the editor/fact-checker.
 */
class GeminiStudio(
    apiKey: String,
    private val notice: (String) -> Unit = {},
    /** Models from Settings, tried first (like the original app: research and script on gemini-3.8-flash). */
    private val preferred: List<String> = emptyList(),
) : ScriptStudio {
    private val gemini = Gemini(apiKey, notice = notice, strict = true, maxWaitMs = 30_000)
    /** Pro first (newest), then Flash; never Flash-Lite or Gemma for scripts. */
    val chain: List<String> by lazy { Gemini.premiumChain(runCatching { gemini.available() }.getOrDefault(emptyList()), preferred) }
    private var used = ""

    override val name: String get() = "Gemini ${used.ifBlank { chain.firstOrNull() ?: "Pro" }}".replace("Gemini gemini-", "Gemini ")

    private fun run(system: String, prompt: String, json: Boolean, search: Boolean, temperature: Double): ClaudeStudio.Reply {
        val r = gemini.generate("$system\n\n$prompt", chain.first(), json = json, search = search, fallbacks = chain.drop(1), temperature = temperature)
        used = r.model
        return ClaudeStudio.Reply(r.text, r.sources)
    }

    override fun research(p: Project, today: LocalDate): ClaudeStudio.Reply {
        notice("Researching with Google Search on ${chain.firstOrNull() ?: "Gemini Pro"}…")
        return run(ClaudeStudio.RESEARCH_SYSTEM, ClaudeStudio.researchPrompt(p, today), json = false, search = true, temperature = 0.2)
    }

    override fun write(scriptPrompt: String, today: LocalDate): ClaudeStudio.Reply {
        notice("Writing the draft with $name…")
        return run(ClaudeStudio.WRITER_SYSTEM, "Today is $today.\n\n$scriptPrompt\n\n${ClaudeStudio.WRITER_ADDENDUM}", json = true, search = false, temperature = 0.75)
    }

    override fun edit(draftJson: String, factSheet: String, issues: List<String>, words: IntRange, today: LocalDate): ClaudeStudio.Reply {
        notice("Editing and fact-checking with $name…")
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
        return run(ClaudeStudio.EDITOR_SYSTEM, prompt, json = true, search = false, temperature = 0.35)
    }
}
