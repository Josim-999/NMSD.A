package com.videostudio.app.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.videostudio.app.ai.Gemini
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
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.SceneMedia
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.render.VideoRenderer
import com.videostudio.app.upload.YouTube
import com.videostudio.app.voice.DeviceTts
import com.videostudio.app.voice.EdgeTts
import com.videostudio.app.voice.VoiceResult
import com.videostudio.app.voice.Voices
import com.videostudio.app.voice.Word
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The steps of making a video. Each runs inside a job; [log] and [step] feed the screen. */
class Pipeline(
    private val context: Context,
    private val store: ProjectStore,
    private val settings: Settings,
    private val log: (String) -> Unit,
    private val step: (String, Float?) -> Unit,
    private val cancelled: () -> Boolean,
) {
    private fun gemini() = Gemini(settings.geminiKey)

    fun writeScript(p: Project, feedback: String = "") {
        if (p.research && p.researchNotes.isBlank()) {
            step("Researching the topic (Google Search)", null)
            log("Researching with ${settings.researchModel}…")
            val reply = gemini().generate(Prompts.research(p), settings.researchModel, search = true)
            val pages = reply.sources.distinctBy { it.second }.joinToString("\n") { "- ${it.first}: ${it.second}" }
            p.researchNotes = reply.text + if (pages.isNotBlank()) "\n\nPages searched:\n$pages" else ""
            store.save(p)
            log("Research done (${reply.sources.size} pages).")
        }
        if (cancelled()) return
        step(if (feedback.isBlank()) "Writing the script" else "Rewriting the script", null)
        log("Writing the script with ${settings.scriptModel}…")
        val current = if (feedback.isBlank()) "" else JSONObject().put("scenes", JSONArray(p.scenes.map { it.toJson() })).toString(1)
        val prompt = Prompts.script(
            p, settings.seriesName, settings.seriesPromise, settings.defaultHashtags, feedback, current,
            stockFootage = settings.pexelsKey.isNotBlank(),
        )
        applyScript(p, extractJson(gemini().generate(prompt, settings.scriptModel, json = true).text))
        p.status = Status.SCRIPTED
        p.error = ""
        store.save(p)
        val words = p.scenes.sumOf { it.say.split(Regex("\\s+")).size }
        log("Script ready: ${p.scenes.size} scenes, $words words.")
    }

    private fun applyScript(p: Project, o: JSONObject) {
        val scenes = objects(o.optJSONArray("scenes")).map { Scene.fromJson(it) }.filter { it.say.isNotBlank() }
        if (scenes.size < 3) throw IllegalStateException("The script came back with only ${scenes.size} scenes. Try again.")
        p.scenes = scenes.toMutableList()
        if (p.scenes.first().type != "hook" && p.scenes.first().text.isBlank()) p.scenes.first().type = "hook"
        p.title = o.optString("title").ifBlank { p.topic }
        p.commentQuestion = o.optString("comment_question")
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
        dir.listFiles()?.filter { it.name.startsWith("voice.") || it.name.startsWith("photo_") }?.forEach { it.delete() }

        val script = p.scenes.joinToString(" ") { it.say.trim() }
        step("Recording the voice", null)
        val voice: VoiceResult = try {
            log("Voice: ${Voices.find(p.voice).label} (Microsoft online voice)…")
            EdgeTts.speak(script, p.voice, settings.voiceRate, File(dir, "voice.mp3"))
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

        val timeline = Timeline(p.scenes, voice.words, voice.seconds)

        // Photo scenes: a real stock video clip, else a stock photo, else a big emoji.
        val media = LinkedHashMap<Int, SceneMedia.Lazy>()
        val photoScenes = p.scenes.withIndex().filter { it.value.type == "photo" }
        if (photoScenes.isNotEmpty()) {
            if (settings.pexelsKey.isBlank()) {
                log("No Pexels key in Settings: photo scenes show an emoji instead.")
            } else {
                step("Finding stock footage", null)
                val credits = ArrayList<String>()
                var clips = 0
                var stills = 0
                for ((i, scene) in photoScenes) {
                    if (cancelled()) return
                    val query = scene.query.ifBlank { scene.text }
                    val span = timeline.spans[i]
                    val seconds = (span.end - span.start) + ScenePainter.TRANSITION
                    val clip = File(dir, "photo_$i.mp4")
                    val videoCredit = runCatching { Stock.pexelsVideo(settings.pexelsKey, query, clip, seconds) }.getOrNull()
                    if (videoCredit != null) {
                        credits.add(videoCredit)
                        clips++
                        media[i] = SceneMedia.Lazy { SceneMedia.Clip.load(clip, seconds) ?: loadBitmap(clip)?.let { SceneMedia.Photo(it) } }
                        continue
                    }
                    val f = File(dir, "photo_$i.jpg")
                    val credit = runCatching { Stock.pexelsPhoto(settings.pexelsKey, query, f) }.getOrNull()
                    if (credit != null) {
                        credits.add(credit)
                        stills++
                        media[i] = SceneMedia.Lazy { loadBitmap(f)?.let { SceneMedia.Photo(it) } }
                    }
                }
                File(dir, "credits.txt").writeText(credits.joinToString("\n"))
                log("Stock footage: $clips video clip(s), $stills photo(s) for ${photoScenes.size} scene(s).")
            }
        }

        val painter = ScenePainter(context, p.scenes, timeline, Palettes.find(p.palette), settings.seriesName, media)
        val (w, h) = if (settings.quality == "720") 720 to 1280 else 1080 to 1920
        val fps = if (settings.smooth60) 60 else 30
        val renderer = VideoRenderer(w, h, fps)
        val audioPlan = AudioMix.Plan(
            durationSec = timeline.duration,
            sceneStarts = timeline.spans.map { it.start },
            music = settings.music,
            sfx = settings.soundEffects,
            seed = p.id.hashCode(),
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
            video, p.ytTitle.ifBlank { p.displayTitle() }, publishText(p, settings, forYouTube = true),
            p.hashtags, visibility, settings.aiLabel
        ) { step("Uploading to YouTube", it) }
        p.uploads.add(Upload("youtube", result.url, visibility, System.currentTimeMillis()))
        p.status = Status.POSTED
        store.save(p)
        log("Posted: ${result.url}")
    }

    companion object {
        /** Caption / description text. YouTube also gets the sources; everything gets the hashtags. */
        fun publishText(p: Project, s: Settings, forYouTube: Boolean = false): String {
            val sb = StringBuilder()
            if (!forYouTube) sb.append(p.ytTitle.ifBlank { p.displayTitle() }).append("\n\n")
            if (p.description.isNotBlank()) sb.append(p.description.trim()).append("\n\n")
            if (p.commentQuestion.isNotBlank()) sb.append(p.commentQuestion.trim()).append("\n\n")
            if (forYouTube && p.sources.isNotEmpty()) {
                sb.append("Sources:\n")
                for (src in p.sources.take(8)) sb.append("• ").append(src.claim.take(80)).append(" — ").append(src.url).append("\n")
                sb.append("\n")
            }
            if (s.aiLabel && s.aiLabelText.isNotBlank()) sb.append(s.aiLabelText.trim()).append("\n\n")
            sb.append((p.hashtags + if (forYouTube) listOf("shorts") else emptyList()).distinct().joinToString(" ") { "#$it" })
            return sb.toString().trim()
        }

        fun words(f: File): List<Word> = runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Word(o.getString("word"), o.getDouble("start"), o.getDouble("end"))
            }
        }.getOrDefault(emptyList())
    }
}
