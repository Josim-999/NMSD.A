package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.ai.BackupAi
import com.videostudio.app.data.Scene
import com.videostudio.app.render.Look
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.SceneMedia
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.voice.Word
import org.junit.Assert.assertEquals
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
class V21Test {
    @Test fun glassModeOnlyPicksGlassLooksAndStillVaries() {
        var last = -1
        repeat(20) { i ->
            val id = Look.pick(i * 31, last, "glass")
            assertTrue(Look.of(id).glass)
            if (last >= 0) assertTrue(Look.of(id).transition != Look.of(last).transition)
            last = id
        }
        repeat(20) { i -> assertTrue(!Look.of(Look.pick(i, -1, "classic")).glass) }
    }

    @Test fun everyFreeServiceIsListedInOrder() {
        val keys = BackupAi.Keys(zai = "z", nvidia = "n", groq = "g", alibaba = "a", mistral = "m", openRouter = "o", siliconFlow = "s",
            cloudflareAccount = "acc", cloudflareToken = "t", pollinations = "", keyless = false)
        val all = BackupAi.all(keys)
        assertEquals(listOf("zai", "nvidia", "groq", "alibaba", "mistral", "openrouter", "siliconflow", "cloudflare", "pollinations"), all.map { it.id })
        assertEquals(8, all.count { it.usable })
        assertTrue(all.first { it.id == "cloudflare" }.base.contains("/accounts/acc/ai/v1"))
        // Cloudflare needs both the account and the token.
        assertTrue(!BackupAi.all(keys.copy(cloudflareAccount = "")).first { it.id == "cloudflare" }.usable)
        assertEquals("no key", BackupAi.status(BackupAi.all(BackupAi.Keys(keyless = false)).first()))
        assertEquals(setOf("zai", "nvidia"), BackupAi.BIG)
    }

    @Test fun paintsEverySceneInLiquidGlass() {
        val scenes = listOf(
            Scene("Why does your phone die in the cold?", type = "hook", text = "Why does cold kill batteries?", emoji = "🥶"),
            Scene("Batteries lose seventy percent at minus twenty.", type = "stat", value = "70%", label = "power lost at -20°C"),
            Scene("Three things slow down.", type = "list", text = "What slows down", items = listOf("Ions move slower", "Resistance rises", "Voltage drops")),
            Scene("Myth: the battery is broken.", type = "compare", left = "Myth", leftText = "Your battery is broken", right = "Fact", rightText = "It recovers when warm"),
            Scene("Keep it in an inner pocket.", type = "statement", text = "Keep your phone in an inner pocket", highlight = "inner"),
            Scene("Phones sleep better when warm.", type = "photo", text = "Warm phone, happy battery", query = "phone winter"),
            Scene("What drains yours fastest?", type = "outro", text = "What drains yours fastest?", emoji = "💬"),
        )
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.35 }; t += 0.3 }
        val timeline = Timeline(scenes, words, t)
        val photo = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawPaint(android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 540f, 960f, 0xFF2A9D8F.toInt(), 0xFFE9C46A.toInt(), android.graphics.Shader.TileMode.CLAMP)
            })
        }
        val painter = ScenePainter(
            ApplicationProvider.getApplicationContext(), scenes, timeline, Palettes.find("electric"), "",
            mapOf(5 to SceneMedia.Lazy { SceneMedia.Photo(photo) }), look = Look(0, 0, Look.GLASS),
            emphasisTimes = listOf(words[3].start),
        )
        val out = File("build/frames21").apply { mkdirs() }
        for (span in timeline.spans) {
            val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.scale(0.5f, 0.5f)
            val at = if (scenes[span.index].type == "outro") span.start + 1.15 else span.start + minOf(1.4, (span.end - span.start) * 0.8)
            painter.draw(c, at)
            File(out, "glass_${span.index}_${scenes[span.index].type}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val colours = (0 until 960 step 20).map { bmp.getPixel(270, it) }.toSet()
            assertTrue("blank frame", colours.size > 3)
        }
        // Mid theme-switch (list -> compare): the radial wipe.
        val sw = timeline.spans[3]
        val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.scale(0.5f, 0.5f)
        painter.draw(c, sw.start + ScenePainter.TRANSITION * 0.7)
        File(out, "glass_wipe.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        painter.release()
    }
}
