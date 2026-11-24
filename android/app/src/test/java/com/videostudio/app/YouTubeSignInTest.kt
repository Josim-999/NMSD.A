package com.videostudio.app

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.upload.UploadError
import com.videostudio.app.upload.YouTube
import com.videostudio.app.work.KeepAliveService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * The Google sign-in that hung after "Continue": the app must stay awake (keep-alive service),
 * answer Google's redirect on the loopback port, swap the code for tokens and finish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeSignInTest {
    private fun setUpYouTube(tokens: MockWebServer): YouTube {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.settings.youtubeClientId = "123-abc.apps.googleusercontent.com"
        app.settings.youtubeClientSecret = "GOCSPX-secret"
        app.settings.signOutYouTube()
        return YouTube(app.settings, tokens.url("/token").toString())
    }

    /** Runs the main looper until the sign-in thread has opened Google's page, and returns that URL. */
    private fun waitForGooglePage(): Uri {
        val app = ApplicationProvider.getApplicationContext<App>()
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            val started: Intent? = shadowOf(app).peekNextStartedActivity()
            if (started?.data?.host == "accounts.google.com") return shadowOf(app).nextStartedActivity.data!!
            Thread.sleep(25)
        }
        throw AssertionError("Google's sign-in page was never opened")
    }

    @Test
    fun continueOnGooglesPageFinishesSignIn() {
        val tokens = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"access_token":"at","refresh_token":"rt","expires_in":3600}"""))
            start()
        }
        val yt = setUpYouTube(tokens)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val error = AtomicReference<Throwable?>(null)
        val t = Thread { runCatching { yt.signIn(activity) }.onFailure { error.set(it) } }
        t.start()

        val page = waitForGooglePage()
        // The keep-alive service runs while the user is on Google's page.
        val service = shadowOf(ApplicationProvider.getApplicationContext<App>()).nextStartedService
        assertEquals(KeepAliveService::class.java.name, service.component!!.className)
        assertEquals("https://www.googleapis.com/auth/youtube.upload", page.getQueryParameter("scope"))

        // Google redirects the browser to the app after "Continue".
        val redirect = page.getQueryParameter("redirect_uri")!!
        val state = page.getQueryParameter("state")!!
        val conn = URL("$redirect/?state=$state&code=the-code&scope=x").openConnection() as HttpURLConnection
        assertEquals(200, conn.responseCode)
        val html = conn.inputStream.bufferedReader().readText()
        assertTrue(html.contains("Signed in to YouTube"))

        t.join(10_000)
        assertEquals(null, error.get())
        assertTrue(yt.signedIn)
        val form = tokens.takeRequest().body.readUtf8()
        assertTrue(form.contains("code=the-code") && form.contains("code_verifier="))
        tokens.shutdown()
    }

    @Test
    fun cancelStopsAWaitingSignIn() {
        val tokens = MockWebServer().apply { start() }
        val yt = setUpYouTube(tokens)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val error = AtomicReference<Throwable?>(null)
        val t = Thread { runCatching { yt.signIn(activity) }.onFailure { error.set(it) } }
        t.start()
        waitForGooglePage()
        yt.cancelSignIn()
        t.join(10_000)
        assertTrue(error.get() is UploadError)
        assertEquals("Sign-in cancelled.", error.get()!!.message)
        tokens.shutdown()
    }
}
