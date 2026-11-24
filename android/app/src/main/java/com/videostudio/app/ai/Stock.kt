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
}
