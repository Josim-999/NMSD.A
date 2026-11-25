package com.videostudio.app.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

open class GeminiError(message: String) : Exception(message)

/** Every model we tried has used up its quota (usually the free daily limit). */
class GeminiQuotaError(message: String) : GeminiError(message)

data class GeminiReply(val text: String, val sources: List<Pair<String, String>>, val model: String = "")

/**
 * Gemini client that keeps working when a model's free quota runs out (HTTP 429):
 * - a per-minute limit: waits the time Gemini asks for, then tries again;
 * - a daily limit (or a model with no free quota): moves to the next model in the list and
 *   remembers until the daily reset (midnight Pacific time) not to try that model again;
 * - an unknown or retired model name: moves on as well.
 * The fallback models cost the same or less than the usual ones.
 */
class Gemini(
    private val apiKey: String,
    private val base: String = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
    /** Told when the client changes model, so the job log can say so. */
    private val notice: (String) -> Unit = {},
    /** Longest wait for a per-minute limit before moving on, in milliseconds. */
    private val maxWaitMs: Long = 65_000,
    /** Try every model the key can use (Gemini Flash, then Gemma) when the usual ones run out. */
    private val autoSwitch: Boolean = true,
    private val listUrl: String = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private class ModelMissing(msg: String) : Exception(msg)
    private class OutOfQuota(msg: String) : Exception(msg)

    /** Tries [model], then [fallbacks], then the built-in list, skipping models known to be out of quota today. */
    fun generate(
        prompt: String,
        model: String,
        json: Boolean = false,
        search: Boolean = false,
        fallbacks: List<String> = emptyList(),
    ): GeminiReply {
        if (apiKey.isBlank()) throw GeminiError("Add your Gemini API key in Settings (free at aistudio.google.com/apikey).")
        val extra = if (autoSwitch) available() else FALLBACK_MODELS
        val chain = (listOf(model) + fallbacks + extra).map { it.trim().removePrefix("models/") }
            .filter { it.isNotEmpty() }.distinct()
            // Google Search grounding only works on Gemini models, not on Gemma.
            .filter { !search || supportsSearch(it) }
            .take(MAX_TRIES)
        val tried = ArrayList<String>()
        var lastProblem = ""
        for (m in chain) {
            if (exhaustedToday(m)) continue
            if (tried.isNotEmpty()) notice("Switching to $m (${lastProblem}).")
            tried.add(m)
            try {
                return call(prompt, m, json, search).copy(model = m)
            } catch (e: OutOfQuota) {
                markExhausted(m)
                lastProblem = "$m: free quota used up"
            } catch (e: ModelMissing) {
                lastProblem = "$m is not available"
            }
        }
        throw GeminiQuotaError(
            "Every free AI model on your key is used up for today (${tried.size} tried: ${tried.ifEmpty { chain }.joinToString()}). " +
                "Free quotas reset at midnight Pacific time."
        )
    }

    private fun call(prompt: String, model: String, json: Boolean, search: Boolean): GeminiReply {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        )
        val config = JSONObject().put("temperature", 0.8)
        // Gemma on the Gemini API has no JSON mode; the prompt already asks for JSON and extractJson reads it.
        if (json && !isGemma(model)) config.put("responseMimeType", "application/json")
        body.put("generationConfig", config)
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        var lastError = ""
        var networkTries = 0
        var rateTries = 0
        while (true) {
            val (code, text) = try {
                http.newCall(
                    Request.Builder()
                        .url(base.format(model))
                        .header("x-goog-api-key", apiKey)
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: Exception) {
                lastError = "network: ${e.message}"
                if (++networkTries > 3) throw GeminiError("Could not reach Gemini ($lastError). Check the internet connection.")
                Thread.sleep(longArrayOf(2000, 6000, 15000)[networkTries - 1])
                continue
            }
            when {
                code == 200 -> {
                    val reply = parse(text) ?: run {
                        lastError = "Gemini returned an empty answer"
                        if (++networkTries > 2) throw GeminiError(lastError)
                        null
                    }
                    if (reply != null) return reply
                }
                code == 404 -> throw ModelMissing("Gemini does not know the model \"$model\"")
                code == 429 -> {
                    val q = quotaInfo(text)
                    // A per-minute limit clears by itself: wait as long as Gemini says, a couple of times.
                    if (!q.daily && q.retryMs in 1..maxWaitMs && rateTries < 2) {
                        rateTries++
                        notice("Gemini asked to slow down; waiting ${q.retryMs / 1000} s.")
                        Thread.sleep(q.retryMs)
                        continue
                    }
                    throw OutOfQuota(reason(text))
                }
                code == 400 && (text.contains("not supported", true) || text.contains("not enabled", true) ||
                    text.contains("not found", true)) -> throw ModelMissing(reason(text))
                code == 400 || code == 401 || code == 403 -> throw GeminiError(explain(code, text))
                else -> {
                    // 500/503: Gemini is busy. Short waits, then the next model.
                    lastError = "Gemini answered $code: ${reason(text)}"
                    if (++networkTries > 3) throw ModelMissing(lastError)
                    Thread.sleep(longArrayOf(3000, 8000, 20000)[networkTries - 1])
                }
            }
        }
    }

    private fun parse(text: String): GeminiReply? {
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
        return if (sb.isNotBlank()) GeminiReply(sb.toString(), sources) else null
    }

    /** Every text model this key can call, best first; cached for six hours. Falls back to a built-in list. */
    fun available(): List<String> {
        cachedModels?.let { (at, list) -> if (System.currentTimeMillis() - at < 6 * 3600_000L) return list }
        val names = runCatching {
            http.newCall(Request.Builder().url(listUrl).header("x-goog-api-key", apiKey).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val arr = JSONObject(r.body?.string() ?: "").optJSONArray("models") ?: return@use null
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val methods = o.optJSONArray("supportedGenerationMethods")
                    val ok = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                    if (ok) o.optString("name").removePrefix("models/") else null
                }
            }
        }.getOrNull()
        val ranked = if (names.isNullOrEmpty()) FALLBACK_MODELS + GEMMA_FALLBACKS else rank(names)
        if (!names.isNullOrEmpty()) cachedModels = System.currentTimeMillis() to ranked
        return ranked
    }

    private fun reason(text: String): String =
        runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrElse { text.take(300) }

    private fun explain(code: Int, text: String): String {
        val r = reason(text)
        return when {
            r.contains("API key not valid", true) || r.contains("API_KEY_INVALID", true) ->
                "Gemini did not accept the API key. Copy it again from aistudio.google.com/apikey into Settings."
            code == 403 && r.contains("location", true) -> "Gemini is not available in your region with this key ($r)."
            else -> "Gemini refused the request ($code): $r"
        }
    }

    companion object {
        /**
         * Tried after the chosen model, cheapest-first among the widely available ones. Unknown names
         * are skipped automatically, so this list can stay as it is when Google renames models.
         */
        val FALLBACK_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash")

        /** Gemma models on the same key have their own, much larger free daily quota: the last safety net. */
        val GEMMA_FALLBACKS = listOf("gemma-4-31b-it", "gemma-4-26b-a4b-it", "gemma-3-27b-it", "gemma-3-12b-it")

        const val MAX_TRIES = 16

        @Volatile private var cachedModels: Pair<Long, List<String>>? = null

        fun isGemma(model: String) = model.startsWith("gemma")

        fun supportsSearch(model: String) = model.startsWith("gemini")

        private val SKIP = listOf(
            "embedding", "aqa", "imagen", "veo", "tts", "image", "live", "audio", "native", "robotics",
            "computer-use", "vision", "learnlm", "nano", "deep-research", "lyria", "gemma-3n", "-1b-it", "-4b-it", "-2b-it",
        )

        private fun version(name: String): Double =
            Regex("(?:gemini|gemma)-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        private fun size(name: String): Int = Regex("(\\d+)b").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        /**
         * Orders the models a key can use: Gemini Flash (newest first, "latest" aliases first), then
         * Flash-Lite, then Pro, then Gemma (largest first). Image, audio, embedding and tiny models are left out,
         * and so are dated duplicates like "-001".
         */
        fun rank(names: List<String>): List<String> {
            val usable = names.map { it.removePrefix("models/") }.distinct().filter { n ->
                (n.startsWith("gemini-") || n.startsWith("gemma-")) && SKIP.none { n.contains(it) } && !Regex("-\\d{3}$").containsMatchIn(n)
            }
            fun group(n: String): Int = when {
                n.startsWith("gemma") -> 3
                n.contains("flash-lite") -> 1
                n.contains("flash") -> 0
                else -> 2
            }
            return usable.sortedWith(
                compareBy<String> { group(it) }
                    .thenBy { if (it.contains("latest")) 0 else 1 }
                    .thenByDescending { version(it) }
                    .thenBy { if (it.contains("preview") || it.contains("exp")) 1 else 0 }
                    .thenByDescending { size(it) }
            )
        }

        /** For tests. */
        fun forgetModels() { cachedModels = null }

        data class Quota(val daily: Boolean, val retryMs: Long)

        /** Reads Gemini's 429 details: which quota ran out and how long to wait. */
        fun quotaInfo(text: String): Quota {
            val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull() ?: return Quota(daily = true, retryMs = 0)
            var daily = false
            var retry = 0L
            val details = err.optJSONArray("details") ?: JSONArray()
            for (i in 0 until details.length()) {
                val d = details.optJSONObject(i) ?: continue
                d.optJSONArray("violations")?.let { v ->
                    for (j in 0 until v.length()) {
                        val id = v.optJSONObject(j)?.optString("quotaId") ?: ""
                        val value = v.optJSONObject(j)?.optString("quotaValue") ?: ""
                        if (id.contains("PerDay", true) || value == "0") daily = true
                    }
                }
                d.optString("retryDelay").takeIf { it.isNotBlank() }?.let { s ->
                    retry = ((s.removeSuffix("s").toDoubleOrNull() ?: 0.0) * 1000).toLong() + 1000
                }
            }
            if (details.length() == 0 && err.optString("message").contains("quota", true)) daily = true
            return Quota(daily, retry)
        }

        private val exhausted = ConcurrentHashMap<String, Long>()

        /** Free quotas reset at midnight Pacific time. */
        fun nextReset(): Long =
            ZonedDateTime.now(ZoneId.of("America/Los_Angeles")).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()

        fun markExhausted(model: String) {
            exhausted[model] = nextReset()
        }

        fun exhaustedToday(model: String): Boolean {
            val until = exhausted[model] ?: return false
            if (System.currentTimeMillis() >= until) {
                exhausted.remove(model)
                return false
            }
            return true
        }

        /** For tests. */
        fun forgetQuotas() = exhausted.clear()
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
