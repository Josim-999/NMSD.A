package com.videostudio.app.upload

import com.videostudio.app.data.Settings
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A social network the app can post to. [key] is the Upload-Post platform id. */
data class Platform(val key: String, val name: String)

/** One linked account, as Upload-Post reports it. */
data class SocialAccount(val platform: String, val name: String)

/** What happened on one network after "Post everywhere". */
data class PlatformResult(val platform: String, val ok: Boolean, val url: String, val error: String)

/**
 * One-tap posting to every network through Upload-Post (upload-post.com), which holds the
 * approved app registrations with TikTok, Meta, Google, X and LinkedIn. The user logs in to each
 * network once on Upload-Post's connect page; after that a single call posts everywhere.
 * No Google Cloud project, no developer accounts, no app reviews.
 */
class SocialPost(private val settings: Settings, private val base: String = BASE) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /** How long to wait between status checks while the networks publish. */
    var pollMillis = 8000L

    val configured: Boolean get() = settings.socialApiKey.isNotBlank()

    private fun request(path: String) = Request.Builder()
        .url(base + path)
        .header("Authorization", "Apikey ${settings.socialApiKey}")
        .header("User-Agent", "VideoStudio-Android/1.1")

    private fun call(req: Request): Pair<Int, String> = try {
        http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    } catch (e: Exception) {
        throw UploadError("Could not reach the posting service: ${e.message}")
    }

    private fun requireKey() {
        if (!configured) throw UploadError("Paste your Upload-Post API key in Settings → Connected accounts first.")
    }

    /** Creates the profile the accounts hang off. Already existing is fine. */
    fun ensureProfile() {
        requireKey()
        val (code, text) = call(
            request("/uploadposts/users")
                .post(JSONObject().put("username", settings.socialProfile).toString().toRequestBody(json))
                .build()
        )
        when {
            code in 200..299 || code == 409 -> Unit
            code == 401 -> throw UploadError("The posting service did not accept the API key. Copy it again from upload-post.com → API Keys.")
            code == 403 && text.contains("PROFILE_LIMIT", true) ->
                // The plan's profile is taken: reuse the first existing profile instead.
                listProfiles().firstOrNull()?.let { settings.socialProfile = it.optString("username") }
                    ?: throw UploadError("Your Upload-Post plan has no free profile. Delete one at upload-post.com.")
            else -> throw UploadError("Could not set up the profile ($code): ${reason(text)}")
        }
    }

    /**
     * A link to Upload-Post's page where the user taps each network and logs in normally.
     * When they are done, the page sends them back to the app (videostudio://connected).
     */
    fun connectLink(): String {
        ensureProfile()
        val body = JSONObject()
            .put("username", settings.socialProfile)
            .put("redirect_url", "videostudio://connected")
            .put("redirect_button_text", "Back to Video Studio")
            .put("connect_title", "Connect your accounts")
            .put("connect_description", "Log in to each network once. Video Studio will then post to all of them with one tap.")
            .put("platforms", JSONArray(SUPPORTED.map { it.key }))
        val (code, text) = call(request("/uploadposts/users/generate-jwt").post(body.toString().toRequestBody(json)).build())
        if (code !in 200..299) throw UploadError("Could not open the connect page ($code): ${reason(text)}")
        val o = JSONObject(text)
        return o.optString("access_url").ifBlank { o.optString("connection_url") }.ifBlank { o.optString("url") }
            .ifBlank { throw UploadError("The posting service gave no connect link.") }
    }

    private fun listProfiles(): List<JSONObject> {
        val (code, text) = call(request("/uploadposts/users").get().build())
        if (code == 401) throw UploadError("The posting service did not accept the API key. Copy it again from upload-post.com → API Keys.")
        if (code !in 200..299) throw UploadError("Could not read your accounts ($code): ${reason(text)}")
        val o = JSONObject(text)
        val arr = o.optJSONArray("profiles") ?: o.optJSONArray("users") ?: o.optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** Reads which networks are linked, and remembers them in Settings. */
    fun refreshAccounts(): List<SocialAccount> {
        requireKey()
        val profile = listProfiles().firstOrNull { it.optString("username") == settings.socialProfile }
            ?: run {
                ensureProfile()
                return emptyList<SocialAccount>().also { remember(it) }
            }
        val accounts = parseAccounts(profile.optJSONObject("social_accounts"))
        remember(accounts)
        return accounts
    }

    private fun remember(accounts: List<SocialAccount>) {
        settings.socialConnected = accounts.map { it.platform }
        settings.socialAccountNames = accounts.associate { it.platform to it.name }
    }

    /**
     * Posts [video] to [platforms] in one request, then follows the job until every network has
     * answered. Visibility "private"/"unlisted"/"public" is mapped to each network's own setting.
     */
    fun post(
        video: File,
        ytTitle: String,
        caption: String,
        ytDescription: String,
        hashtags: List<String>,
        platforms: List<String>,
        visibility: String,
        aiLabel: Boolean,
        progress: (String, Float?) -> Unit,
        cancelled: () -> Boolean,
    ): List<PlatformResult> {
        requireKey()
        if (!video.exists()) throw UploadError("Render the video first.")
        if (platforms.isEmpty()) throw UploadError("Pick at least one connected account to post to.")

        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("user", settings.socialProfile)
            // "title" is the caption on TikTok, Instagram, Facebook, X, Threads, LinkedIn and Bluesky.
            .addFormDataPart("title", caption.take(2200))
            .addFormDataPart("description", caption.take(5000))
            .addFormDataPart("async_upload", "true")
        platforms.forEach { form.addFormDataPart("platform[]", it) }

        if ("youtube" in platforms) {
            form.addFormDataPart("youtube_title", ytTitle.take(100))
            form.addFormDataPart("youtube_description", ytDescription.take(5000))
            form.addFormDataPart("privacyStatus", visibility)
            form.addFormDataPart("categoryId", "27")
            form.addFormDataPart("selfDeclaredMadeForKids", "false")
            form.addFormDataPart("containsSyntheticMedia", aiLabel.toString())
            hashtags.take(30).forEach { form.addFormDataPart("tags[]", it) }
        }
        if ("tiktok" in platforms) {
            form.addFormDataPart(
                "privacy_level",
                when (visibility) {
                    "public" -> "PUBLIC_TO_EVERYONE"
                    "unlisted" -> "MUTUAL_FOLLOW_FRIENDS"
                    else -> "SELF_ONLY"
                }
            )
            form.addFormDataPart("is_aigc", aiLabel.toString())
        }
        if ("instagram" in platforms) form.addFormDataPart("media_type", "REELS")
        if ("facebook" in platforms) {
            form.addFormDataPart("facebook_media_type", "REELS")
            if (aiLabel) form.addFormDataPart("facebook_is_ai_generated", "true")
        }
        if ("x" in platforms) form.addFormDataPart("x_title", caption.take(280))
        if ("bluesky" in platforms) form.addFormDataPart("bluesky_title", caption.take(300))
        if ("threads" in platforms) form.addFormDataPart("threads_title", caption.take(500))

        val total = video.length()
        form.addFormDataPart("video", video.name, ProgressBody(video, "video/mp4".toMediaType()) { sent ->
            progress("Sending the video", sent.toFloat() / total)
        })

        progress("Sending the video", 0f)
        val (code, text) = call(request("/upload").post(form.build()).build())
        if (code == 401) throw UploadError("The posting service did not accept the API key. Copy it again from upload-post.com → API Keys.")
        if (code == 402 || code == 429) throw UploadError("Your Upload-Post plan's monthly uploads are used up ($code). ${reason(text)}")
        if (code !in 200..299) throw UploadError("The posting service refused the video ($code): ${reason(text)}")

        val first = JSONObject(text)
        var results = parseResults(first, platforms)
        val requestId = first.optString("request_id").ifBlank { first.optJSONObject("data")?.optString("request_id") ?: "" }
        if (requestId.isBlank() || done(first, results, platforms)) return finish(results, platforms)

        // Async: each network takes from seconds to several minutes to process the video.
        val deadline = System.currentTimeMillis() + 25 * 60_000L
        var last = first
        while (System.currentTimeMillis() < deadline) {
            if (cancelled()) break
            val doneCount = results.size
            progress("Publishing ($doneCount of ${platforms.size} done)", doneCount.toFloat() / platforms.size)
            Thread.sleep(pollMillis)
            val url = (base + "/uploadposts/status").toHttpUrl().newBuilder().addQueryParameter("request_id", requestId).build()
            val answer = runCatching { call(request("").url(url).get().build()) }.getOrNull() ?: continue
            if (answer.first !in 200..299) continue
            last = runCatching { JSONObject(answer.second) }.getOrNull() ?: continue
            results = parseResults(last, platforms)
            if (done(last, results, platforms)) break
        }
        return finish(results, platforms, timedOut = !done(last, results, platforms))
    }

    private fun finish(found: List<PlatformResult>, asked: List<String>, timedOut: Boolean = false): List<PlatformResult> =
        asked.map { p ->
            found.firstOrNull { it.platform == p }
                ?: PlatformResult(p, ok = false, url = "", error = if (timedOut) "still processing — check the app later" else "no answer from this network")
        }

    private fun done(o: JSONObject, results: List<PlatformResult>, asked: List<String>): Boolean {
        val status = (o.optString("status").ifBlank { o.optJSONObject("data")?.optString("status") ?: "" }).lowercase()
        if (status in setOf("completed", "complete", "finished", "done", "success", "failed", "error", "partial", "partially_completed")) return true
        return asked.all { p -> results.any { it.platform == p } }
    }

    private fun reason(text: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return text.take(300)
        return o.optString("message").ifBlank { o.optString("error") }.ifBlank { o.optString("detail") }.ifBlank { text.take(300) }
    }

    /** Streams the file and reports how many bytes went out. */
    private class ProgressBody(private val file: File, private val type: MediaType, private val onSent: (Long) -> Unit) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            file.source().use { src ->
                var sent = 0L
                var lastReport = 0L
                while (true) {
                    val n = src.read(sink.buffer, 64 * 1024)
                    if (n < 0) break
                    sink.flush()
                    sent += n
                    if (sent - lastReport > 256 * 1024 || sent == file.length()) {
                        lastReport = sent
                        onSent(sent)
                    }
                }
            }
        }
    }

    companion object {
        const val BASE = "https://api.upload-post.com/api"
        const val SIGNUP_URL = "https://app.upload-post.com/"

        val SUPPORTED = listOf(
            Platform("tiktok", "TikTok"),
            Platform("instagram", "Instagram"),
            Platform("youtube", "YouTube"),
            Platform("facebook", "Facebook"),
            Platform("x", "X (Twitter)"),
            Platform("linkedin", "LinkedIn"),
            Platform("threads", "Threads"),
            Platform("bluesky", "Bluesky"),
        )

        fun name(key: String) = SUPPORTED.firstOrNull { it.key == key }?.name ?: key.replaceFirstChar { it.uppercase() }

        /** social_accounts maps platform -> account object (or a name string); empty means not linked. */
        fun parseAccounts(o: JSONObject?): List<SocialAccount> {
            if (o == null) return emptyList()
            val out = ArrayList<SocialAccount>()
            for (key in o.keys()) {
                val platform = key.lowercase().let { if (it == "twitter") "x" else it }
                when (val v = o.opt(key)) {
                    is JSONObject -> if (v.length() > 0) {
                        val name = listOf("display_name", "username", "handle", "name", "social_username")
                            .map { v.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" } ?: name(platform)
                        out.add(SocialAccount(platform, name))
                    }
                    is JSONArray -> if (v.length() > 0) out.add(SocialAccount(platform, name(platform)))
                    is String -> if (v.isNotBlank() && v != "null") out.add(SocialAccount(platform, v))
                    is Boolean -> if (v) out.add(SocialAccount(platform, name(platform)))
                }
            }
            return out.sortedBy { a -> SUPPORTED.indexOfFirst { it.key == a.platform }.let { if (it < 0) 99 else it } }
        }

        /**
         * Pulls per-network outcomes out of an upload or status answer. The service has used a few
         * shapes over time ({"results": {"tiktok": {...}}}, a list with "platform" fields, nested
         * under "data"), so this walks the whole answer instead of trusting one layout.
         */
        fun parseResults(o: JSONObject, asked: List<String>): List<PlatformResult> {
            val found = LinkedHashMap<String, PlatformResult>()
            fun fromEntry(platform: String, e: JSONObject) {
                val p = platform.lowercase().let { if (it == "twitter") "x" else it }
                if (p !in asked || found.containsKey(p)) return
                val url = listOf("url", "post_url", "postUrl", "permalink", "link", "video_url", "share_url")
                    .map { e.optString(it) }.firstOrNull { it.startsWith("http") } ?: ""
                val error = listOf("error", "error_message", "message", "reason")
                    .map { e.opt(it) }.firstOrNull { it is String && it.isNotBlank() } as String? ?: ""
                val status = e.optString("status").lowercase()
                val hasSuccess = e.has("success")
                val ok = when {
                    hasSuccess -> e.optBoolean("success")
                    status in setOf("success", "completed", "published", "done") -> true
                    status in setOf("failed", "error") -> false
                    url.isNotBlank() -> true
                    else -> return // still pending on this network
                }
                found[p] = PlatformResult(p, ok, url, if (ok) "" else error.ifBlank { "failed" })
            }
            fun walk(node: Any?, depth: Int) {
                if (depth > 6) return
                when (node) {
                    is JSONObject -> {
                        val named = node.optString("platform")
                        if (named.isNotBlank()) fromEntry(named, node)
                        for (k in node.keys()) {
                            val v = node.opt(k)
                            if (v is JSONObject && k.lowercase().let { it in asked || it == "twitter" }) fromEntry(k, v)
                            walk(v, depth + 1)
                        }
                    }
                    is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
                }
            }
            walk(o, 0)
            return found.values.toList()
        }
    }
}
