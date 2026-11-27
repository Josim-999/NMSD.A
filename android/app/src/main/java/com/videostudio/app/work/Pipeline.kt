package com.videostudio.app.work

import com.videostudio.app.compliance.YouTubeCheck
import com.videostudio.app.ai.BrandSpotter
import com.videostudio.app.ai.ClaudeStudio
import com.videostudio.app.ai.BackupAi
import com.videostudio.app.ai.BuiltInWriter
import com.videostudio.app.ai.ScriptCheck
import com.videostudio.app.ai.GeminiReply
import com.videostudio.app.render.AnimatedEmoji
import com.videostudio.app.render.Cutout
import com.videostudio.app.render.Segmenter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.videostudio.app.ai.Gemini
import com.videostudio.app.ai.GeminiError
import com.videostudio.app.ai.GeminiQuotaError
import com.videostudio.app.ai.Logo
import com.videostudio.app.ai.MediaFinder
import com.videostudio.app.ai.Prompts
import com.videostudio.app.ai.Stock
import com.videostudio.app.ai.extractJson
import com.videostudio.app.data.Project
import com.videostudio.app.data.ProjectStore
import com.videostudio.app.data.Scene
import com.videostudio.app.data.Settings
import com.videostudio.app.data.Source
import com.videostudio.app.data.Status
import com.videostudio.app.data.Upload
import com.videostudio.app.data.objects
import com.videostudio.app.data.strings
import com.videostudio.app.render.AudioMix
import com.videostudio.app.render.Look
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.SceneMedia
import com.videostudio.app.render.MotionPlan
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.render.VideoRenderer
import com.videostudio.app.upload.YouTube
import com.videostudio.app.voice.DeviceTts
import com.videostudio.app.voice.EdgeTts
import com.videostudio.app.voice.GeminiTts
import com.videostudio.app.voice.VoiceResult
import com.videostudio.app.voice.Voices
import com.videostudio.app.voice.Word
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** The steps of making a video. Each runs inside a job; [log] and [step] feed the screen. */
class Pipeline(
    private val context: Context,
    private val store: ProjectStore,
    private val settings: Settings,
    private val log: (String) -> Unit,
    private val step: (String, Float?) -> Unit,
    private val cancelled: () -> Boolean,
) {
    private fun gemini() = Gemini(settings.geminiKey, notice = log, autoSwitch = settings.autoSwitchModels)

    fun writeScript(p: Project, feedback: String = "") {
        // Every video is 50-60 s (older projects may still say 30 or 45).
        p.lengthSec = p.lengthSec.coerceIn(Prompts.MIN_SEC, Prompts.MAX_SEC)
        // Premium studio first (live research, writer, editor/fact-checker); the free chain is the backup.
        if (settings.premiumScript && settings.anthropicKey.isNotBlank()) {
            val done = runCatching { studioScript(p, feedback) }.onFailure {
                if (cancelled()) throw it
                log("Premium studio failed (${it.message?.take(100)}); using the free writers instead.")
            }.getOrDefault(false)
            if (done) return
        }
        if (p.research && p.researchNotes.isBlank()) {
            step("Researching the topic (Google Search)", null)
            log("Researching with ${settings.researchModel}…")
            // Research is a bonus: if no model has quota left for it, write the script without it.
            val reply = try {
                gemini().generate(Prompts.research(p), settings.researchModel, search = true, fallbacks = listOf(settings.scriptModel))
            } catch (e: GeminiError) {
                // Free, keyless research instead: facts from Wikipedia with their sources.
                val facts = runCatching { BuiltInWriter().facts(p.topic) }.getOrDefault(emptyList())
                if (facts.isNotEmpty()) {
                    p.researchNotes = facts.joinToString("\n") { "${it.text} — ${it.url}" }
                    store.save(p)
                    log("Gemini research is used up; using ${facts.size} facts from Wikipedia instead.")
                } else log("Research skipped (${e.message?.take(80)}). Writing from general knowledge.")
                null
            }
            if (reply != null) {
                val pages = reply.sources.distinctBy { it.second }.joinToString("\n") { "- ${it.first}: ${it.second}" }
                p.researchNotes = reply.text + if (pages.isNotBlank()) "\n\nPages searched:\n$pages" else ""
                store.save(p)
                log("Research done with ${reply.model} (${reply.sources.size} pages).")
            }
        }
        if (cancelled()) return
        step(if (feedback.isBlank()) "Writing the script" else "Rewriting the script", null)
        log("Writing the script with ${settings.scriptModel}…")
        val current = if (feedback.isBlank()) "" else JSONObject().put("scenes", JSONArray(p.scenes.map { it.toJson() })).toString(1)
        val prompt = Prompts.script(
            p, settings.seriesName, settings.seriesPromise, settings.defaultHashtags, feedback, current,
            stockFootage = settings.pexelsKey.isNotBlank() || settings.pixabayKey.isNotBlank(),
            take = p.take,
        )
        // Never fails: if every free AI is busy, the built-in writer (no AI, no limits) writes it.
        val reply = try {
            scriptReply(prompt)
        } catch (e: Exception) {
            if (cancelled()) throw e
            log(if (e is GeminiQuotaError) "Every free AI is busy right now, so the built-in writer is writing this script (no limits)."
                else "The AI could not be reached (${e.message?.take(80)}), so the built-in writer is writing this script.")
            GeminiReply(BuiltInWriter().write(p, settings.defaultHashtags).toString(), emptyList(), BUILT_IN)
        }
        p.writer = reply.model
        applyScript(p, extractJson(reply.text))
        // Quality gate: score against the playbook; below the bar, one targeted rewrite fixes exactly what is wrong.
        var check = ScriptCheck.check(p.scenes, p.lengthSec, research = p.researchNotes, sources = p.sources.map { it.claim + " " + it.url })
        log("Script score: ${check.score}/100${if (check.issues.isNotEmpty()) " (" + check.issues.size + " things to fix)" else ""}.")
        if (reply.model != BUILT_IN && !check.good) {
            log("Improving it: ${check.issues.joinToString(" ").take(220)}")
            runCatching {
                val current2 = JSONObject().put("scenes", JSONArray(p.scenes.map { it.toJson() })).toString(1)
                val better = scriptReply(Prompts.script(
                    p, settings.seriesName, settings.seriesPromise, settings.defaultHashtags,
                    "Rewrite to pass the playbook. Fix ALL of these: " + check.issues.joinToString(" ") +
                        " Keep what already works, keep it about ${(p.lengthSec * Prompts.WORDS_PER_SECOND).toInt()} words.",
                    current2, stockFootage = settings.pexelsKey.isNotBlank() || settings.pixabayKey.isNotBlank(), take = p.take,
                ))
                val o = extractJson(better.text)
                val candidate = objects(o.optJSONArray("scenes")).map { Scene.fromJson(it) }.filter { it.say.isNotBlank() }
                val c2 = ScriptCheck.check(candidate, p.lengthSec, research = p.researchNotes, sources = p.sources.map { it.claim + " " + it.url })
                if (candidate.size >= 3 && c2.score > check.score) {
                    applyScript(p, o)
                    check = c2
                    log("Improved script: ${c2.score}/100.")
                } else log("The rewrite was not better (${c2.score}/100); keeping the first version.")
            }.onFailure { log("Could not improve the script (${it.message?.take(60)}); keeping it.") }
        }
        ensureLength(p, reply.model != BUILT_IN)
        check = ScriptCheck.check(p.scenes, p.lengthSec, research = p.researchNotes, sources = p.sources.map { it.claim + " " + it.url })
        p.scriptScore = check.score
        p.status = Status.SCRIPTED
        p.error = ""
        store.save(p)
        val words = p.scenes.sumOf { it.say.split(Regex("\\s+")).size }
        log("Script ready with ${reply.model}: ${p.scenes.size} scenes, $words words.")
    }

    /** The premium three-pass studio. Returns true when the script is ready. */
    private fun studioScript(p: Project, feedback: String): Boolean {
        val studio = ClaudeStudio(settings.anthropicKey, settings.premiumModel, notice = log)
        val name = ClaudeStudio.label(studio.model)
        // Fresh research every new script (rewrites reuse the sheet they were written from).
        if (feedback.isBlank() || !p.researchNotes.startsWith(STUDIO_MARK)) {
            step("Researching the live web", null)
            val r = studio.research(p)
            val pages = r.sources.distinctBy { it.second }.take(20).joinToString("\n") { "- ${it.first}: ${it.second}" }
            p.researchNotes = STUDIO_MARK + "\n" + r.text.trim() + if (pages.isNotBlank()) "\n\nPages read:\n$pages" else ""
            store.save(p)
            log("Research done: ${r.sources.size} pages read.")
        }
        if (cancelled()) return false
        step(if (feedback.isBlank()) "Writing the script (premium)" else "Rewriting the script (premium)", null)
        val current = if (feedback.isBlank()) "" else JSONObject().put("scenes", JSONArray(p.scenes.map { it.toJson() })).toString(1)
        val prompt = Prompts.script(
            p, settings.seriesName, settings.seriesPromise, settings.defaultHashtags, feedback, current,
            stockFootage = settings.pexelsKey.isNotBlank() || settings.pixabayKey.isNotBlank(), take = p.take,
        )
        val draft = extractJson(studio.write(prompt).text)
        if (objects(draft.optJSONArray("scenes")).size < 3) throw IllegalStateException("the draft had no scenes")
        applyScript(p, draft)
        val src = { p.sources.map { it.claim + " " + it.url } }
        var check = ScriptCheck.check(p.scenes, p.lengthSec, research = p.researchNotes, sources = src())
        log("Draft score: ${check.score}/100.")
        if (cancelled()) return false
        step("Editing and fact-checking (premium)", null)
        runCatching {
            val edited = extractJson(studio.edit(draft.toString(1), p.researchNotes, check.issues, Prompts.wordRange(p.lengthSec)).text)
            val scenes = objects(edited.optJSONArray("scenes")).map { Scene.fromJson(it) }.filter { it.say.isNotBlank() }
            if (scenes.size >= 3) {
                applyScript(p, edited)
                check = ScriptCheck.check(p.scenes, p.lengthSec, research = p.researchNotes, sources = src())
                log("Edited and fact-checked: ${check.score}/100.")
            } else log("The editor's version was incomplete; keeping the draft.")
        }.onFailure { log("Editing pass failed (${it.message?.take(80)}); keeping the draft.") }
        p.writer = "$name studio"
        ensureLength(p, true)
        check = ScriptCheck.check(p.scenes, p.lengthSec, research = p.researchNotes, sources = src())
        p.scriptScore = check.score
        p.status = Status.SCRIPTED
        p.error = ""
        store.save(p)
        log("Script ready with $name: ${p.scenes.size} scenes, ${words(p)} words, ${p.sources.size} sources.")
        return true
    }

    private fun words(p: Project) = p.scenes.sumOf { it.say.split(Regex("\\s+")).filter { w -> w.isNotBlank() }.size }

    /**
     * Guarantees a 50+ second script: asks the AI once more for a longer version, then, if still
     * short, adds fact scenes from Wikipedia (keyless) before the ending, then a recap line.
     */
    private fun ensureLength(p: Project, ai: Boolean) {
        val range = Prompts.wordRange(p.lengthSec)
        if (words(p) >= range.first) return
        if (ai) runCatching {
            val have = words(p)
            log("The script has $have words; a 50-60 s video needs ${range.first}-${range.last}. Asking for a longer version…")
            val current = JSONObject().put("scenes", JSONArray(p.scenes.map { it.toJson() })).toString(1)
            val longer = scriptReply(Prompts.script(
                p, settings.seriesName, settings.seriesPromise, settings.defaultHashtags,
                "Too short: $have words. Rewrite to ${range.first}-${range.last} words in total by adding ${maxOf(2, (range.first - have) / 10)} scenes with specific facts, numbers or examples. Keep the hook and the ending.",
                current, stockFootage = settings.pexelsKey.isNotBlank() || settings.pixabayKey.isNotBlank(), take = p.take,
            ))
            val o = extractJson(longer.text)
            val n = objects(o.optJSONArray("scenes")).sumOf { it.optString("say").split(Regex("\\s+")).filter { w -> w.isNotBlank() }.size }
            if (n > have) applyScript(p, o)
        }.onFailure { log("Could not lengthen with AI (${it.message?.take(60)}).") }
        if (words(p) >= range.first) { log("Script length: ${words(p)} words."); return }
        // Still short: real facts (Wikipedia, no key) as scenes just before the payoff and ending.
        val facts = runCatching { BuiltInWriter().facts(p.topic) }.getOrDefault(emptyList())
            .map { it.copy(text = BuiltInWriter.speakable(it.text)) }
            .filter { f -> f.text.split(" ").size in 6..28 && p.scenes.none { it.say.contains(f.text.take(30)) } }
        val at = (p.scenes.size - 2).coerceAtLeast(1)
        var added = 0
        for (fact in facts) {
            if (words(p) >= range.first) break
            val f = fact.text
            val short = BuiltInWriter.shortText(f, 8)
            p.scenes.add(at + added, Scene(f, type = "document", text = BuiltInWriter.shortText(f, 20), label = "Wikipedia: ${fact.page}", highlight = short.split(" ").take(3).joinToString(" ")))
            // Facts only: every added fact carries its source.
            if (fact.url.isNotBlank()) p.sources = p.sources + Source(f.take(80), fact.url)
            added++
        }
        if (words(p) < range.first) {
            // Offline and still short: a recap of the key points keeps the video above 50 s.
            val points = p.scenes.drop(1).dropLast(1).map { BuiltInWriter.shortText(it.say, 6) }.filter { it.isNotBlank() }.distinct().take(3)
            if (points.isNotEmpty()) p.scenes.add((p.scenes.size - 1).coerceAtLeast(1),
                Scene("Quick recap. " + points.joinToString(". ") + ".", type = "list", text = "Quick recap", items = points))
        }
        log("Script length: ${words(p)} words${if (added > 0) " ($added fact scene(s) added)" else ""}.")
    }

    /**
     * Never stuck: the free services with the most room (Z.ai GLM, NVIDIA) go first, then every free
     * Gemini/Gemma model, then Groq, Alibaba Qwen, Mistral, OpenRouter, SiliconFlow, Cloudflare and
     * Pollinations. A used-up service is skipped at once (no waiting) while another one has room.
     */
    private fun scriptReply(prompt: String): GeminiReply {
        val isScript = { t: String -> runCatching { extractJson(t).optJSONArray("scenes")?.length() ?: 0 }.getOrDefault(0) >= 3 }
        val all = BackupAi.all(settings.aiKeys())
        val usable = all.filter { it.usable }
        val first = BackupAi(usable.filter { it.id in BackupAi.BIG }, notice = log)
        val rest = BackupAi(usable.filter { it.id !in BackupAi.BIG }, notice = log)
        first.generate(prompt, isScript)?.let { log("Script written by ${it.model}."); return it }
        var quota: GeminiQuotaError? = null
        if (settings.geminiKey.isNotBlank()) {
            try {
                // With backups ready, never sit waiting on a Gemini per-minute limit: switch at once.
                val g = Gemini(settings.geminiKey, notice = log, autoSwitch = settings.autoSwitchModels,
                    maxWaitMs = if (usable.isNotEmpty()) 0 else 65_000)
                return g.generate(prompt, settings.scriptModel, json = true, fallbacks = listOf(settings.researchModel))
            } catch (e: GeminiQuotaError) { quota = e }
            log("Gemini is out of free quota; switching to the next free AI…")
        }
        rest.generate(prompt, isScript)?.let { log("Script written by ${it.model}."); return it }
        val missing = all.filter { !it.usable }.map { it.name }
        throw GeminiQuotaError(
            (quota?.message ?: "No AI could write the script right now.") + " The free backup AI services could not answer either." +
                if (missing.isNotEmpty()) " Add a free key for ${missing.take(3).joinToString()} in Settings → Backup AI (Z.ai GLM is free and has the most room)." else ""
        )
    }

    private fun applyScript(p: Project, o: JSONObject) {
        val scenes = objects(o.optJSONArray("scenes")).map { Scene.fromJson(it) }.filter { it.say.isNotBlank() }
        if (scenes.size < 3) throw IllegalStateException("The script came back with only ${scenes.size} scenes. Try again.")
        p.scenes = scenes.toMutableList()
        if (p.scenes.first().type != "hook" && p.scenes.first().text.isBlank()) p.scenes.first().type = "hook"
        p.title = o.optString("title").ifBlank { p.topic }
        p.hookOptions = objects(o.optJSONArray("hook_options"))
            .sortedByDescending { it.optDouble("score", 0.0) }
            .map { it.optString("text").trim() }
            .filter { it.isNotBlank() && !it.equals(p.scenes.first().say.trim(), ignoreCase = true) }
            .take(4)
        p.commentQuestion = o.optString("comment_question")
        p.mood = o.optString("mood").lowercase().trim()
        o.optJSONObject("compliance")?.let { c ->
            p.sensitive = c.optString("sensitive").lowercase().trim().takeIf { it in setOf("health", "finance", "news", "elections") } ?: ""
            // Only ever switch "made for kids" on from the script; the creator decides to switch it off.
            if (c.optBoolean("made_for_kids", false)) p.madeForKids = true
        }
        p.sources = objects(o.optJSONArray("sources")).map { Source(it.optString("claim"), it.optString("url")) }
            .filter { it.url.startsWith("http") }
        val publish = o.optJSONObject("publish")
        p.titleOptions = strings(publish?.optJSONArray("titles"))
        p.ytTitle = p.titleOptions.firstOrNull() ?: p.title
        p.description = publish?.optString("description") ?: ""
        p.hashtags = strings(publish?.optJSONArray("hashtags")).map { it.removePrefix("#").replace(" ", "") }
    }

    fun render(p: Project) {
        val dir = p.dir(store.root)
        dir.mkdirs()
        // Old voice and photo files from an earlier render.
        dir.listFiles()?.filter { it.name.startsWith("voice.") || it.name.startsWith("photo_") || it.name.startsWith("cutout_") }?.forEach { it.delete() }

        val script = p.scenes.joinToString(" ") { it.say.trim() }
        step("Recording the voice", null)
        val expressive: VoiceResult? = if (settings.expressiveVoice && settings.geminiKey.isNotBlank()) runCatching {
            log("Voice: expressive Gemini voice (${settings.expressiveVoiceName})…")
            GeminiTts.speak(settings.geminiKey, script, settings.expressiveVoiceName, File(dir, "voice.wav"))
        }.onFailure { log("The expressive voice is not available right now (${it.message?.take(80)}); using the Microsoft voice.") }.getOrNull() else null
        val voice: VoiceResult = expressive ?: try {
            log("Voice: ${Voices.find(p.voice).label} (Microsoft online voice)…")
            val first = EdgeTts.speak(script, p.voice, settings.voiceRate, File(dir, "voice.mp3"))
            // Fit the chosen length (60 s by default): re-record slightly slower or faster, at most 12%.
            val fit = fitRate(settings.voiceRate, first.seconds)
            if (fit != null) {
                log(String.format("Voice is %.0f s; re-recording at %s to land in 50-60 s.", first.seconds, fit))
                runCatching { EdgeTts.speak(script, p.voice, fit, File(dir, "voice.mp3")) }.getOrDefault(first)
            } else first
        } catch (e: Exception) {
            log("The Microsoft voice failed (${e.message}). Using the phone's own voice instead.")
            DeviceTts.speak(context, script, Voices.find(p.voice).locale, Voices.rateFactor(settings.voiceRate), File(dir, "voice.wav"))
        }
        p.voiceUsed = voice.voice
        File(dir, "words.json").writeText(
            JSONArray(voice.words.map { JSONObject().put("word", it.text).put("start", it.start).put("end", it.end) }).toString()
        )
        log(String.format("Voice: %.1f s, %d words.", voice.seconds, voice.words.size))
        if (cancelled()) return

        // Just under 50 s? Hold the ending (comment question on screen, music playing) for up to 4.5 s.
        val duration = if (voice.seconds < Prompts.MIN_SEC) minOf(Prompts.MIN_SEC + 0.5, voice.seconds + 4.5) else voice.seconds
        if (duration > voice.seconds + 0.1) log(String.format("Holding the ending %.1f s so the video reaches %.0f s.", duration - voice.seconds, duration))
        val timeline = Timeline(p.scenes, voice.words, duration)

        // Pictures and logos, matched to the script. Free sources first need no key at all.
        val finder = MediaFinder(File(context.cacheDir, "media"), settings.pexelsKey, log, pixabayKey = settings.pixabayKey)
        val found = java.util.concurrent.ConcurrentHashMap<Int, SceneMedia.Lazy>()
        val logos = LinkedHashMap<Int, Logo>()
        val credits = java.util.Collections.synchronizedList(ArrayList<String>())
        // Brand names the script forgot to mark still get their logo (checked against the official site).
        val autoBrands = BrandSpotter.mark(p.scenes)
        if (autoBrands > 0) { log("Found $autoBrands brand name(s) in the script without a logo; adding them."); store.save(p) }
        val photoScenes = p.scenes.withIndex().filter { it.value.type == "photo" }
        val brandScenes = p.scenes.withIndex().filter { it.value.type == "logo" || it.value.brand.isNotBlank() }
        // Text scenes get a relevant picture or clip behind the words (darkened so the text stays readable).
        val backdropScenes = if (settings.backdrops) p.scenes.withIndex().filter { it.index > 0 && ScenePainter.wantsBackdrop(it.value.type) } else emptyList()
        if (photoScenes.isNotEmpty() || brandScenes.isNotEmpty() || backdropScenes.isNotEmpty()) step("Finding pictures, clips and logos", null)
        val clips = java.util.concurrent.atomic.AtomicInteger()
        val pictures = java.util.concurrent.atomic.AtomicInteger()
        val backdrops = java.util.concurrent.atomic.AtomicInteger()
        val stock = settings.pexelsKey.isNotBlank() || settings.pixabayKey.isNotBlank()
        // Everything downloads at the same time (6 at once) instead of one after another.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val jobs = ArrayList<java.util.concurrent.Future<*>>()
        fun clipFor(i: Int, query: String, seconds: Double): Boolean {
            val clip = File(dir, "photo_$i.mp4")
            val credit = runCatching { Stock.pexelsVideo(settings.pexelsKey, query, clip, seconds) }.getOrNull()
                ?: runCatching { finder.pixabayVideo(query, clip, seconds) }.getOrNull() ?: return false
            credits.add(credit)
            found[i] = SceneMedia.Lazy { SceneMedia.Clip.load(clip, seconds) }
            return true
        }
        for ((i, scene) in photoScenes) jobs += pool.submit {
            if (cancelled()) return@submit
            val query = scene.query.ifBlank { scene.text }
            val span = timeline.spans[i]
            val seconds = (span.end - span.start) + ScenePainter.TRANSITION
            if (stock && scene.library.isBlank() && clipFor(i, query, seconds)) { clips.incrementAndGet(); return@submit }
            val f = File(dir, "photo_$i.jpg")
            val hit = runCatching { finder.photo(query, scene.wiki, f, scene.library) }.getOrNull() ?: return@submit
            credits.add(hit.credit)
            pictures.incrementAndGet()
            // Long scenes get one or two more pictures, so the image changes every ~2 seconds.
            val extra = when {
                seconds >= 5.5 -> 2
                seconds >= 3.6 -> 1
                else -> 0
            }
            val files = arrayListOf(f)
            for (k in 1..extra) {
                val g = File(dir, "photo_${i}_$k.jpg")
                val more = runCatching { finder.photo(query.ifBlank { scene.wiki }, "", g, scene.library) }.getOrNull() ?: break
                credits.add(more.credit)
                files.add(g)
            }
            found[i] = SceneMedia.Lazy {
                val bmps = files.mapNotNull { loadBitmap(it) }
                when {
                    bmps.size > 1 -> SceneMedia.Slideshow(bmps)
                    bmps.size == 1 -> SceneMedia.Photo(bmps[0])
                    else -> null
                }
            }
        }
        // Quote scenes: the speaker's photo from Wikipedia.
        for ((i, scene) in p.scenes.withIndex().filter { it.value.type == "quote" && it.value.wiki.isNotBlank() }) jobs += pool.submit {
            val f = File(dir, "photo_$i.jpg")
            runCatching { finder.photo("", scene.wiki, f) }.getOrNull()?.let { hit ->
                credits.add(hit.credit)
                found[i] = SceneMedia.Lazy { loadBitmap(f)?.let { SceneMedia.Photo(it) } }
            }
        }
        for ((i, scene) in backdropScenes) jobs += pool.submit {
            if (cancelled()) return@submit
            val query = scene.query.ifBlank { MediaFinder.keywords(scene.text.ifBlank { scene.say }, p.topic) }
            if (query.isBlank()) return@submit
            val span = timeline.spans[i]
            val seconds = (span.end - span.start) + ScenePainter.TRANSITION
            if (stock && clipFor(i, query, seconds)) { backdrops.incrementAndGet(); return@submit }
            val f = File(dir, "photo_$i.jpg")
            runCatching { finder.photo(query, "", f) }.getOrNull()?.let { hit ->
                credits.add(hit.credit)
                backdrops.incrementAndGet()
                found[i] = SceneMedia.Lazy { loadBitmap(f)?.let { SceneMedia.Photo(it) } }
            }
        }
        // Logos in one task (the logo check keeps state between steps), alongside the pictures.
        jobs += pool.submit {
            val logoCache = HashMap<String, Logo?>()
            for ((i, scene) in brandScenes) {
                if (cancelled()) return@submit
                val name = scene.brand.ifBlank { scene.text }
                val logo = logoCache.getOrPut(MediaFinder.key(name + scene.domain)) { runCatching { finder.logo(name, scene.domain, if (scene.type == "logo") scene.wiki else "") }.getOrNull() }
                if (logo != null) synchronized(logos) { logos[i] = logo }
            }
        }
        pool.shutdown()
        for (j in jobs) runCatching { j.get(90, java.util.concurrent.TimeUnit.SECONDS) }
        pool.shutdownNow()
        if (cancelled()) return
        val media = LinkedHashMap<Int, SceneMedia.Lazy>().apply { found.keys.sorted().forEach { put(it, found.getValue(it)) } }
        if (credits.isNotEmpty()) File(dir, "credits.txt").writeText(credits.joinToString("\n"))
        if (photoScenes.isNotEmpty()) log("Pictures: ${clips.get()} video clip(s), ${pictures.get()} photo(s) for ${photoScenes.size} photo scene(s).")
        if (backdropScenes.isNotEmpty()) log("Backgrounds: ${backdrops.get()} of ${backdropScenes.size} talking scenes got a matching picture or clip.")
        if (brandScenes.isNotEmpty()) log("Logos: ${logos.size} of ${brandScenes.size} brand scene(s).")

        // A look of its own for every video (captions, transitions, background), different from the last one.
        if (p.style < 0) {
            val history = settings.styleHistory.ifEmpty { listOfNotNull(settings.lastStyle.takeIf { it >= 0 }) }
            p.style = Look.pickFresh(p.id.hashCode(), history, settings.lookMode)
            settings.lastStyle = p.style
            settings.styleHistory = listOf(p.style) + history
            store.save(p)
        }
        val look = Look.of(p.style)
        // Colour palette: Auto picks the least recently used one, so the colours change every video too.
        if (p.palette == Palettes.AUTO || p.palette.isBlank()) {
            p.palette = Palettes.pickFresh(p.id.hashCode() / 3, settings.paletteHistory)
            settings.paletteHistory = listOf(p.palette) + settings.paletteHistory
            store.save(p)
        }
        log("Style: ${look.description}${if (p.mood.isNotBlank()) ", ${p.mood} music" else ""}.")

        // Emphasis words: when each is spoken (zoom punch, coloured caption, hit sound).
        val emphasisTimes = p.scenes.withIndex().mapNotNull { (i, sc) ->
            val k = MediaFinder.key(sc.emphasis)
            if (k.isEmpty()) null else timeline.words.firstOrNull { it.scene == i && MediaFinder.key(it.text) == k }?.start
        }
        // Sound design cues from the scenes.
        val pops = ArrayList<Double>()
        val hits = ArrayList<Double>(emphasisTimes)
        p.scenes.forEachIndexed { i, sc ->
            val span = timeline.spans[i]
            when (sc.type) {
                "list", "steps", "chart", "timeline", "tier" -> {
                    val n = when (sc.type) { "chart" -> sc.items.take(5).size; "tier" -> sc.items.take(6).size; else -> sc.items.take(4).size }
                    ScenePainter.itemDelays(n, span.end - span.start).forEach { pops.add(span.start + it + 0.05) }
                }
                "logo", "quote" -> pops.add(span.start + 0.12)
                "stat" -> if (emphasisTimes.none { it in span.start..span.end }) hits.add(span.start + 0.15)
            }
        }
        // Liquid glass look: a tactile click on every UI micro-event (cursor taps, toggle, notification).
        if (look.glass) p.scenes.forEachIndexed { i, sc ->
            val span = timeline.spans[i]
            val d = span.end - span.start
            when (sc.type) {
                "hook" -> pops.add(span.start + ScenePainter.HOOK_CLICK)
                "compare" -> pops.add(span.start + (d * 0.42).coerceIn(0.5, 2.2))
                "outro" -> { pops.add(span.start + ScenePainter.OUTRO_BANNER + 0.1); pops.add(span.start + ScenePainter.OUTRO_CLICK) }
            }
        }
        // Hook intro: an impact boom right at the start (a sharp sound in the first second stops the scroll).
        hits.add(0, 0.03)
        if (timeline.spans.size > 1) pops.add(timeline.spans[1].start - 0.05)
                val risers = if (timeline.spans.size >= 3) listOf(timeline.spans.last().start) else emptyList()
        // Richer sound design: clicks, paper, a bass drop on the payoff, a heartbeat under the hook.
        val clicks = ArrayList<Double>()
        val papers = ArrayList<Double>()
        p.scenes.forEachIndexed { i, sc ->
            val span = timeline.spans[i]
            when (sc.type) {
                "document" -> { papers.add(span.start + 0.05); clicks.add(span.start + 0.6) }
                "map" -> clicks.add(span.start + ScenePainter.MAP_PIN)
            }
            if (look.paper && i > 0) papers.add(span.start)
        }
        val drops = risers.toList()
        val pulses = if (p.mood in setOf("dramatic", "energetic", "")) listOf(0.6, 1.4).filter { it < (timeline.spans.firstOrNull()?.end ?: 0.0) } else emptyList()

        // Animated emoji (Noto, free): the fire flickers, the bulb glows.
        val emojis = (p.scenes.map { it.emoji.trim() } + "💬" + "💡").filter { it.isNotBlank() }.distinct()
        val animated = emojis.mapNotNull { e -> AnimatedEmoji.load(e, File(context.cacheDir, "emoji"))?.let { e to it } }.toMap()
        if (animated.isNotEmpty()) credits.add(AnimatedEmoji.CREDIT)
        // Pop-out photos: cut the subject out on the phone, so the headline can sit behind it.
        val cutouts = HashMap<Int, Cutout>()
        for ((i, scene) in p.scenes.withIndex()) {
            if (scene.type != "photo" || scene.text.isBlank() || cutouts.size >= 3) continue
            val f = File(dir, "photo_$i.jpg")
            if (!f.exists()) continue
            val out = File(dir, "cutout_$i.png")
            if (Segmenter.cut(context, f, out)) Cutout.load(out)?.let { cutouts[i] = it }
        }
        if (credits.isNotEmpty()) File(dir, "credits.txt").writeText(credits.joinToString("\n"))
        log("Motion: ${animated.size} animated emoji, ${cutouts.size} pop-out photo(s).")

        val motion = MotionPlan(p.id.hashCode(), p.scenes.map { it.type.lowercase(Locale.ROOT) }, look, settings.motionHistory)
        settings.motionHistory = listOf(motion.signature) + settings.motionHistory
        log("Motion plan: ${motion.describe()}")
        val painter = ScenePainter(
            context, p.scenes, timeline, Palettes.find(p.palette), settings.seriesName, media, logos, look, emphasisTimes,
            animated, cutouts, stutter12 = settings.stutter12, motionPlan = motion,
        )
        val (w, h) = if (settings.quality == "720") 720 to 1280 else 1080 to 1920
        val fps = if (settings.smooth60) 60 else 30
        val renderer = VideoRenderer(w, h, fps)
        val audioPlan = AudioMix.Plan(
            durationSec = timeline.duration,
            sceneStarts = timeline.spans.map { it.start },
            music = settings.music,
            sfx = settings.soundEffects,
            seed = p.id.hashCode(),
            mood = p.mood,
            pops = pops,
            hits = hits,
            risers = risers,
            clicks = clicks,
            papers = papers,
            drops = drops,
            pulses = pulses,
        )
        step("Rendering the video", 0f)
        log(String.format("Rendering ${w}x$h at $fps fps, %.1f s…", timeline.duration))
        val started = System.currentTimeMillis()
        try {
            renderer.render(painter, timeline.duration, voice.audio, audioPlan, p.video(store.root), cancelled) { step("Rendering the video", it) }
            val first = timeline.spans.first()
            renderer.thumbnail(painter, minOf(first.start + 1.2, (first.end - 0.2).coerceAtLeast(0.5)), p.thumb(store.root))
        } finally {
            painter.release()
        }
        p.durationSec = timeline.duration
        p.status = if (p.uploads.isNotEmpty()) Status.POSTED else Status.RENDERED
        p.error = ""
        store.save(p)
        log(String.format("Video ready in %d s.", (System.currentTimeMillis() - started) / 1000))
    }

    private fun loadBitmap(f: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Direct YouTube upload with the user's own Google Cloud client (advanced). */
    fun uploadYouTube(p: Project, visibility: String) {
        val video = p.video(store.root)
        if (!video.exists()) throw IllegalStateException("Render the video first.")
        step("Uploading to YouTube", 0f)
        log("Uploading to YouTube as $visibility…")
        val result = YouTube(settings).upload(
            video, p.ytTitle.ifBlank { p.displayTitle() }, publishText(p, settings, forYouTube = true, credits = credits(p.dir(store.root))),
            p.hashtags, visibility, settings.aiLabel, p.madeForKids, p.paidPromotion
        ) { step("Uploading to YouTube", it) }
        p.uploads.add(Upload("youtube", result.url, visibility, System.currentTimeMillis()))
        p.status = Status.POSTED
        store.save(p)
        log("Posted: ${result.url}")
    }

    /**
     * Uploads now as private with a publish time; YouTube makes it public at that time on its own
     * servers, so nothing needs to run on the phone then.
     */
    fun uploadScheduled(p: Project) {
        val video = p.video(store.root)
        if (!video.exists()) throw IllegalStateException("Render the video first.")
        if (p.scheduledAt <= System.currentTimeMillis() + 60_000) throw IllegalStateException("The scheduled time has passed. Pick a new time.")
        step("Scheduling on YouTube", 0f)
        log("Uploading to YouTube, to go public ${scheduleText(p.scheduledAt, p.scheduleZone)}…")
        val result = YouTube(settings).upload(
            video, p.ytTitle.ifBlank { p.displayTitle() }, publishText(p, settings, forYouTube = true, credits = credits(p.dir(store.root))),
            p.hashtags, "private", settings.aiLabel, p.madeForKids, p.paidPromotion,
            publishAt = java.time.Instant.ofEpochMilli(p.scheduledAt),
        ) { step("Scheduling on YouTube", it) }
        p.uploads.add(Upload("youtube", result.url, "scheduled " + scheduleText(p.scheduledAt, p.scheduleZone), System.currentTimeMillis()))
        p.scheduleState = "uploaded"
        p.status = Status.POSTED
        store.save(p)
        log("Scheduled: ${result.url}")
    }

    companion object {
        const val BUILT_IN = "built-in writer"
        const val STUDIO_MARK = "[Live research by the premium studio]"

        /**
         * A speaking rate ("+4%") that brings [seconds] close to [target], changing the current rate by
         * at most 12 points (beyond that voices sound rushed or dragged). Null when it is already close.
         */
        fun fitRate(current: String, seconds: Double, min: Double = Prompts.MIN_SEC.toDouble(), max: Double = Prompts.MAX_SEC.toDouble()): String? {
            if (seconds <= 0 || seconds in min..max) return null
            val target = if (seconds < min) min + 2 else max - 2
            val r0 = current.trim().removeSuffix("%").toIntOrNull() ?: 0
            val wanted = ((1 + r0 / 100.0) * seconds / target - 1) * 100
            val r = wanted.toInt().coerceIn(r0 - 12, r0 + 12).coerceIn(-15, 30)
            if (kotlin.math.abs(r - r0) < 3) return null
            return (if (r >= 0) "+" else "") + r + "%"
        }

        /** "Thu Nov 27, 7:00 PM ET" in the audience's zone. */
        fun scheduleText(at: Long, zone: String): String {
            val z = runCatching { java.time.ZoneId.of(zone) }.getOrDefault(java.time.ZoneId.of("America/New_York"))
            val t = java.time.Instant.ofEpochMilli(at).atZone(z)
            return java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", java.util.Locale.US).format(t) + " " + zoneShort(zone)
        }

        fun zoneShort(zone: String) = when (zone) {
            "America/New_York" -> "ET"; "America/Chicago" -> "CT"; "America/Denver" -> "MT"
            "America/Phoenix" -> "Arizona"; "America/Los_Angeles" -> "PT"; "America/Anchorage" -> "AKT"; "Pacific/Honolulu" -> "HT"
            else -> zone
        }

        /** Caption / description text. YouTube also gets the sources; everything gets the hashtags. */
        fun publishText(p: Project, s: Settings, forYouTube: Boolean = false, credits: List<String> = emptyList()): String {
            val sb = StringBuilder()
            if (!forYouTube) sb.append(p.ytTitle.ifBlank { p.displayTitle() }).append("\n\n")
            if (p.description.isNotBlank()) sb.append(p.description.trim()).append("\n\n")
            if (p.commentQuestion.isNotBlank()) sb.append(p.commentQuestion.trim()).append("\n\n")
            if (forYouTube && p.sources.isNotEmpty()) {
                sb.append("Sources:\n")
                for (src in p.sources.take(8)) sb.append("• ").append(src.claim.take(80)).append(" — ").append(src.url).append("\n")
                sb.append("\n")
            }
            if (p.sensitive == "health") sb.append("For information only, not medical advice.\n\n")
            if (p.sensitive == "finance") sb.append("For information only, not financial advice.\n\n")
            if (p.paidPromotion) sb.append("Includes paid promotion.\n\n")
            if (credits.isNotEmpty()) {
                if (forYouTube) {
                    sb.append("Picture credits:\n")
                    for (c in credits.distinct().take(12)) sb.append("• ").append(c).append("\n")
                    sb.append("\n")
                } else sb.append(YouTubeCheck.shortCredits(credits)).append("\n\n")
            }
            if (s.aiLabel && s.aiLabelText.isNotBlank()) sb.append(s.aiLabelText.trim()).append("\n\n")
            sb.append((p.hashtags + if (forYouTube) listOf("shorts") else emptyList()).distinct().joinToString(" ") { "#$it" })
            return sb.toString().trim()
        }

        /** Credit lines of the pictures used in this video (written when it was made). */
        fun credits(dir: File): List<String> = runCatching {
            File(dir, "credits.txt").readLines().map { it.trim() }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

        fun words(f: File): List<Word> = runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Word(o.getString("word"), o.getDouble("start"), o.getDouble("end"))
            }
        }.getOrDefault(emptyList())
    }
}
