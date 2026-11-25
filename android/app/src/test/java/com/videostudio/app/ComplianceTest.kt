package com.videostudio.app

import com.videostudio.app.compliance.YouTubeCheck
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.data.Settings
import com.videostudio.app.data.Source
import com.videostudio.app.work.Pipeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComplianceTest {
    private fun project(topic: String, title: String = topic) = Project(
        id = topic.hashCode().toString(), topic = topic, title = title,
        scenes = mutableListOf(Scene(say = "Why do cats sleep so much?")),
        sources = listOf(Source("Cats sleep 15 hours", "https://example.org")),
    )

    @Test fun cleanVideoHasNoWarnings() {
        val p = project("why cats sleep so much").apply { take = "They are ambush hunters." }
        val items = YouTubeCheck.check(p, listOf(p), emptyList(), aiLabel = true)
        assertEquals(0, items.count { it.level == YouTubeCheck.Level.WARN })
    }

    @Test fun findsSwearingClickbaitAndRiskyClaims() {
        assertEquals(listOf("shit"), YouTubeCheck.profanity("This is shit, honestly"))
        assertTrue(YouTubeCheck.profanity("Shitake? no: classic assessment").isEmpty())
        assertTrue(YouTubeCheck.clickbait("You WON'T believe this").isNotEmpty())
        assertTrue(YouTubeCheck.clickbait("THIS CHANGES EVERYTHING").contains("ALL CAPS"))
        assertTrue(YouTubeCheck.clickbait("How black holes form").isEmpty())
        assertTrue(YouTubeCheck.riskyClaims("This tea cures cancer").isNotEmpty())
    }

    @Test fun warnsOnNearDuplicateTopic() {
        val a = project("why cats sleep so much")
        val b = project("why do cats sleep so much").copy(id = "b")
        assertTrue(YouTubeCheck.similarity(a.topic, b.topic) >= 0.6)
        val items = YouTubeCheck.check(b, listOf(a, b), emptyList(), true)
        assertTrue(items.any { it.title.startsWith("Very close") })
        assertTrue(YouTubeCheck.mostSimilar(b, listOf(project("history of the roman empire"))) == null)
    }

    @Test fun sensitiveAndPaidAreFlagged() {
        val p = project("vitamin d and colds").apply { sensitive = "health"; paidPromotion = true }
        val items = YouTubeCheck.check(p, emptyList(), emptyList(), true)
        assertTrue(items.any { it.title.contains("Sensitive") })
        assertTrue(items.any { it.title.contains("Paid promotion") })
    }

    @Test fun captionCarriesCreditsAndDisclaimers() {
        val s = Settings(RuntimeEnvironment.getApplication())
        val p = project("vitamin d").apply { sensitive = "health"; paidPromotion = true; hashtags = listOf("health") }
        val credits = listOf("\"Sun\" by Ann, CC BY 4.0 via Openverse (https://example.org/x)")
        val yt = Pipeline.publishText(p, s, forYouTube = true, credits = credits)
        assertTrue(yt.contains("Picture credits:") && yt.contains("https://example.org/x"))
        assertTrue(yt.contains("not medical advice") && yt.contains("paid promotion"))
        val caption = Pipeline.publishText(p, s, credits = credits)
        assertTrue(caption.contains("Pictures: \"Sun\" by Ann, CC BY 4.0 via Openverse"))
        assertFalse(caption.contains("https://example.org/x"))
    }

    @Test fun complianceFieldsSurviveSaveAndOldData() {
        val p = project("x").apply { madeForKids = true; paidPromotion = true; sensitive = "finance" }
        val back = Project.fromJson(JSONObject(p.toJson().toString()))
        assertTrue(back.madeForKids && back.paidPromotion)
        assertEquals("finance", back.sensitive)
        val old = Project.fromJson(JSONObject("""{"id":"o","topic":"t"}"""))
        assertFalse(old.madeForKids || old.paidPromotion)
    }
}
