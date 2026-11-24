package com.videostudio.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Migrations
import com.videostudio.app.data.ProjectStore
import com.videostudio.app.data.Settings
import com.videostudio.app.data.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** An update must keep everything the older app saved: videos, scripts and settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCompatTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** project.json exactly as Video Studio 1.0 (the original release) wrote it. */
    private val v10Project = """{
 "id": "2026-11-20-why-the-sky-is-blue",
 "topic": "Why the sky is blue",
 "audience": "kids",
 "lengthSec": 30,
 "language": "English",
 "voice": "en-US-AvaNeural",
 "palette": "ocean",
 "research": true,
 "status": "posted",
 "title": "The sky's secret",
 "researchNotes": "Rayleigh scattering — https://example.com",
 "scenes": [
  {"say": "Why is the sky blue?", "type": "hook", "text": "WHY BLUE?", "emoji": "🌤️", "value": "", "label": "",
   "items": [], "left": "", "leftText": "", "right": "", "rightText": "", "highlight": "", "query": ""},
  {"say": "Blue light scatters most.", "type": "stat", "text": "", "emoji": "", "value": "5x", "label": "more scattering",
   "items": [], "left": "", "leftText": "", "right": "", "rightText": "", "highlight": "", "query": ""}
 ],
 "sources": [{"claim": "Blue scatters more", "url": "https://example.com"}],
 "ytTitle": "Why is the sky blue?",
 "description": "Light and air.",
 "hashtags": ["science", "sky"],
 "titleOptions": ["Why is the sky blue?", "The sky's secret"],
 "commentQuestion": "What colour is your sky?",
 "uploads": [{"platform": "youtube", "url": "https://youtube.com/shorts/abc", "visibility": "public", "at": 1763600000000}],
 "error": "",
 "voiceUsed": "en-US-AvaNeural",
 "durationSec": 31.5,
 "created": 1763600000000,
 "updated": 1763600100000
}"""

    @Test
    fun projectsFromVersion10LoadCompletely() {
        val root = Files.createTempDirectory("projects").toFile()
        File(root, "2026-11-20-why-the-sky-is-blue").apply { mkdirs() }.resolve("project.json").writeText(v10Project)
        // A broken folder from a crash must not hide the good ones.
        File(root, "broken").apply { mkdirs() }.resolve("project.json").writeText("{not json")

        val store = ProjectStore(root)
        val p = store.projects.value.single()
        assertEquals("Why the sky is blue", p.topic)
        assertEquals(Status.POSTED, p.status)
        assertEquals(2, p.scenes.size)
        assertEquals("5x", p.scenes[1].value)
        assertEquals("https://youtube.com/shorts/abc", p.uploads.single().url)
        assertEquals(31.5, p.durationSec, 0.0)

        // Saving with the new version keeps every field and adds the data version.
        store.save(p)
        val saved = org.json.JSONObject(File(root, p.id).resolve("project.json").readText())
        assertEquals(Migrations.CURRENT, saved.getInt("schema"))
        assertEquals("What colour is your sky?", saved.getString("commentQuestion"))
        assertEquals("ocean", ProjectStore(root).get(p.id)!!.palette)
    }

    @Test
    fun settingsFromOlderVersionsAreKeptAndOldServiceIsCleanedUp() {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear()
            // 1.0 keys
            .putString("gemini_key", "AIza-old-key")
            .putString("voice", "bn-BD-PradeepNeural")
            .putString("series_name", "Tech in 60s")
            .putString("yt_refresh", "refresh-token")
            .putBoolean("ai_label", false)
            // 1.1 keys of the removed posting service
            .putString("social_api_key", "old-upload-post-key")
            .putString("social_connected", "tiktok,youtube")
            .commit()

        Migrations.run(ctx, ProjectStore(Files.createTempDirectory("p").toFile()))
        val s = Settings(ctx)
        assertEquals("AIza-old-key", s.geminiKey)
        assertEquals("bn-BD-PradeepNeural", s.voice)
        assertEquals("Tech in 60s", s.seriesName)
        assertEquals("refresh-token", s.youtubeRefreshToken)
        assertFalse(s.aiLabel)
        assertFalse(prefs.contains("social_api_key"))
        assertFalse(prefs.contains("social_connected"))
        assertEquals(Migrations.CURRENT, prefs.getInt("data_version", 0))

        // Running again (every start-up) changes nothing.
        Migrations.run(ctx, ProjectStore(Files.createTempDirectory("p").toFile()))
        assertEquals("AIza-old-key", Settings(ctx).geminiKey)
    }

    @Test
    fun versionCodeIsAboveEveryPublishedBuild() {
        // 1.0 = 1, 1.1 = 2, 1.2 = 3: anything at or below would be refused as an update.
        assertTrue(BuildConfig.VERSION_CODE > 3)
        assertEquals("com.videostudio.app", BuildConfig.APPLICATION_ID)
    }
}
