package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.ai.BrandSpotter
import com.videostudio.app.ai.Logo
import com.videostudio.app.ai.MediaFinder
import com.videostudio.app.data.Scene
import com.videostudio.app.render.Look
import com.videostudio.app.render.MotionPlan
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.SceneMedia
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.voice.Word
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
class V33Test {
    @Test fun spotsBrandsTheScriptForgot() {
        assertEquals("ChatGPT" to "chatgpt.com", BrandSpotter.find("Millions now ask ChatGPT before they ask a doctor."))
        assertEquals("Netflix" to "netflix.com", BrandSpotter.find("Netflix lost 200,000 subscribers in one quarter."))
        assertEquals("Apple" to "apple.com", BrandSpotter.find("Last year, Apple sold more watches than Rolex."))
        assertEquals("Google Gemini" to "gemini.google.com", BrandSpotter.find("Even Google Gemini got this wrong."))
        assertEquals("McDonald's" to "mcdonalds.com", BrandSpotter.find("A McDonald's fry has 19 ingredients."))
        // Ordinary words and people are not brands.
        assertNull(BrandSpotter.find("Apple trees can live for 100 years."))
        assertNull(BrandSpotter.find("The engineer Nikola Tesla never got rich."))
        assertNull(BrandSpotter.find("An apple a day keeps you full."))
        assertNull(BrandSpotter.find("Your brain burns twenty percent of your energy."))
        val scenes = listOf(
            Scene("Tesla's rival BYD now sells more electric cars.", type = "statement", text = "BYD beat Tesla"),
            Scene("Microsoft paid billions for it.", type = "statement", text = "A huge deal", brand = "OpenAI", domain = "openai.com"),
            Scene("Sleep is the fix.", type = "statement", text = "Sleep wins"),
        )
        assertEquals(1, BrandSpotter.mark(scenes))
        assertEquals("BYD", scenes[0].brand); assertEquals("byd.com", scenes[0].domain)
        assertEquals("OpenAI", scenes[1].brand) // what the script set is kept
        assertEquals("", scenes[2].brand)
    }

    @Test fun backdropSearchUsesConcreteWords() {
        val q = MediaFinder.keywords("That is more than your heart and lungs put together every single day.", "the human brain")
        assertTrue(q, q.split(" ").size in 2..3 && "heart" in q && "lungs" in q && "every" !in q)
        val q2 = MediaFinder.keywords("Why?", "deep ocean creatures")
        assertTrue(q2, "ocean" in q2)
    }

    @Test fun rendersBackdropBehindTalkAndBigLogoBadge() {
        val scenes = listOf(
            Scene("Your brain burns twenty percent of all your energy every day of your life.", type = "statement", text = "Your brain eats 20% of your energy", highlight = "20%"),
            Scene("Netflix found its viewers quit after the first ninety seconds of a show.", type = "statement", text = "Viewers quit in 90 seconds", highlight = "90", brand = "Netflix", domain = "netflix.com"),
        )
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.36 }; t += 0.25 }
        val tl = Timeline(scenes, words, t)
        // A stand-in "photo": a striped picture, so it is easy to see behind the text.
        val pic = Bitmap.createBitmap(400, 700, Bitmap.Config.ARGB_8888).apply { for (y in 0 until 700) for (x in 0 until 400) setPixel(x, y, if ((x / 40 + y / 40) % 2 == 0) Color.rgb(230, 120, 40) else Color.rgb(40, 120, 230)) }
        val logo = Logo.Vector("Netflix", "M5.398 0v.006c3.028 8.556 5.37 15.175 8.348 23.596 2.344.058 4.85.398 4.854.398-2.8-7.924-5.923-16.747-8.487-24zm8.489 0v9.63L18.6 22.951c-.043-7.86-.004-15.913.002-22.95zM5.398 1.05V24c1.873-.225 2.81-.312 4.715-.398v-9.22z", Color.rgb(229, 9, 20))
        val out = File("build/frames33").apply { mkdirs() }
        for (fam in listOf(0, 3, 4)) {
            val look = Look(1, 1, fam)
            val plan = MotionPlan(2, scenes.map { it.type }, look).also { it.overlay[1] = 1; it.entrance[1] = 0 }
            val media = mapOf(1 to SceneMedia.Lazy { SceneMedia.Photo(pic.copy(Bitmap.Config.ARGB_8888, false)) })
            val p = ScenePainter(ApplicationProvider.getApplicationContext(), scenes, tl, Palettes.find("electric"), "", media, mapOf(1 to logo), look = look, motionPlan = plan)
            val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); c.scale(0.5f, 0.5f)
            p.draw(c, tl.spans[1].start + 1.5)
            File(out, "backdrop_badge_f$fam.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // The picture shows through (warm and cool stripes both visible behind the text).
            val px = (0 until 960 step 8).flatMap { y -> listOf(60, 480).map { x -> bmp.getPixel(x, y) } }
            assertTrue("backdrop visible in family $fam", px.any { Color.red(it) > Color.blue(it) + 25 } && px.any { Color.blue(it) > Color.red(it) + 25 })
            p.release()
        }
    }
}
