package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Scene
import com.videostudio.app.render.Look
import com.videostudio.app.render.MediaLoad
import com.videostudio.app.render.MotionPlan
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.SceneMedia
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

/**
 * Owner's rule (3.6): "Make the video" must never fail or hang. Every build renders a full
 * 60-second video, every frame, on a second thread like the phone does, with backgrounds,
 * slideshows and big photos, and checks the frame time and the memory each picture takes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderSafetyTest {
    private val dir = File("build/render-safety").apply { mkdirs() }

    private fun bigPhoto(name: String, w: Int, h: Int, color: Int): File {
        val f = File(dir, name)
        if (!f.exists()) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply { eraseColor(color) }
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            b.recycle()
        }
        return f
    }

    @Test fun picturesNeverTakeMoreThanAFrame() {
        val huge = MediaLoad.fit(bigPhoto("huge.jpg", 4000, 6000, Color.rgb(200, 90, 40)))!!
        // 4000x6000 just covers 1080x1920 at 1280x1920 (instead of 96 MB in full).
        assertTrue("${huge.width}x${huge.height}", huge.width in 1080..1281 && huge.height in 1919..1921)
        assertTrue("at most ~10 MB", huge.byteCount <= 1281 * 1921 * 4)
        val wide = MediaLoad.fit(bigPhoto("wide.jpg", 6000, 2000, Color.rgb(40, 90, 200)))!!
        assertTrue("covers the frame, panorama trimmed: ${wide.width}x${wide.height}", wide.height in 1919..1921 && wide.width <= 1080 * 1.5 + 2)
        val small = MediaLoad.fit(bigPhoto("small.jpg", 640, 480, Color.GREEN))!!
        assertTrue("never enlarged: ${small.width}x${small.height}", small.width <= 640 && small.height == 480)
    }

    @Test fun fullVideoRendersEveryFrameWithoutHanging() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scenes = listOf(
            Scene("AI agents can now break into computer systems faster than any human team ever could.", type = "hook", text = "AI hacks faster than humans", emoji = "🤖", highlight = "faster"),
            Scene("Security teams found agents testing thousands of passwords in a single minute.", type = "statement", text = "Thousands of passwords a minute", highlight = "Thousands"),
            Scene("That is ninety percent faster than the best human attackers on record today.", type = "stat", text = "90%", label = "faster than human attackers"),
            Scene("Here is what they go after first, every single time they get in.", type = "list", text = "First targets", items = listOf("Passwords", "Email", "Cloud keys")),
            Scene("Old defenses look for people, not for software that never sleeps or slows down.", type = "compare", text = "People vs agents", items = listOf("Human: sleeps", "Agent: never stops")),
            Scene("Picture a server room where every door is tested at once, all night long.", type = "photo", text = "Every door at once", query = "server room"),
            Scene("So who wins the race between attackers and defenders over the next few years?", type = "question", text = "Who wins the race?"),
            Scene("Defenders are now using the very same agents to find holes before attackers do.", type = "emoji", text = "Fight AI with AI", emoji = "🛡️"),
            Scene("Companies that switched cut break-ins by more than half in their first year.", type = "chart", text = "Break-ins after switching", items = listOf("Before: 100", "After: 45")),
            Scene("And the next attack might already be testing your own front door right now.", type = "outro", text = "Is your door locked?", emoji = "💬"),
        )
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.32)); t += 0.37 }; t += 0.3 }
        val tl = Timeline(scenes, words, t.coerceAtLeast(55.0))
        val colors = listOf(Color.rgb(200, 60, 60), Color.rgb(60, 160, 90), Color.rgb(60, 90, 200), Color.rgb(200, 160, 40))
        for (look in listOf(Look(1, 0, 0), Look(2, 3, Look.GLASS))) {
            val media = HashMap<Int, SceneMedia.Lazy>()
            scenes.forEachIndexed { i, s ->
                if (i > 0 && (s.type == "photo" || ScenePainter.wantsBackdrop(s.type))) {
                    val f = bigPhoto("p$i.jpg", 3000, 4500, colors[i % colors.size])
                    media[i] = if (s.type == "photo") SceneMedia.Lazy { SceneMedia.Slideshow(List(3) { MediaLoad.fit(f)!! }) }
                    else SceneMedia.Lazy { MediaLoad.fit(f)?.let { SceneMedia.Photo(it) } }
                }
            }
            val painter = ScenePainter(ctx, scenes, tl, Palettes.find("electric"), "Series", media, emptyMap(), look, listOf(1.2, 9.0, 20.0),
                motionPlan = MotionPlan(look.id, scenes.map { it.type }, look))
            val frames = (tl.duration * 30).toInt()
            val pool = java.util.concurrent.ArrayBlockingQueue<Bitmap>(2).apply { repeat(2) { add(Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)) } }
            val ready = java.util.concurrent.ArrayBlockingQueue<Any>(3)
            var worst = 0L
            val worker = Thread {
                try {
                    for (i in 0 until frames) {
                        val b = pool.take()
                        val st = System.nanoTime()
                        painter.draw(Canvas(b), i / 30.0)
                        worst = maxOf(worst, (System.nanoTime() - st) / 1_000_000)
                        ready.put(b)
                    }
                } catch (e: Throwable) { ready.put(e) }
            }
            worker.start()
            val start = System.currentTimeMillis()
            for (i in 0 until frames) {
                val item = ready.poll(20, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("${look.description}: frame $i hung")
                if (item is Throwable) throw AssertionError("${look.description}: frame $i failed", item)
                pool.put(item as Bitmap)
            }
            worker.join(5000)
            val secs = (System.currentTimeMillis() - start) / 1000.0
            println("SAFETY ${look.description}: $frames frames in $secs s, worst frame $worst ms")
            assertTrue("worst frame $worst ms", worst < 2500)
            painter.release()
        }
    }
}
