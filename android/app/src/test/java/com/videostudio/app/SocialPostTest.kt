package com.videostudio.app

import androidx.test.core.app.ApplicationProvider
import com.videostudio.app.data.Settings
import com.videostudio.app.upload.SocialPost
import com.videostudio.app.upload.UploadError
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SocialPostTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: Settings
    private lateinit var social: SocialPost

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = Settings(ApplicationProvider.getApplicationContext())
        settings.socialApiKey = "test-key"
        social = SocialPost(settings, server.url("/api").toString().trimEnd('/'))
        social.pollMillis = 10
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    @Test
    fun connectLinkCreatesProfileThenAsksForTheLink() {
        server.enqueue(json(409, """{"success":false,"message":"Username already in use"}"""))
        server.enqueue(json(200, """{"success":true,"access_url":"https://connect.example/abc"}"""))

        assertEquals("https://connect.example/abc", social.connectLink())

        val create = server.takeRequest()
        assertEquals("/api/uploadposts/users", create.path)
        assertEquals("Apikey test-key", create.getHeader("Authorization"))
        assertEquals("videostudio", JSONObject(create.body.readUtf8()).getString("username"))

        val jwt = server.takeRequest()
        assertEquals("/api/uploadposts/users/generate-jwt", jwt.path)
        val body = JSONObject(jwt.body.readUtf8())
        assertEquals("videostudio", body.getString("username"))
        assertEquals("videostudio://connected", body.getString("redirect_url"))
        assertTrue(body.getJSONArray("platforms").toString().contains("tiktok"))
    }

    @Test
    fun connectLinkAcceptsConnectionUrlField() {
        server.enqueue(json(200, """{"success":true}"""))
        server.enqueue(json(200, """{"success":true,"jwt":"x","connection_url":"https://connect.example/def"}"""))
        assertEquals("https://connect.example/def", social.connectLink())
    }

    @Test
    fun badKeyGivesAPlainMessage() {
        server.enqueue(json(401, """{"message":"Invalid API key"}"""))
        try {
            social.connectLink()
            fail("expected an error")
        } catch (e: UploadError) {
            assertTrue(e.message!!.contains("API key"))
        }
    }

    @Test
    fun refreshAccountsReadsLinkedNetworksOnly() {
        server.enqueue(
            json(
                200, """{"success":true,"profiles":[
                {"username":"other","social_accounts":{"tiktok":{"display_name":"Not me"}}},
                {"username":"videostudio","social_accounts":{
                    "tiktok":{"display_name":"My TikTok","username":"me"},
                    "instagram":"",
                    "youtube":{"display_name":"My Channel"},
                    "twitter":{"username":"me_on_x"},
                    "facebook":null,
                    "linkedin":{}
                }}]}"""
            )
        )
        val accounts = social.refreshAccounts()
        assertEquals(listOf("tiktok", "youtube", "x"), accounts.map { it.platform })
        assertEquals("My TikTok", accounts[0].name)
        assertEquals("me_on_x", accounts[2].name)
        assertEquals(listOf("tiktok", "youtube", "x"), settings.socialConnected)
        assertEquals("My Channel", settings.socialAccountNames["youtube"])
    }

    @Test
    fun postSendsOneRequestForAllNetworksAndFollowsTheJob() {
        server.enqueue(json(200, """{"success":true,"request_id":"req-1","message":"processing"}"""))
        server.enqueue(json(200, """{"status":"in_progress","results":[{"platform":"tiktok","success":true,"url":"https://tiktok.com/@me/video/1"}]}"""))
        server.enqueue(
            json(
                200, """{"status":"completed","results":[
                {"platform":"tiktok","success":true,"url":"https://tiktok.com/@me/video/1"},
                {"platform":"youtube","success":true,"post_url":"https://youtube.com/shorts/abc"},
                {"platform":"instagram","success":false,"error":"Account needs reconnecting"}]}"""
            )
        )
        val video = File.createTempFile("video", ".mp4").apply { writeBytes(ByteArray(300_000) { 7 }) }
        val steps = ArrayList<String>()

        val results = social.post(
            video, ytTitle = "My title", caption = "Caption #ai", ytDescription = "Long description",
            hashtags = listOf("ai", "tech"), platforms = listOf("tiktok", "youtube", "instagram"),
            visibility = "public", aiLabel = true, progress = { s, _ -> steps.add(s) }, cancelled = { false },
        )

        assertEquals(listOf("tiktok", "youtube", "instagram"), results.map { it.platform })
        assertTrue(results[0].ok)
        assertEquals("https://youtube.com/shorts/abc", results[1].url)
        assertFalse(results[2].ok)
        assertEquals("Account needs reconnecting", results[2].error)
        assertTrue(steps.any { it.startsWith("Sending the video") })

        val upload: RecordedRequest = server.takeRequest()
        assertEquals("/api/upload", upload.path)
        assertEquals("Apikey test-key", upload.getHeader("Authorization"))
        val form = upload.body.readUtf8()
        // Each multipart part: headers, blank line, value. Read the value after the part's blank line.
        fun field(name: String): List<String> = form.split("Content-Disposition: form-data; name=\"$name\"").drop(1).map { part ->
            part.substringAfter("\r\n\r\n").substringBefore("\r\n")
        }
        assertEquals(listOf("videostudio"), field("user"))
        assertEquals(listOf("tiktok", "youtube", "instagram"), field("platform[]"))
        assertEquals(listOf("Caption #ai"), field("title"))
        assertEquals(listOf("My title"), field("youtube_title"))
        assertEquals(listOf("public"), field("privacyStatus"))
        assertEquals(listOf("PUBLIC_TO_EVERYONE"), field("privacy_level"))
        assertEquals(listOf("REELS"), field("media_type"))
        assertEquals(listOf("ai", "tech"), field("tags[]"))
        assertEquals(listOf("true"), field("async_upload"))
        assertTrue(form.contains("filename=\"${video.name}\""))

        assertEquals("/api/uploadposts/status?request_id=req-1", server.takeRequest().path)
    }

    @Test
    fun privateMapsToTikTokSelfOnly() {
        server.enqueue(json(200, """{"success":true,"results":{"tiktok":{"success":true,"url":"https://tiktok.com/x"}}}"""))
        val video = File.createTempFile("video", ".mp4").apply { writeBytes(ByteArray(20_000)) }
        val results = social.post(video, "t", "c", "d", emptyList(), listOf("tiktok"), "private", false, { _, _ -> }, { false })
        assertTrue(results.single().ok)
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("SELF_ONLY"))
        assertFalse(form.contains("privacyStatus"))
    }

    @Test
    fun parseResultsHandlesNestedShapes() {
        val asked = listOf("tiktok", "x", "facebook")
        val o = JSONObject(
            """{"data":{"results":{
                "tiktok":{"status":"published","share_url":"https://t/1"},
                "twitter":{"success":true,"url":"https://x.com/1"},
                "facebook":{"status":"processing"}}}}"""
        )
        val r = SocialPost.parseResults(o, asked)
        assertEquals(setOf("tiktok", "x"), r.map { it.platform }.toSet())
        assertTrue(r.all { it.ok })
    }

    @Test
    fun noPlatformsIsRefused() {
        val video = File.createTempFile("video", ".mp4").apply { writeBytes(ByteArray(10)) }
        try {
            social.post(video, "t", "c", "d", emptyList(), emptyList(), "public", false, { _, _ -> }, { false })
            fail("expected an error")
        } catch (e: UploadError) {
            assertTrue(e.message!!.contains("at least one"))
        }
    }
}
