package com.videostudio.app.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiError(message: String) : Exception(message)

data class GeminiReply(val text: String, val sources: List<Pair<String, String>>)

class Gemini(private val apiKey: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private class ModelMissing(msg: String) : Exception(msg)

    fun generate(prompt: String, model: String, json: Boolean = false, search: Boolean = false): GeminiReply {
        if (apiKey.isBlank()) throw GeminiError("Add your Gemini API key in Settings (free at aistudio.google.com/apikey).")
        return try {
            call(prompt, model, json, search)
        } catch (e: ModelMissing) {
            if (model != FALLBACK_MODEL) call(prompt, FALLBACK_MODEL, json, search)
            else throw GeminiError(e.message ?: "model not found")
        }
    }

    private fun call(prompt: String, model: String, json: Boolean, search: Boolean): GeminiReply {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        )
        val config = JSONObject().put("temperature", 0.8)
        if (json) config.put("responseMimeType", "application/json")
        body.put("generationConfig", config)
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val waits = longArrayOf(0, 4000, 12000, 30000)
        var lastError = ""
        for (attempt in waits.indices) {
            if (waits[attempt] > 0) Thread.sleep(waits[attempt])
            val (code, text) = try {
                http.newCall(
                    Request.Builder()
                        .url(URL.format(model))
                        .header("x-goog-api-key", apiKey)
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: Exception) {
                lastError = "network: ${e.message}"
                continue
            }
            if (code == 404) throw ModelMissing("Gemini does not know the model \"$model\"")
            if (code == 400 || code == 401 || code == 403) throw GeminiError("Gemini refused the request ($code): ${reason(text)}")
            if (code != 200) {
                lastError = "Gemini answered $code: ${reason(text)}"
                continue
            }
            val o = JSONObject(text)
            val cand = o.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw GeminiError("Gemini returned no answer: " + (o.optJSONObject("promptFeedback")?.toString() ?: text.take(300)))
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (!part.optBoolean("thought")) sb.append(part.optString("text"))
            }
            val sources = ArrayList<Pair<String, String>>()
            cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
                for (i in 0 until chunks.length()) {
                    chunks.optJSONObject(i)?.optJSONObject("web")?.let { sources.add(it.optString("title") to it.optString("uri")) }
                }
            }
            if (sb.isNotBlank()) return GeminiReply(sb.toString(), sources)
            lastError = "Gemini returned an empty answer (${cand.optString("finishReason")})"
        }
        throw GeminiError(lastError.ifBlank { "Gemini did not answer" })
    }

    private fun reason(text: String): String =
        runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrElse { text.take(300) }

    companion object {
        const val FALLBACK_MODEL = "gemini-2.5-flash"
        const val URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    }
}

/** Reads the JSON object out of a model reply, tolerating code fences and stray text. */
fun extractJson(text: String): JSONObject {
    val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    runCatching { return JSONObject(t) }
    val a = t.indexOf('{')
    val b = t.lastIndexOf('}')
    if (a >= 0 && b > a) runCatching { return JSONObject(t.substring(a, b + 1)) }
    throw GeminiError("The script came back in a shape that could not be read")
}
