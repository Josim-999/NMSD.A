package com.videostudio.app

import com.videostudio.app.ai.BackupAi
import com.videostudio.app.ai.BackupProvider
import com.videostudio.app.ai.Gemini
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupAiTest {
    private lateinit var server: MockWebServer
    private val calls = ArrayList<String>()
    private val auth = ArrayList<String>()

    private fun chat(text: String) = MockResponse().setBody(
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))).toString()
    )

    @Before fun setUp() {
        BackupAi.forget()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                synchronized(auth) { auth.add(request.getHeader("Authorization") ?: "") }
                if (path.endsWith("/models")) return when {
                    path.startsWith("/a/") -> MockResponse().setBody("""{"data":[{"id":"big-120b"},{"id":"whisper-large"}]}""")
                    path.startsWith("/c/") -> MockResponse().setBody("""{"data":[{"id":"paid-model"},{"id":"free-70b:free"},{"id":"other:free"}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
                val model = JSONObject(request.body.readUtf8()).getString("model")
                synchronized(calls) { calls.add(path.substringBefore("/chat") + " " + model) }
                return when {
                    path.startsWith("/a/") -> MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit reached for requests per day (RPD)"}}""")
                    path.startsWith("/b/") && model == "weak" -> chat("Sure! Here is a nice script without JSON.")
                    path.startsWith("/b/") -> chat("""{"scenes":[{"say":"a"},{"say":"b"},{"say":"c"}]}""")
                    path.startsWith("/c/") -> chat("""{"scenes":[{"say":"x"},{"say":"y"},{"say":"z"}]}""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun p(id: String, key: String = "k-$id", preferred: List<String> = emptyList(), filter: (JSONObject) -> Boolean = { true }, needsKey: Boolean = true) =
        BackupProvider(id, id.uppercase(), server.url("/$id/").toString().trimEnd('/'), key, preferred, filter, needsKey = needsKey)

    private val isScript = { t: String -> runCatching { JSONObject(t).getJSONArray("scenes").length() >= 3 }.getOrDefault(false) }

    @Test fun movesPastAUsedUpServiceAndAWeakAnswer() {
        val ai = BackupAi(listOf(p("a"), p("b", preferred = listOf("weak", "good"))))
        val r = ai.generate("prompt", isScript)!!
        assertEquals("B · good", r.model)
        assertEquals(listOf("/a big-120b", "/b weak", "/b good"), calls)
        assertTrue(auth.contains("Bearer k-a"))
        // "a" hit its daily limit: it is rested and not called again.
        calls.clear()
        ai.generate("prompt", isScript)
        assertTrue(calls.none { it.startsWith("/a") })
    }

    @Test fun skipsServicesWithoutAKeyAndKeepsOnlyFreeModels() {
        val ai = BackupAi(listOf(p("a", key = ""), p("c", filter = { it.optString("id").endsWith(":free") })))
        val r = ai.generate("prompt", isScript)!!
        assertTrue(r.model.startsWith("C · ") && r.model.endsWith(":free"))
        assertTrue(calls.none { it.startsWith("/a") || it.contains("paid-model") })
        // Bigger free model first.
        assertEquals("/c free-70b:free", calls.first())
    }

    @Test fun keylessServiceNeedsNoKeyAndNullWhenNobodyAnswers() {
        val ai = BackupAi(listOf(p("z", key = "", preferred = listOf("openai"), needsKey = false)))
        assertNull(ai.generate("prompt", isScript))
        assertTrue(calls.contains("/z openai"))
        assertTrue(auth.all { it.isEmpty() })
    }

    @Test fun providerListIsInTheRightOrder() {
        val list = BackupAi.providers("g", "o", "m", "n", keyless = true)
        assertEquals(listOf("groq", "nvidia", "mistral", "openrouter", "pollinations"), list.map { it.id })
        assertEquals(4, BackupAi.providers("g", "o", "m", "n", keyless = false).size)
        assertTrue(BackupAi.providers("", "", "", "", true).filter { it.usable }.map { it.id } == listOf("pollinations"))
    }

    @Test fun perMinuteLimitRestsAModelForMinutesNotTheDay() {
        Gemini.forgetQuotas()
        Gemini.markResting("gemini-x")
        val until = Gemini.skippedUntil("gemini-x") - System.currentTimeMillis()
        assertTrue(until in 60_000..5 * 60_000)
        Gemini.markExhausted("gemini-y")
        assertTrue(Gemini.skippedUntil("gemini-y") - System.currentTimeMillis() > 5 * 60_000)
    }
}
