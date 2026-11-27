package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.render.Look
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.voice.Word
import com.videostudio.app.work.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class V23Test {
    @Test fun sixtySecondsByDefaultAndVoiceFitting() {
        assertEquals(60, Project(id = "a", topic = "t").lengthSec)
        // Word ranges always land inside 50-60 s.
        for (sec in listOf(30, 50, 55, 60, 90)) {
            val r = com.videostudio.app.ai.Prompts.wordRange(sec)
            assertTrue("$sec -> $r", r.first >= 140 && r.last <= 160 && r.first < r.last)
        }
        assertNull(Pipeline.fitRate("+8%", 55.0))                  // inside 50-60 s
        assertEquals("+0%", Pipeline.fitRate("+8%", 48.0))         // under 50 s: slower, towards 52 s
        assertEquals("-4%", Pipeline.fitRate("+8%", 40.0))         // capped at 12 points
        assertEquals("+19%", Pipeline.fitRate("+8%", 64.0))        // over 60 s: faster, towards 58 s
    }

    @Test fun lookModesPickTheirFamily() {
        repeat(10) { i ->
            assertTrue(Look.of(Look.pick(i * 17, -1, "paper")).paper)
            assertTrue(Look.of(Look.pick(i * 17, -1, "kinetic")).kinetic)
            val classic = Look.of(Look.pick(i * 17, -1, "classic"))
            assertTrue(!classic.glass && !classic.paper && !classic.kinetic)
        }
    }

    private fun render(look: Look, name: String) {
        val scenes = listOf(
            Scene("Why does your phone die in the cold?", type = "hook", text = "Why does cold kill batteries?", emoji = "🥶"),
            Scene("Batteries lose seventy percent at minus twenty.", type = "stat", value = "70%", label = "power lost at -20°C"),
            Scene("Keep your phone in an inner pocket, always.", type = "statement", text = "Keep your phone in an inner pocket", highlight = "inner", emphasis = "inner"),
            Scene("Which phone survives winter best?", type = "tier", text = "Winter battery tier list",
                items = listOf("S: iPhone 16 Pro", "A: Pixel 9", "B: Galaxy S25", "C: Old Android", "D: Flip phone? Wait")),
            Scene("What drains yours fastest?", type = "outro", text = "What drains yours fastest?", emoji = "💬"),
        )
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.35 }; t += 0.3 }
        val timeline = Timeline(scenes, words, t)
        val emph = timeline.words.filter { it.text.startsWith("inner") }.map { it.start }
        val painter = ScenePainter(ApplicationProvider.getApplicationContext(), scenes, timeline, Palettes.find("electric"), "",
            emptyMap(), look = look, emphasisTimes = emph)
        val out = File("build/frames23").apply { mkdirs() }
        for (span in timeline.spans) {
            val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.scale(0.5f, 0.5f)
            painter.draw(c, span.start + minOf(1.9, (span.end - span.start) * 0.85))
            File(out, "${name}_${span.index}_${scenes[span.index].type}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue((0 until 960 step 20).map { bmp.getPixel(270, it) }.toSet().size > 3)
        }
        painter.release()
    }

    @Test fun paintsPaperKineticAndTier() {
        render(Look(0, 1, Look.PAPER), "paper")
        render(Look(1, 0, Look.KINETIC), "kinetic")
    }
}
