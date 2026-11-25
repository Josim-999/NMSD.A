package com.videostudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.ai.MediaFinder
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.data.Settings
import com.videostudio.app.render.AnimatedEmoji
import com.videostudio.app.render.Animoji
import com.videostudio.app.render.Cutout
import com.videostudio.app.render.Look
import com.videostudio.app.render.Palettes
import com.videostudio.app.render.SceneMedia
import com.videostudio.app.render.ScenePainter
import com.videostudio.app.render.Timeline
import com.videostudio.app.upload.Schedule
import com.videostudio.app.upload.YouTube
import com.videostudio.app.voice.Word
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V19Test {
    private val ny = "America/New_York"

    // ---------------- Scheduling ----------------

    @Test fun schedulesInTheAudienceTimeZone() {
        val now = Instant.parse("2026-11-25T12:00:00Z").toEpochMilli() // 7:00 AM in New York
        assertEquals(Instant.parse("2026-11-26T00:00:00Z").toEpochMilli(), Schedule.at(ny, 0, 19 * 60, now)) // 7 PM ET
        assertEquals(Instant.parse("2026-11-26T03:00:00Z").toEpochMilli(), Schedule.at("America/Los_Angeles", 0, 19 * 60, now))
        assertEquals(0, Schedule.firstDay(ny, 19 * 60, now))
        assertEquals(1, Schedule.firstDay(ny, 7 * 60, now)) // 7 AM already passed
        assertEquals("Tomorrow", Schedule.dayLabel(ny, 1, now))
        assertEquals("7:00 PM", Schedule.timeLabel(19 * 60))
        // Shown on a phone in Bangladesh (UTC+6): 7 PM ET is 6 AM the next day.
        assertEquals("Thu Nov 26, 6:00 AM", Schedule.onPhone(Schedule.at(ny, 0, 19 * 60, now), ZoneId.of("Asia/Dhaka")))
    }

    @Test fun summerTimeIsHandled() {
        val now = Instant.parse("2027-07-01T12:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2027-07-01T23:00:00Z").toEpochMilli(), Schedule.at(ny, 0, 19 * 60, now)) // EDT = UTC-4
    }

    @Test fun scheduledUploadSendsPrivateWithPublishAt() {
        val server = MockWebServer()
        var meta = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "POST" -> { meta = request.body.readUtf8(); MockResponse().setHeader("Location", server.url("/session").toString()) }
                else -> MockResponse().setBody("""{"id":"abc123"}""")
            }
        }
        server.start()
        val s = Settings(ApplicationProvider.getApplicationContext())
        s.youtubeRefreshToken = "r"; s.youtubeAccessToken = "a"; s.youtubeAccessExpires = System.currentTimeMillis() + 3_600_000
        val video = File.createTempFile("video", ".mp4").apply { writeBytes(ByteArray(2000)) }
        val at = Instant.parse("2026-12-01T00:00:00Z")
        val r = YouTube(s, uploadUrl = server.url("/upload").toString()).upload(
            video, "Title", "Desc", listOf("a"), "public", aiLabel = true, madeForKids = false, paidPromotion = true, publishAt = at,
        ) {}
        assertEquals("abc123", r.id)
        val status = JSONObject(meta).getJSONObject("status")
        assertEquals("private", status.getString("privacyStatus"))
        assertEquals("2026-12-01T00:00:00Z", status.getString("publishAt"))
        assertTrue(JSONObject(meta).getJSONObject("paidProductPlacementDetails").getBoolean("hasPaidProductPlacement"))
        server.shutdown()
    }

    @Test fun newProjectFieldsSurvive() {
        val p = Project(id = "x", topic = "t").apply { scheduledAt = 123; scheduleZone = ny; scheduleState = "queued" }
        p.scenes = mutableListOf(Scene("s", type = "photo", library = "nasa"))
        val back = Project.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(123L, back.scheduledAt); assertEquals(ny, back.scheduleZone); assertEquals("queued", back.scheduleState)
        assertEquals("nasa", back.scenes[0].library)
        val fromModel = Scene.fromJson(JSONObject("""{"say":"x","visual":{"type":"photo","library":"Museum"}}"""))
        assertEquals("museum", fromModel.library)
        assertEquals("", Scene.fromJson(JSONObject("""{"say":"x","visual":{"library":"bogus"}}""")).library)
    }

    // ---------------- Animated emoji ----------------

    private fun fire() = AnimatedEmoji.parse(javaClass.getResource("/lottie/1f525.json")!!.readText(), "test_fire")!!

    @Test fun emojiNamesMatchGooglesFiles() {
        assertEquals(listOf("1f525"), AnimatedEmoji.names("🔥"))
        assertEquals(listOf("2764_fe0f", "2764"), AnimatedEmoji.names("❤️"))
        assertTrue(AnimatedEmoji.names("").isEmpty())
    }

    @Test fun animatedEmojiMovesOverTime() {
        val a = Animoji(fire())
        fun frame(t: Double): Bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also { a.draw(Canvas(it), 100f, 100f, 180f, t) }
        val f1 = frame(0.1); val f2 = frame(0.6)
        val painted = (0 until 200 step 10).sumOf { y -> (0 until 200 step 10).count { x -> Color.alpha(f1.getPixel(x, y)) > 0 } }
        assertTrue("emoji drew nothing", painted > 20)
        val diff = (0 until 200 step 5).sumOf { y -> (0 until 200 step 5).count { x -> f1.getPixel(x, y) != f2.getPixel(x, y) } }
        assertTrue("emoji did not move", diff > 10)
    }

    @Test fun loadsAndCachesFromTheNotoServer() {
        val server = MockWebServer()
        val json = javaClass.getResource("/lottie/1f525.json")!!.readText()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path!!.contains("/1f525/")) MockResponse().setBody(json) else MockResponse().setResponseCode(404)
        }
        server.start()
        val old = AnimatedEmoji.base
        AnimatedEmoji.base = server.url("/e/%s/lottie.json").toString().replace("%25s", "%s")
        try {
            val dir = Files.createTempDirectory("emo").toFile()
            assertNotNull(AnimatedEmoji.load("🔥", dir))
            assertNull(AnimatedEmoji.load("🦄", dir))
            server.shutdown()
            assertNotNull("cached copy used offline", AnimatedEmoji.load("🔥", dir))
        } finally {
            AnimatedEmoji.base = old
        }
    }

    // ---------------- Pop-out ----------------

    /** A photo of a "person" (orange shape) on a teal background, and its cut-out. */
    private fun subject(): Pair<Bitmap, Bitmap> {
        val photo = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        val fg = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        val bg = Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, 960f, 0xFF1D3557.toInt(), 0xFF2A9D8F.toInt(), android.graphics.Shader.TileMode.CLAMP) }
        Canvas(photo).drawPaint(bg)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4A261.toInt() }
        for (c in listOf(Canvas(photo), Canvas(fg))) {
            c.drawCircle(270f, 380f, 110f, body)
            c.drawRoundRect(120f, 480f, 420f, 960f, 90f, 90f, body)
        }
        return photo to fg
    }

    @Test fun cutoutNeedsAClearSubject() {
        val (_, fg) = subject()
        val c = Cutout.fromForeground(fg)
        assertNotNull(c)
        assertTrue(c!!.bounds.top in 0.25f..0.32f)
        val empty = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertNull(Cutout.fromForeground(empty))
        val full = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertNull(Cutout.fromForeground(full))
    }

    @Test fun paintsPopOutAnimatedEmojiAndEachDisplayFont() {
        val scenes = listOf(
            Scene("This is hot right now.", type = "hook", text = "This is hot right now", emoji = "🔥"),
            Scene("Meet the woman behind it.", type = "photo", text = "The mind behind it", query = "scientist"),
            Scene("Tell me below.", type = "outro", text = "Would you try it?", emoji = "🔥"),
        )
        val words = ArrayList<Word>(); var t = 0.0
        for (s in scenes) { for (w in s.say.split(" ")) { words.add(Word(w, t, t + 0.3)); t += 0.35 }; t += 0.3 }
        val timeline = Timeline(scenes, words, t)
        val out = File("build/frames19").apply { mkdirs() }
        for (style in 0 until Look.CAPTIONS) {
            val (photo, fg) = subject() // release() frees the pictures, so each painter gets its own
            val painter = ScenePainter(
                ApplicationProvider.getApplicationContext(), scenes, timeline, Palettes.find("electric"), "", 
                mapOf(1 to SceneMedia.Lazy { SceneMedia.Photo(photo) }), look = Look(style, 0, style % 3),
                animated = mapOf("🔥" to fire()), cutouts = mapOf(1 to Cutout.fromForeground(fg)!!),
            )
            for (span in timeline.spans) {
                val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp); c.scale(0.5f, 0.5f)
                painter.draw(c, span.start + minOf(1.2, (span.end - span.start) * 0.8))
                File(out, "style${style}_scene${span.index}_${scenes[span.index].type}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val colours = (0 until 960 step 20).map { bmp.getPixel(270, it) }.toSet()
                assertTrue("blank frame", colours.size > 3)
            }
            painter.release()
        }
    }

    // ---------------- New picture sources ----------------

    private fun png(w: Int, h: Int): Buffer {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, o)
        return Buffer().write(o.toByteArray())
    }

    @Test fun nasaMetAndPixabayPictures() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                return when {
                    p.startsWith("/nasa") -> MockResponse().setBody(
                        """{"collection":{"items":[{"data":[{"nasa_id":"as11-40-5874","title":"Buzz Aldrin on the Moon"}],"links":[{"href":"${server.url("/img/as11~thumb.jpg")}"}]}]}}"""
                    )
                    p.startsWith("/met/search") -> MockResponse().setBody("""{"total":2,"objectIDs":[1,2]}""")
                    p.startsWith("/met/objects/1") -> MockResponse().setBody("""{"isPublicDomain":false,"primaryImage":"${server.url("/img/no.jpg")}"}""")
                    p.startsWith("/met/objects/2") -> MockResponse().setBody(
                        """{"isPublicDomain":true,"title":"Armor of Henry II","primaryImage":"${server.url("/img/armor.jpg")}","objectURL":"https://www.metmuseum.org/art/collection/search/2"}"""
                    )
                    p.startsWith("/pix/?") -> MockResponse().setBody(
                        """{"hits":[{"id":7,"user":"Ann","pageURL":"https://pixabay.com/photos/7","largeImageURL":"${server.url("/img/pix.jpg")}"}]}"""
                    )
                    p.startsWith("/img/as11~large") || p.startsWith("/img/armor") || p.startsWith("/img/pix") -> MockResponse().setBody(png(800, 1000))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val f = MediaFinder(
            Files.createTempDirectory("mf").toFile(),
            endpoints = MediaFinder.Endpoints(
                wikipedia = server.url("/wiki/").toString(), openverse = server.url("/ov/").toString(), commons = server.url("/commons").toString(),
                nasa = server.url("/nasa").toString(), met = server.url("/met/").toString(), pixabay = server.url("/pix/").toString(),
            ),
            pixabayKey = "k",
        )
        val dir = Files.createTempDirectory("out").toFile()
        val nasa = f.photo("moon landing", "", File(dir, "a.jpg"), library = "nasa")
        assertTrue(nasa!!.credit.contains("NASA (public domain)") && nasa.credit.contains("as11-40-5874"))
        val met = f.photo("knight armor", "", File(dir, "b.jpg"), library = "museum")
        assertTrue(met!!.credit.contains("Metropolitan Museum") && met.credit.contains("CC0"))
        val pix = f.photo("coffee cup", "", File(dir, "c.jpg"))
        assertTrue(pix!!.credit.contains("from Pixabay"))
        server.shutdown()
    }
}
