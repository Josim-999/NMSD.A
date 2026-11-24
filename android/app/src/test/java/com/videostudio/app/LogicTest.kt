package com.videostudio.app

import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.ai.extractJson
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.data.Settings
import com.videostudio.app.data.Source
import com.videostudio.app.render.Timeline
import com.videostudio.app.upload.YouTube
import com.videostudio.app.voice.EdgeTts
import com.videostudio.app.voice.Word
import com.videostudio.app.work.Pipeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicTest {
    @Test
    fun projectSurvivesJsonRoundTrip() {
        val p = Project(id = "x", topic = "Cold batteries")
        p.scenes = mutableListOf(Scene("Why does it die?", type = "hook", emoji = "🔋"), Scene("Because chemistry.", items = listOf("a", "b")))
        p.sources = listOf(Source("claim", "https://example.com"))
        p.hashtags = listOf("tech")
        val back = Project.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p.copy(updated = back.updated), back.copy(updated = back.updated))
    }

    @Test
    fun sceneReadsModelShape() {
        val s = Scene.fromJson(JSONObject("""{"say":"Hi","visual":{"type":"stat","value":"70%","label":"of people"}}"""))
        assertEquals("stat", s.type)
        assertEquals("70%", s.value)
    }

    @Test
    fun extractJsonToleratesFences() {
        assertEquals(1, extractJson("```json\n{\"a\":1}\n```").getInt("a"))
        assertEquals(2, extractJson("Sure! {\"a\":2} hope that helps").getInt("a"))
    }

    @Test
    fun timelineSplitsScenesAndCaptions() {
        val scenes = listOf(Scene("Why is the sky blue?"), Scene("Light scatters."), Scene("Comment below!"))
        val words = listOf(
            Word("Why", 0.0, 0.2), Word("is", 0.2, 0.3), Word("the", 0.3, 0.4), Word("sky", 0.4, 0.6), Word("blue?", 0.6, 0.9),
            Word("Light", 1.2, 1.5), Word("scatters.", 1.5, 2.0),
            Word("Comment", 2.4, 2.7), Word("below!", 2.7, 3.0),
        )
        val t = Timeline(scenes, words, 3.0)
        assertEquals(listOf(0.0, 1.2, 2.4), t.spans.map { it.start })
        assertEquals(2, t.sceneAt(2.5).index)
        // At most 3 words per caption, and a break after "blue?".
        assertTrue(t.captions.all { it.words.size <= 3 })
        assertEquals("blue?", t.captions.first { g -> g.words.any { it.text == "blue?" } }.words.last().text)
        assertTrue(t.duration > 3.0)
    }

    @Test
    fun publishTextHasHashtagsAndSourcesForYouTube() {
        val s = Settings(ApplicationProvider.getApplicationContext())
        val p = Project(id = "x", topic = "T", ytTitle = "Title", description = "Desc", hashtags = listOf("ai"))
        p.sources = listOf(Source("A fact", "https://example.com"))
        val yt = Pipeline.publishText(p, s, forYouTube = true)
        assertTrue(yt.contains("Sources:") && yt.contains("#ai") && yt.contains("#shorts"))
        val social = Pipeline.publishText(p, s, forYouTube = false)
        assertTrue(social.startsWith("Title"))
        assertTrue(!social.contains("#shorts"))
    }

    @Test
    fun youtubeErrorsAreExplained() {
        val noChannel = """{"error":{"code":401,"message":"Unauthorized","errors":[{"reason":"youtubeSignupRequired"}]}}"""
        assertTrue(YouTube.explainApiError(noChannel).contains("no YouTube channel"))
        val disabled = """{"error":{"code":403,"message":"YouTube Data API v3 has not been used in project 1 before or it is disabled."}}"""
        assertTrue(YouTube.explainApiError(disabled).contains("not enabled"))
        assertTrue(YouTube.explainSignInError("access_denied").contains("Test users"))
        assertTrue(YouTube.explainTokenError("""{"error":"invalid_client"}""").contains("client secret"))
    }

    @Test
    fun youtubeConfigChecks() {
        val s = Settings(ApplicationProvider.getApplicationContext())
        val yt = YouTube(s)
        s.youtubeClientId = "AIzaSomething"
        s.youtubeClientSecret = "GOCSPX-abc"
        assertTrue(yt.configProblem()!!.contains("API key"))
        s.youtubeClientId = "123-abc.apps.googleusercontent.com"
        assertEquals(null, yt.configProblem())
    }

    @Test
    fun edgeVoiceNames() {
        assertEquals("Microsoft Server Speech Text to Speech Voice (en-US, AndrewNeural)", EdgeTts.longVoiceName("en-US-AndrewNeural"))
    }
}
