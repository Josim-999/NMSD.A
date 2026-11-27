package com.videostudio.app

import com.videostudio.app.render.AudioMix
import org.junit.Assume.assumeTrue
import org.junit.Test

class AudioSpeedTest {
    @Test fun mixTime() {
        assumeTrue(System.getenv("PROFILE") == "1")
        val rate = 24000
        val voice = ShortArray(rate * 58) { ((Math.sin(it * 0.05) * 8000)).toInt().toShort() }
        val plan = AudioMix.Plan(durationSec = 59.0, sceneStarts = (0 until 12).map { it * 4.9 }, music = true, sfx = true, seed = 3, mood = "energetic",
            pops = listOf(1.0, 5.0, 9.0), hits = listOf(0.03, 3.0, 12.0), risers = listOf(55.0), clicks = listOf(20.0), papers = emptyList(), drops = listOf(55.0), pulses = listOf(0.6, 1.4))
        repeat(2) { AudioMix.mix(voice, rate, 1, plan) }
        val st = System.nanoTime()
        AudioMix.mix(voice, rate, 1, plan)
        println("AUDIOMIX ${(System.nanoTime() - st) / 1_000_000} ms")
    }
}
