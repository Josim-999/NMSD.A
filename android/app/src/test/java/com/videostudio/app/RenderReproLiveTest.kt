package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.ai.BuiltInWriter
import com.videostudio.app.ai.MediaFinder
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.render.AnimatedEmoji
import com.videostudio.app.render.Look
import com.videostudio.app.render.MotionPlan
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.SceneMedia
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.voice.Word
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** LIVE_NET=1: renders the owner's failing videos end to end (every frame) and reports slow frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderReproLiveTest {
    @Test fun renderFailingTopics() {
        assumeTrue(System.getenv("LIVE_NET") == "1")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File("build/repro").apply { mkdirs() }
        for ((topic, lookId) in listOf("AI Agents Are Becoming A Cybersecurity Threat" to Look(0, 1, 0).id, "Gemini Can Take Control Of Your Mac" to Look(2, 0, Look.GLASS).id)) {
            val p = Project(id = topic.hashCode().toString(), topic = topic)
            val o = BuiltInWriter().write(p, "")
            val arr = o.getJSONArray("scenes")
            val scenes = (0 until arr.length()).map { Scene.fromJson(arr.getJSONObject(it)) }.filter { it.say.isNotBlank() }.toMutableList()
            println("REPRO $topic: ${scenes.size} scenes: " + scenes.joinToString { it.type + (if (it.emoji.isNotBlank()) "(" + it.emoji + ")" else "") })
            val words = ArrayList<Word>(); var t = 0.0
            for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.36 }; t += 0.25 }
            val tl = Timeline(scenes, words, t)
            val finder = MediaFinder(File(dir, "media"), log = { println("REPROLOG $it") })
            val media = HashMap<Int, SceneMedia.Lazy>()
            scenes.forEachIndexed { i, s ->
                if (i > 0 && (s.type == "photo" || ScenePainter.wantsBackdrop(s.type))) {
                    val f = File(dir, "${p.id}_$i.jpg")
                    val q = s.query.ifBlank { MediaFinder.keywords(s.text.ifBlank { s.say }, topic) }
                    if (runCatching { finder.photo(q, s.wiki, f) }.getOrNull() != null) media[i] = SceneMedia.Lazy {
                        val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath, b)
                        var sample = 1; while (b.outWidth / (sample * 2) >= 1080) sample *= 2
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.let { SceneMedia.Photo(it) }
                    }
                }
            }
            val emojis = (scenes.map { it.emoji.trim() } + "💬" + "💡").filter { it.isNotBlank() }.distinct()
            val animated = emojis.mapNotNull { e -> AnimatedEmoji.load(e, File(dir, "emoji"))?.let { e to it } }.toMap()
            println("REPRO media=${media.size} animated=${animated.keys}")
            val look = Look.of(lookId)
            val painter = ScenePainter(ctx, scenes, tl, Palettes.find("electric"), "", media, emptyMap(), look, emptyList(), animated, motionPlan = MotionPlan(p.id.hashCode(), scenes.map { it.type }, look))
            val frames = (tl.duration * 30).toInt()
            val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            val times = LongArray(frames)
            val worker = Thread {
                for (i in 0 until frames) {
                    val st = System.nanoTime()
                    painter.draw(Canvas(bmp), i / 30.0)
                    times[i] = (System.nanoTime() - st) / 1_000_000
                    if (times[i] > 3000) println("REPRO SLOW frame $i (t=${"%.2f".format(i / 30.0)}) ${times[i]} ms scene=${tl.sceneAt(i / 30.0).index}")
                }
            }
            worker.start(); worker.join(600_000)
            if (worker.isAlive) {
                println("REPRO HANG in $topic; stack:"); worker.stackTrace.take(40).forEach { println("REPRO   at $it") }
                worker.interrupt()
            } else {
                val worst = times.withIndex().sortedByDescending { it.value }.take(5)
                println("REPRO $topic done: $frames frames, total ${times.sum() / 1000} s, worst ${worst.joinToString { "#${it.index}=${it.value}ms" }}")
            }
            painter.release()
        }
    }
}
