package com.videostudio.app

import android.graphics.Bitmap
import com.videostudio.app.ai.Logo
import com.videostudio.app.ai.MediaFinder
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** Media finder logic against fake servers (no internet needed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaFinderTest {
    private lateinit var server: MockWebServer
    private val agents = ArrayList<String>()

    private fun png(w: Int, h: Int): Buffer {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Buffer().write(out.toByteArray())
    }

    private fun finder() = MediaFinder(
        Files.createTempDirectory("mf").toFile(),
        endpoints = MediaFinder.Endpoints(
            simpleIcons = server.url("/si").toString().trimEnd('/'),
            favicon = server.url("/fav").toString(),
            wikipedia = server.url("/wiki/").toString(),
            openverse = server.url("/ov/").toString(),
            commons = server.url("/commons").toString(),
        ),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(agents) { agents.add(request.getHeader("User-Agent") ?: "") }
                val path = request.path!!
                return when {
                    path.startsWith("/si/_data/simple-icons.json") ->
                        MockResponse().setBody("""[{"title":"NVIDIA","hex":"76B900"},{"title":"Google Gemini","hex":"8E75B2"},{"title":"Dot.com","hex":"000000"}]""")
                    path == "/si/icons/nvidia.svg" -> MockResponse().setBody("""<svg role="img" viewBox="0 0 24 24"><title>NVIDIA</title><path d="M0 0h24v24H0z"/></svg>""")
                    path == "/si/icons/googlegemini.svg" -> MockResponse().setBody("""<svg><path d="M1 1h22v22H1z"/></svg>""")
                    path.startsWith("/fav") && path.contains("bigicon.com") -> MockResponse().setBody(png(256, 256))
                    path.startsWith("/fav") -> MockResponse().setBody(png(16, 16)) // Google's "unknown site" globe
                    path.startsWith("/wiki/Sam_Altman") -> MockResponse().setBody(
                        """{"type":"standard","originalimage":{"source":"${server.url("/img/altman.jpg")}"},"content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Sam_Altman"}}}"""
                    )
                    path.startsWith("/ov/?") -> MockResponse().setBody(
                        """{"results":[
                          {"id":"small","width":300,"height":200,"url":"${server.url("/img/small.jpg")}","title":"tiny","creator":"x","license":"by"},
                          {"id":"wide","width":2000,"height":1000,"url":"${server.url("/img/wide.jpg")}","title":"Wide","creator":"A","license":"by"},
                          {"id":"tall","width":1080,"height":1500,"url":"${server.url("/img/tall.jpg")}","title":"Tall servers","creator":"B","license":"by-sa","license_version":"2.0","foreign_landing_url":"https://flickr.com/x"}]}"""
                    )
                    path.startsWith("/ov/") && path.contains("/thumb/") -> MockResponse().setBody(png(900, 1300))
                    path.startsWith("/img/") -> MockResponse().setBody(png(800, 1000))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun vectorLogoInBrandColour() {
        val logo = finder().logo("Nvidia", "")
        assertTrue(logo is Logo.Vector)
        logo as Logo.Vector
        assertEquals("M0 0h24v24H0z", logo.pathData)
        assertEquals(0xFF76B900.toInt(), logo.color)
        assertTrue(finder().logo("Google Gemini", "gemini.google.com") is Logo.Vector)
    }

    @Test
    fun siteIconFallbackRejectsTheUnknownSiteGlobe() {
        assertTrue(finder().logo("Big Icon", "bigicon.com") is Logo.Raster)
        assertNull(finder().logo("Nobody Knows", "nobodyknows.example"))
    }

    @Test
    fun wikipediaPhotoForANamedPerson() {
        val f = Files.createTempFile("p", ".jpg").toFile()
        val found = finder().photo("", "Sam Altman", f)
        assertTrue(found!!.credit.contains("Wikipedia"))
    }

    @Test
    fun openverseBestPortraitAndNoRepeats() {
        val mf = finder()
        val a = mf.photo("servers", "", Files.createTempFile("a", ".jpg").toFile())!!
        assertTrue(a.credit.contains("Tall servers"))
        val b = mf.photo("servers", "", Files.createTempFile("b", ".jpg").toFile())!!
        assertNotEquals(a.credit, b.credit) // a second scene gets a different picture
        assertTrue(agents.all { it.startsWith("VideoStudioApp/") })
    }

    @Test
    fun helpers() {
        assertEquals("dotdotcom", MediaFinder.slug("Dot.com"))
        assertEquals("googlegemini", MediaFinder.slug("Google Gemini"))
        assertEquals("cplusplus", MediaFinder.slug("C++"))
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/5/57/Data.jpg/1280px-Data.jpg",
            MediaFinder.wikiSizes("https://thumb.wikimedia.org/wikipedia/commons/thumb/5/57/Data.jpg/3840px-Data.jpg?utm=x")[0]
        )
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/3/32/Google.jpg/1280px-Google.jpg",
            MediaFinder.wikiSizes("https://upload.wikimedia.org/wikipedia/commons/3/32/Google.jpg?utm=x")[0]
        )
        assertTrue(MediaFinder.score(1080, 1500) > MediaFinder.score(2000, 1000))
    }
}
