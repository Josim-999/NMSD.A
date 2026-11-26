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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoAccuracyTest {
    private lateinit var server: MockWebServer
    private val hits = ArrayList<String>()

    private fun png(w: Int, h: Int): Buffer {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF10A37F.toInt()) }
        val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Buffer().write(out.toByteArray())
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                synchronized(hits) { hits.add(p) }
                return when {
                    p.startsWith("/si/_data") -> MockResponse().setBody("""[
                        {"title":"Gemini","hex":"00DCFA","source":"https://www.gemini.com"},
                        {"title":"Google Gemini","hex":"8E75B2","source":"https://gemini.google.com"},
                        {"title":"Google","hex":"4285F4","source":"https://google.com"},
                        {"title":"OpenAI","hex":"412991","source":"https://openai.com/brand"}]""")
                    p.startsWith("/si/icons/") -> MockResponse().setBody("""<svg><path d="M0 0h24v24H0z"/></svg>""")
                    p.startsWith("/wd") && p.contains("wbsearchentities") && p.contains("ChatGPT") ->
                        MockResponse().setBody("""{"search":[{"id":"Q2"},{"id":"Q1"}]}""")
                    p.startsWith("/wd") && p.contains("wbsearchentities") && p.contains("Copilot") ->
                        MockResponse().setBody("""{"search":[{"id":"Q9"}]}""")
                    p.startsWith("/wd") && p.contains("wbgetentities") -> MockResponse().setBody("""{"entities":{
                        "Q2":{"labels":{"en":{"value":"ChatGPT"}},"claims":{}},
                        "Q1":{"labels":{"en":{"value":"ChatGPT"}},"claims":{
                          "P856":[{"mainsnak":{"datavalue":{"value":"https://chatgpt.com/"}}}],
                          "P154":[{"mainsnak":{"datavalue":{"value":"ChatGPT logo.svg"}}}]}},
                        "Q9":{"labels":{"en":{"value":"Copilot"}},"claims":{
                          "P856":[{"mainsnak":{"datavalue":{"value":"https://github.com/features/copilot"}}}],
                          "P154":[{"mainsnak":{"datavalue":{"value":"GitHub Copilot logo.svg"}}}]}}}}""")
                    p.startsWith("/file/") -> MockResponse().setBody(png(512, 512))
                    p.startsWith("/fav") -> MockResponse().setBody(png(16, 16))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun finder() = MediaFinder(
        Files.createTempDirectory("logo").toFile(),
        endpoints = MediaFinder.Endpoints(
            simpleIcons = server.url("/si").toString().trimEnd('/'), favicon = server.url("/fav").toString(),
            wikipedia = server.url("/wiki/").toString(), openverse = server.url("/ov/").toString(),
            commons = server.url("/commons").toString(), wikidata = server.url("/wd").toString(),
            commonsFile = server.url("/file/").toString(),
        ),
    )

    @Test fun geminiOnGoogleIsGoogleGeminiNotTheCryptoExchange() {
        val logo = finder().logo("Gemini", "gemini.google.com")
        assertTrue(logo is Logo.Vector)
        assertEquals("Google Gemini", logo!!.title)
    }

    @Test fun neverFallsBackToTheFirstWordOrADomainPrefix() {
        // "Google Antigravity" is not Google; "ai.google.dev" is not the "AI" icon.
        assertNull(finder().logo("Google Antigravity", "antigravity.example"))
        assertNull(finder().logo("Studio", "ai.google.dev"))
    }

    @Test fun wikidataLogoIsUsedOnlyWhenItsOfficialSiteMatches() {
        val chat = finder().logo("ChatGPT", "chatgpt.com")
        assertTrue(chat is Logo.Raster)
        assertEquals("ChatGPT", chat!!.title)
        // Copilot's entry points to github.com, not microsoft.com: rejected, and no guessed favicon either.
        assertNull(finder().logo("Copilot", "copilot.microsoft.com"))
        assertTrue(hits.none { it.startsWith("/fav") && it.contains("copilot.com") })
    }

    @Test fun exactNameWithItsOwnSite() {
        assertEquals("OpenAI", finder().logo("OpenAI", "openai.com")!!.title)
        assertEquals("OpenAI", finder().logo("OpenAI", "")!!.title)
    }

    @Test fun domainHelpersAndPhotoRelevance() {
        assertEquals("google.com", MediaFinder.registrable("gemini.google.com"))
        assertEquals("bbc.co.uk", MediaFinder.registrable("news.bbc.co.uk"))
        assertEquals("gemini.google.com", MediaFinder.cleanHost("https://www.Gemini.Google.com/app?x=1"))
        assertTrue(MediaFinder.brandMatchesDomain("OpenAI", "openai.com"))
        assertTrue(!MediaFinder.brandMatchesDomain("Copilot", "microsoft.com"))
        assertEquals(2, MediaFinder.relevance("robot arm factory", "Industrial robot arms at work"))
        assertEquals(2, MediaFinder.minRelevance("robot arm factory"))
        assertEquals(0, MediaFinder.relevance("robot arm", "sunset over the sea"))
    }
}
