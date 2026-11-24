package com.videostudio.app

import com.videostudio.app.render.AudioMix
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Writes build/audio/mix.wav (fake voice + music + whooshes) to listen to the soundtrack. */
class MixDumpTest {
    @Test
    fun writeSampleMix() {
        val rate = 24_000
        val voice = ShortArray(rate * 8) { i ->
            val t = i.toDouble() / rate
            val talking = (t % 2.0) < 1.3
            val f = 140 + 30 * sin(2 * PI * 3 * t)
            val v = if (talking) 0.2 * (sin(2 * PI * f * t) + 0.4 * sin(4 * PI * f * t)) else 0.0
            (v * 32767 * 0.4).toInt().toShort()
        }
        val out = AudioMix.mix(voice, rate, 1, AudioMix.Plan(8.5, listOf(0.0, 2.0, 4.0, 6.0), music = true, sfx = true, seed = 7))
        val bb = ByteBuffer.allocate(44 + out.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + out.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(AudioMix.RATE).putInt(AudioMix.RATE * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(out.size * 2)
        out.forEach { bb.putShort(it) }
        File("build/audio").apply { mkdirs() }.resolve("mix.wav").writeBytes(bb.array())
    }
}
