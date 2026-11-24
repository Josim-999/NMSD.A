package com.videostudio.app

import com.videostudio.app.ai.Gemini
import com.videostudio.app.ai.GeminiQuotaError
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gemini quota errors (HTTP 429) must not stop the app: wait, switch model, or explain. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiQuotaTest {
    private lateinit var server: MockWebServer
    private val hits = ArrayList<String>()

    /** The 429 body Gemini sends when a model's free daily quota is used up. */
    private val dailyQuota = """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details.",
        "status":"RESOURCE_EXHAUSTED","details":[
        {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
          "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaDimensions":{"model":"gemini-3.8-flash"},"quotaValue":"20"}]},
        {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"38s"}]}}"""

    private val perMinute = """{"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED","details":[
        {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier","quotaValue":"10"}]},
        {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"0.2s"}]}}"""

    private fun ok(text: String) = """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}"""

    private fun serve(answers: Map<String, ArrayDeque<MockResponse>>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val model = request.path!!.substringAfter("/models/").substringBefore(":")
                synchronized(hits) { hits.add(model) }
                return answers[model]?.removeFirstOrNull() ?: MockResponse().setResponseCode(404).setBody("{}")
            }
        }
    }

    private fun gemini(notes: MutableList<String> = ArrayList()) =
        Gemini("key", server.url("/models/%s:generateContent").toString().replace("%25s", "%s"), notice = { notes.add(it) }, maxWaitMs = 5_000)

    @Before
    fun setUp() {
        Gemini.forgetQuotas()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun dailyQuotaSwitchesModelAndRemembersIt() {
        serve(
            mapOf(
                "gemini-3.8-flash" to ArrayDeque(listOf(MockResponse().setResponseCode(429).setBody(dailyQuota))),
                "gemini-3.1-flash-lite" to ArrayDeque(listOf(MockResponse().setBody(ok("first")), MockResponse().setBody(ok("second")))),
            )
        )
        val notes = ArrayList<String>()
        val g = gemini(notes)
        val r = g.generate("p", "gemini-3.8-flash", fallbacks = listOf("gemini-3.1-flash-lite"))
        assertEquals("first", r.text)
        assertEquals("gemini-3.1-flash-lite", r.model)
        assertTrue(notes.any { it.contains("Switching to gemini-3.1-flash-lite") })
        // Next call goes straight to the model that still has quota.
        hits.clear()
        assertEquals("second", g.generate("p", "gemini-3.8-flash", fallbacks = listOf("gemini-3.1-flash-lite")).text)
        assertEquals(listOf("gemini-3.1-flash-lite"), hits)
    }

    @Test
    fun perMinuteLimitWaitsAndRetriesTheSameModel() {
        serve(
            mapOf(
                "gemini-3.1-flash-lite" to ArrayDeque(
                    listOf(MockResponse().setResponseCode(429).setBody(perMinute), MockResponse().setBody(ok("after wait")))
                ),
            )
        )
        val r = gemini().generate("p", "gemini-3.1-flash-lite")
        assertEquals("after wait", r.text)
        assertEquals("gemini-3.1-flash-lite", r.model)
    }

    @Test
    fun unknownModelIsSkipped() {
        serve(mapOf("gemini-2.5-flash" to ArrayDeque(listOf(MockResponse().setBody(ok("fallback"))))))
        val r = gemini().generate("p", "gemini-99-imaginary")
        assertEquals("gemini-2.5-flash", r.model)
    }

    @Test
    fun everythingExhaustedGivesAClearMessage() {
        val q = { ArrayDeque(listOf(MockResponse().setResponseCode(429).setBody(dailyQuota))) }
        serve(Gemini.FALLBACK_MODELS.associateWith { q() } + ("gemini-3.8-flash" to q()))
        try {
            gemini().generate("p", "gemini-3.8-flash")
            fail("expected a quota error")
        } catch (e: GeminiQuotaError) {
            assertTrue(e.message!!.contains("midnight Pacific"))
        }
    }

    @Test
    fun quotaDetailsAreRead() {
        val d = Gemini.quotaInfo(dailyQuota)
        assertTrue(d.daily)
        val m = Gemini.quotaInfo(perMinute)
        assertTrue(!m.daily && m.retryMs in 1000..2000)
    }
}
