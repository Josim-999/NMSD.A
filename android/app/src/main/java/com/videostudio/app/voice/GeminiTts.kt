package com.videostudio.app.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Expressive narration from Gemini's text-to-speech (free quota on the user's Gemini key):
 * varied pace and emphasis instead of a flat read. Gemini returns raw audio without word timings,
 * so timings for the captions are estimated from where the speech and the pauses are.
 */
object GeminiTts {
    val VOICES = listOf("Puck", "Kore", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr")
    val MODELS = listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts")
    var base = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(3, TimeUnit.MINUTES).build()

    fun speak(apiKey: String, text: String, voiceName: String, out: File): VoiceResult {
        if (apiKey.isBlank()) throw VoiceError("no Gemini key")
        val prompt = "Read this aloud as an energetic, curious educator talking to one friend. Vary your pace, stress the key words, " +
            "and leave a short natural pause between sentences. Read the text exactly, word for word:\n\n$text"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)))))
        var last = ""
        for (model in MODELS) {
            val (code, resp) = runCatching {
                http.newCall(Request.Builder().url(base.format(model)).header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
                    .use { it.code to (it.body?.string() ?: "") }
            }.getOrElse { throw VoiceError("could not reach Gemini: ${it.message}") }
            if (code != 200) { last = "$model answered $code"; continue }
            val part = runCatching {
                JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
            }.getOrNull() ?: run { last = "$model returned no audio"; null } ?: continue
            val rate = Regex("rate=(\\d+)").find(part.optString("mimeType"))?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            val pcm = android.util.Base64.decode(part.getString("data"), android.util.Base64.DEFAULT)
            writeWav(out, pcm, rate)
            val samples = ShortArray(pcm.size / 2) { i -> ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort() }
            val seconds = samples.size.toDouble() / rate
            return VoiceResult(out, estimateWords(text, samples, rate), seconds, "Gemini · $voiceName")
        }
        throw VoiceError("Gemini voice unavailable ($last)")
    }

    private fun writeWav(f: File, pcm: ByteArray, rate: Int) {
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            r.write("RIFF".toByteArray()); r.write(le32(36 + pcm.size)); r.write("WAVE".toByteArray())
            r.write("fmt ".toByteArray()); r.write(le32(16)); r.write(le16(1)); r.write(le16(1)); r.write(le32(rate)); r.write(le32(rate * 2)); r.write(le16(2)); r.write(le16(16))
            r.write("data".toByteArray()); r.write(le32(pcm.size)); r.write(pcm)
        }
    }

    /**
     * Word timings from the audio: 10 ms frames are marked as speech or silence, the silences are
     * removed, and each word gets a share of the remaining speech time in proportion to its length
     * (punctuation adds a pause). Accurate to about a word, which is what captions need.
     */
    fun estimateWords(text: String, samples: ShortArray, rate: Int): List<Word> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || samples.isEmpty()) return emptyList()
        val frame = maxOf(1, rate / 100)
        val n = samples.size / frame
        val energy = DoubleArray(n) { i -> var s = 0.0; for (j in i * frame until (i + 1) * frame) s += abs(samples[j].toInt()).toDouble(); s / frame }
        val peak = energy.maxOrNull() ?: 0.0
        val speech = BooleanArray(n) { energy[it] > peak * 0.04 }
        val speechTimes = (0 until n).filter { speech[it] }.map { it * 0.01 }
        if (speechTimes.isEmpty()) return emptyList()
        val weights = tokens.map { t -> t.length.toDouble() + if (t.last() in ".!?") 6.0 else if (t.last() in ",;:—") 3.0 else 1.0 }
        val total = weights.sum()
        var acc = 0.0
        return tokens.mapIndexed { i, t ->
            val a = speechTimes[((acc / total) * (speechTimes.size - 1)).toInt().coerceIn(0, speechTimes.size - 1)]
            acc += weights[i]
            val b = speechTimes[((acc / total) * (speechTimes.size - 1)).toInt().coerceIn(0, speechTimes.size - 1)]
            Word(t.trim('"'), a, maxOf(a + 0.08, b - 0.02))
        }
    }
}
