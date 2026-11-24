package com.videostudio.app

import com.videostudio.app.render.AudioMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure-Kotlin checks of the soundtrack: loudness target, peak ceiling, music ducking. */
class AudioMixTest {
    /** 6 s of fake 24 kHz speech: 1 s bursts of a voiced tone with gaps, quiet like a raw recording. */
    private fun fakeVoice(rate: Int = 24_000): ShortArray {
        val n = rate * 6
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val talking = (t % 2.0) < 1.2
            val v = if (talking) 0.12 * (sin(2 * PI * 180 * t) + 0.5 * sin(2 * PI * 360 * t) + 0.3 * sin(2 * PI * 900 * t)) else 0.0
            (v * 32767 * 0.5).toInt().toShort()
        }
    }

    private fun rms(x: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += (x[i] / 32768.0) * (x[i] / 32768.0)
        return 20 * log10(sqrt(s / (to - from)) + 1e-9)
    }

    @Test
    fun finishedTrackHitsLoudnessTargetWithoutClipping() {
        val plan = AudioMix.Plan(6.5, listOf(0.0, 2.0, 4.0), music = true, sfx = true, seed = 42)
        val out = AudioMix.mix(fakeVoice(), 24_000, 1, plan)
        assertEquals((6.5 * AudioMix.RATE).toInt(), out.size)
        val lufs = AudioMix.loudness(FloatArray(out.size) { out[it] / 32768f })
        assertTrue("loudness $lufs LUFS", lufs in -15.5..-12.5)
        val peak = out.maxOf { abs(it.toInt()) } / 32768.0
        assertTrue("peak $peak", peak <= AudioMix.db(-1.0) + 1e-3)
    }

    @Test
    fun musicIsLoudInPausesAndDuckedUnderTheVoice() {
        val voiceOnly = AudioMix.mix(fakeVoice(), 24_000, 1, AudioMix.Plan(6.0, listOf(0.0), music = false, sfx = false, seed = 1))
        val withMusic = AudioMix.mix(fakeVoice(), 24_000, 1, AudioMix.Plan(6.0, listOf(0.0), music = true, sfx = false, seed = 1))
        val r = AudioMix.RATE
        // In a pause (1.5-1.9 s) only music plays; it must be clearly audible but well under speech.
        val pause = rms(withMusic, (1.5 * r).toInt(), (1.9 * r).toInt())
        val speech = rms(withMusic, (2.2 * r).toInt(), (3.0 * r).toInt())
        assertTrue("pause $pause dB", pause > -40)
        assertTrue("music should sit under the voice: speech $speech, pause $pause", speech - pause > 6)
        // Silence stays silent without music.
        assertTrue(rms(voiceOnly, (1.5 * r).toInt(), (1.9 * r).toInt()) < -60)
    }

    @Test
    fun limiterNeverPassesTheCeiling() {
        val x = FloatArray(48_000) { (sin(it * 0.05) * 3).toFloat() }
        AudioMix.limit(x, 0.89f)
        assertTrue(x.all { abs(it) <= 0.89f })
    }
}
