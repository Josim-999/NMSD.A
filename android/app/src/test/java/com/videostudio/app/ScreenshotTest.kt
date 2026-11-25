package com.videostudio.app

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.data.Status
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Writes screenshots of the new screens to build/screens for a visual check (not an assertion). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        rule.waitForIdle()
        // Draw the window ourselves; works with Robolectric's native graphics.
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun settingsAndPostEverywhere() {
        shot("1_home_empty")
        rule.onNodeWithContentDescription("Settings").performClick()
        shot("2_settings_not_connected")

        // Pretend accounts are already linked, then open a project with a finished video.
        val app = rule.activity.application as App
        for (pkg in listOf("com.zhiliaoapp.musically", "com.instagram.android", "com.google.android.youtube", "com.facebook.katana")) {
            org.robolectric.Shadows.shadowOf(app.packageManager).installPackage(android.content.pm.PackageInfo().apply { packageName = pkg })
        }
        val p = Project(id = "demo", topic = "Why your phone battery dies in the cold", status = Status.RENDERED, durationSec = 42.0)
        p.title = "Why cold kills your battery"
        p.ytTitle = "Why your phone dies in the cold"
        p.description = "Cold slows the chemistry inside lithium batteries."
        p.hashtags = listOf("tech", "battery", "shorts")
        p.scenes = mutableListOf(Scene("Why?", type = "hook"), Scene("Because.", type = "statement"), Scene("Comment!", type = "outro"))
        rule.runOnUiThread {
            app.store.save(p)
            p.video(app.store.root).writeBytes(ByteArray(1000))
        }
        rule.activity.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Why cold kills your battery").performClick()
        rule.onNodeWithText("Video & upload").performClick()
        rule.onNodeWithText("Paid promotion").performScrollTo()
        shot("3a_youtube_check")
        rule.onNodeWithText("Post to all 4").performScrollTo()
        shot("3_post_to_all")
        rule.onNodeWithText("Schedule in the YouTube app").performScrollTo()
        shot("3b_schedule")

        // Tap the button: the video goes to the gallery and TikTok opens with it.
        rule.onNodeWithText("Post to all 4").performClick()
        rule.waitUntil(10_000) { app.postAll.state.value?.waiting == true }
        val started = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        org.junit.Assert.assertEquals("com.zhiliaoapp.musically", started.`package`)
        org.junit.Assert.assertTrue(started.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM).toString().startsWith("content://"))
        shot("4_waiting_for_tiktok")

        // Back from TikTok: Instagram is next, with a countdown.
        rule.runOnUiThread { app.postAll.onReturn() }
        rule.onNodeWithText("Opening Instagram in", substring = true).assertExists()
        shot("5_next_instagram")
    }
}
