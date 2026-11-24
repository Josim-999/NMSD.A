package com.videostudio.app.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fallback: the phone's own text-to-speech engine, written to a WAV file. */
object DeviceTts {
    fun speak(context: Context, text: String, locale: Locale, rate: Float, out: File): VoiceResult {
        val ready = CountDownLatch(1)
        var status = -1
        val tts = TextToSpeech(context.applicationContext) { s ->
            status = s
            ready.countDown()
        }
        try {
            if (!ready.await(20, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) {
                throw VoiceError("the phone's text-to-speech engine did not start")
            }
            val lang = tts.setLanguage(locale)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US)
            tts.setSpeechRate(rate)

            val ranges = ArrayList<Pair<Int, Long>>()
            var sampleRate = 22050
            val finished = CountDownLatch(1)
            var error: String? = null
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished.countDown()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    error = "synthesis failed"
                    finished.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    error = "synthesis failed ($errorCode)"
                    finished.countDown()
                }

                override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    sampleRate = sampleRateInHz
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    synchronized(ranges) { ranges.add(start to frame.toLong()) }
                }
            })

            out.parentFile?.mkdirs()
            if (tts.synthesizeToFile(text, Bundle(), out, "vo") != TextToSpeech.SUCCESS) {
                throw VoiceError("the phone's voice could not start")
            }
            if (!finished.await(5, TimeUnit.MINUTES)) throw VoiceError("the phone's voice timed out")
            error?.let { throw VoiceError(it) }

            val seconds = wavSeconds(out)
            val tokens = Regex("\\S+").findAll(text).toList()
            val words = ArrayList<Word>()
            if (ranges.size < tokens.size / 2) {
                // The engine gave no word timings: spread the words by length.
                val totalChars = tokens.sumOf { it.value.length + 2 }.toDouble()
                val span = (seconds - 0.3).coerceAtLeast(0.5)
                var t = 0.15
                for (m in tokens) {
                    val end = t + (m.value.length + 2) * span / totalChars
                    words.add(Word(m.value, t, end))
                    t = end
                }
            } else {
                val sorted = ranges.sortedBy { it.first }
                sorted.forEachIndexed { i, (start, frame) ->
                    val m = tokens.firstOrNull { it.range.first >= start } ?: return@forEachIndexed
                    val end = if (i + 1 < sorted.size) sorted[i + 1].second.toDouble() / sampleRate else seconds
                    words.add(Word(m.value, frame.toDouble() / sampleRate, end))
                }
            }
            return VoiceResult(out, words, seconds, "device")
        } finally {
            tts.shutdown()
        }
    }

    fun wavSeconds(f: File): Double = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(44)
            raf.readFully(h)
            val rate = le32(h, 24)
            val byteRate = le32(h, 28)
            val data = f.length() - 44
            if (byteRate > 0) data.toDouble() / byteRate else data / (rate * 2.0)
        }
    }.getOrDefault(0.0)

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
