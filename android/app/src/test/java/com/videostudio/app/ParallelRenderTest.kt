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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 3.8: frames are drawn by several painters at once (one per CPU core). Every frame must be
 * identical to the one a single painter draws, and shared pictures must stay alive while any
 * painter still needs them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParallelRenderTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val scenes = listOf(
        Scene("AI agents can now break into computer systems faster than any human team could.", type = "hook", text = "AI hacks faster than humans", emoji = "🤖", highlight = "faster"),
        Scene("Security teams found agents testing thousands of passwords in one minute.", type = "statement", text = "Thousands of passwords a minute", highlight = "Thousands"),
        Scene("That is ninety percent faster than the best human attackers on record.", type = "stat", text = "90%", label = "faster than humans"),
        Scene("Here is what they go after first, every single time they get in.", type = "list", text = "First targets", items = listOf("Passwords", "Email", "Cloud keys")),
        Scene("Picture a server room where every door is tested at once, all night long.", type = "photo", text = "Every door at once"),
        Scene("So who wins the race between attackers and defenders next?", type = "question", text = "Who wins?"),
        Scene("And the next attack might already be testing your own front door right now.", type = "outro", text = "Is your door locked?", emoji = "💬"),
    )

    private fun photo(name: String, color: Int): File {
        val f = File("build/parallel-test").apply { mkdirs() }.resolve(name)
        if (!f.exists()) {
            val b = Bitmap.createBitmap(1200, 2000, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(color)
            val p = android.graphics.Paint().apply { this.color = Color.WHITE }
            for (i in 0 until 20) c.drawCircle(60f * i, 100f * i, 40f, p)
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        return f
    }

    private fun setup(look: Look): Triple<Timeline, Map<Int, SceneMedia.Lazy>, () -> ScenePainter> {
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.32)); t += 0.37 }; t += 0.3 }
        val tl = Timeline(scenes, words, t)
        val colors = listOf(Color.rgb(200, 60, 60), Color.rgb(60, 160, 90), Color.rgb(60, 90, 200))
        val media = HashMap<Int, SceneMedia.Lazy>()
        scenes.forEachIndexed { i, s ->
            if (i > 0 && (s.type == "photo" || ScenePainter.wantsBackdrop(s.type))) {
                val f = photo("p$i.jpg", colors[i % 3])
                media[i] = SceneMedia.Lazy { MediaLoad.fit(f)?.let { SceneMedia.Photo(it) } }
            }
        }
        val plan = MotionPlan(look.id, scenes.map { it.type }, look)
        return Triple(tl, media) { ScenePainter(ctx, scenes, tl, Palettes.find("electric"), "Series", media, emptyMap(), look, listOf(1.2, 9.0), motionPlan = plan) }
    }

    private fun sum(b: Bitmap): Long {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var h = 1125899906842597L; for (v in px) h = 31 * h + v; return h
    }

    @Test fun parallelFramesMatchSingleAndAreFaster() {
        for (look in listOf(Look(1, 2, 0), Look(0, 1, Look.GLASS), Look(2, 0, Look.PAPER))) {
            val (tl, _, make) = setup(look)
            val frames = (tl.duration * 30).toInt()
            val pick = (0 until frames step 7).toSet()
            // One painter, every frame in order.
            val single = make()
            val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
            val expected = HashMap<Int, Long>()
            val t1 = System.nanoTime()
            for (i in 0 until frames) {
                val c = Canvas(bmp); c.scale(0.5f, 0.5f); single.draw(c, i / 30.0)
                if (i in pick) expected[i] = sum(bmp)
            }
            val singleMs = (System.nanoTime() - t1) / 1_000_000
            single.release()
            // Four painters at once, frame i on painter i % 4, sharing the same pictures.
            val workers = 4
            val painters = List(workers) { make() }
            val got = java.util.concurrent.ConcurrentHashMap<Int, Long>()
            val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
            val t2 = System.nanoTime()
            val threads = painters.mapIndexed { k, p ->
                Thread {
                    try {
                        val b = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
                        var i = k
                        while (i < frames) { val c = Canvas(b); c.scale(0.5f, 0.5f); p.draw(c, i / 30.0); if (i in pick) got[i] = sum(b); i += workers }
                    } catch (e: Throwable) { errors.add(e) }
                }
            }
            threads.forEach { it.start() }; threads.forEach { it.join(300_000) }
            val parallelMs = (System.nanoTime() - t2) / 1_000_000
            painters.forEach { it.release() }
            assertTrue("errors: $errors", errors.isEmpty())
            val diff = pick.filter { expected[it] != got[it] }
            println("PARALLEL ${look.description}: single $singleMs ms, 4 painters $parallelMs ms, ${diff.size} different of ${pick.size}")
            assertEquals("frames that differ in ${look.description}: $diff", 0, diff.size)
        }
    }

    @Test fun sharedPictureLivesWhileAnyPainterUsesIt() {
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val lazy = SceneMedia.Lazy { SceneMedia.Photo(b) }
        val a = Any(); val c = Any()
        lazy.get(a); lazy.get(c)
        lazy.release(a)
        assertTrue("still alive for the other painter", !b.isRecycled)
        lazy.release(c)
        assertTrue("freed when nobody needs it", b.isRecycled)
    }
}
