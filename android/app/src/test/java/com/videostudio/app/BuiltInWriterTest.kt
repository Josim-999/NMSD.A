package com.videostudio.app

import com.videostudio.app.ai.BuiltInWriter
import com.videostudio.app.data.Project
import com.videostudio.app.data.Scene
import com.videostudio.app.data.objects
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuiltInWriterTest {
    private fun scenes(o: org.json.JSONObject) = objects(o.getJSONArray("scenes")).map { Scene.fromJson(it) }

    @Test fun writesAFullScriptFromWikipediaFacts() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                return when {
                    p.startsWith("/search") -> MockResponse().setBody("""{"pages":[{"key":"Lithium-ion_battery"},{"key":"Cold"}]}""")
                    p.startsWith("/sum/Lithium-ion_battery") -> MockResponse().setBody(
                        """{"type":"standard","title":"Lithium-ion battery","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Lithium-ion_battery"}},
                        "extract":"A lithium-ion battery (Li-ion) is a rechargeable battery that uses lithium ions. It was commercialised by Sony in 1991. Cold weather can cut its usable capacity by 40 percent. The ions move more slowly when the electrolyte is cold. Charging below freezing can damage the cell permanently."}"""
                    )
                    p.startsWith("/sum/Cold") -> MockResponse().setBody("""{"type":"disambiguation","extract":"Cold may refer to many things."}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val w = BuiltInWriter(server.url("/search").toString(), server.url("/sum/").toString(), server.url("/ddg/").toString())
        val p = Project(id = "x", topic = "Why phone batteries die in the cold", lengthSec = 45, take = "Keep it in an inner pocket")
        val facts = w.facts(p.topic)
        assertEquals(5, facts.size)
        assertTrue(facts.none { it.text.contains("(") })
        val o = w.write(p, "tech shorts", facts)
        val sc = scenes(o)
        assertEquals("hook", sc.first().type)
        assertEquals("outro", sc.last().type)
        assertTrue(sc.size >= 6)
        assertTrue(sc.any { it.type == "stat" && it.value.contains("40") })
        assertTrue(sc.any { it.say.contains("my take", ignoreCase = true) })
        assertTrue(o.getJSONArray("sources").length() == 1)
        assertTrue(o.getJSONObject("publish").getJSONArray("titles").length() == 3)
        server.shutdown()
    }

    @Test fun offlineStillWritesAStructuredScript() {
        val w = BuiltInWriter("http://127.0.0.1:9/none", "http://127.0.0.1:9/none/", "http://127.0.0.1:9/ddg/")
        val o = w.write(Project(id = "y", topic = "How to make your first YouTube Short", lengthSec = 30), "")
        val sc = scenes(o)
        assertTrue(sc.size >= 5)
        assertEquals("hook", sc.first().type)
        assertEquals("How to make your first YouTube Short?", sc.first().say)
        assertTrue(sc.all { it.say.isNotBlank() })
        val words = sc.sumOf { it.say.split(" ").size }
        assertTrue("about 30 s of speech, got $words words", words in 50..110)
    }

    @Test fun helpers() {
        assertEquals("youtube short", BuiltInWriter.searchTerms("How to make your first YouTube Short"))
        assertEquals("Paris is the capital", BuiltInWriter.speakable("Paris (French pronunciation: [paʁi]) is the capital"))
        assertEquals("It recovers quickly", BuiltInWriter.shortText("It recovers quickly, when warm", 5))
    }

    @Test fun liveWikipedia() {
        assumeTrue(System.getenv("LIVE_NET") == "1")
        val o = BuiltInWriter().write(Project(id = "z", topic = "Why do cats sleep so much", lengthSec = 45), "shorts")
        java.io.File("build/live_script.json").writeText(o.toString(2))
        assertTrue(scenes(o).size >= 6)
    }
}
