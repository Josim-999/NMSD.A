package com.videostudio.app.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Turns the raw voice into a finished soundtrack, all on the phone:
 * - voice: high-pass (removes rumble phone speakers can't play), gentle compression for an even level;
 * - music: a soft chord bed generated here (no files, no licences), ducked under the voice;
 * - whoosh on every scene change;
 * - loudness brought to about -14 LUFS with a -1 dBFS peak ceiling, the usual target for TikTok,
 *   Reels and Shorts.
 * Music sits about 16 dB under the voice while it speaks and about 8 dB under in the pauses.
 */
object AudioMix {
    const val RATE = 48_000

    class Plan(
        val durationSec: Double,
        /** Scene start times in seconds; a whoosh plays at each one after the first. */
        val sceneStarts: List<Double>,
        val music: Boolean,
        val sfx: Boolean,
        /** Picks the key and chord progression, so videos don't all sound the same. */
        val seed: Int,
        /** energetic, calm, dramatic or playful: tempo, harmony and instruments of the music. */
        val mood: String = "",
        /** Soft pops when items appear on screen. */
        val pops: List<Double> = emptyList(),
        /** Impact hits on emphasis words and big numbers. */
        val hits: List<Double> = emptyList(),
        /** Risers that build into a moment (the payoff scene). */
        val risers: List<Double> = emptyList(),
    )

    /** Mono 48 kHz 16-bit PCM of the finished soundtrack. */
    fun mix(voice: ShortArray, voiceRate: Int, voiceChannels: Int, plan: Plan): ShortArray {
        val n = (plan.durationSec * RATE).toInt().coerceAtLeast(1)
        val v = processVoice(resampleMono(voice, voiceRate, voiceChannels, n))
        val out = v.copyOf()

        if (plan.music) {
            // Levels relative to the voice's own loudness, so they hold for quiet and loud voices.
            val voiceLufs = loudness(v).takeIf { it > -69 } ?: -20.0
            val bed = music(n, plan.seed, plan.mood)
            val bedLufs = loudness(bed).takeIf { it > -69 } ?: -20.0
            val underSpeech = db(voiceLufs - 16 - bedLufs)
            val inPauses = db(voiceLufs - 8 - bedLufs)
            val env = envelope(v, attackMs = 15.0, releaseMs = 300.0)
            val talkLevel = (env.maxOrNull() ?: 0f) * 0.25f
            for (i in 0 until n) {
                val speaking = (env[i] / talkLevel.coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
                val gain = underSpeech * speaking + inPauses * (1 - speaking)
                out[i] += bed[i] * gain.toFloat()
            }
        }
        if (plan.sfx) {
            val rnd = Random(plan.seed)
            for (t in plan.sceneStarts.drop(1)) addWhoosh(out, t, rnd)
            for (t in plan.pops) addPop(out, t)
            for (t in plan.hits) addHit(out, t, rnd)
            for (t in plan.risers) addRiser(out, t, rnd)
        }
        normalizeLoudness(out, targetLufs = -14.0)
        limit(out, ceiling = db(-1.0).toFloat())
        return ShortArray(n) { (out[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    fun db(d: Double) = 10.0.pow(d / 20.0)

    // ---- Voice ----

    private fun resampleMono(src: ShortArray, rate: Int, channels: Int, n: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = src.size / ch
        val out = FloatArray(n)
        if (frames == 0) return out
        val step = rate.toDouble() / RATE
        for (i in 0 until n) {
            val pos = i * step
            val a = pos.toInt()
            if (a >= frames) break
            val b = min(a + 1, frames - 1)
            val f = (pos - a).toFloat()
            var sa = 0f
            var sb = 0f
            for (c in 0 until ch) {
                sa += src[a * ch + c]
                sb += src[b * ch + c]
            }
            out[i] = (sa + (sb - sa) * f) / ch / 32768f
        }
        return out
    }

    private fun processVoice(x: FloatArray): FloatArray {
        // High-pass at 80 Hz (biquad, Butterworth).
        highPass(x, 80.0)
        // Compressor: 3:1 above -24 dBFS RMS, 10 ms attack, 150 ms release.
        val env = envelope(x, attackMs = 10.0, releaseMs = 150.0)
        val threshold = db(-24.0).toFloat()
        for (i in x.indices) {
            val e = env[i]
            if (e > threshold) {
                val over = e / threshold
                val gain = over.toDouble().pow(1.0 / 3.0 - 1.0).toFloat()
                x[i] *= gain
            }
        }
        return x
    }

    private fun highPass(x: FloatArray, freq: Double) {
        val w = 2 * PI * freq / RATE
        val q = 0.7071
        val alpha = sin(w) / (2 * q)
        val cosw = kotlin.math.cos(w)
        val a0 = 1 + alpha
        val b0 = ((1 + cosw) / 2 / a0).toFloat()
        val b1 = (-(1 + cosw) / a0).toFloat()
        val b2 = b0
        val a1 = (-2 * cosw / a0).toFloat()
        val a2 = ((1 - alpha) / a0).toFloat()
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val x0 = x[i]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
            x[i] = y0
        }
    }

    /** Peak-ish level follower. */
    private fun envelope(x: FloatArray, attackMs: Double, releaseMs: Double): FloatArray {
        val att = exp(-1.0 / (RATE * attackMs / 1000)).toFloat()
        val rel = exp(-1.0 / (RATE * releaseMs / 1000)).toFloat()
        val out = FloatArray(x.size)
        var e = 0f
        for (i in x.indices) {
            val a = abs(x[i])
            e = if (a > e) att * e + (1 - att) * a else rel * e + (1 - rel) * a
            out[i] = e
        }
        return out
    }

    // ---- Loudness ----

    /** Approximates integrated loudness (K-weighting shelf + 400 ms gated blocks) and applies gain. */
    fun loudness(x: FloatArray): Double {
        // K-weighting approximated by a high-pass at 100 Hz and a +4 dB high shelf above ~1.7 kHz.
        val k = x.copyOf()
        highPass(k, 100.0)
        highShelf(k, 1700.0, 4.0)
        val block = (0.4 * RATE).toInt()
        val hop = block / 4
        val powers = ArrayList<Double>()
        var start = 0
        while (start + block <= k.size) {
            var sum = 0.0
            for (i in start until start + block) sum += k[i].toDouble() * k[i]
            powers.add(sum / block)
            start += hop
        }
        if (powers.isEmpty()) return -70.0
        fun lufs(p: Double) = -0.691 + 10 * log10(max(p, 1e-12))
        val abs = powers.filter { lufs(it) > -70 }
        if (abs.isEmpty()) return -70.0
        val relGate = lufs(abs.average()) - 10
        val gated = abs.filter { lufs(it) > relGate }
        return lufs(gated.average())
    }

    private fun highShelf(x: FloatArray, freq: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * freq / RATE
        val cosw = kotlin.math.cos(w)
        val alpha = sin(w) / 2 * sqrt(2.0)
        val b0 = a * ((a + 1) + (a - 1) * cosw + 2 * sqrt(a) * alpha)
        val b1 = -2 * a * ((a - 1) + (a + 1) * cosw)
        val b2 = a * ((a + 1) + (a - 1) * cosw - 2 * sqrt(a) * alpha)
        val a0 = (a + 1) - (a - 1) * cosw + 2 * sqrt(a) * alpha
        val a1 = 2 * ((a - 1) - (a + 1) * cosw)
        val a2 = (a + 1) - (a - 1) * cosw - 2 * sqrt(a) * alpha
        val c0 = (b0 / a0).toFloat(); val c1 = (b1 / a0).toFloat(); val c2 = (b2 / a0).toFloat()
        val d1 = (a1 / a0).toFloat(); val d2 = (a2 / a0).toFloat()
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val x0 = x[i]
            val y0 = c0 * x0 + c1 * x1 + c2 * x2 - d1 * y1 - d2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
            x[i] = y0
        }
    }

    private fun normalizeLoudness(x: FloatArray, targetLufs: Double) {
        val now = loudness(x)
        if (now <= -69) return
        val gain = db((targetLufs - now).coerceIn(-20.0, 24.0)).toFloat()
        for (i in x.indices) x[i] *= gain
    }

    /** Look-ahead peak limiter: 5 ms look-ahead, 80 ms release. Never lets a sample pass [ceiling]. */
    fun limit(x: FloatArray, ceiling: Float) {
        val look = (0.005 * RATE).toInt()
        val rel = exp(-1.0 / (RATE * 0.08)).toFloat()
        val need = FloatArray(x.size) { val a = abs(x[it]); if (a > ceiling) ceiling / a else 1f }
        // Minimum over the look-ahead window, so gain drops before the peak arrives.
        val minAhead = FloatArray(x.size)
        val dq = ArrayDeque<Int>()
        for (i in x.indices.reversed()) {
            while (dq.isNotEmpty() && dq.last() > i + look) dq.removeLast()
            while (dq.isNotEmpty() && need[dq.first()] >= need[i]) dq.removeFirst()
            dq.addFirst(i)
            minAhead[i] = need[dq.last()]
        }
        var g = 1f
        for (i in x.indices) {
            val target = minAhead[i]
            g = if (target < g) target else rel * g + (1 - rel) * target
            x[i] = (x[i] * g).coerceIn(-ceiling, ceiling)
        }
    }

    // ---- Music: a soft lo-fi chord bed ----

    private val progressions = listOf(
        // semitones from the key root: i - VI - III - VII (minor), I - V - vi - IV (major), vi - IV - I - V
        listOf(intArrayOf(0, 3, 7), intArrayOf(-4, 0, 3), intArrayOf(3, 7, 10), intArrayOf(-2, 2, 5)),
        listOf(intArrayOf(0, 4, 7), intArrayOf(7, 11, 14), intArrayOf(9, 12, 16), intArrayOf(5, 9, 12)),
        listOf(intArrayOf(-3, 0, 4), intArrayOf(-7, -3, 0), intArrayOf(0, 4, 7), intArrayOf(-5, -1, 2)),
    )

    fun music(n: Int, seed: Int, mood: String = ""): FloatArray {
        val rnd = Random(seed)
        // Mood sets harmony and tempo: minor and slow for dramatic, major and quick for playful/energetic.
        val prog = when (mood) {
            "dramatic" -> progressions[0]
            "playful", "energetic" -> progressions[1]
            "calm" -> progressions[2]
            else -> progressions[abs(seed) % progressions.size]
        }
        val rootMidi = 45 + rnd.nextInt(0, 7) // A2..E3
        val bpm = when (mood) {
            "energetic" -> 108 + rnd.nextInt(0, 13)
            "playful" -> 98 + rnd.nextInt(0, 11)
            "dramatic" -> 72 + rnd.nextInt(0, 11)
            "calm" -> 70 + rnd.nextInt(0, 13)
            else -> 84 + rnd.nextInt(0, 17)
        }
        val arps = mood == "energetic" || mood == "playful"
        val beat = 60.0 / bpm
        val bar = beat * 4
        val out = FloatArray(n)
        fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12)

        // Pads: three detuned soft voices per chord note, slow attack and release.
        val chordLen = (bar * RATE).toInt()
        var start = 0
        var ci = 0
        while (start < n) {
            val chord = prog[ci % prog.size]
            val len = min(chordLen, n - start)
            for (note in chord) {
                val f = hz(rootMidi + 12.0 + note)
                for (det in doubleArrayOf(-0.08, 0.0, 0.08)) {
                    val fr = f * 2.0.pow(det / 12)
                    val w = 2 * PI * fr / RATE
                    val ph = rnd.nextDouble() * 2 * PI
                    for (i in 0 until len) {
                        val t = i.toDouble() / chordLen
                        val amp = smooth(min(t / 0.25, (1 - t) / 0.2).coerceIn(0.0, 1.0))
                        // Sine plus a little second harmonic: warm, not buzzy.
                        val s = sin(w * i + ph) + 0.18 * sin(2 * w * i + ph)
                        out[start + i] += (s * amp * 0.045).toFloat()
                    }
                }
            }
            // Bass: chord root, on beats 1 and 3, plucked.
            val bassF = hz(rootMidi.toDouble() + chord[0] - if (chord[0] > 5) 12 else 0)
            for (b in intArrayOf(0, 2)) {
                val s0 = start + (b * beat * RATE).toInt()
                val dur = (beat * 1.6 * RATE).toInt()
                for (i in 0 until dur) {
                    val idx = s0 + i
                    if (idx >= n) break
                    val amp = exp(-i.toDouble() / (0.45 * RATE)) * min(1.0, i / (0.01 * RATE))
                    out[idx] += (sin(2 * PI * bassF * i / RATE) * amp * 0.16).toFloat()
                }
            }
            start += chordLen
            ci++
        }
        // Plucked arpeggio in eighth notes for energetic and playful moods.
        if (arps) {
            val eighth = beat / 2
            val steps = (n / (eighth * RATE)).toInt()
            for (k in 0 until steps) {
                val chord = prog[((k * eighth) / bar).toInt() % prog.size]
                val note = chord[k % chord.size] + 24 + (if (k % 8 >= 4) 12 else 0)
                val f = hz(rootMidi.toDouble() + note)
                val s0 = (k * eighth * RATE).toInt()
                val dur = (0.22 * RATE).toInt()
                for (i in 0 until dur) {
                    val idx = s0 + i
                    if (idx >= n) break
                    val env = exp(-i.toDouble() / (0.07 * RATE)) * min(1.0, i / (0.003 * RATE))
                    out[idx] += ((sin(2 * PI * f * i / RATE) + 0.3 * sin(4 * PI * f * i / RATE)) * env * 0.05).toFloat()
                }
            }
        }
        // Drums: soft kick on 1 and 3, brushed hat on the off-beats.
        val beats = (n / (beat * RATE)).toInt() + 1
        for (k in 0 until beats) {
            val s0 = (k * beat * RATE).toInt()
            if (k % 2 == 0) {
                val dur = (0.18 * RATE).toInt()
                var phase = 0.0
                for (i in 0 until dur) {
                    val idx = s0 + i
                    if (idx >= n) break
                    val f = 50 + 70 * exp(-i.toDouble() / (0.03 * RATE))
                    phase += 2 * PI * f / RATE
                    out[idx] += (sin(phase) * exp(-i.toDouble() / (0.06 * RATE)) * 0.22).toFloat()
                }
            }
            val h0 = s0 + (beat / 2 * RATE).toInt()
            val hd = (0.05 * RATE).toInt()
            var prev = 0f
            for (i in 0 until hd) {
                val idx = h0 + i
                if (idx >= n) break
                val noise = rnd.nextFloat() * 2 - 1
                val hp = noise - prev // crude high-pass: brighter hiss
                prev = noise
                out[idx] += (hp * exp(-i.toDouble() / (0.012 * RATE)) * 0.025).toFloat()
            }
        }
        // Fade in and out, then soften the top end (phone speakers turn highs harsh).
        val fade = (1.0 * RATE).toInt()
        for (i in 0 until min(fade, n)) out[i] *= smooth(i.toDouble() / fade).toFloat()
        for (i in 0 until min(fade, n)) out[n - 1 - i] *= smooth(i.toDouble() / fade).toFloat()
        lowPass(out, 6000.0)
        // Normalise the bed so the duck levels above mean what they say.
        val peak = out.maxOfOrNull { abs(it) } ?: 1f
        if (peak > 0) for (i in out.indices) out[i] /= peak
        return out
    }

    private fun lowPass(x: FloatArray, freq: Double) {
        val a = (1 - exp(-2 * PI * freq / RATE)).toFloat()
        var y = 0f
        for (i in x.indices) {
            y += a * (x[i] - y)
            x[i] = y
        }
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    // ---- Pop: a short, round blip for things appearing on screen ----

    private fun addPop(out: FloatArray, at: Double) {
        val s0 = (at * RATE).toInt()
        val len = (0.07 * RATE).toInt()
        var ph = 0.0
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx < 0 || idx >= out.size) continue
            val f = 1100 - 500 * (i.toDouble() / len)
            ph += 2 * PI * f / RATE
            val env = exp(-i.toDouble() / (0.018 * RATE)) * min(1.0, i / (0.002 * RATE))
            out[idx] += (sin(ph) * env * db(-24.0)).toFloat()
        }
    }

    // ---- Hit: deep boom plus a click, for emphasis words and big numbers ----

    private fun addHit(out: FloatArray, at: Double, rnd: Random) {
        val s0 = (at * RATE).toInt()
        val len = (0.45 * RATE).toInt()
        var ph = 0.0
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx < 0 || idx >= out.size) continue
            val f = 48 + 90 * exp(-i.toDouble() / (0.04 * RATE))
            ph += 2 * PI * f / RATE
            val boom = sin(ph) * exp(-i.toDouble() / (0.16 * RATE))
            val click = if (i < 0.004 * RATE) (rnd.nextFloat() * 2 - 1) * (1 - i / (0.004 * RATE)) else 0.0
            out[idx] += ((boom * db(-16.0)) + click * db(-24.0)).toFloat()
        }
    }

    // ---- Riser: noise and a rising tone that swell into a moment ----

    private fun addRiser(out: FloatArray, at: Double, rnd: Random) {
        val len = (1.1 * RATE).toInt()
        val s0 = (at * RATE).toInt() - len
        var ph = 0.0
        var lp = 0f
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx < 0 || idx >= out.size) continue
            val t = i.toDouble() / len
            ph += 2 * PI * (220 + 660 * t * t) / RATE
            val noise = rnd.nextFloat() * 2 - 1
            lp += (0.05f + 0.5f * t.toFloat()) * (noise - lp) // opens up as it rises
            val env = t.pow(2.2)
            out[idx] += ((0.6 * sin(ph) + lp) * env * db(-23.0)).toFloat()
        }
    }

    // ---- Whoosh: band-passed noise that sweeps up and fades, peaking on the cut ----

    private fun addWhoosh(out: FloatArray, at: Double, rnd: Random) {
        val len = (0.42 * RATE).toInt()
        val peakAt = (0.30 * RATE).toInt()
        val start = (at * RATE).toInt() - peakAt
        var lp = 0f
        var bp = 0f
        for (i in 0 until len) {
            val idx = start + i
            if (idx < 0 || idx >= out.size) continue
            val t = i.toDouble() / len
            val f = 300 + 3500 * t * t
            val g = (2 * PI * f / RATE).toFloat().coerceAtMost(0.9f)
            val noise = rnd.nextFloat() * 2 - 1
            // Two-pole state-variable band-pass.
            lp += g * bp
            val hp = noise - lp - 0.6f * bp
            bp += g * hp
            val env = if (i < peakAt) (i.toDouble() / peakAt).pow(2.0) else exp(-(i - peakAt).toDouble() / (0.04 * RATE))
            out[idx] += (bp * env * db(-20.0)).toFloat()
        }
    }
}
