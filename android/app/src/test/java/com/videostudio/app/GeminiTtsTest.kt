package com.videostudio.app

import com.videostudio.app.voice.GeminiTts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiTtsTest {
    /** 1 s speech, 0.5 s pause, 1 s speech at 24 kHz. */
    private fun audio(): ShortArray {
        val rate = 24000
        return ShortArray((2.5 * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            if (t in 1.0..1.5) 0 else (8000 * kotlin.math.sin(2 * Math.PI * 220 * t)).toInt().toShort()
        }
    }

    @Test fun wordTimingsFollowTheSpeechAndSkipThePause() {
        val words = GeminiTts.estimateWords("Cats sleep a lot. Here is why.", audio(), 24000)
        assertEquals(7, words.size)
        assertTrue(words.zipWithNext().all { (a, b) -> b.start >= a.start })
        // The second sentence starts after the pause.
        assertTrue("starts at ${words[4].start}", words[4].start >= 1.45)
        assertTrue(words.last().end <= 2.51)
    }
}
