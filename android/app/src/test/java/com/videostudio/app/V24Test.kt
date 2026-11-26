package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Scene
import com.videostudio.app.render.Look
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V24Test {
    @Test fun hookIntroFrames() {
        val scenes = listOf(
            Scene("This AI tool replaced my whole editing team.", type = "hook", text = "This AI replaced my editors", emoji = "🤯", emphasis = "replaced"),
            Scene("In sixty seconds you'll know exactly how.", type = "statement", text = "You'll know exactly how", highlight = "how"),
            Scene("It starts with one simple setting.", type = "statement", text = "One simple setting", highlight = "simple"),
        )
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.33 }; t += 0.25 }
        val timeline = Timeline(scenes, words, t)
        val emph = timeline.words.filter { it.text.startsWith("replaced") }.map { it.start }
        val out = File("build/frames24").apply { mkdirs() }
        for ((name, look) in listOf("dark" to Look(1, 0, 0), "glass" to Look(0, 2, Look.GLASS))) {
            val painter = ScenePainter(ApplicationProvider.getApplicationContext(), scenes, timeline, Palettes.find("electric"), "",
                emptyMap(), look = look, emphasisTimes = emph)
            val cut = timeline.spans[1].start
            for ((label, at) in listOf("0_first" to 0.0, "1_flash" to 0.08, "2_burst" to 0.45, "3_sticker" to emph[0] + 0.25, "4_wipe" to cut + 0.06, "5_at3s" to 3.0)) {
                val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp); c.scale(0.5f, 0.5f)
                painter.draw(c, at)
                File(out, "${name}_$label.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue((0 until 960 step 20).map { bmp.getPixel(270, it) }.toSet().size > 3)
            }
            painter.release()
        }
    }
}
