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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Frame cost per look (PROFILE=1 only): how long one 1080x1920 frame takes to draw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderSpeedTest {
    @Test fun frameCost() {
        assumeTrue(System.getenv("PROFILE") == "1")
        val scenes = listOf(
            Scene("Your brain burns twenty percent of your energy every single day of your life.", type = "statement", text = "Your brain eats 20% of your energy", highlight = "20%"),
            Scene("That is more than your heart and lungs put together.", type = "stat", text = "20%", label = "of your body's energy"),
            Scene("Three things drain it the most every single day.", type = "list", text = "Biggest drains", items = listOf("Sleep loss", "Stress", "Screens")),
            Scene("So what actually helps it recover, according to science?", type = "question", text = "What helps?"),
            Scene("Sleep, and nothing else comes even close to it.", type = "statement", text = "Sleep wins", highlight = "Sleep"),
        )
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.36 }; t += 0.25 }
        val tl = Timeline(scenes, words, t)
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        for (fam in 0 until Look.BACKGROUNDS) {
            val look = Look(1, 2, fam)
            for (motif in listOf(1)) {
                val hist = (1 until MotionPlan.MOTIFS.size).filter { it != motif }.map { "$it:0" }
                val plan = MotionPlan(3, scenes.map { it.type }, look, if (motif == 0) emptyList() else hist)
                val p = ScenePainter(ApplicationProvider.getApplicationContext(), scenes, tl, Palettes.find("electric"), "Series", emptyMap(), look = look, motionPlan = plan)
                repeat(10) { p.draw(c, 6.0 + it / 30.0) }
                val n = 90; p.profiling = true; val st = System.nanoTime()
                for (i in 0 until n) p.draw(c, 6.0 + i * (t - 7) / n)
                println("PROF fam=$fam " + p.prof.entries.joinToString { "${it.key}=${"%.1f".format(it.value / 1e6 / n)}" })
                println("SPEED family=$fam motif=${plan.motif} ${"%.1f".format((System.nanoTime() - st) / 1e6 / n)} ms/frame")
                p.release()
            }
        }
    }
}
