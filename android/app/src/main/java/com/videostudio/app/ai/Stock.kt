package com.videostudio.app.ai

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object Stock {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Downloads the best portrait photo for [query] into [out]. Returns the credit line, or null. */
    fun pexelsPhoto(key: String, query: String, out: File): String? {
        if (key.isBlank() || query.isBlank()) return null
        val url = "https://api.pexels.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query.take(80))
            .addQueryParameter("orientation", "portrait")
            .addQueryParameter("per_page", "5")
            .build()
        val json = http.newCall(Request.Builder().url(url).header("Authorization", key).build()).execute().use {
            if (!it.isSuccessful) return null
            JSONObject(it.body?.string() ?: return null)
        }
        val photo = json.optJSONArray("photos")?.optJSONObject(0) ?: return null
        val src = photo.optJSONObject("src") ?: return null
        val image = src.optString("portrait").ifBlank { src.optString("large") }.ifBlank { return null }
        http.newCall(Request.Builder().url(image).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            out.parentFile?.mkdirs()
            out.outputStream().use { o -> resp.body!!.byteStream().copyTo(o) }
        }
        return "Photo by ${photo.optString("photographer")} on Pexels (${photo.optString("url")})"
    }

    /**
     * Downloads a portrait stock video clip for [query] into [out] (free Pexels video API, same key).
     * Picks the file closest to 1080 px wide without going over 1920. Returns the credit line, or null.
     */
    fun pexelsVideo(key: String, query: String, out: File, minSeconds: Double): String? {
        if (key.isBlank() || query.isBlank()) return null
        var json: JSONObject? = null
        for (base in listOf("https://api.pexels.com/videos/search", "https://api.pexels.com/v1/videos/search")) {
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("query", query.take(80))
                .addQueryParameter("orientation", "portrait")
                .addQueryParameter("size", "medium")
                .addQueryParameter("per_page", "8")
                .build()
            json = http.newCall(Request.Builder().url(url).header("Authorization", key).build()).execute().use {
                if (it.isSuccessful) JSONObject(it.body?.string() ?: "{}") else null
            }
            if (json != null) break
        }
        val videos = json?.optJSONArray("videos") ?: return null
        // Prefer clips long enough for the scene, then the best portrait file of each.
        val candidates = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
            .sortedByDescending { if (it.optDouble("duration", 0.0) >= minSeconds) 1 else 0 }
        for (v in candidates) {
            val files = v.optJSONArray("video_files") ?: continue
            val best = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
                .filter { it.optString("file_type") == "video/mp4" && it.optInt("height") > it.optInt("width") && it.optInt("width") in 540..1920 }
                .minByOrNull { kotlin.math.abs(it.optInt("width") - 1080) }
                ?: continue
            val link = best.optString("link")
            if (link.isBlank()) continue
            val ok = http.newCall(Request.Builder().url(link).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                out.parentFile?.mkdirs()
                out.outputStream().use { o -> resp.body!!.byteStream().copyTo(o) }
                true
            }
            if (ok && out.length() > 50_000) {
                return "Video by ${v.optJSONObject("user")?.optString("name") ?: "Pexels"} on Pexels (${v.optString("url")})"
            }
        }
        return null
    }
}
