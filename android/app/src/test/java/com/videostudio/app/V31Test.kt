package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Scene
import com.videostudio.app.render.Look
import com.videostudio.app.render.MotionPlan
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.voice.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V31Test {
    private val types = listOf("hook", "statement", "stat", "question", "list", "statement", "emoji", "chart", "statement", "outro")

    @Test fun libraryHasOverOneHundredTechniques() {
        assertTrue("total ${MotionPlan.TOTAL}", MotionPlan.TOTAL in 100..150)
    }

    @Test fun planNeverRepeatsBackToBackAndKeepsSceneOneStill() {
        for (seed in 0 until 200) {
            val plan = MotionPlan(seed, types, Look.of(seed % Look.COUNT))
            assertEquals(0, plan.entrance[0]); assertEquals(0, plan.camera[0])
            for (i in 2 until types.size) {
                if (plan.entrance[i] != 0 && plan.entrance[i - 1] != 0) assertNotEquals(plan.entrance[i], plan.entrance[i - 1])
                if (plan.camera[i] != 0) assertNotEquals(plan.camera[i], plan.camera[i - 1])
                assertNotEquals("seed $seed scene $i", plan.transition[i], plan.transition[i - 1])
                if (plan.overlay[i] != 0) assertNotEquals(plan.overlay[i], plan.overlay[i - 1])
            }
            assertEquals(0, plan.entrance[types.indexOf("chart")])
            // Emphasis hits rotate: consecutive words never get the same effect.
            for (n in 0 until 6) assertNotEquals(plan.emphasis(n), plan.emphasis(n + 1))
            assertEquals("all 14 hits used in 14 words", 14, (0 until 14).map { plan.emphasis(it) }.toSet().size)
        }
    }

    @Test fun motifAndCaptionsRotateAcrossVideos() {
        val history = ArrayList<String>()
        val motifs = ArrayList<Int>()
        repeat(12) { v ->
            val plan = MotionPlan(v * 977, types, Look.of(0), history)
            motifs += plan.motif
            history.add(0, plan.signature)
        }
        assertEquals("all 12 motifs used once in 12 videos: $motifs", 12, motifs.toSet().size)
        assertTrue(motifs.none { it == 0 })
    }

    @Test fun looksCycleThroughEveryFamilyAndNeverRepeatRecently() {
        val history = ArrayList<Int>()
        val fams = ArrayList<Int>()
        repeat(30) { v ->
            val id = Look.pickFresh(v * 131, history, "mix")
            assertTrue("look $id repeated within the last 12", id !in history.take(12))
            fams += Look.family(id)
            history.add(0, id)
        }
        for (start in 0..(30 - Look.BACKGROUNDS)) {
            assertEquals("every 6 videos use all 6 families: ${fams.subList(start, start + 6)}", Look.BACKGROUNDS, fams.subList(start, start + 6).toSet().size)
        }
    }

    @Test fun autoPaletteRotates() {
        val history = ArrayList<String>()
        val picks = ArrayList<String>()
        repeat(8) { v -> val p = Palettes.pickFresh(v * 17, history); picks += p; history.add(0, p) }
        assertEquals(picks.toString(), 8, picks.toSet().size)
    }

    private fun timelineFor(scenes: List<Scene>): Timeline {
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.36 }; t += 0.25 }
        return Timeline(scenes, words, t)
    }

    private fun shot(painter: ScenePainter, t: Double, file: File) {
        val bmp = Bitmap.createBitmap(270, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.scale(0.25f, 0.25f)
        painter.draw(c, t)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.name, (0 until 480 step 10).map { bmp.getPixel(135, it) }.toSet().size > 3)
    }

    private val scenes = listOf(
        Scene("Your brain uses twenty percent of all the energy your whole body burns every single day, even when you are resting quietly.", type = "statement", text = "Your brain eats 20% of your energy", highlight = "20%"),
        Scene("That is more than your heart and your lungs put together in a day.", type = "statement", text = "More than your heart and lungs combined", highlight = "heart"),
        Scene("And it never switches off, not even when you sleep at night.", type = "statement", text = "It never switches off", highlight = "never"),
    )

    @Test fun rendersEveryTransitionEntranceMotifOverlayAndHit() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tl = timelineFor(scenes)
        val out = File("build/frames31").apply { mkdirs() }
        val types3 = scenes.map { it.type }
        for (k in 0 until MotionPlan.TRANSITIONS.size) {
            val plan = MotionPlan(1, types3, Look.of(0)); plan.transition[1] = k; plan.entrance[1] = 0
            val p = ScenePainter(ctx, scenes, tl, Palettes.find("electric"), "", emptyMap(), look = Look.of(0), motionPlan = plan)
            shot(p, tl.spans[1].start + ScenePainter.TRANSITION * 0.5, File(out, "t%02d.png".format(k))); p.release()
        }
        for (k in 1 until MotionPlan.ENTRANCES.size) {
            val plan = MotionPlan(1, types3, Look.of(0)); plan.transition[1] = 1; plan.entrance[1] = k; plan.overlay[1] = 0
            val p = ScenePainter(ctx, scenes, tl, Palettes.find("sunset"), "", emptyMap(), look = Look.of(0), motionPlan = plan)
            shot(p, tl.spans[1].start + 0.3, File(out, "e%02d.png".format(k))); p.release()
        }
        for (m in 1 until MotionPlan.MOTIFS.size) {
            val hist = (1 until MotionPlan.MOTIFS.size).filter { it != m }.map { "$it:0" }
            val plan = MotionPlan(1, types3, Look.of(0), hist)
            assertEquals(m, plan.motif)
            for (i in plan.overlay.indices) plan.overlay[i] = 0
            val p = ScenePainter(ctx, scenes, tl, Palettes.find("mint"), "", emptyMap(), look = Look.of(0), motionPlan = plan)
            shot(p, tl.spans[2].start + 1.0, File(out, "m%02d.png".format(m))); p.release()
        }
        for (o in 1 until MotionPlan.OVERLAYS.size) {
            val plan = MotionPlan(1, types3, Look.of(0)); plan.overlay[2] = o
            val p = ScenePainter(ctx, scenes, tl, Palettes.find("electric"), "", emptyMap(), look = Look.of(0), motionPlan = plan)
            shot(p, tl.spans[2].start + 0.5, File(out, "o%02d.png".format(o))); p.release()
        }
        // Emphasis hits: one painter per effect, emphasis on scene 2.
        val hitAt = tl.spans[2].start + 0.3
        for (n in 0 until 14) {
            val plan = MotionPlan(1, types3, Look.of(0)); for (i in plan.overlay.indices) plan.overlay[i] = 0
            val p = ScenePainter(ctx, scenes, tl, Palettes.find("electric"), "", emptyMap(), look = Look.of(0), emphasisTimes = List(n) { 0.0 } + hitAt, motionPlan = plan)
            shot(p, hitAt + 0.1, File(out, "h%02d_fx%02d.png".format(n, plan.emphasis(n)))); p.release()
        }
    }

    @Test fun rendersKaraokeBounceDonutAndLineGraph() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val out = File("build/frames31").apply { mkdirs() }
        val charts = listOf(
            Scene("Here is how the whole planet really splits up when you measure it.", type = "statement", text = "How Earth splits up", highlight = "Earth"),
            Scene("Most of the planet is ocean, the rest is land and ice.", type = "chart", text = "Earth's surface", items = listOf("Ocean: 71", "Land: 26", "Ice: 3")),
            Scene("Smartphone users grew fast over the last decade worldwide.", type = "chart", text = "Smartphone users (billions)", items = listOf("2014: 1.6", "2017: 2.5", "2020: 3.5", "2023: 4.3")),
        )
        val tl = timelineFor(charts)
        for (cap in 4..5) {
            val plan = MotionPlan(3, charts.map { it.type }, Look.of(0))
            val f = MotionPlan::class.java.getDeclaredField("captionVariant"); f.isAccessible = true; f.setInt(plan, cap)
            val p = ScenePainter(ctx, charts, tl, Palettes.find("electric"), "", emptyMap(), look = Look.of(0), motionPlan = plan)
            shot(p, tl.spans[1].start + 2.0, File(out, "chart_donut_cap$cap.png"))
            shot(p, tl.spans[2].start + 2.2, File(out, "chart_line_cap$cap.png")); p.release()
        }
    }
}
