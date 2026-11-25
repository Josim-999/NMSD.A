package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Scene
import com.videostudio.app.render.AudioMix
import com.videostudio.app.render.Look
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
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StyleTest {
    private val scenes = listOf(
        Scene("Why does AI need so much power?", type = "hook", text = "AI's huge power demands", emoji = "⚡", emphasis = "power"),
        Scene("Usage grew ten times in four years.", type = "chart", text = "AI power use (TWh)", items = listOf("2020: 12", "2022: 38", "2024: 120"), emphasis = "ten"),
        Scene("Here is how we got here.", type = "timeline", text = "How we got here", items = listOf("2017 — Transformers", "2020 — GPT-3", "2023 — ChatGPT boom")),
        Scene("Energy is the bottleneck, says Altman.", type = "quote", text = "Energy is the bottleneck", label = "Sam Altman"),
        Scene("What would you switch off first?", type = "outro", text = "What would you switch off?", emoji = "💬"),
    )

    private fun timeline(): Timeline {
        val words = ArrayList<Word>()
        var t = 0.0
        for (s in scenes) {
            for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.35 }
            t += 0.3
        }
        return Timeline(scenes, words, t)
    }

    private fun frame(p: ScenePainter, t: Double): Bitmap {
        val b = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(0.5f, 0.5f)
        p.draw(c, t)
        return b
    }

    private fun painter(look: Look, tl: Timeline = timeline()) = ScenePainter(
        ApplicationProvider.getApplicationContext(), scenes, tl, Palettes.find("aurora"), "", emptyMap(), emptyMap(),
        look, listOf(tl.words.first { it.text == "power?" }.start)
    )

    /** Share of bright (near-white) pixels in the middle band: text. */
    private fun textCoverage(b: Bitmap): Double {
        var bright = 0
        var n = 0
        for (y in 200 until 620 step 3) for (x in 40 until 500 step 3) {
            val p = b.getPixel(x, y)
            if ((p shr 16 and 255) > 225 && (p shr 8 and 255) > 225 && (p and 255) > 225) bright++
            n++
        }
        return bright.toDouble() / n
    }

    @Test
    fun hookIsReadableOnTheFirstFrame() {
        val p = painter(Look(0, 0, 0))
        val first = textCoverage(frame(p, 0.0))
        val later = textCoverage(frame(p, 1.0))
        assertTrue("frame 1 text $first vs later $later", first > 0.02 && first > later * 0.7)
    }

    @Test
    fun lastFrameLoopsIntoTheFirst() {
        val tl = timeline()
        val p = painter(Look(1, 1, 1), tl)
        val a = frame(p, 0.0)
        val z = frame(p, tl.duration - 1.0 / 30)
        var diff = 0.0
        var n = 0
        for (y in 0 until 960 step 8) for (x in 0 until 540 step 8) {
            val pa = a.getPixel(x, y)
            val pz = z.getPixel(x, y)
            diff += abs((pa shr 16 and 255) - (pz shr 16 and 255)) + abs((pa shr 8 and 255) - (pz shr 8 and 255)) + abs((pa and 255) - (pz and 255))
            n++
        }
        assertTrue("loop difference ${diff / n}", diff / n < 30)
    }

    @Test
    fun everyLookAndSceneRenders() {
        val out = File("build/style-frames").apply { mkdirs() }
        val tl = timeline()
        for (look in listOf(Look(0, 0, 0), Look(1, 1, 1), Look(2, 2, 2), Look(3, 3, 0))) {
            val p = painter(look, tl)
            for (span in tl.spans) {
                val b = frame(p, span.start + minOf(1.6, (span.end - span.start) * 0.85))
                File(out, "look${look.id}_scene${span.index}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val colours = (0 until 960 step 16).map { b.getPixel(270, it) }.toSet()
                assertTrue("look ${look.id} scene ${span.index} blank", colours.size > 4)
            }
            val chartEnd = frame(p, tl.spans[1].end - 0.05)
            File(out, "look${look.id}_chart_full.png").outputStream().use { chartEnd.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // A transition frame of this look's kind.
            val b = frame(p, tl.spans[2].start + ScenePainter.TRANSITION / 2)
            File(out, "look${look.id}_transition.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            p.release()
        }
    }

    @Test
    fun eachNewVideoLooksDifferentFromTheLastOne() {
        var last = -1
        repeat(30) { seed ->
            val next = Look.pick(seed * 7919, last)
            if (last >= 0) {
                val a = Look.of(last)
                val b = Look.of(next)
                assertNotEquals(a.captions, b.captions)
                assertNotEquals(a.transition, b.transition)
                assertNotEquals(a.background, b.background)
            }
            assertEquals(next, Look.of(next).id)
            last = next
        }
    }

    @Test
    fun soundDesignCuesAreAudible() {
        val voice = ShortArray(24_000 * 6)
        fun rms(x: ShortArray, a: Double, b: Double): Double {
            var s = 0.0
            val from = (a * AudioMix.RATE).toInt()
            val to = (b * AudioMix.RATE).toInt()
            for (i in from until to) s += (x[i] / 32768.0) * (x[i] / 32768.0)
            return 20 * log10(sqrt(s / (to - from)) + 1e-9)
        }
        val plain = AudioMix.mix(voice, 24_000, 1, AudioMix.Plan(6.0, listOf(0.0), music = false, sfx = true, seed = 3))
        val cues = AudioMix.mix(
            voice, 24_000, 1,
            AudioMix.Plan(6.0, listOf(0.0), music = false, sfx = true, seed = 3, pops = listOf(1.0), hits = listOf(2.5), risers = listOf(5.0))
        )
        assertTrue(rms(cues, 2.5, 2.8) > rms(plain, 2.5, 2.8) + 20) // hit
        assertTrue(rms(cues, 1.0, 1.06) > rms(plain, 1.0, 1.06) + 20) // pop
        assertTrue(rms(cues, 4.5, 5.0) > rms(plain, 4.5, 5.0) + 20) // riser
        val energetic = AudioMix.music(48_000 * 4, 5, "energetic")
        val calm = AudioMix.music(48_000 * 4, 5, "calm")
        assertTrue(energetic.indices.any { abs(energetic[it] - calm[it]) > 0.05f })
    }
}
