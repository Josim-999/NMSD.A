package com.videostudio.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.data.Status
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Smoke test: every screen opens and shows its key controls without crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeToSettingsShowsConnectedAccounts() {
        rule.onNodeWithText("Make your first Short").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Connected accounts — post everywhere with one tap").assertIsDisplayed()
        rule.onNodeWithText("Upload-Post API key").assertIsDisplayed()
        rule.onNodeWithText("Connect accounts").assertIsDisplayed()
        rule.onNodeWithText("YouTube direct upload (advanced, optional)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun newVideoScreenOpens() {
        rule.onNodeWithText("New video", useUnmergedTree = true).performClick()
        rule.onNodeWithText("What is the video about?").assertIsDisplayed()
        rule.onNodeWithText("Write the script").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun projectScreenShowsScriptAndVideoTabs() {
        val app = rule.activity.application as App
        val p = Project(id = "test-project", topic = "Why is the sky blue", status = Status.SCRIPTED)
        p.scenes = mutableListOf(
            Scene("Why is the sky blue?", type = "hook", text = "WHY BLUE?"),
            Scene("Light scatters.", type = "statement"),
            Scene("Tell me below!", type = "outro"),
        )
        rule.runOnUiThread { app.store.save(p) }
        rule.waitForIdle()
        rule.onNodeWithText("Why is the sky blue").performClick()
        rule.onNodeWithText("Make the video").assertIsDisplayed()
        rule.onNodeWithText("Video & upload").performClick()
        assertTrue(rule.onAllNodesWithText("No video yet").fetchSemanticsNodes().isNotEmpty())
    }
}
