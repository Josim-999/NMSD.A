package com.videostudio.app

import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.ai.BackupAi
import com.videostudio.app.ai.BrandSpotter
import com.videostudio.app.ai.ClaudeStudio
import com.videostudio.app.ai.Prompts
import com.videostudio.app.data.Project
import com.videostudio.app.data.Settings
import com.videostudio.app.render.Look
import com.videostudio.app.render.MotionPlan
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.upload.Captions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards every feature the owner asked for, release after release (see FEATURES.md).
 * A new version must keep all of these; if one has to change, FEATURES.md and this test change
 * together, on purpose, never by accident.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureGuardTest {
    private val settings = Settings(ApplicationProvider.getApplicationContext())

    @Test fun looksStylesAndMotionLibraryStayComplete() {
        assertEquals("6 style families (soft light, grid, aurora, liquid glass, paper, kinetic)", 6, Look.BACKGROUNDS)
        assertEquals(96, Look.COUNT)
        assertTrue("at least 8 colour palettes", Palettes.all.size >= 8)
        assertTrue("115+ motion techniques", MotionPlan.TOTAL >= 115)
        assertEquals(21, MotionPlan.TRANSITIONS.size)
        assertEquals(20, MotionPlan.ENTRANCES.size)
        assertEquals(15, MotionPlan.EMPHASIS.size)
        assertEquals(5.0, ScenePainter.HOOK_FX_END, 0.0) // hook intro: first 5 seconds
        assertTrue("backdrops behind talking scenes", ScenePainter.wantsBackdrop("statement") && !ScenePainter.wantsBackdrop("chart"))
    }

    @Test fun defaultsTheOwnerChose() {
        assertEquals("auto", settings.palette)          // palette rotates every video
        assertEquals("mix", settings.lookMode)          // every look family in rotation
        assertEquals("+0%", settings.voiceRate)
        assertTrue(settings.premiumScript)              // premium studio used whenever a key is set
        assertEquals("claude-opus-5-5", settings.premiumModel)
        assertTrue(settings.backdrops)                  // pictures/clips behind talking scenes
        assertTrue(settings.autoSwitchModels)           // free models switch automatically
    }

    @Test fun scriptRulesStayInEveryPrompt() {
        assertEquals(50, Prompts.MIN_SEC); assertEquals(60, Prompts.MAX_SEC)
        for (sec in listOf(50, 55, 60)) Prompts.wordRange(sec).let { assertTrue("$sec s -> $it", it.first >= 140 && it.last <= 160) }
        val prompt = Prompts.script(Project(id = "g", topic = "Why we dream"), "", "", "")
        for (rule in listOf("FACTS ONLY", "what_if", "myth_vs_fact", "loop", "brand", "domain", "\"query\"", "logo", "sources"))
            assertTrue("script prompt keeps: $rule", prompt.contains(rule))
        assertTrue(ClaudeStudio.researchPrompt(Project(id = "g", topic = "x"), java.time.LocalDate.now()).contains("FACTS:"))
    }

    @Test fun scriptsNeverStopAndLogosAreNeverMissed() {
        assertTrue("10 free AI services in the chain", BackupAi.all(settings.aiKeys()).size >= 9)
        assertEquals("ChatGPT" to "chatgpt.com", BrandSpotter.find("I asked ChatGPT."))
    }

    @Test fun captionsForEveryApp() {
        for (app in listOf("tiktok", "instagram", "facebook", "youtube", "x", "threads", "linkedin", "whatsapp")) assertTrue(app, app in Captions.RULES)
        assertEquals(5, Captions.RULES.getValue("instagram").maxTags)
    }

    @Test fun installableNoForbiddenPermissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (bad in listOf("BIND_ACCESSIBILITY_SERVICE", "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "BIND_NOTIFICATION_LISTENER_SERVICE"))
            assertTrue("Play Protect blocks $bad", bad !in manifest)
    }

    @Test fun clipsAreNeverDecodedWhileRendering() {
        // 3.4: decoding a clip frame by frame inside the render made one frame take over a minute.
        val pipeline = File("src/main/java/com/videostudio/app/work/Pipeline.kt").readText()
        assertTrue(pipeline.contains("SceneMedia.Clip.prepare("))
        assertTrue("no lazy clip decoding during the render", !pipeline.contains("Lazy { SceneMedia.Clip.load("))
    }

    @Test fun preparedClipFramesLoadBack() {
        val dir = File("build/clip-frames-test").apply { deleteRecursively(); mkdirs() }
        repeat(5) { i ->
            val b = android.graphics.Bitmap.createBitmap(54, 96, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(40 * i, 80, 120)) }
            File(dir, "f%03d.jpg".format(i)).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        }
        val clip = com.videostudio.app.render.SceneMedia.Clip.loadFrames(dir)!!
        assertEquals(54, clip.width)
        clip.release()
    }

    @Test fun oneRenderAtATimeAndClipsMatchTheScene() {
        // 3.5: several renders at once each ran ~3x slower; they now queue.
        assertEquals(1, com.videostudio.app.render.RenderGate.render.availablePermits())
        val pipeline = File("src/main/java/com/videostudio/app/work/Pipeline.kt").readText()
        assertTrue(pipeline.contains("RenderGate.acquire(RenderGate.render"))
        assertTrue(pipeline.contains("RenderGate.with(RenderGate.decode)"))
        // A queued video waits and can be stopped while waiting.
        val gate = java.util.concurrent.Semaphore(1); gate.acquire()
        var stop = false
        val t = Thread { Thread.sleep(300); stop = true }; t.start()
        assertTrue(!com.videostudio.app.render.RenderGate.acquire(gate, { stop }))
        gate.release()
        assertTrue(com.videostudio.app.render.RenderGate.acquire(gate, { false })); gate.release()
        // Clips must match the scene's words, like photos.
        val stock = File("src/main/java/com/videostudio/app/ai/Stock.kt").readText()
        val finder = File("src/main/java/com/videostudio/app/ai/MediaFinder.kt").readText()
        assertTrue(stock.contains("MediaFinder.relevance(query, slug)"))
        assertTrue(finder.contains("relevance(query, h.optString(\"tags\")) < minRelevance(query)) return@mapNotNull null"))
        assertTrue(com.videostudio.app.ai.MediaFinder.relevance("man sleeping bed", "man sleeping in bed 4821") >= com.videostudio.app.ai.MediaFinder.minRelevance("man sleeping bed"))
        assertTrue(com.videostudio.app.ai.MediaFinder.relevance("man sleeping bed", "city traffic night 22") < com.videostudio.app.ai.MediaFinder.minRelevance("man sleeping bed"))
    }

    @Test fun lightAppAndSafeRenderRules() {
        // 3.6 rules: no heavy SDKs (the Claude SDK added ~10 MB and a newer Kotlin runtime), more memory for renders,
        // two frame buffers at most, pictures capped to the frame, and an automatic safe-mode retry.
        val gradle = File("build.gradle.kts").readText()
        for (heavy in listOf("com.anthropic", "jackson", "opentelemetry", "kotlin-reflect")) assertTrue("no $heavy dependency", heavy !in gradle)
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:largeHeap=\"true\""))
        val renderer = File("src/main/java/com/videostudio/app/render/VideoRenderer.kt").readText()
        assertTrue(renderer.contains("val buffers = if (twoThreads) 2 else 1"))
        val pipeline = File("src/main/java/com/videostudio/app/work/Pipeline.kt").readText()
        assertTrue(pipeline.contains("MediaLoad.fit(f)"))
        assertTrue(pipeline.contains("twoThreads = false"))
    }
}
