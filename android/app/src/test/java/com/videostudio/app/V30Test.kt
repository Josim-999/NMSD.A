package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Scene
import com.videostudio.app.render.Look
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.render.WorldMap
import com.videostudio.app.voice.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class V30Test {
    @Test fun worldMapFindsCountriesByNameAliasAndCode() {
        val w = WorldMap.load(ApplicationProvider.getApplicationContext())!!
        assertTrue(w.countries.size > 150)
        assertEquals("JPN", w.find("Japan")!!.iso)
        assertEquals("USA", w.find("USA")!!.iso)
        assertEquals("USA", w.find("United States")!!.iso)
        assertEquals("GBR", w.find("UK")!!.iso)
        assertEquals("BGD", w.find("Bangladesh")!!.iso)
        assertNull(w.find("Atlantis"))
        assertNotNull(w.find("Brazil"))
    }

    private fun render(look: Look, scenes: List<Scene>, name: String, times: List<Pair<String, (Timeline) -> Double>>, stutter: Boolean = false) {
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.36 }; t += 0.25 }
        val timeline = Timeline(scenes, words, t)
        val painter = ScenePainter(ApplicationProvider.getApplicationContext(), scenes, timeline, Palettes.find("electric"), "",
            emptyMap(), look = look, stutter12 = stutter)
        val out = File("build/frames30").apply { mkdirs() }
        for ((label, at) in times) {
            val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.scale(0.5f, 0.5f)
            painter.draw(c, at(timeline))
            File(out, "${name}_$label.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue((0 until 960 step 20).map { bmp.getPixel(270, it) }.toSet().size > 3)
        }
        painter.release()
    }

    @Test fun paintsDocumentMapAndPushBlur() {
        val scenes = listOf(
            Scene("Japan sleeps less than almost any country on Earth.", type = "statement", text = "Japan sleeps the least", highlight = "least"),
            Scene("A 2023 survey found the average adult sleeps under seven hours.", type = "document",
                text = "The average Japanese adult sleeps 6 hours and 22 minutes a night, the lowest among 33 countries.",
                label = "OECD, Gender Data Portal 2023", highlight = "lowest among 33 countries"),
            Scene("Tokyo is where it hits hardest, in the middle of the city.", type = "map", place = "Japan", lat = 35.68, lon = 139.69, text = "Tokyo, Japan"),
            Scene("And the fix starts tonight, with one rule.", type = "statement", text = "One rule tonight", highlight = "rule"),
        )
        render(Look(0, 0, 0), scenes, "dark", listOf(
            "doc" to { tl -> tl.spans[1].start + 1.4 },
            "map_world" to { tl -> tl.spans[2].start + 0.2 },
            "map_zoom" to { tl -> tl.spans[2].start + 2.0 },
            "push" to { tl -> tl.spans[1].start + ScenePainter.TRANSITION * 0.5 },
        ))
        render(Look(0, 2, Look.GLASS), scenes, "glass", listOf(
            "doc" to { tl -> tl.spans[1].start + 1.4 },
            "map_zoom" to { tl -> tl.spans[2].start + 2.0 },
        ), stutter = true)
    }
}
