package com.videostudio.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Longest one frame may take to draw before the render is stopped (seconds). */
private const val FRAME_TIMEOUT_S = 240L

/** Paints every frame with [ScenePainter] and encodes H.264 + AAC into an MP4, all on the phone. */
class VideoRenderer(private val width: Int, private val height: Int, private val fps: Int = 30) {
    // Upload bitrates creators use for 9:16 1080p: 8-12 Mbps at 30 fps, 12-16 Mbps at 60 fps.
    // The platforms re-encode anyway; a clean, high-bitrate master survives that best.
    private val bitrate = when {
        height >= 1920 && fps >= 50 -> 16_000_000
        height >= 1920 -> 12_000_000
        fps >= 50 -> 10_000_000
        else -> 8_000_000
    }

    fun render(
        painter: ScenePainter,
        durationSec: Double,
        audioFile: File,
        audioPlan: AudioMix.Plan,
        out: File,
        cancelled: () -> Boolean,
        twoThreads: Boolean = true,
        progress: (Float) -> Unit,
    ) {
        val audio = encodeAudio(audioFile, audioPlan)
        out.parentFile?.mkdirs()
        val part = File(out.parentFile, out.name + ".part")
        part.delete()

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // Best settings first; phones whose encoder refuses some of them fall back step by step.
        val attempts = listOf(true to true, false to true, false to false)
        var surface: Surface? = null
        for ((high, extras) in attempts) {
            try {
                encoder.configure(videoFormat(high, extras), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = encoder.createInputSurface()
                encoder.start()
                break
            } catch (e: Exception) {
                runCatching { encoder.reset() }
            }
        }
        if (surface == null) {
            encoder.release()
            throw RenderError("This phone's video encoder does not support ${width}x$height. Try 720p in Settings.")
        }

        val muxer = MediaMuxer(part.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val egl = Egl(surface)
        val drawer = TextureDrawer()
        // Two threads: the painter draws the next frame while this (GL) thread uploads and encodes
        // the current one. Two bitmaps rotate between them (each 8 MB at 1080p). With [twoThreads]
        // off (the safe retry), one bitmap and one thread, exactly like before 3.3.
        val buffers = if (twoThreads) 2 else 1
        val pool = java.util.concurrent.ArrayBlockingQueue<Bitmap>(buffers)
        val canvases = HashMap<Bitmap, Canvas>()
        repeat(buffers) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { pool.add(it); canvases[it] = Canvas(it) } }
        val ready = java.util.concurrent.ArrayBlockingQueue<Any>(buffers + 1)
        val scale = width / ScenePainter.W
        val info = MediaCodec.BufferInfo()
        val mux = MuxState()
        val frames = (durationSec * fps).toInt().coerceAtLeast(1)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        fun paint(bmp: Bitmap, i: Int) {
            val canvas = canvases.getValue(bmp)
            canvas.save()
            canvas.scale(scale, scale)
            painter.draw(canvas, i.toDouble() / fps, captions = true)
            canvas.restore()
        }
        val painterThread = if (!twoThreads) null else Thread({
            try {
                for (i in 0 until frames) {
                    if (stop.get()) return@Thread
                    val bmp = pool.poll(30, java.util.concurrent.TimeUnit.SECONDS) ?: throw RenderError("the encoder stalled")
                    paint(bmp, i)
                    ready.put(bmp)
                }
            } catch (e: Throwable) {
                runCatching { ready.put(e) }
            }
        }, "frame-painter")
        painterThread?.start()
        try {
            for (i in 0 until frames) {
                if (cancelled()) throw RenderError("cancelled")
                val bmp: Bitmap
                if (painterThread == null) {
                    bmp = pool.peek()!!
                    paint(bmp, i)
                } else {
                    // Generous: a scene's pictures load on its first frame. Only a real hang stops the render.
                    val item = ready.poll(FRAME_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
                        ?: throw RenderError("frame ${i + 1} of $frames did not finish drawing in $FRAME_TIMEOUT_S s")
                    if (item is Throwable) throw item
                    bmp = item as Bitmap
                }
                drawer.draw(bmp)
                if (painterThread != null) pool.put(bmp)
                egl.present(i * 1_000_000_000L / fps)
                drain(encoder, info, muxer, audio, mux, end = false)
                if (i % 15 == 0) progress(i.toFloat() / frames)
            }
            drain(encoder, info, muxer, audio, mux, end = true)
            progress(1f)
        } finally {
            stop.set(true)
            pool.clear(); ready.clear()
            painterThread?.interrupt()
            runCatching { painterThread?.join(5000) }
            canvases.keys.forEach { runCatching { it.recycle() } }
            runCatching { encoder.stop() }
            encoder.release()
            drawer.release()
            egl.release()
            surface.release()
            runCatching { if (mux.started) muxer.stop() }
            muxer.release()
        }
        if (!mux.started || part.length() < 10_000) throw RenderError("the encoder produced no video")
        out.delete()
        if (!part.renameTo(out)) throw RenderError("could not save the video file")
    }

    /**
     * [high]: H.264 High profile (better quality per bit than Baseline). [extras]: variable bitrate
     * (Android 12+ keeps a minimum quality in VBR) and BT.709 colour tags, the HD standard, so
     * colours look the same after the platforms re-encode.
     */
    private fun videoFormat(high: Boolean, extras: Boolean) =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (high) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, if (fps >= 50) MediaCodecInfo.CodecProfileLevel.AVCLevel42 else MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
            if (extras) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
        }

    private class MuxState {
        var videoTrack = -1
        var started = false
    }

    private fun drain(encoder: MediaCodec, info: MediaCodec.BufferInfo, muxer: MediaMuxer, audio: EncodedAudio, mux: MuxState, end: Boolean) {
        if (end) encoder.signalEndOfInputStream()
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, if (end) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    mux.videoTrack = muxer.addTrack(encoder.outputFormat)
                    val audioTrack = muxer.addTrack(audio.format)
                    muxer.start()
                    mux.started = true
                    // The whole voice track is already encoded: write it in one go.
                    val ai = MediaCodec.BufferInfo()
                    for (s in audio.samples) {
                        ai.set(0, s.data.size, s.ptsUs, s.flags)
                        muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), ai)
                    }
                }
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && mux.started) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(mux.videoTrack, buf, info)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun thumbnail(painter: ScenePainter, t: Double, out: File) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = width / ScenePainter.W
        c.scale(s, s)
        painter.draw(c, t, captions = false)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
    }

    // ---- Audio: decode the voice (MP3 or WAV) to PCM, pad to the video length, encode AAC ----

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private class EncodedAudio(val format: MediaFormat, val samples: List<Sample>)
    private class Pcm(val data: ShortArray, val rate: Int, val channels: Int)

    private fun decode(file: File): Pcm {
        if (file.name.endsWith(".wav", ignoreCase = true)) return readWav(file)
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw RenderError("the voice file has no audio")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0)
        dec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = dec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val n = ex.readSampleData(dec.getInputBuffer(inIdx)!!, 0)
                    if (n < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        dec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val outIdx = dec.dequeueOutputBuffer(info, 10_000L)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rate = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (outIdx >= 0) {
                val buf = dec.getOutputBuffer(outIdx)!!
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(bytes)
                pcm.write(bytes)
                dec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        dec.stop()
        dec.release()
        ex.release()
        val raw = pcm.toByteArray()
        val shorts = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return Pcm(shorts, rate, channels)
    }

    private fun readWav(file: File): Pcm = RandomAccessFile(file, "r").use { raf ->
        val len = raf.length().toInt()
        val b = ByteArray(len)
        raf.readFully(b)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var channels: Short = 1
        var rate = 22050
        var dataStart = 44
        var dataLen = len - 44
        var pos = 12
        while (pos + 8 <= len) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10)
                rate = bb.getInt(pos + 12)
            }
            if (id == "data") {
                dataStart = pos + 8
                val declared = if (size <= 0) Long.MAX_VALUE else size.toLong()
                dataLen = minOf(declared, (len - dataStart).toLong()).toInt()
                break
            }
            pos += size + 8 + (size and 1)
        }
        val shorts = ShortArray(dataLen / 2)
        ByteBuffer.wrap(b, dataStart, dataLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        Pcm(shorts, rate, channels.toInt())
    }

    private fun encodeAudio(file: File, plan: AudioMix.Plan): EncodedAudio {
        val voice = decode(file)
        // Voice cleanup, music bed, whooshes and loudness, as one 48 kHz mono track.
        val data = AudioMix.mix(voice.data, voice.rate, voice.channels, plan)
        val pcm = Pcm(data, AudioMix.RATE, 1)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.rate, pcm.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val samples = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = enc.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val buf = enc.getInputBuffer(inIdx)!!
                    buf.clear()
                    val n = minOf(buf.capacity() / 2, minOf(data.size - pos, pcm.channels * 2048))
                    val pts = (pos / pcm.channels) * 1_000_000L / pcm.rate
                    if (n <= 0) {
                        enc.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(data, pos, n)
                        enc.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                        pos += n
                    }
                }
            }
            val outIdx = enc.dequeueOutputBuffer(info, 10_000L)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outFormat = enc.outputFormat
            } else if (outIdx >= 0) {
                val buf = enc.getOutputBuffer(outIdx)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    samples.add(Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME))
                }
                enc.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        enc.stop()
        enc.release()
        return EncodedAudio(outFormat ?: throw RenderError("the audio encoder gave no format"), samples)
    }

    // ---- OpenGL: push each painted bitmap into the encoder's input surface ----

    private class Egl(surface: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val eglSurface: EGLSurface

        init {
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
            )
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        }

        fun present(nanos: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        companion object {
            const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }

    private class TextureDrawer {
        private val program = GLES20.glCreateProgram()
        private val tex: Int
        private var uploaded = false
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        private val posLoc: Int
        private val texLoc: Int

        init {
            GLES20.glAttachShader(
                program, shader(
                    GLES20.GL_VERTEX_SHADER,
                    "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex;\nvoid main() { gl_Position = aPos; vTex = aTex; }"
                )
            )
            GLES20.glAttachShader(
                program, shader(
                    GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float; varying vec2 vTex; uniform sampler2D uTex;\nvoid main() { gl_FragColor = texture2D(uTex, vTex); }"
                )
            )
            GLES20.glLinkProgram(program)
            posLoc = GLES20.glGetAttribLocation(program, "aPos")
            texLoc = GLES20.glGetAttribLocation(program, "aTex")
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            tex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        fun draw(bmp: Bitmap) {
            GLES20.glViewport(0, 0, bmp.width, bmp.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            if (uploaded) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
            else {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                uploaded = true
            }
            quad.position(0)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(posLoc)
            quad.position(2)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            GLES20.glDeleteProgram(program)
        }
    }
}
