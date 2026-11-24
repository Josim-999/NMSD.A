package com.videostudio.app.upload

import android.app.Activity
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.videostudio.app.data.Settings
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class UploadError(message: String) : Exception(message)

data class UploadResult(val id: String, val url: String)

/**
 * Direct upload to YouTube with the user's own Google Cloud OAuth client ("Desktop app" type).
 * This is the advanced path; "Post everywhere" (SocialPost) needs no Google Cloud setup at all.
 */
class YouTube(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    /** Null when the client ID and secret look usable; otherwise what to fix. */
    fun configProblem(): String? {
        val id = settings.youtubeClientId
        val secret = settings.youtubeClientSecret
        return when {
            id.isBlank() || secret.isBlank() -> "Add your Google OAuth client ID and secret in Settings → YouTube."
            id.startsWith("AIza") -> "That is an API key. Uploads need an OAuth client ID ending in .apps.googleusercontent.com."
            !id.endsWith(".apps.googleusercontent.com") -> "The client ID should end with .apps.googleusercontent.com (OAuth client type: Desktop app)."
            !secret.startsWith("GOCSPX-") && secret.length < 20 -> "The client secret looks wrong. It usually starts with GOCSPX-."
            else -> null
        }
    }

    val signedIn: Boolean get() = settings.youtubeRefreshToken.isNotBlank()

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /**
     * Google sign-in with PKCE. Opens the consent page in a Custom Tab and catches the answer on a
     * one-off loopback port (the redirect Google allows for "Desktop app" clients). Blocking: call it
     * off the main thread.
     */
    fun signIn(activity: Activity) {
        configProblem()?.let { throw UploadError(it) }
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
        val state = b64(ByteArray(16).also { SecureRandom().nextBytes(it) })

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 300_000
            val redirect = "http://127.0.0.1:${server.localPort}"
            val url = Uri.parse(AUTH_URL).buildUpon()
                .appendQueryParameter("client_id", settings.youtubeClientId)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
            activity.runOnUiThread { CustomTabsIntent.Builder().build().launchUrl(activity, url) }

            var code: String? = null
            var error: String? = null
            while (code == null && error == null) {
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    throw UploadError("Sign-in timed out. Try again.")
                }
                socket.use { s ->
                    val line = s.getInputStream().bufferedReader().readLine() ?: return@use
                    val target = line.split(" ").getOrNull(1) ?: return@use
                    val params = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                    if (params.isEmpty()) {
                        // Browsers also ask for /favicon.ico.
                        s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        return@use
                    }
                    val ok = params["state"] == state && params["code"] != null
                    if (ok) code = params["code"] else error = params["error"] ?: "the sign-in answer did not match"
                    val page = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;background:#0e0f1a;color:#fff;text-align:center;padding:60px 24px}
a{display:inline-block;margin-top:28px;padding:16px 28px;border-radius:30px;background:#ffd23f;color:#000;text-decoration:none;font-weight:700}</style></head>
<body><h2>${if (ok) "Signed in to YouTube ✓" else "Sign-in failed"}</h2>
<p>${if (ok) "You can go back to Video Studio now." else "Go back to Video Studio and try again."}</p>
<a href="videostudio://signed-in">Back to Video Studio</a>
<script>setTimeout(function(){location.href='videostudio://signed-in'},600)</script></body></html>""".toByteArray()
                    s.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n".toByteArray() + page
                    )
                    s.getOutputStream().flush()
                }
            }
            error?.let { throw UploadError(explainSignInError(it)) }

            val body = FormBody.Builder()
                .add("code", code!!)
                .add("client_id", settings.youtubeClientId)
                .add("client_secret", settings.youtubeClientSecret)
                .add("redirect_uri", redirect)
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .build()
            http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.code != 200) throw UploadError("Google did not issue a token (${resp.code}): ${explainTokenError(text)}")
                val o = JSONObject(text)
                val refresh = o.optString("refresh_token")
                if (refresh.isBlank()) throw UploadError("Google gave no long-term sign-in. Remove Video Studio at myaccount.google.com/permissions and sign in again.")
                settings.youtubeRefreshToken = refresh
                settings.youtubeAccessToken = o.getString("access_token")
                settings.youtubeAccessExpires = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000
            }
        }
    }

    private fun accessToken(): String {
        if (!signedIn) throw UploadError("Sign in to YouTube first (Settings → YouTube → Sign in).")
        if (settings.youtubeAccessToken.isNotBlank() && System.currentTimeMillis() < settings.youtubeAccessExpires) {
            return settings.youtubeAccessToken
        }
        val body = FormBody.Builder()
            .add("client_id", settings.youtubeClientId)
            .add("client_secret", settings.youtubeClientSecret)
            .add("refresh_token", settings.youtubeRefreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val (code, text) = try {
            http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) {
            // A network blip must not throw the sign-in away.
            throw UploadError("Could not reach Google to refresh the sign-in: ${e.message}")
        }
        if (code != 200) {
            val err = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
            if (err == "invalid_grant" || err == "invalid_client" || err == "unauthorized_client") {
                settings.signOutYouTube()
                throw UploadError(
                    "The YouTube sign-in expired. Sign in again in Settings. " +
                        "Tip: while your Google Cloud consent screen is in \"Testing\", Google ends every sign-in after 7 days; " +
                        "set it to \"In production\" (Google Auth Platform → Audience → Publish app) so it lasts."
                )
            }
            throw UploadError("Google refused to refresh the sign-in ($code): ${explainTokenError(text)}")
        }
        val o = JSONObject(text)
        settings.youtubeAccessToken = o.getString("access_token")
        settings.youtubeAccessExpires = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000
        return settings.youtubeAccessToken
    }

    /** Resumable upload in 8 MB chunks, resuming after network errors. */
    fun upload(
        video: File,
        title: String,
        description: String,
        tags: List<String>,
        visibility: String,
        aiLabel: Boolean,
        progress: (Float) -> Unit,
    ): UploadResult {
        if (!video.exists()) throw UploadError("Render the video first.")
        val token = accessToken()
        val length = video.length()
        val meta = JSONObject()
            .put(
                "snippet", JSONObject()
                    .put("title", title.take(100))
                    .put("description", description.take(5000))
                    .put("tags", JSONArray(tags.take(30)))
                    .put("categoryId", "27")
            )
            .put(
                "status", JSONObject()
                    .put("privacyStatus", visibility)
                    .put("selfDeclaredMadeForKids", false)
                    .put("containsSyntheticMedia", aiLabel)
            )
        val session = http.newCall(
            Request.Builder()
                .url("$UPLOAD_URL?uploadType=resumable&part=snippet,status")
                .header("Authorization", "Bearer $token")
                .header("X-Upload-Content-Length", length.toString())
                .header("X-Upload-Content-Type", "video/mp4")
                .post(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        ).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val location = resp.header("Location")
            if (resp.code != 200 || location == null) throw UploadError("YouTube refused the upload (${resp.code}): ${explainApiError(text)}")
            location
        }

        var offset = 0L
        var failures = 0
        RandomAccessFile(video, "r").use { raf ->
            while (offset < length) {
                val size = minOf(CHUNK.toLong(), length - offset).toInt()
                val buf = ByteArray(size)
                raf.seek(offset)
                raf.readFully(buf)
                val result = runCatching {
                    http.newCall(
                        Request.Builder().url(session)
                            .header("Authorization", "Bearer $token")
                            .header("Content-Range", "bytes $offset-${offset + size - 1}/$length")
                            .put(buf.toRequestBody("video/mp4".toMediaType()))
                            .build()
                    ).execute().use { Triple(it.code, it.header("Range"), it.body?.string() ?: "") }
                }
                val (code, range, text) = result.getOrElse { Triple(-1, null, "") }
                when {
                    code == 200 || code == 201 -> {
                        progress(1f)
                        val id = JSONObject(text).getString("id")
                        return UploadResult(id, "https://youtube.com/shorts/$id")
                    }
                    code == 308 -> {
                        offset = range?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: (offset + size)
                        failures = 0
                        progress(offset.toFloat() / length)
                    }
                    code < 0 || code >= 500 || code == 408 || code == 429 -> {
                        if (++failures > 6) throw UploadError("YouTube upload kept failing: ${if (code < 0) "network error" else code.toString()}")
                        Thread.sleep(minOf(30_000L, 1000L shl failures))
                        offset = askOffset(session, token, length) ?: return finishedResult(session, token, length)
                    }
                    else -> throw UploadError("YouTube upload failed ($code): ${explainApiError(text)}")
                }
            }
        }
        return finishedResult(session, token, length)
    }

    /** After an error: how many bytes YouTube already has. Null means the upload is complete. */
    private fun askOffset(session: String, token: String, length: Long): Long? = runCatching {
        http.newCall(
            Request.Builder().url(session)
                .header("Authorization", "Bearer $token")
                .header("Content-Range", "bytes */$length")
                .put(ByteArray(0).toRequestBody(null))
                .build()
        ).execute().use { resp ->
            when (resp.code) {
                200, 201 -> null
                308 -> resp.header("Range")?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: 0L
                else -> 0L
            }
        }
    }.getOrDefault(0L)

    private fun finishedResult(session: String, token: String, length: Long): UploadResult {
        val text = http.newCall(
            Request.Builder().url(session)
                .header("Authorization", "Bearer $token")
                .header("Content-Range", "bytes */$length")
                .put(ByteArray(0).toRequestBody(null))
                .build()
        ).execute().use { it.body?.string() ?: "{}" }
        val id = runCatching { JSONObject(text).getString("id") }.getOrNull()
            ?: throw UploadError("YouTube upload ended without a video id")
        return UploadResult(id, "https://youtube.com/shorts/$id")
    }

    companion object {
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val UPLOAD_URL = "https://www.googleapis.com/upload/youtube/v3/videos"
        const val SCOPE = "https://www.googleapis.com/auth/youtube.upload"
        const val CHUNK = 8 * 1024 * 1024

        /** Turns Google's terse sign-in errors into steps the user can take. */
        fun explainSignInError(error: String): String = when (error) {
            "access_denied" -> "Google sign-in was refused. If you saw \"Access blocked\" or \"has not completed verification\": " +
                "in Google Cloud Console open Google Auth Platform → Audience and add your Gmail under Test users " +
                "(or press Publish app). Then sign in again."
            "redirect_uri_mismatch" -> "Google rejected the return address. Create the OAuth client as type \"Desktop app\", not \"Web\" or \"Android\"."
            "invalid_client" -> "Google does not recognise this client ID. Copy it again from Google Cloud Console → Clients."
            else -> "Google sign-in failed: $error"
        }

        fun explainTokenError(text: String): String {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return text.take(300)
            return when (o.optString("error")) {
                "invalid_client" -> "the client secret does not match the client ID. Copy both again from Google Cloud Console."
                "invalid_grant" -> "the sign-in code expired. Try again."
                else -> o.optString("error_description").ifBlank { o.optString("error") }.ifBlank { text.take(300) }
            }
        }

        fun explainApiError(text: String): String {
            val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull() ?: return text.take(300)
            val reason = err.optJSONArray("errors")?.optJSONObject(0)?.optString("reason") ?: ""
            val message = err.optString("message")
            return when (reason) {
                "youtubeSignupRequired" -> "this Google account has no YouTube channel yet. Open YouTube, create a channel, then try again."
                "quotaExceeded" -> "your Google Cloud project used up today's YouTube quota (about 6 uploads a day by default). Try again tomorrow."
                "uploadLimitExceeded" -> "YouTube's daily upload limit for this channel is reached. Try again tomorrow."
                "accessNotConfigured", "SERVICE_DISABLED" ->
                    "the YouTube Data API v3 is not enabled in your Google Cloud project. Enable it under APIs & Services → Library."
                "forbidden", "insufficientPermissions" -> "the sign-in lacks upload permission. Sign out and sign in again, and allow YouTube access."
                else -> if (message.contains("has not been used", true) || message.contains("is disabled", true))
                    "the YouTube Data API v3 is not enabled in your Google Cloud project. Enable it under APIs & Services → Library."
                else message.ifBlank { text.take(300) }
            }
        }
    }
}
