package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Scene
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.voice.Word
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Paints real frames of every scene type and writes them to build/frames for a visual check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderFramesTest {
    @Test
    fun paintsEverySceneType() {
        val scenes = listOf(
            Scene("Why does your phone die in the cold?", type = "hook", text = "Why does cold kill batteries?", emoji = "🥶"),
            Scene("Lithium batteries lose seventy percent of power at minus twenty.", type = "stat", value = "70%", label = "power lost at -20°C"),
            Scene("Three things slow down.", type = "list", text = "What slows down", items = listOf("Ions move slower", "Resistance rises", "Voltage drops")),
            Scene("Myth: the battery is broken.", type = "compare", left = "Myth", leftText = "Your battery is broken", right = "Fact", rightText = "It recovers when warm"),
            Scene("Keep it in an inner pocket.", type = "statement", text = "Keep your phone in an inner pocket", highlight = "inner"),
            Scene("What drains yours fastest?", type = "outro", text = "What drains yours fastest?", emoji = "💬"),
        )
        // Fake word timings: 0.35 s per word, small gap between scenes.
        val words = ArrayList<Word>()
        var t = 0.0
        for (s in scenes) {
            for (w in s.say.split(" ")) {
                words.add(Word(w, t, t + 0.3))
                t += 0.35
            }
            t += 0.3
        }
        val timeline = Timeline(scenes, words, t)
        val painter = ScenePainter(ApplicationProvider.getApplicationContext(), scenes, timeline, Palettes.find("electric"), "Tech in 60s", emptyMap())
        val out = File("build/frames").apply { mkdirs() }
        for (span in timeline.spans) {
            val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(0.5f, 0.5f)
            painter.draw(c, span.start + minOf(1.4, (span.end - span.start) * 0.8))
            File(out, "scene_${span.index}_${scenes[span.index].type}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Not a blank frame: the centre column has more than one colour.
            val colours = (0 until 960 step 20).map { bmp.getPixel(270, it) }.toSet()
            assertTrue("scene ${span.index} looks blank", colours.size > 3)
        }
    }
}
