package com.videostudio.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Project
import com.videostudio.app.data.Status
import com.videostudio.app.upload.Share
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostAllTest {
    private lateinit var app: App
    private val video: Uri = Uri.parse("content://media/external/video/media/42")

    private fun install(pkg: String) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = pkg })
    }

    private fun nextStarted(): Intent? = shadowOf(app).nextStartedActivity

    private fun clipboard() =
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.store.save(Project(id = "p1", topic = "Cold batteries", status = Status.RENDERED))
    }

    @Test
    fun opensEachAppInTurnWithVideoAndCaption() {
        install("com.zhiliaoapp.musically")
        install("com.instagram.android")
        install("com.google.android.youtube")
        val pa = app.postAll
        pa.start("p1", listOf("tiktok", "instagram", "youtube"), video, caption = "Caption #ai", title = "My title")

        pa.openCurrent(app)
        val tiktok = nextStarted()!!
        assertEquals(Intent.ACTION_SEND, tiktok.action)
        assertEquals("com.zhiliaoapp.musically", tiktok.`package`)
        assertEquals(video, tiktok.getParcelableExtra(Intent.EXTRA_STREAM))
        assertTrue(tiktok.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("Caption #ai", clipboard())
        assertTrue(pa.state.value!!.waiting)

        // User posts in TikTok and comes back: TikTok is done, Instagram is next.
        pa.onReturn()
        assertEquals(listOf("tiktok"), pa.state.value!!.done)
        assertEquals("instagram", pa.state.value!!.current)
        assertFalse(pa.state.value!!.waiting)

        pa.openCurrent(app)
        assertEquals("com.instagram.android", nextStarted()!!.`package`)
        pa.onReturn()

        pa.openCurrent(app)
        val yt = nextStarted()!!
        assertEquals("com.google.android.youtube", yt.`package`)
        assertEquals("My title", yt.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("My title", clipboard()) // YouTube needs the title pasted, not the caption
        pa.onReturn()

        assertTrue(pa.state.value!!.finished)
        assertEquals(listOf("tiktok", "instagram", "youtube"), pa.state.value!!.done)
        val p = app.store.get("p1")!!
        assertEquals(Status.POSTED, p.status)
        assertEquals(listOf("tiktok", "instagram", "youtube"), p.uploads.map { it.platform })
    }

    @Test
    fun missingAppIsSkippedAutomatically() {
        install("com.instagram.android")
        val pa = app.postAll
        pa.start("p1", listOf("tiktok", "instagram"), video, "c", "t")
        pa.openCurrent(app)
        assertEquals("com.instagram.android", nextStarted()!!.`package`)
        assertEquals(listOf("tiktok"), pa.state.value!!.failed)
        assertEquals("instagram", pa.state.value!!.current)
    }

    @Test
    fun returningWithoutOpeningDoesNothing() {
        install("com.zhiliaoapp.musically")
        val pa = app.postAll
        pa.start("p1", listOf("tiktok"), video, "c", "t")
        pa.onReturn() // e.g. the screen was just rotated
        assertEquals(0, pa.state.value!!.index)
        assertTrue(pa.state.value!!.done.isEmpty())
    }

    @Test
    fun skipRetryAndStop() {
        install("com.zhiliaoapp.musically")
        install("com.facebook.katana")
        val pa = app.postAll
        pa.start("p1", listOf("tiktok", "facebook"), video, "c", "t")
        pa.openCurrent(app)
        pa.onReturn()
        nextStarted()
        pa.retryLast(app) // did not post in TikTok after all
        assertEquals("com.zhiliaoapp.musically", nextStarted()!!.`package`)
        assertEquals("tiktok", pa.state.value!!.current)
        pa.onReturn()
        pa.skip()
        assertEquals(listOf("facebook"), pa.state.value!!.skipped)
        assertTrue(pa.state.value!!.finished)
        pa.stop()
        assertNull(pa.state.value)
    }

    @Test
    fun installedCheckAndNames() {
        assertFalse(Share.isInstalled(app, "tiktok"))
        install("com.ss.android.ugc.trill") // TikTok's other package id
        assertTrue(Share.isInstalled(app, "tiktok"))
        assertEquals("YouTube", Share.name("youtube"))
        assertEquals("YouTube", Share.name("youtube_app"))
    }
}
