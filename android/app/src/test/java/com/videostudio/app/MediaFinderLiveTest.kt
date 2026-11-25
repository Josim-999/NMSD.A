package com.videostudio.app

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.ai.Logo
import com.videostudio.app.data.Scene
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.SceneMedia
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.ai.MediaFinder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Talks to the real free sources. Runs only with LIVE_NET=1 (CI machines may have no internet). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaFinderLiveTest {
    private val dir = File("build/live-media").apply { mkdirs() }
    private val logs = ArrayList<String>()
    private val finder = MediaFinder(dir, log = { logs.add(it) })

    private fun size(f: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.absolutePath, it) }.let { it.outWidth to it.outHeight }

    @Test
    fun logosForCommonBrands() {
        assumeTrue(System.getenv("LIVE_NET") == "1")
        for ((brand, domain) in listOf("NVIDIA" to "nvidia.com", "OpenAI" to "openai.com", "Anthropic" to "", "Google Gemini" to "gemini.google.com", "Microsoft" to "microsoft.com", "Tesla" to "")) {
            val logo = finder.logo(brand, domain)
            println("LOGO $brand -> $logo")
            assertNotNull("no logo for $brand", logo)
        }
        assertTrue(finder.logo("NVIDIA", "") is Logo.Vector)
        assertTrue(finder.logo("Microsoft", "microsoft.com") is Logo.Raster)
    }

    @Test
    fun photosFromWikipediaAndOpenverse() {
        assumeTrue(System.getenv("LIVE_NET") == "1")
        val a = finder.photo("data center servers", "", File(dir, "q_datacenter.jpg"))
        println("PHOTO query -> ${a?.credit} ${a?.let { size(it.file) }}")
        assertNotNull(a)
        val b = finder.photo("", "Sam Altman", File(dir, "w_altman.jpg"))
        println("PHOTO wiki -> ${b?.credit} ${b?.let { size(it.file) }}")
        val c = finder.photo("power plant cooling towers", "Electricity generation", File(dir, "both.jpg"))
        println("PHOTO both -> ${c?.credit} ${c?.let { size(it.file) }}")
        assertNotNull(c)
    }

    /** A real script's scenes, rendered with logos and photos fetched live. Frames go to build/live-frames. */
    @Test
    fun rendersPremiumFramesWithRealMedia() {
        assumeTrue(System.getenv("LIVE_NET") == "1")
        val scenes = listOf(
            Scene("Why does AI need so much power?", type = "hook", text = "AI's huge power demands", emoji = "⚡"),
            Scene("NVIDIA chips run most of it.", type = "logo", brand = "NVIDIA", domain = "nvidia.com", text = "Runs most AI training"),
            Scene("Each data center draws as much as a city.", type = "photo", query = "data center servers", wiki = "Data center", text = "As much power as a city"),
            Scene("OpenAI wants five gigawatts.", type = "stat", value = "5 GW", label = "planned by OpenAI", brand = "OpenAI", domain = "openai.com"),
            Scene("Sam Altman says energy is the bottleneck.", type = "photo", query = "", wiki = "Sam Altman", text = "Energy is the bottleneck"),
            Scene("Microsoft is even buying nuclear power.", type = "statement", text = "Microsoft is buying nuclear power", highlight = "nuclear", brand = "Microsoft", domain = "microsoft.com"),
        )
        val words = ArrayList<com.videostudio.app.voice.Word>()
        var t = 0.0
        for (sc in scenes) {
            for (w in sc.say.split(" ")) { words.add(com.videostudio.app.voice.Word(w, t, t + 0.3)); t += 0.35 }
            t += 0.3
        }
        val timeline = Timeline(scenes, words, t)
        val media = HashMap<Int, SceneMedia.Lazy>()
        val logos = HashMap<Int, Logo>()
        for ((i, sc) in scenes.withIndex()) {
            if (sc.type == "photo") {
                val f = File(dir, "scene_$i.jpg")
                if (finder.photo(sc.query, sc.wiki, f) != null) media[i] = SceneMedia.Lazy { BitmapFactory.decodeFile(f.absolutePath)?.let { SceneMedia.Photo(it) } }
            }
            if (sc.brand.isNotBlank()) finder.logo(sc.brand, sc.domain)?.let { logos[i] = it }
        }
        println("MEDIA ${media.keys} LOGOS ${logos.keys}")
        val painter = ScenePainter(ApplicationProvider.getApplicationContext(), scenes, timeline, Palettes.find("electric"), "", media, logos)
        val out = File("build/live-frames").apply { mkdirs() }
        for (span in timeline.spans) {
            val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(0.5f, 0.5f)
            painter.draw(c, span.start + minOf(1.5, (span.end - span.start) * 0.85))
            File(out, "scene_${span.index}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        painter.release()
        assertTrue(logos.size >= 3)
        assertTrue(media.size >= 1)
    }
}
