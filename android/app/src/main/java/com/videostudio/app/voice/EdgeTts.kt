package com.videostudio.app.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Microsoft's free online neural voices (the Edge "read aloud" service), with word timings. */
object EdgeTts {
    private const val WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val CHROMIUM_FULL = "143.0.3650.75"
    private val CHROMIUM_MAJOR = CHROMIUM_FULL.substringBefore('.')

    /** Difference between the server clock and ours; the token is time-based. */
    @Volatile
    private var clockSkewSeconds = 0.0

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun uuid() = UUID.randomUUID().toString().replace("-", "")

    private fun muid(): String {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun jsDate(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    /** "en-US-AndrewNeural" -> "Microsoft Server Speech Text to Speech Voice (en-US, AndrewNeural)". */
    fun longVoiceName(voice: String): String {
        val m = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(voice) ?: return voice
        var region = m.groupValues[2]
        var name = m.groupValues[3]
        val dash = name.indexOf('-')
        if (dash != -1) {
            region = region + "-" + name.substring(0, dash)
            name = name.substring(dash + 1)
        }
        return "Microsoft Server Speech Text to Speech Voice (${m.groupValues[1]}-$region, $name)"
    }

    private fun clean(text: String) = buildString {
        for (c in text) append(if (c.code < 9 || c.code in 11..12 || c.code in 14..31) ' ' else c)
    }

    private fun escapeXml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescapeXml(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun speak(text: String, voice: String, rate: String, out: File): VoiceResult {
        var last = "no answer"
        for (wait in longArrayOf(0, 3000, 10000, 25000)) {
            if (wait > 0) Thread.sleep(wait)
            try {
                val r = once(text, voice, rate, out)
                if (out.length() >= 1000 && r.words.isNotEmpty()) return r
                last = "the voice service returned no audio"
            } catch (e: Exception) {
                last = e.message ?: e.toString()
            }
        }
        out.delete()
        throw VoiceError("Microsoft voice failed: $last")
    }

    private fun once(text: String, voice: String, rate: String, out: File): VoiceResult {
        val token = EdgeToken.generate(System.currentTimeMillis() / 1000.0 + clockSkewSeconds)
        val request = Request.Builder()
            .url("$WSS?TrustedClientToken=${EdgeToken.TRUSTED_CLIENT_TOKEN}&ConnectionId=${uuid()}&Sec-MS-GEC=$token&Sec-MS-GEC-Version=2-$CHROMIUM_FULL")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${muid()};")
            .build()

        val audio = ByteArrayOutputStream()
        val words = ArrayList<Word>()
        val done = CountDownLatch(1)
        var failure: String? = null

        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "X-Timestamp:${jsDate()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                )
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                    "<voice name='${longVoiceName(voice)}'><prosody pitch='+0Hz' rate='$rate' volume='+0%'>" +
                    escapeXml(clean(text)) + "</prosody></voice></speak>"
                webSocket.send(
                    "X-RequestId:${uuid()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${jsDate()}Z\r\nPath:ssml\r\n\r\n$ssml"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val split = text.indexOf("\r\n\r\n")
                val head = if (split >= 0) text.substring(0, split) else text
                val body = if (split >= 0) text.substring(split + 4) else ""
                val path = head.lineSequence().firstOrNull { it.startsWith("Path:") }?.substringAfter("Path:")?.trim()
                when (path) {
                    "audio.metadata" -> runCatching {
                        val meta = JSONObject(body).getJSONArray("Metadata")
                        for (i in 0 until meta.length()) {
                            val m = meta.getJSONObject(i)
                            if (m.optString("Type") == "WordBoundary") {
                                val d = m.getJSONObject("Data")
                                val start = d.getLong("Offset") / 1e7
                                val dur = d.getLong("Duration") / 1e7
                                words.add(Word(unescapeXml(d.getJSONObject("text").getString("Text")), start, start + dur))
                            }
                        }
                    }
                    "turn.end" -> {
                        webSocket.close(1000, null)
                        done.countDown()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val headLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                val start = headLen + 2
                if (start > b.size) return
                if (String(b, 2, headLen, Charsets.UTF_8).contains("Path:audio") && b.size > start) {
                    audio.write(b, start, b.size - start)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A 403 usually means our clock is off; learn the server's time for the retry.
                response?.header("Date")?.let { date ->
                    runCatching {
                        val server = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(date)!!.time / 1000.0
                        clockSkewSeconds += server - (System.currentTimeMillis() / 1000.0 + clockSkewSeconds)
                    }
                }
                failure = "${response?.code ?: ""} ${t.message ?: t}".trim()
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        })

        if (!done.await(3, TimeUnit.MINUTES)) {
            ws.cancel()
            throw VoiceError("the voice service timed out")
        }
        failure?.let { throw VoiceError(it) }
        out.parentFile?.mkdirs()
        out.writeBytes(audio.toByteArray())
        // 48 kbit/s mono MP3: bytes * 8 / 48000 = seconds.
        return VoiceResult(out, words, audio.size() * 8.0 / 48000.0, voice)
    }
}
