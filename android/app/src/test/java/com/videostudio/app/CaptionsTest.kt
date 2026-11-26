package com.videostudio.app

import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Project
import com.videostudio.app.data.Settings
import com.videostudio.app.upload.AutoCaption
import com.videostudio.app.upload.Captions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptionsTest {
    private val s get() = Settings(ApplicationProvider.getApplicationContext())
    private fun p() = Project(id = "c", topic = "AI editing").apply {
        ytTitle = "This AI Replaced My Editors"
        description = "One setting turns raw clips into finished Shorts. Here is how it works. And a third sentence."
        commentQuestion = "Would you trust AI with your edits?"
        hashtags = listOf("ai", "videoediting", "aitools", "shorts", "creator", "contentcreator", "tech")
    }
    private fun tagCount(t: String) = Regex("#\\w+").findAll(t).count()

    @Test fun eachAppGetsItsOwnCaption() {
        val all = Captions.all(listOf("tiktok", "instagram", "facebook", "youtube", "x", "threads"), p(), s, listOf("\"Sun\" by Ann, CC BY 4.0 (https://x.org)"))
        assertEquals(5, tagCount(all.getValue("tiktok")))
        assertTrue(all.getValue("tiktok").contains("#fyp"))
        assertEquals(5, tagCount(all.getValue("instagram")))
        assertTrue(all.getValue("instagram").contains("Pictures: \"Sun\" by Ann"))
        assertEquals(3, tagCount(all.getValue("facebook")))
        assertTrue(all.getValue("youtube").length <= 100 && all.getValue("youtube").contains("#shorts"))
        assertTrue(all.getValue("x").length <= 280)
        assertEquals(1, tagCount(all.getValue("threads")))
        // Every caption has the title and (where it fits) the comment question.
        for (k in listOf("tiktok", "instagram", "facebook", "x")) {
            assertTrue(k, all.getValue(k).startsWith("This AI Replaced My Editors"))
            assertTrue(k, all.getValue(k).contains("Would you trust AI"))
        }
        // Long-form apps get the description; Facebook only its first sentence.
        assertTrue(all.getValue("instagram").contains("Here is how it works."))
        assertTrue(!all.getValue("facebook").contains("Here is how it works."))
        assertTrue(!all.getValue("tiktok").contains("#shorts"))
    }

    @Test fun findsTheCaptionBoxButNeverSearchOrComments() {
        assertTrue(AutoCaption.score("Write a caption...", "", "", "", true) > 0)
        assertTrue(AutoCaption.score("Add description", "", "", "com.zhiliaoapp.musically:id/desc", true) > 0)
        assertTrue(AutoCaption.score("Describe your reel", "", "", "", false) > 0)
        assertTrue(AutoCaption.score("Caption your Short", "", "", "", false) > 0)
        assertEquals(0, AutoCaption.score("Search", "", "", "search_box", false))
        assertEquals(0, AutoCaption.score("Add a comment…", "", "", "", true))
        assertEquals(0, AutoCaption.score("", "", "", "username", false))
    }

    @Test fun armExpiresAndTargetsOnlyThatApp() {
        AutoCaption.arm("instagram", listOf("com.instagram.android"), "hello")
        val pnd = AutoCaption.pending!!
        assertEquals(setOf("com.instagram.android"), pnd.packages)
        assertTrue(pnd.until > System.currentTimeMillis())
        AutoCaption.pending = null
    }
}
