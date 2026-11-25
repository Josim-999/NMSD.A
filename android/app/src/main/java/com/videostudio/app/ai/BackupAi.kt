package com.videostudio.app.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A free AI service with the common "OpenAI-compatible" chat API. Used for scripts when Gemini's
 * free quota is used up. Each has its own free daily allowance, so together they add thousands of
 * scripts a day. Only text generation (no Google Search), the script prompt already asks for JSON.
 */
data class BackupProvider(
    val id: String,
    val name: String,
    /** Base URL ending before /chat/completions. */
    val base: String,
    val key: String,
    /** Models to try first, best first; the rest are found by asking the service for its list. */
    val preferred: List<String>,
    /** Keep only these from the service's model list (e.g. OpenRouter's free ones). */
    val listFilter: (JSONObject) -> Boolean = { true },
    /** Where the key is made (shown in Settings). */
    val keyPage: String = "",
    val needsKey: Boolean = true,
    /** How many of its models to try before moving on (each model has its own quota on some services). */
    val maxModels: Int = 3,
) {
    val usable: Boolean get() = !needsKey || key.isNotBlank()
}

class BackupAi(
    private val providers: List<BackupProvider>,
    private val notice: (String) -> Unit = {},
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** All the free keys from Settings. */
    data class Keys(
        val zai: String = "", val nvidia: String = "", val groq: String = "", val alibaba: String = "",
        val mistral: String = "", val openRouter: String = "", val siliconFlow: String = "",
        val cloudflareAccount: String = "", val cloudflareToken: String = "", val pollinations: String = "",
        val keyless: Boolean = true,
    )

    private class Busy(msg: String, val minutes: Long) : Exception(msg)

    /** Tries each provider in turn (up to 3 models each). Null when none could answer. */
    fun generate(prompt: String, valid: (String) -> Boolean = { true }): GeminiReply? {
        val problems = ArrayList<String>()
        for (p in providers.filter { it.usable }) {
            if (resting(p.id)) continue
            for (model in models(p).take(p.maxModels)) {
                if (resting(p.id + "/" + model)) continue
                try {
                    notice("Writing with ${p.name} ($model)…")
                    val text = chat(p, model, prompt)
                    if (!valid(text)) { problems.add("${p.name} $model: answer was not a usable script"); continue }
                    return GeminiReply(text, emptyList(), "${p.name} · $model")
                } catch (e: Busy) {
                    // A whole-account limit rests the provider; a model limit rests just that model.
                    rest(if (e.minutes >= 60) p.id else p.id + "/" + model, e.minutes)
                    problems.add("${p.name}: ${e.message}")
                    if (e.minutes >= 60) break
                } catch (e: Exception) {
                    problems.add("${p.name} $model: ${e.message}")
                }
            }
        }
        if (problems.isNotEmpty()) notice("Backup AI could not answer: ${problems.takeLast(3).joinToString("; ")}")
        return null
    }

    private fun chat(p: BackupProvider, model: String, prompt: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0.8)
            .put("max_tokens", 6000)
        val req = Request.Builder().url(p.base.trimEnd('/') + "/chat/completions")
            .header("User-Agent", "VideoStudioApp/2.0 (Android)")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (p.key.isNotBlank()) req.header("Authorization", "Bearer ${p.key}")
        if (p.id == "openrouter") req.header("X-Title", "Video Studio")
        val (code, text) = http.newCall(req.build()).execute().use { it.code to (it.body?.string() ?: "") }
        when {
            code == 200 -> {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: throw IllegalStateException("unreadable answer")
                val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val content = msg?.optString("content").orEmpty()
                if (content.isBlank()) throw IllegalStateException("empty answer")
                return content
            }
            code == 429 || code == 402 -> {
                val daily = text.contains("day", true) || text.contains("daily", true) || code == 402 ||
                    text.contains("credits", true) || text.contains("quota", true)
                throw Busy(if (daily) "daily free limit reached" else "busy", if (daily) 12 * 60 else 3)
            }
            code == 401 || code == 403 -> throw Busy("the key was not accepted", 24 * 60)
            code == 404 || code == 400 -> throw IllegalStateException("model not usable ($code)")
            else -> throw IllegalStateException("answered $code")
        }
    }

    /** The provider's preferred models that it really offers, then its other chat models. */
    private fun models(p: BackupProvider): List<String> {
        val listed = modelCache.getOrPut(p.id) {
            runCatching {
                val req = Request.Builder().url(p.base.trimEnd('/') + "/models").header("User-Agent", "VideoStudioApp/2.0 (Android)")
                if (p.key.isNotBlank()) req.header("Authorization", "Bearer ${p.key}")
                http.newCall(req.build()).execute().use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    val raw = r.body?.string() ?: ""
                    val arr = runCatching { JSONObject(raw).optJSONArray("data") }.getOrNull()
                        ?: runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull arr.optString(i).takeIf { it.isNotBlank() }
                        if (!p.listFilter(o)) null else o.optString("id").ifBlank { o.optString("name") }.takeIf { it.isNotBlank() }
                    }.filter { id -> SKIP.none { id.contains(it, true) } }
                }
            }.getOrDefault(emptyList())
        }
        if (listed.isEmpty()) return p.preferred
        val pref = p.preferred.filter { it in listed }
        return (pref + listed.filter { it !in pref }.sortedByDescending { score(it) }).distinct()
    }

    companion object {
        private val SKIP = listOf("whisper", "tts", "guard", "embed", "vision", "audio", "image", "moderation", "rerank", "ocr", "transcribe")
        private val modelCache = ConcurrentHashMap<String, List<String>>()
        private val restingUntil = ConcurrentHashMap<String, Long>()

        fun rest(key: String, minutes: Long) { restingUntil[key] = System.currentTimeMillis() + minutes * 60_000 }
        fun resting(key: String): Boolean {
            val until = restingUntil[key] ?: return false
            if (System.currentTimeMillis() >= until) { restingUntil.remove(key); return false }
            return true
        }

        /** Bigger models write better scripts. */
        fun score(id: String): Int {
            val size = Regex("(\\d+)b", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val known = listOf("gpt-oss-120b", "deepseek", "qwen3", "llama-4", "kimi", "llama-3.3-70b", "mistral-large", "mistral-medium", "gemma-3-27b")
            return (if (known.any { id.contains(it, true) }) 1000 else 0) + size
        }

        fun forget() { modelCache.clear(); restingUntil.clear() }


        /** Services that have big free room: tried before Gemini so Gemini's small quota is kept for research. */
        val BIG = setOf("zai", "nvidia")

        /** Every free service, in the order they are tried. */
        fun all(k: Keys) = listOf(
            BackupProvider(
                "zai", "Z.ai GLM", "https://api.z.ai/api/paas/v4", k.zai,
                listOf("glm-4.7-flash", "glm-4.5-flash"), listFilter = { false },
                keyPage = "https://z.ai/manage-apikey/apikey-list",
            ),
            BackupProvider(
                "nvidia", "NVIDIA", "https://integrate.api.nvidia.com/v1", k.nvidia,
                listOf("openai/gpt-oss-120b", "deepseek-ai/deepseek-v3.1", "qwen/qwen3-235b-a22b", "moonshotai/kimi-k2-instruct", "meta/llama-3.3-70b-instruct"),
                keyPage = "https://build.nvidia.com", maxModels = 4,
            ),
            BackupProvider(
                "groq", "Groq", "https://api.groq.com/openai/v1", k.groq,
                listOf("openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct", "qwen/qwen3-32b", "meta-llama/llama-4-maverick-17b-128e-instruct", "openai/gpt-oss-20b"),
                keyPage = "https://console.groq.com/keys", maxModels = 4,
            ),
            BackupProvider(
                "alibaba", "Alibaba Qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", k.alibaba,
                listOf("qwen-plus", "qwen-turbo", "qwen-flash", "qwen-max", "qwen3-235b-a22b", "qwen3-32b"), listFilter = { false },
                keyPage = "https://modelstudio.console.alibabacloud.com/", maxModels = 6,
            ),
            BackupProvider(
                "mistral", "Mistral", "https://api.mistral.ai/v1", k.mistral,
                listOf("mistral-medium-latest", "mistral-small-latest"), listFilter = { false },
                keyPage = "https://console.mistral.ai/api-keys",
            ),
            BackupProvider(
                "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", k.openRouter,
                listOf("openai/gpt-oss-120b:free", "deepseek/deepseek-chat-v3.1:free", "meta-llama/llama-3.3-70b-instruct:free", "qwen/qwen3-235b-a22b:free"),
                listFilter = { it.optString("id").endsWith(":free") },
                keyPage = "https://openrouter.ai/keys",
            ),
            BackupProvider(
                "siliconflow", "SiliconFlow", "https://api.siliconflow.com/v1", k.siliconFlow,
                listOf("Qwen/Qwen3-8B", "THUDM/GLM-4-9B-0414", "Qwen/Qwen2.5-7B-Instruct"), listFilter = { false },
                keyPage = "https://cloud.siliconflow.com/account/ak",
            ),
            BackupProvider(
                "cloudflare", "Cloudflare", "https://api.cloudflare.com/client/v4/accounts/${k.cloudflareAccount.trim()}/ai/v1",
                if (k.cloudflareAccount.isBlank()) "" else k.cloudflareToken,
                listOf("@cf/openai/gpt-oss-120b", "@cf/meta/llama-3.3-70b-instruct-fp8-fast", "@cf/qwen/qwen3-30b-a3b-fp8"), listFilter = { false },
                keyPage = "https://dash.cloudflare.com/profile/api-tokens",
            ),
            BackupProvider(
                "pollinations", "Pollinations", "https://gen.pollinations.ai/v1", k.pollinations,
                listOf("openai", "openai-fast"), listFilter = { false },
                keyPage = "https://enter.pollinations.ai", needsKey = !k.keyless,
            ),
        )

        /** Kept for older callers and tests. */
        fun providers(groq: String, openRouter: String, mistral: String, nvidia: String, keyless: Boolean) =
            all(Keys(groq = groq, openRouter = openRouter, mistral = mistral, nvidia = nvidia, keyless = keyless))
                .filter { it.id in setOf("groq", "nvidia", "mistral", "openrouter", "pollinations") }
                .sortedBy { listOf("groq", "nvidia", "mistral", "openrouter", "pollinations").indexOf(it.id) }

        /** "ready", "resting until …" or "no key", for the Settings panel. */
        fun status(p: BackupProvider): String {
            if (!p.usable) return "no key"
            val until = restingUntil[p.id] ?: return "ready"
            return if (System.currentTimeMillis() >= until) "ready" else "resting until " +
                java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()))
        }
    }
}
