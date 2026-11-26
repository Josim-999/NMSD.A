package com.videostudio.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import com.videostudio.app.ai.Logo
import com.videostudio.app.data.Scene
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Draws one frame of the video on a 1080x1920 canvas. */
class ScenePainter(
    context: Context,
    private val scenes: List<Scene>,
    private val timeline: Timeline,
    palette: Palette,
    private val seriesName: String,
    /** Stock photo or video clip per photo scene, loaded only while its scene is on screen. */
    private val media: Map<Int, SceneMedia.Lazy>,
    /** Brand logos by scene index: the logo scene's subject, or a badge on any scene naming a brand. */
    private val logos: Map<Int, Logo> = emptyMap(),
    /** This video's style (captions, transition, background). */
    private val look: Look = Look(0, 0, 0),
    /** Times (s) of the emphasis words: zoom punch and coloured caption. */
    private val emphasisTimes: List<Double> = emptyList(),
    /** Animated versions of the emoji in this video (Noto, Lottie), by emoji. */
    animated: Map<String, com.airbnb.lottie.LottieComposition> = emptyMap(),
    /** Subject cut-outs of photo scenes, by scene index: the text goes behind the subject. */
    private val cutouts: Map<Int, Cutout> = emptyMap(),
) {
    private val animoji: Map<String, Animoji> = animated.mapValues { Animoji(it.value) }
    private val basePalette = palette
    /** The colours in use right now: in the glass look they switch between light and dark per scene. */
    private var palette: Palette = when {
        look.glass -> Palettes.GLASS_LIGHT
        look.paper -> Palettes.PAPER
        else -> palette
    }

    /** Glass look: problems, myths, questions and data scenes go dark; the rest stay light. */
    private fun darkScene(index: Int) = look.glass &&
        scenes.getOrNull(index)?.type?.lowercase(Locale.ROOT) in setOf("compare", "question", "chart", "timeline", "logo", "quote")

    private fun paletteFor(index: Int): Palette = when {
        look.paper -> Palettes.PAPER
        !look.glass -> basePalette
        darkScene(index) -> Palettes.GLASS_DARK
        else -> Palettes.GLASS_LIGHT
    }
    /** The time of the frame being drawn, for animated emoji. */
    private var now = 0.0

    private val black: Typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/Inter-Black.otf") }
        .getOrElse { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    /** Headline and caption lettering: a different free (OFL) display font for each caption style. */
    private val display: Typeface = runCatching {
        Typeface.createFromAsset(context.assets, "fonts/" + if (look.paper) "PermanentMarker-Regular.ttf" else DISPLAY_FONTS[look.captions % DISPLAY_FONTS.size])
    }.getOrElse { black }
    private val bold: Typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/Inter-ExtraBold.otf") }
        .getOrElse { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bgPaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, H, palette.bgTop, palette.bgBottom, Shader.TileMode.CLAMP)
    }
    private val blob1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 620f, withAlpha(palette.accent2, 70), 0, Shader.TileMode.CLAMP)
    }
    private val blob2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 560f, withAlpha(palette.accent, 55), 0, Shader.TileMode.CLAMP)
    }
    private val layouts = HashMap<String, StaticLayout>()

    // Finishing layers drawn over every frame: soft vignette and fine film grain (hides gradient
    // banding after the platforms re-encode and gives flat colour a filmic texture).
    private val vignette = Paint().apply {
        shader = RadialGradient(W / 2, H * 0.45f, H * 0.72f, intArrayOf(0, 0, Color.argb(120, 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
    private val grainBitmap: Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
        val rnd = java.util.Random(7)
        val px = IntArray(256 * 256) { val v = rnd.nextInt(256); Color.argb(rnd.nextInt(22), v, v, v) }
        setPixels(px, 0, 256, 0, 0, 256, 256)
    }
    private val grainPaint = Paint().apply {
        shader = android.graphics.BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val grainShift = android.graphics.Matrix()
    private val scrim = Paint()
    private val rect = RectF()
    private val path = Path()
    private val zoneMid = (ZONE_TOP + ZONE_BOTTOM) / 2f
    private val zoneW = W - 2 * SIDE
    private val numberRe = Regex("^([^0-9]*)([0-9][0-9,]*\\.?[0-9]*)(.*)$")

    private fun clamp(x: Double) = x.coerceIn(0.0, 1.0)
    private fun outCubic(x: Double) = 1 - (1 - clamp(x)).pow(3)
    private fun outBack(x: Double): Double {
        val c = clamp(x) - 1
        return 1 + 2.70158 * c * c * c + 1.70158 * c * c
    }

    /** Damped spring from 0 to 1: overshoots a little and settles, like real objects. */
    private fun spring(x: Double): Double {
        val t = x.coerceAtLeast(0.0)
        if (t >= 1.6) return 1.0
        return 1 - kotlin.math.exp(-6.5 * t) * cos(11.0 * t)
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Upper-case Latin text; leave scripts without case (Bangla, Hindi, Arabic...) alone. */
    private fun upper(s: String): String = if (s.any { it.code > 767 }) s else s.uppercase(Locale.getDefault())

    /** A text block that fits [maxWidth] x [maxHeight], shrinking the font until it does. */
    private fun block(
        key: String,
        text: CharSequence,
        face: Typeface,
        startSize: Float,
        maxWidth: Float,
        maxHeight: Float,
        color: Int = palette.text,
    ): StaticLayout {
        val cacheKey = "$key|$text|$startSize|$maxWidth|$maxHeight|$color"
        layouts[cacheKey]?.let { return it }
        var size = startSize
        var layout: StaticLayout
        while (true) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face
                textSize = size
                this.color = color
                // Soft drop shadow: text stays readable over photos, video and bright colours.
                if (luminance(color) < 0.4) setShadowLayer(size * 0.10f, 0f, size * 0.03f, Color.argb(110, 255, 255, 255))
                else setShadowLayer(size * 0.12f, 0f, size * 0.05f, Color.argb(150, 0, 0, 0))
            }
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 0.98f)
                .setIncludePad(false)
                .build()
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
            // A line that breaks inside a word means the font is too big.
            val cleanBreaks = (0 until layout.lineCount).all {
                val end = layout.getLineEnd(it)
                !(end < text.length && end > 0 && !text[end - 1].isWhitespace() && !text[end].isWhitespace())
            }
            if ((layout.height > maxHeight || widest > maxWidth || !cleanBreaks) && size >= 26f) size *= 0.92f else break
        }
        layouts[cacheKey] = layout
        return layout
    }

    /** Draws [layout] with each line centred on [cx], the block centred on [cy]. */
    private fun drawBlock(c: Canvas, layout: StaticLayout, cx: Float, cy: Float, alpha: Float = 1f, scale: Float = 1f) {
        if (alpha <= 0.01f) return
        c.save()
        c.translate(cx, cy)
        c.scale(scale, scale)
        val h = layout.height.toFloat()
        val faded = alpha < 0.99f
        if (faded) c.saveLayerAlpha(-W, -h / 2 - 40, W, h / 2 + 40, (alpha * 255).toInt())
        for (i in 0 until layout.lineCount) {
            val lw = layout.getLineWidth(i)
            c.save()
            c.translate(-lw / 2 - layout.getLineLeft(i), -h / 2)
            // Clip to this line only: neighbouring lines must not bleed in. Extra room above the
            // first and below the last line keeps shadows and descenders whole.
            val last = layout.lineCount - 1
            c.clipRect(
                layout.getLineLeft(i) - 40, layout.getLineTop(i) - if (i == 0) 40f else 0f,
                layout.getLineRight(i) + 40, layout.getLineBottom(i) + if (i == last) 40f else 0f
            )
            layout.draw(c)
            c.restore()
        }
        if (faded) c.restore()
        c.restore()
    }

    private fun highlighted(text: String, word: String, base: Int, hi: Int): CharSequence {
        val s = SpannableString(text)
        s.setSpan(ForegroundColorSpan(base), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (word.isNotBlank()) {
            var at = text.indexOf(word, 0, ignoreCase = true)
            while (at >= 0) {
                s.setSpan(ForegroundColorSpan(hi), at, at + word.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                at = text.indexOf(word, at + word.length, ignoreCase = true)
            }
        }
        return s
    }

    private fun emoji(c: Canvas, e: String, cx: Float, cy: Float, size: Float, alpha: Float = 1f) {
        if (e.isBlank()) return
        animoji[e.trim()]?.let { it.draw(c, cx, cy, size * 1.15f, now, alpha); return }
        emojiPaint.textSize = size
        emojiPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val fm = emojiPaint.fontMetrics
        c.drawText(e, cx, cy - (fm.ascent + fm.descent) / 2, emojiPaint)
    }

    private fun card(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 44f) {
        // Neutral cards become frosted glass in the glass look.
        if (look.paper && (color and 0xFFFFFF) == (palette.card and 0xFFFFFF)) {
            sketchCard(c, l, t, r, b, Color.alpha(color) / Color.alpha(palette.card).coerceAtLeast(1).toFloat())
            return
        }
        if (look.glass && (color and 0xFFFFFF) == (palette.card and 0xFFFFFF)) {
            glassCard(c, l, t, r, b, radius, Color.alpha(color) / Color.alpha(palette.card).coerceAtLeast(1).toFloat())
            return
        }
        fill.shader = null
        fill.color = color
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, radius, radius, fill)
    }

    fun draw(c: Canvas, t: Double, captions: Boolean = true) {
        now = t
        background(c, t)
        val span = timeline.sceneAt(t)
        manageMedia(span.index, t - span.start)
        val lt = t - span.start
        hookBackdrop(c, t)
        // Impact shake in the opening (and on early punch words).
        val (sx, sy) = shake(t)
        c.save()
        c.translate(sx, sy)
        if (t in GLITCH_A || (timeline.spans.size > 1 && t - timeline.spans[1].start in GLITCH_CUT)) glitch(c, span.index, lt, span.end - span.start, t)
        if (span.index > 0 && lt < TRANSITION) {
            val k = outCubic(lt / TRANSITION).toFloat()
            val prev = timeline.spans[span.index - 1]
            val pt = t - prev.start
            val pd = prev.end - prev.start
            val cd = span.end - span.start
            when (look.transition) {
                1 -> { // slide-up: the new scene pushes the old one up
                    drawScene(c, prev.index, pt, pd, t, alpha = 1f - 0.6f * k, scale = 1f, dy = -H * 0.45f * k)
                    drawScene(c, span.index, lt, cd, t, alpha = 1f, scale = 1f, dy = H * 0.45f * (1 - k))
                }
                2 -> { // whip-pan: fast sideways move with a motion-blur ghost
                    drawScene(c, prev.index, pt, pd, t, alpha = 1f - k, scale = 1f, dx = -W * 0.7f * k)
                    drawScene(c, span.index, lt, cd, t, alpha = 0.35f * (1 - k), scale = 1f, dx = W * 0.7f * (1 - k) + 60f)
                    drawScene(c, span.index, lt, cd, t, alpha = k, scale = 1f, dx = W * 0.7f * (1 - k))
                }
                3 -> { // iris: the new scene opens in a growing circle
                    drawScene(c, prev.index, pt, pd, t, alpha = 1f, scale = 1f + 0.05f * k)
                    c.save()
                    path.reset()
                    path.addCircle(W / 2, H * 0.42f, 40f + k * H * 0.75f, Path.Direction.CW)
                    c.clipPath(path)
                    background(c, t)
                    drawScene(c, span.index, lt, cd, t, alpha = 1f, scale = 1.06f - 0.06f * k)
                    c.restore()
                }
                else -> { // zoom-push: the old scene grows and fades away, the new one settles in
                    drawScene(c, prev.index, pt, pd, t, alpha = 1f - k, scale = 1f + 0.10f * k, dy = -70f * k)
                    drawScene(c, span.index, lt, cd, t, alpha = k, scale = 0.90f + 0.10f * k, dy = 90f * (1 - k))
                }
            }
        } else {
            drawScene(c, span.index, lt, span.end - span.start, t, alpha = 1f, scale = 1f)
        }
        // Seamless loop: the last moments fade into the very first frame, so a replay feels continuous.
        val loopK = clamp((t - (timeline.duration - LOOP)) / LOOP).toFloat()
        if (loopK > 0f && scenes.isNotEmpty()) {
            c.saveLayerAlpha(0f, 0f, W, H, (outCubic(loopK.toDouble()) * 255).toInt())
            background(c, 0.0)
            drawScene(c, 0, 0.0, timeline.spans[0].end - timeline.spans[0].start, 0.0, alpha = 1f, scale = 1f)
            c.restore()
        }
        c.restore()
        hookOverlay(c, t)
        palette = paletteFor(span.index)
        chrome(c, t, span.index)
        if (!look.paper && (!look.glass || darkScene(span.index))) c.drawRect(0f, 0f, W, H, vignette)
        grainShift.setTranslate(((t * 97) % 256).toFloat(), ((t * 61) % 256).toFloat())
        grainPaint.shader.setLocalMatrix(grainShift)
        c.drawRect(0f, 0f, W, H, grainPaint)
        val kineticNow = look.kinetic && scenes[span.index].type.lowercase(Locale.ROOT) in KINETIC_TYPES
        if (captions && loopK < 0.5f && !kineticNow) {
            when {
                look.glass -> glassCaptions(c, t)
                look.paper -> markerCaptions(c, t)
                else -> captions(c, t)
            }
        }
    }

    /** Zoom punch on emphasis words: a quick push-in that eases back. */
    private fun emphasisPunch(t: Double): Float {
        var k = 0.0
        for (e in emphasisTimes) {
            val x = t - e
            if (x < 0 || x > 0.6) continue
            k = max(k, if (x < 0.07) x / 0.07 else kotlin.math.exp(-(x - 0.07) * 7))
        }
        return (1 + 0.065 * k).toFloat()
    }

    /** "Cut-in" every 2 s inside long scenes: the frame steps between wide and close, like an editor's cut. */
    private fun cutIn(lt: Double, dur: Double): Float {
        if (dur < 2.6) return 1f
        val seg = (lt / 2.0).toInt()
        val level = { n: Int -> if (n % 2 == 1) 1.075f else 1f }
        if (seg == 0) return 1f
        val e = outCubic((lt - seg * 2.0) / 0.12).toFloat()
        return level(seg - 1) + (level(seg) - level(seg - 1)) * e
    }

    /** One scene, with a slow push-in "camera" over its whole length. */
    private fun drawScene(
        c: Canvas, index: Int, lt: Double, durRaw: Double, t: Double,
        alpha: Float, scale: Float, dy: Float = 0f, dx: Float = 0f,
    ) {
        if (alpha <= 0.01f) return
        val scene = scenes[index]
        val dur = durRaw.coerceAtLeast(0.3)
        val savedPalette = palette
        palette = paletteFor(index)
        val drift = (1f + 0.035f * clamp(lt / dur).toFloat()) * emphasisPunch(t) *
            (if (scene.type == "logo") 1f else cutIn(lt, dur))
        c.saveLayerAlpha(0f, 0f, W, H, (alpha * 255).toInt())
        c.translate(dx, dy)
        c.scale(scale * drift, scale * drift, W / 2, H * 0.42f)
        val type = scene.type.lowercase(Locale.ROOT)
        if (look.kinetic && type in KINETIC_TYPES) kineticScene(c, index, lt)
        else when (type) {
            "tier" -> tier(c, scene, lt, dur)
            "hook" -> hook(c, scene, lt)
            "stat" -> stat(c, scene, lt)
            "list" -> list(c, scene, lt, dur, numbered = false)
            "steps" -> list(c, scene, lt, dur, numbered = true)
            "compare" -> compare(c, scene, lt, dur)
            "emoji" -> emojiScene(c, scene, lt)
            "question" -> question(c, scene, lt)
            "photo" -> photo(c, scene, index, lt, dur)
            "logo" -> logoScene(c, scene, index, lt)
            "chart" -> chart(c, scene, lt, dur)
            "timeline" -> timelineScene(c, scene, lt, dur)
            "quote" -> quote(c, scene, index, lt)
            "outro" -> outro(c, scene, lt)
            else -> statement(c, scene, lt)
        }
        badge(c, scene, index, lt)
        c.restore()
        palette = savedPalette
    }

    /** Keeps only the clips of the scene on screen (and the one leaving) in memory. */
    private fun manageMedia(index: Int, lt: Double) {
        val keep = if (index > 0 && lt < TRANSITION + 0.05) setOf(index, index - 1) else setOf(index)
        for ((i, m) in media) if (i !in keep) m.release()
    }

    /** Frees every clip; call when rendering is finished. */
    fun release() {
        media.values.forEach { it.release() }
        logoBitmaps.values.forEach { it?.recycle() }
        grainBitmap.recycle()
    }

    private fun background(c: Canvas, t: Double) {
        if (look.glass) { glassBackground(c, t); return }
        if (look.paper) { paperBackground(c, t); return }
        if (look.kinetic) { kineticBackground(c, t); return }
        c.drawRect(0f, 0f, W, H, bgPaint)
        when (look.background) {
            1 -> { gridBackground(c, t); return }
            2 -> { auroraBackground(c, t); return }
        }
        val a = 0.35 * t
        c.save()
        c.translate((260 * sin(a) + W * 0.25).toFloat(), (180 * cos(0.8 * a) + H * 0.28).toFloat())
        c.drawCircle(0f, 0f, 620f, blob1)
        c.restore()
        c.save()
        c.translate((220 * cos(0.9 * a) + W * 0.8).toFloat(), (200 * sin(a * 0.7) + H * 0.72).toFloat())
        c.drawCircle(0f, 0f, 560f, blob2)
        c.restore()
        // Slowly drifting dot grid.
        fill.shader = null
        fill.color = Color.argb(14, 255, 255, 255)
        var y = ((t * 12) % 90).toFloat() - 90f
        while (y < H) {
            var x = 45f
            while (x < W) {
                c.drawCircle(x, y, 2.4f, fill)
                x += 90f
            }
            y += 90f
        }
    }

    /** Fine perspective grid drifting towards the viewer, with one soft light. */
    private fun gridBackground(c: Canvas, t: Double) {
        c.save()
        c.translate((180 * sin(0.3 * t) + W * 0.5).toFloat(), (140 * cos(0.25 * t) + H * 0.35).toFloat())
        c.drawCircle(0f, 0f, 700f, blob1)
        c.restore()
        stroke.shader = null
        stroke.strokeWidth = 2f
        val horizon = H * 0.18f
        val off = ((t * 0.35) % 1.0).toFloat()
        for (i in 0 until 14) {
            val f = ((i + off) / 14f)
            val y = horizon + (H - horizon) * f * f
            stroke.color = Color.argb((10 + 40 * f).toInt(), 255, 255, 255)
            c.drawLine(0f, y, W, y, stroke)
        }
        for (i in -6..6) {
            stroke.color = Color.argb(22, 255, 255, 255)
            c.drawLine(W / 2 + i * 40f, horizon, W / 2 + i * 260f, H, stroke)
        }
    }

    /** Slow aurora ribbons in the palette's two accents. */
    private fun auroraBackground(c: Canvas, t: Double) {
        for (band in 0 until 3) {
            path.reset()
            val base = H * (0.25f + 0.22f * band)
            val amp = 120f + 40f * band
            path.moveTo(0f, H)
            var x = 0f
            while (x <= W) {
                val y = base + amp * sin(x / 260.0 + t * (0.4 + 0.15 * band) + band).toFloat()
                path.lineTo(x, y)
                x += 30f
            }
            path.lineTo(W, H)
            path.close()
            val color = if (band % 2 == 0) palette.accent2 else palette.accent
            fill.shader = LinearGradient(0f, base - amp, 0f, base + 500f, withAlpha(color, 46), 0, Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
        }
        fill.shader = null
    }

    private fun chrome(c: Canvas, t: Double, sceneIndex: Int) {
        val progress = clamp(t / timeline.duration).toFloat()
        fill.shader = null
        fill.color = withAlpha(palette.text, 40)
        c.drawRect(0f, 0f, W, 12f, fill)
        fill.color = palette.accent
        c.drawRect(0f, 0f, progress * W, 12f, fill)
        if (seriesName.isBlank() || sceneIndex != 0) return
        val s = (0.92 + 0.08 * spring(t / 0.5)).toFloat()
        tp.typeface = black
        tp.textSize = 40f
        tp.color = Color.BLACK
        val label = upper(seriesName).take(28)
        val w = tp.measureText(label) + 64f
        c.save()
        c.translate(W / 2, 230f)
        c.scale(s, s)
        card(c, -w / 2, -38f, w / 2, 38f, palette.accent, 38f)
        c.drawText(label, -w / 2 + 32f, 14f, tp)
        c.restore()
    }

    /**
     * Word-by-word captions in this video's style:
     * 0 chip (spoken word on an accent chip), 1 colour-pop (spoken word coloured and bigger),
     * 2 boxed (the group on a dark box), 3 glow (soft glow, underline under the spoken word).
     * The emphasis word always gets the second accent colour.
     */
    private fun captions(c: Canvas, t: Double) {
        val g = timeline.captionAt(t) ?: return
        val style = look.captions
        val pop = outBack((t - g.start) / 0.18).toFloat()
        tp.typeface = display
        tp.textSize = if (style == 1) 86f else 82f
        val texts = g.words.map { upper(it.text.trim()) }
        val space = tp.measureText(" ")
        var widths = texts.map { tp.measureText(it) }
        var total = widths.sum() + (texts.size - 1) * space
        if (total > zoneW) {
            tp.textSize = tp.textSize * (zoneW / total)
            widths = texts.map { tp.measureText(it) }
            total = widths.sum() + (texts.size - 1) * space
        }
        val size = tp.textSize
        c.save()
        c.translate(W / 2, CAPTION_Y)
        val sc = 0.85f + 0.15f * pop
        c.scale(sc, sc)
        if (style == 2) {
            fill.shader = null
            fill.color = Color.argb(175, 8, 9, 16)
            rect.set(-total / 2 - 34f, -size * 1.05f, total / 2 + 34f, size * 0.45f)
            c.drawRoundRect(rect, 28f, 28f, fill)
        }
        var x = -total / 2
        texts.forEachIndexed { i, word ->
            val start = g.words[i].start
            val nextStart = g.words.getOrNull(i + 1)?.start ?: g.end
            val active = t >= start && t < nextStart
            val emph = emphasisTimes.any { kotlin.math.abs(it - start) < 1e-6 }
            val hot = if (emph) palette.accent2 else palette.accent
            val wordPop = if (active) 1f + 0.12f * (1f - spring((t - start) / 0.25).toFloat()).coerceAtLeast(-0.2f) else 1f
            c.save()
            val grow = if (active && style == 1) 1.14f * wordPop else wordPop
            c.scale(grow, grow, x + widths[i] / 2, -0.28f * size)
            when (style) {
                0 -> {
                    if (active) card(c, x - 18f, -0.86f * size, x + widths[i] + 18f, size * 0.3f, hot, 22f) else outline(c, word, x)
                    tp.style = Paint.Style.FILL
                    tp.color = if (active) Color.BLACK else if (emph) palette.accent2 else Color.WHITE
                    c.drawText(word, x, 0f, tp)
                }
                1 -> {
                    outline(c, word, x)
                    tp.style = Paint.Style.FILL
                    tp.color = if (active) hot else Color.WHITE
                    c.drawText(word, x, 0f, tp)
                }
                2 -> {
                    tp.style = Paint.Style.FILL
                    tp.color = if (active) hot else Color.WHITE
                    c.drawText(word, x, 0f, tp)
                }
                else -> {
                    tp.style = Paint.Style.FILL
                    tp.setShadowLayer(26f, 0f, 0f, if (active) withAlpha(hot, 220) else Color.argb(170, 0, 0, 0))
                    tp.color = Color.WHITE
                    c.drawText(word, x, 0f, tp)
                    tp.clearShadowLayer()
                    if (active) {
                        val w = widths[i] * outCubic((t - start) / 0.18).toFloat()
                        fill.shader = null
                        fill.color = hot
                        c.drawRoundRect(x, size * 0.16f, x + w, size * 0.16f + 12f, 6f, 6f, fill)
                    }
                }
            }
            c.restore()
            x += widths[i] + space
        }
        c.restore()
    }

    private fun outline(c: Canvas, word: String, x: Float) {
        tp.style = Paint.Style.STROKE
        tp.strokeWidth = 14f
        tp.strokeJoin = Paint.Join.ROUND
        tp.color = Color.argb(200, 0, 0, 0)
        c.drawText(word, x, 0f, tp)
    }

    /** The hook is fully readable on the very first frame (the feed preview), then comes alive. */
    private fun hook(c: Canvas, s: Scene, lt: Double) {
        val words = upper(s.text.ifBlank { s.say }).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val hasEmoji = s.emoji.isNotBlank()
        if (hasEmoji) emoji(c, s.emoji, W / 2, 480f + (sin(4 * lt) * 12).toFloat(), (0.85 + 0.15 * spring(lt / 0.6)).toFloat() * 230f)
        val full = block(
            "hook", highlighted(words.joinToString(" "), words.lastOrNull() ?: "", palette.text, palette.accent),
            display, 150f, zoneW, if (hasEmoji) 600f else 820f
        )
        val pop = (0.96 + 0.04 * spring(lt / 0.5)).toFloat()
        val cy = zoneMid + if (hasEmoji) 150f else 0f
        if (look.glass) {
            // The hook sits in a frosted card; a cursor glides in and taps "send" (a UI micro-event).
            val h = full.height + 140f
            val k = spring(lt / 0.55).toFloat()
            c.save()
            c.scale(0.94f + 0.06f * k, 0.94f + 0.06f * k, W / 2, cy)
            glassCard(c, SIDE - 30f, cy - h / 2, W - SIDE + 30f, cy + h / 2, 56f)
            val bx = W - SIDE - 40f
            val by = cy + h / 2 - 50f
            fill.shader = LinearGradient(bx - 44f, by - 44f, bx + 44f, by + 44f, palette.accent, palette.accent2, Shader.TileMode.CLAMP)
            c.drawCircle(bx, by, 44f, fill)
            fill.shader = null
            tp.typeface = black; tp.textSize = 50f; tp.color = Color.WHITE; tp.textAlign = Paint.Align.CENTER
            c.drawText("↑", bx, by + 18f, tp)
            tp.textAlign = Paint.Align.LEFT
            c.restore()
            drawBlock(c, full, W / 2, cy - 20f, 1f, pop)
            cursorTo(c, lt, W * 0.9f, H * 0.95f, bx + 10f, by + 16f, HOOK_CLICK)
            return
        }
        drawBlock(c, full, W / 2, cy, 1f, pop)
    }

    private fun stat(c: Canvas, s: Scene, lt: Double) {
        val final = s.value.ifBlank { s.text }.trim()
        var shown = final
        numberRe.find(final)?.let { m ->
            val raw = m.groupValues[2].replace(",", "")
            val target = raw.toDoubleOrNull() ?: 0.0
            val decimals = raw.substringAfter('.', "").length
            val v = target * outCubic(lt / 0.9)
            val num = when {
                decimals > 0 -> String.format(Locale.US, "%.${decimals}f", v)
                m.groupValues[2].contains(',') -> String.format(Locale.US, "%,d", v.toLong())
                else -> v.toLong().toString()
            }
            shown = m.groupValues[1] + num + m.groupValues[3]
        }
        val cy = zoneMid - 90f
        val r = 330f
        stroke.shader = null
        stroke.strokeWidth = 30f
        if (look.paper) {
            scribbleCircle(c, W / 2, cy, r, outCubic(lt / 0.9).toFloat())
        } else {
            stroke.color = withAlpha(palette.text, 34)
            c.drawCircle(W / 2, cy, r, stroke)
            stroke.color = palette.accent
            rect.set(W / 2 - r, cy - r, W / 2 + r, cy + r)
            c.drawArc(rect, -90f, (360 * outCubic(lt / 1.0)).toFloat(), false, stroke)
        }
        // Size the counting number by the final value so it does not jump while counting.
        val size = block("statfinal", final, display, 220f, r * 1.6f, r * 1.1f, palette.accent).paint.textSize
        drawBlock(c, block("stat", shown, display, size, r * 1.8f, r * 1.3f, palette.accent), W / 2, cy, 1f, outBack(lt / 0.4).toFloat())
        if (s.label.isBlank()) return
        val a = clamp((lt - 0.35) / 0.3).toFloat()
        drawBlock(c, block("statlabel", s.label, bold, 64f, zoneW, 220f), W / 2, cy + r + 140f - 30f * a, a)
        // Glass look: a percentage also fills a frosted progress bar, like a UI meter.
        val pct = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*%").find(final)?.groupValues?.get(1)?.toDoubleOrNull()
        if (look.glass && pct != null && pct <= 100) {
            val k = outCubic((lt - 0.5) / 0.9).toFloat()
            val top = cy + r + 178f
            glassCard(c, SIDE, top, W - SIDE, top + 56f, 28f, clamp((lt - 0.4) / 0.25).toFloat())
            val fillW = (zoneW - 16f) * (pct / 100.0).toFloat() * k
            if (fillW > 4f) {
                fill.color = Color.WHITE
                fill.shader = LinearGradient(SIDE + 8f, 0f, W - SIDE - 8f, 0f, palette.accent, palette.accent2, Shader.TileMode.CLAMP)
                rect.set(SIDE + 8f, top + 8f, SIDE + 8f + fillW, top + 48f)
                c.drawRoundRect(rect, 20f, 20f, fill)
                fill.shader = null
            }
        }
    }

    private fun list(c: Canvas, s: Scene, lt: Double, dur: Double, numbered: Boolean) {
        val items = s.items.take(4).ifEmpty { listOf(s.text.ifBlank { s.say }) }
        var top = ZONE_TOP + 20f
        if (s.text.isNotBlank() && s.items.isNotEmpty()) {
            val title = block("listtitle", upper(s.text), display, 84f, zoneW, 210f, palette.accent)
            drawBlock(c, title, W / 2, top + title.height / 2f, outCubic(lt / 0.3).toFloat())
            top += title.height + 50f
        }
        val gap = 34f
        val room = ZONE_BOTTOM - top
        val rowH = min((room - (items.size - 1) * gap) / items.size, 230f)
        val y0 = top + (room - (items.size * rowH + (items.size - 1) * gap)) / 2
        val delays = itemDelays(items.size, dur)
        items.forEachIndexed { i, item ->
            val delay = delays[i]
            val a = outCubic((lt - delay) / 0.35).toFloat()
            if (a <= 0f) return@forEachIndexed
            val bounce = spring((lt - delay) / 0.45).toFloat()
            val y = y0 + i * (rowH + gap)
            c.save()
            c.translate(-(1 - bounce) * 160f, 0f)
            card(c, SIDE, y, W - SIDE, y + rowH, withAlpha(palette.card, (Color.alpha(palette.card) * a).toInt()))
            val cy = y + rowH / 2
            val alpha = (255 * a).toInt()
            fill.color = palette.accent
            fill.alpha = alpha
            c.drawCircle(170f, cy, 44f, fill)
            tp.typeface = black
            tp.textSize = 50f
            tp.color = Color.BLACK
            tp.alpha = alpha
            tp.textAlign = Paint.Align.CENTER
            c.drawText(if (numbered) "${i + 1}" else "✓", 170f, cy + 18f, tp)
            tp.textAlign = Paint.Align.LEFT
            val text = block("item$i", item, bold, 60f, 700f, rowH - 40f)
            c.save()
            c.translate(250f, cy - text.height / 2f)
            text.paint.alpha = alpha
            text.draw(c)
            text.paint.alpha = 255
            c.restore()
            c.restore()
        }
        // Glass look: a cursor hops to each item as it appears and clicks it.
        if (look.glass) {
            val shown = delays.indexOfLast { lt >= it }
            if (shown >= 0) {
                val y = y0 + shown * (rowH + gap) + rowH / 2
                val fromY = if (shown == 0) H else y0 + (shown - 1) * (rowH + gap) + rowH / 2
                cursorTo(c, lt - delays[shown], W - SIDE - 30f, fromY + 40f, W - SIDE - 90f, y + 20f, 0.3)
            }
        }
    }

    private fun compare(c: Canvas, s: Scene, lt: Double, dur: Double) {
        val second = (dur * 0.42).coerceIn(0.5, 2.2)
        val gap = 50f
        val h = (ZONE_BOTTOM - ZONE_TOP - gap) / 2
        compareHalf(c, lt, h, second, ZONE_TOP, s.left.ifBlank { "Myth" }, s.leftText, palette.bad, "✕", 0.0, strike = true)
        compareHalf(c, lt, h, second, ZONE_TOP + h + gap, s.right.ifBlank { "Fact" }, s.rightText, palette.good, "✓", second, strike = false)
        if (look.glass) toggle(c, W / 2, ZONE_TOP - 70f, outCubic((lt - second) / 0.25).toFloat(), clamp(lt / 0.3).toFloat())
    }

    private fun compareHalf(
        c: Canvas, lt: Double, h: Float, second: Double, top: Float,
        title: String, body: String, color: Int, mark: String, delay: Double, strike: Boolean,
    ) {
        val s = outBack((lt - delay) / 0.4).toFloat()
        if (s <= 0f) return
        c.save()
        val cy = top + h / 2
        c.translate(W / 2, cy)
        c.scale(s, s)
        c.translate(-W / 2, -cy)
        card(c, SIDE, top, W - SIDE, top + h, withAlpha(color, 40))
        stroke.strokeWidth = 6f
        stroke.color = color
        stroke.shader = null
        rect.set(SIDE, top, W - SIDE, top + h)
        c.drawRoundRect(rect, 44f, 44f, stroke)
        tp.typeface = black
        tp.textSize = 54f
        tp.color = color
        tp.textAlign = Paint.Align.LEFT
        c.drawText("$mark  ${upper(title)}", 140f, top + 90f, tp)
        drawBlock(c, block("cmp$title", body, bold, 70f, zoneW - 100f, h - 160f), W / 2, top + 110f + (h - 110f) / 2)
        // Cross out the myth once the fact appears.
        if (strike && lt > second + 0.2) {
            val a = outCubic((lt - second - 0.2) / 0.3).toFloat()
            stroke.strokeWidth = 12f
            stroke.color = color
            val y = top + 0.62f * h
            c.drawLine(130f, y, 130f + (zoneW - 80f) * a, y, stroke)
        }
        c.restore()
    }

    private fun statement(c: Canvas, s: Scene, lt: Double) {
        val a = outCubic(lt / 0.4).toFloat()
        val text = block(
            "stmt", highlighted(upper(s.text.ifBlank { s.say }), upper(s.highlight), palette.text, palette.accent),
            display, 118f, zoneW, 760f
        )
        fill.shader = null
        fill.color = palette.accent2
        val top = zoneMid - text.height / 2f
        if (look.paper) wavyLine(c, SIDE + 40f, W - SIDE - 40f, top + text.height + 40f, outCubic((lt - 0.2) / 0.5).toFloat())
        else c.drawRect(60f, top, 74f, top + text.height * a, fill)
        drawBlock(c, text, W / 2, zoneMid + (1 - a) * 60f, a)
    }

    private fun emojiScene(c: Canvas, s: Scene, lt: Double) {
        val cy = zoneMid - 120f
        for (i in 0 until 3) {
            val ph = (0.8 * lt + i / 3.0) % 1.0
            stroke.strokeWidth = 8f
            stroke.shader = null
            stroke.color = withAlpha(palette.accent2, (120 * (1 - ph)).toInt())
            c.drawCircle(W / 2, cy, (180 + 260 * ph).toFloat(), stroke)
        }
        val pop = outBack(lt / 0.45).toFloat()
        val wobble = 1f + sin(6 * lt).toFloat() * 0.04f
        emoji(c, s.emoji.ifBlank { "💡" }, W / 2, cy, pop * 300f * wobble)
        if (s.text.isBlank()) return
        drawBlock(c, block("emo", upper(s.text), display, 100f, zoneW, 300f), W / 2, cy + 360f, clamp((lt - 0.2) / 0.3).toFloat())
    }

    private fun question(c: Canvas, s: Scene, lt: Double) {
        val pop = outBack(lt / 0.45).toFloat()
        c.save()
        c.translate(W / 2, 560f)
        c.rotate((sin(3 * lt) * 6).toFloat())
        c.scale(pop, pop)
        tp.typeface = black
        tp.textSize = 400f
        tp.color = palette.accent
        tp.textAlign = Paint.Align.CENTER
        c.drawText("?", 0f, 140f, tp)
        tp.textAlign = Paint.Align.LEFT
        c.restore()
        val a = clamp((lt - 0.15) / 0.3).toFloat()
        drawBlock(c, block("q", upper(s.text.ifBlank { s.say }), display, 104f, zoneW, 360f), W / 2, 950f, a)
    }

    /** Full-screen stock video (or photo) with a slow zoom, a dark scrim for legibility, and its text. */
    private fun photo(c: Canvas, s: Scene, index: Int, lt: Double, dur: Double) {
        val m = media[index]?.get()
        if (m == null) {
            // No picture found: the brand's logo if there is one, else clean typography (never a stock emoji).
            when {
                logos[index] != null -> logoScene(c, s, index, lt)
                s.emoji.isNotBlank() -> emojiScene(c, s, lt)
                else -> statement(c, s.copy(text = s.text.ifBlank { s.say }), lt)
            }
            return
        }
        val frame = m.frameAt(lt)
        if (look.glass) { phoneMockup(c, s, frame, lt); return }
        // Slideshows change picture every couple of seconds with a quick crossfade.
        val prevFrame = (m as? SceneMedia.Slideshow)?.previousFrame(lt)
        val zoom = max(W / frame.width, H / frame.height) * (1.04f + 0.08f * clamp(lt / dur.coerceAtLeast(1.0)).toFloat())
        val w = frame.width * zoom
        val h = frame.height * zoom
        val left = (W - w) / 2 - (6 * lt).toFloat()
        val top = (H - h) / 2
        fill.shader = null
        fill.alpha = 255
        fill.isFilterBitmap = true
        if (prevFrame != null) {
            val (pf, fade) = prevFrame
            val pz = max(W / pf.width, H / pf.height) * 1.08f
            val pw = pf.width * pz
            val ph = pf.height * pz
            c.drawBitmap(pf, Rect(0, 0, pf.width, pf.height), RectF((W - pw) / 2, (H - ph) / 2, (W + pw) / 2, (H + ph) / 2), fill)
            fill.alpha = (255 * fade).toInt() // the new picture fades in over the old one
        }
        c.drawBitmap(frame, Rect(0, 0, frame.width, frame.height), RectF(left, top, left + w, top + h), fill)
        fill.alpha = 255
        // Darken top and bottom so the progress bar, text and captions read on any footage.
        scrim.shader = LinearGradient(
            0f, 0f, 0f, H,
            intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(40, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.argb(190, 0, 0, 0)),
            floatArrayOf(0f, 0.3f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, W, H, scrim)
        if (s.text.isBlank()) return
        val a = clamp((lt - 0.2) / 0.3).toFloat()
        val pop = spring((lt - 0.2) / 0.5).toFloat()
        // Pop-out: big text behind the subject, the subject lifted in front (only on the first picture).
        val cut = cutouts[index]
        val firstPicture = m is SceneMedia.Photo || (m is SceneMedia.Slideshow && prevFrame == null && frame === m.frameAt(0.0))
        val dst = RectF(left, top, left + w, top + h)
        val popLayout = if (cut != null && firstPicture) block("popout", upper(s.text), display, 130f, zoneW + 40f, 330f, palette.accent) else null
        // Only the bottom fifth of the letters tucks behind the subject's head, so the words stay readable.
        val ty = if (cut != null && popLayout != null) dst.top + cut.bounds.top * dst.height() - popLayout.height * 0.28f else 0f
        if (cut != null && popLayout != null && ty >= 190f + popLayout.height / 2f) {
            drawBlock(c, popLayout, W / 2, ty, a, 0.9f + 0.1f * pop)
            val lift = 1f + 0.025f * pop
            c.save()
            c.scale(lift, lift, dst.centerX(), dst.top + cut.bounds.bottom * dst.height())
            fill.alpha = (150 * a).toInt()
            c.drawBitmap(cut.shadow, Rect(0, 0, cut.shadow.width, cut.shadow.height), RectF(dst.left, dst.top + 14f, dst.right, dst.bottom + 14f), fill)
            fill.alpha = 255
            c.drawBitmap(cut.bitmap, Rect(0, 0, cut.bitmap.width, cut.bitmap.height), dst, fill)
            c.restore()
            return
        }
        drawBlock(c, block("photo", upper(s.text), display, 96f, zoneW, 300f), W / 2, ZONE_TOP + 160f, a, 0.85f + 0.15f * pop)
    }

    // ---------------- Logos ----------------

    private val logoPaths = HashMap<String, Path?>()
    private val logoBitmaps = HashMap<String, Bitmap?>()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun luminance(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0

    private fun shade(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255), (Color.green(c) * f).toInt().coerceIn(0, 255), (Color.blue(c) * f).toInt().coerceIn(0, 255)
    )

    private fun brandColor(logo: Logo) = (logo as? Logo.Vector)?.color ?: palette.accent

    /**
     * An app-icon style tile: brand-colour gradient with the logo in white (dark logo on light
     * brands), a soft glow behind it and a light sweep across it. [shineT] runs 0..1 for the sweep.
     */
    private fun drawLogoTile(c: Canvas, logo: Logo, cx: Float, cy: Float, size: Float, shineT: Float, alpha: Int = 255) {
        val r = size * 0.24f
        val half = size / 2
        val brand = brandColor(logo)
        // Glow.
        glow.shader = RadialGradient(cx, cy, size * 0.95f, withAlpha(brand, (alpha * 0.55).toInt()), 0, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, size * 0.95f, glow)
        // Tile.
        rect.set(cx - half, cy - half, cx + half, cy + half)
        val raster = logo is Logo.Raster
        val top = if (raster) Color.WHITE else if (luminance(brand) < 0.12) Color.rgb(48, 50, 64) else shade(brand, 1.15f)
        val bottom = if (raster) Color.rgb(232, 234, 240) else if (luminance(brand) < 0.12) Color.rgb(14, 15, 22) else shade(brand, 0.62f)
        fill.shader = LinearGradient(cx, cy - half, cx, cy + half, top, bottom, Shader.TileMode.CLAMP)
        fill.alpha = alpha
        c.drawRoundRect(rect, r, r, fill)
        fill.shader = null
        stroke.shader = null
        stroke.strokeWidth = size * 0.012f
        stroke.color = Color.argb((alpha * 0.35).toInt(), 255, 255, 255)
        c.drawRoundRect(rect, r, r, stroke)
        // Mark.
        val inner = size * 0.58f
        when (logo) {
            is Logo.Vector -> {
                val path = logoPaths.getOrPut(logo.pathData) {
                    runCatching { androidx.core.graphics.PathParser.createPathFromPathData(logo.pathData) }.getOrNull()
                }
                if (path != null) {
                    c.save()
                    c.translate(cx - inner / 2, cy - inner / 2)
                    c.scale(inner / 24f, inner / 24f)
                    fill.color = if (luminance(top) > 0.62) Color.rgb(17, 17, 17) else Color.WHITE
                    fill.alpha = alpha
                    c.drawPath(path, fill)
                    c.restore()
                }
            }
            is Logo.Raster -> {
                val bmp = logoBitmaps.getOrPut(logo.file.absolutePath) { runCatching { BitmapFactory.decodeFile(logo.file.absolutePath) }.getOrNull() }
                if (bmp != null) {
                    val sc = inner / max(bmp.width, bmp.height)
                    val w = bmp.width * sc
                    val h = bmp.height * sc
                    fill.alpha = alpha
                    fill.isFilterBitmap = true
                    c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), fill)
                }
            }
        }
        fill.alpha = 255
        // Light sweep, clipped to the tile.
        if (shineT in 0f..1f) {
            c.save()
            path.reset()
            path.addRoundRect(rect, r, r, Path.Direction.CW)
            c.clipPath(path)
            val x = cx - size + 2 * size * shineT
            shine.shader = LinearGradient(x - size * 0.25f, cy - half, x + size * 0.25f, cy + half,
                intArrayOf(0, Color.argb((alpha * 0.45).toInt(), 255, 255, 255), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(rect, shine)
            c.restore()
        }
    }

    /** A brand takes the stage: tile pops in on a spring, tilts upright, rings pulse, light sweeps. */
    private fun logoScene(c: Canvas, s: Scene, index: Int, lt: Double) {
        val logo = logos[index]
        if (logo == null) {
            statement(c, s.copy(text = s.text.ifBlank { s.brand.ifBlank { s.say } }), lt)
            return
        }
        val cy = zoneMid - 120f
        val brand = brandColor(logo)
        for (i in 0 until 3) {
            val ph = (0.6 * lt + i / 3.0) % 1.0
            stroke.strokeWidth = 5f
            stroke.shader = null
            stroke.color = withAlpha(if (luminance(brand) < 0.12) palette.accent2 else brand, (110 * (1 - ph)).toInt())
            c.drawCircle(W / 2, cy, (300 + 260 * ph).toFloat(), stroke)
        }
        val pop = spring(lt / 0.7).toFloat()
        val tilt = (-10 * (1 - outCubic(lt / 0.6))).toFloat()
        c.save()
        c.rotate(tilt, W / 2, cy)
        c.scale(pop, pop, W / 2, cy)
        drawLogoTile(c, logo, W / 2, cy + (sin(2.2 * lt) * 6).toFloat(), 470f, ((lt - 0.45) / 0.8).toFloat())
        c.restore()
        val name = s.brand.ifBlank { logo.title }
        val a = clamp((lt - 0.3) / 0.3).toFloat()
        drawBlock(c, block("logoname", upper(name), display, 108f, zoneW, 160f), W / 2, cy + 400f, a)
        if (s.text.isNotBlank() && !s.text.equals(name, true)) {
            val b = clamp((lt - 0.5) / 0.3).toFloat()
            drawBlock(c, block("logotext", s.text, bold, 60f, zoneW, 170f, palette.muted), W / 2, cy + 540f, b)
        }
    }

    /** A small glass pill with the logo and name, for scenes that mention a brand. */
    private fun badge(c: Canvas, s: Scene, index: Int, lt: Double) {
        if (s.type == "logo" || s.brand.isBlank()) return
        if (index == 0 && seriesName.isNotBlank()) return // the series badge sits there
        if (s.type == "photo" && media[index]?.get() == null) return // that scene already became the logo scene
        val logo = logos[index] ?: return
        val pop = spring((lt - 0.15) / 0.6).toFloat()
        if (pop <= 0.01f) return
        tp.typeface = bold
        tp.textSize = 40f
        val name = s.brand.take(22)
        val tw = tp.measureText(name)
        val h = 92f
        val w = h + 20f + tw + 36f
        val cy = 244f
        c.save()
        c.scale(pop, pop, W / 2, cy)
        fill.shader = null
        fill.color = Color.argb(150, 10, 12, 22)
        rect.set(W / 2 - w / 2, cy - h / 2, W / 2 + w / 2, cy + h / 2)
        c.drawRoundRect(rect, h / 2, h / 2, fill)
        stroke.shader = null
        stroke.strokeWidth = 2f
        stroke.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(rect, h / 2, h / 2, stroke)
        drawLogoTile(c, logo, W / 2 - w / 2 + h / 2 + 6f, cy, h - 20f, -1f)
        tp.color = Color.WHITE
        tp.textAlign = Paint.Align.LEFT
        c.drawText(name, W / 2 - w / 2 + h + 20f, cy + 14f, tp)
        c.restore()
    }

    // ---------------- Data scenes ----------------

    /** "Label: number" -> label and number (commas and units allowed). */
    private fun parseItem(item: String): Pair<String, Double?> {
        val i = item.lastIndexOf(':')
        if (i <= 0) return item to null
        val num = Regex("-?[0-9][0-9,]*\\.?[0-9]*").find(item.substring(i + 1))?.value?.replace(",", "")?.toDoubleOrNull()
        return item.substring(0, i).trim() to num
    }

    /** Animated horizontal bar chart: bars grow one after another, numbers count up. */
    private fun chart(c: Canvas, s: Scene, lt: Double, dur: Double) {
        val parsed = s.items.take(5).map { parseItem(it) }
        val values = parsed.mapNotNull { it.second }
        if (values.size < 2) { list(c, s, lt, dur, numbered = false); return }
        var top = ZONE_TOP + 20f
        if (s.text.isNotBlank()) {
            val title = block("charttitle", upper(s.text), display, 80f, zoneW, 200f, palette.accent)
            drawBlock(c, title, W / 2, top + title.height / 2f, outCubic(lt / 0.3).toFloat())
            top += title.height + 60f
        }
        val maxV = values.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        val rowH = min((ZONE_BOTTOM - top) / parsed.size, 240f)
        val delays = itemDelays(parsed.size, dur)
        val barLeft = SIDE
        val barMax = W - 2 * SIDE
        parsed.forEachIndexed { i, (label, v) ->
            val grow = outCubic((lt - delays[i]) / 0.7).toFloat()
            if (grow <= 0f) return@forEachIndexed
            val y = top + i * rowH
            tp.typeface = bold
            tp.textSize = 56f
            tp.color = withAlpha(Color.WHITE, (255 * min(1f, grow * 2)).toInt())
            tp.textAlign = Paint.Align.LEFT
            c.drawText(label.take(26), barLeft, y + 54f, tp)
            val frac = ((v ?: 0.0) / maxV).toFloat().coerceIn(0.03f, 1f)
            val bw = barMax * frac * grow
            val by = y + 76f
            val bh = (rowH - 104f).coerceIn(56f, 110f)
            fill.color = Color.WHITE // full opacity; earlier drawing may have left the paint translucent
            fill.shader = LinearGradient(barLeft, 0f, barLeft + barMax, 0f, palette.accent, palette.accent2, Shader.TileMode.CLAMP)
            rect.set(barLeft, by, barLeft + bw, by + bh)
            c.drawRoundRect(rect, 18f, 18f, fill)
            fill.shader = null
            val shown = (v ?: 0.0) * grow
            val txt = if ((v ?: 0.0) % 1.0 == 0.0) String.format(Locale.US, "%,d", shown.toLong()) else String.format(Locale.US, "%.1f", shown)
            tp.typeface = black
            tp.textSize = 58f
            tp.color = Color.WHITE
            val tw = tp.measureText(txt)
            val inside = bw > tw + 40f
            tp.color = if (inside) Color.BLACK else Color.WHITE
            c.drawText(txt, if (inside) barLeft + bw - tw - 20f else barLeft + bw + 16f, by + bh / 2 + 20f, tp)
        }
    }

    /** Vertical timeline drawn downwards; each event pops in on its dot. */
    private fun timelineScene(c: Canvas, s: Scene, lt: Double, dur: Double) {
        val items = s.items.take(4)
        if (items.isEmpty()) { statement(c, s, lt); return }
        var top = ZONE_TOP + 20f
        if (s.text.isNotBlank()) {
            val title = block("tltitle", upper(s.text), display, 80f, zoneW, 200f, palette.accent)
            drawBlock(c, title, W / 2, top + title.height / 2f, outCubic(lt / 0.3).toFloat())
            top += title.height + 60f
        }
        val step = min((ZONE_BOTTOM - top) / items.size, 230f)
        val lineX = SIDE + 50f
        val delays = itemDelays(items.size, dur)
        val lineEnd = top + step * (items.size - 1) * outCubic(lt / (delays.last() + 0.5)).toFloat()
        stroke.shader = null
        stroke.strokeWidth = 8f
        stroke.color = withAlpha(palette.accent2, 160)
        c.drawLine(lineX, top + 30f, lineX, lineEnd + 30f, stroke)
        items.forEachIndexed { i, item ->
            val k = spring((lt - delays[i]) / 0.5).toFloat()
            if (k <= 0.01f) return@forEachIndexed
            val y = top + i * step + 30f
            fill.shader = null
            fill.color = palette.accent
            c.drawCircle(lineX, y, 22f * k, fill)
            val parts = item.split(Regex("\\s+[—–-]\\s+|:\\s+"), limit = 2)
            val when_ = parts[0].trim()
            val what = parts.getOrElse(1) { "" }.trim()
            c.save()
            c.translate((1 - k) * 80f, 0f)
            tp.typeface = black
            tp.textSize = 70f
            tp.color = palette.accent
            tp.textAlign = Paint.Align.LEFT
            c.drawText(when_.take(16), lineX + 60f, y + 24f, tp)
            if (what.isNotBlank()) {
                val body = block("tl$i", what, bold, 62f, W - lineX - 60f - SIDE, step - 90f)
                c.save()
                c.translate(lineX + 60f, y + 50f)
                body.draw(c)
                c.restore()
            }
            c.restore()
        }
    }

    /** A quote card: big quote marks, the words, and the speaker with their photo in a ring. */
    private fun quote(c: Canvas, s: Scene, index: Int, lt: Double) {
        val m = media[index]?.get()
        val cy = zoneMid - 60f
        if (m != null) {
            val k = spring(lt / 0.6).toFloat()
            val r = 150f * k
            val f = m.frameAt(lt)
            c.save()
            path.reset()
            path.addCircle(W / 2, ZONE_TOP + 170f, r, Path.Direction.CW)
            c.clipPath(path)
            val sc = max(2 * r / f.width, 2 * r / f.height)
            val w = f.width * sc
            val h = f.height * sc
            fill.shader = null
            fill.alpha = 255
            fill.isFilterBitmap = true
            // Faces sit in the upper part of portraits: anchor a little above centre.
            c.drawBitmap(f, Rect(0, 0, f.width, f.height), RectF(W / 2 - w / 2, ZONE_TOP + 170f - h * 0.38f, W / 2 + w / 2, ZONE_TOP + 170f + h * 0.62f), fill)
            c.restore()
            stroke.shader = null
            stroke.strokeWidth = 8f
            stroke.color = palette.accent
            c.drawCircle(W / 2, ZONE_TOP + 170f, r + 6f, stroke)
        }
        tp.typeface = black
        tp.textSize = 260f
        tp.color = withAlpha(palette.accent, 90)
        tp.textAlign = Paint.Align.CENTER
        c.drawText("\u201C", W / 2, cy - 40f, tp)
        tp.textAlign = Paint.Align.LEFT
        val a = clamp((lt - 0.15) / 0.35).toFloat()
        val textCy = if (m != null) cy + 160f else cy + 60f
        drawBlock(c, block("quote", s.text.ifBlank { s.say }, black, 96f, zoneW, 440f), W / 2, textCy, a)
        if (s.label.isNotBlank()) {
            val b = clamp((lt - 0.45) / 0.3).toFloat()
            drawBlock(c, block("quoteby", "— " + s.label, bold, 52f, zoneW, 100f, palette.accent), W / 2, textCy + 270f, b)
        }
    }

    private fun outro(c: Canvas, s: Scene, lt: Double) {
        if (look.glass) { glassOutro(c, s, lt); return }
        val e = s.emoji.ifBlank { scenes.firstOrNull()?.emoji ?: "" }.ifBlank { "💬" }
        emoji(c, e, W / 2, 490f + (sin(4 * lt) * 10).toFloat(), outBack(lt / 0.45).toFloat() * 220f)
        drawBlock(
            c, block("outro", upper(s.text.ifBlank { s.say }), display, 112f, zoneW, 520f),
            W / 2, zoneMid + 90f, clamp((lt - 0.1) / 0.3).toFloat()
        )
        val a = clamp((lt - 0.5) / 0.3).toFloat()
        if (a > 0f) {
            val label = "COMMENT BELOW 👇"
            tp.typeface = bold
            tp.textSize = 52f
            val half = (tp.measureText(label) + 80f) / 2
            val alpha = (255 * a).toInt()
            val pop = spring((lt - 0.5) / 0.5).toFloat()
            c.save()
            c.scale(pop, pop, W / 2, 1150f)
            card(c, W / 2 - half, 1100f, W / 2 + half, 1200f, withAlpha(palette.accent, alpha), 50f)
            tp.color = Color.BLACK
            tp.alpha = alpha
            tp.textAlign = Paint.Align.CENTER
            c.drawText(label, W / 2, 1168f, tp)
            tp.textAlign = Paint.Align.LEFT
            c.restore()
        }
    }

    // ---------------- Hook intro (first ~5 seconds) ----------------

    private val hookColors: List<Int> = run {
        val base = listOf(
            Color.rgb(255, 61, 113), Color.rgb(255, 214, 10), Color.rgb(0, 229, 255),
            Color.rgb(124, 255, 107), Color.rgb(179, 136, 255), Color.rgb(255, 138, 0),
        )
        val k = Math.floorMod(scenes.firstOrNull()?.say?.hashCode() ?: 0, base.size)
        base.drop(k) + base.take(k)
    }

    /** When the hook's key word is spoken (its emphasis word, else its last word). */
    private val hookWordAt: Double = run {
        val end = timeline.spans.firstOrNull()?.end ?: 0.0
        emphasisTimes.firstOrNull { it < end } ?: timeline.words.lastOrNull { it.scene == 0 }?.start ?: 0.6
    }

    private val hookWord: String = run {
        val w = timeline.words.firstOrNull { it.scene == 0 && kotlin.math.abs(it.start - hookWordAt) < 1e-6 }?.text
            ?: scenes.firstOrNull()?.emphasis ?: ""
        upper(w.trim(',', '.', '!', '?', ';', ':', '"'))
    }

    private data class Bit(val angle: Double, val speed: Double, val size: Float, val color: Int, val shape: Int, val spin: Double)

    private val confetti: List<Bit> = run {
        val r = java.util.Random((scenes.firstOrNull()?.say?.hashCode() ?: 1).toLong())
        List(42) {
            Bit(r.nextDouble() * 2 * Math.PI, 900 + r.nextDouble() * 1500, 14f + r.nextFloat() * 22f,
                hookColors[it % hookColors.size], it % 3, (r.nextDouble() - 0.5) * 14)
        }
    }

    private fun shake(t: Double): Pair<Float, Float> {
        var a = 0.0
        if (t in 0.03..0.5) a = 22 * kotlin.math.exp(-(t - 0.03) * 9)
        val w = t - hookWordAt
        if (w in 0.0..0.35) a = max(a, 14 * kotlin.math.exp(-w * 11))
        if (a < 0.3) return 0f to 0f
        return (a * sin(t * 97)).toFloat() to (a * cos(t * 83)).toFloat()
    }

    /** Vivid rotating sunburst behind the hook: loud colour in the first frames, fading out by ~5 s. */
    private fun hookBackdrop(c: Canvas, t: Double) {
        if (t > HOOK_FX_END) return
        val fade = (1 - clamp((t - 1.6) / (HOOK_FX_END - 1.6))).toFloat()
        val strength = (if (look.glass || look.paper) 0.55f else 0.75f) * fade
        if (strength <= 0.01f) return
        val cx = W / 2
        val cy = zoneMid - 60f
        val rays = 16
        val rot = (t * 22).toFloat()
        fill.shader = null
        for (i in 0 until rays) {
            val a0 = Math.toRadians((i * 360.0 / rays) + rot)
            val a1 = Math.toRadians(((i + 0.5) * 360.0 / rays) + rot)
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + (2400 * kotlin.math.cos(a0)).toFloat(), cy + (2400 * sin(a0)).toFloat())
            path.lineTo(cx + (2400 * kotlin.math.cos(a1)).toFloat(), cy + (2400 * sin(a1)).toFloat())
            path.close()
            fill.color = withAlpha(hookColors[i % 3], (70 * strength).toInt())
            c.drawPath(path, fill)
        }
        // A soft colour bloom at the centre so the text stays readable.
        fill.shader = RadialGradient(cx, cy, 700f, intArrayOf(withAlpha(palette.bgTop, (230 * strength).toInt()), withAlpha(palette.bgTop, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, 700f, fill)
        fill.shader = null
    }

    /** RGB split: the scene drawn again in red and cyan, offset sideways, for a few frames. */
    private fun glitch(c: Canvas, index: Int, lt: Double, dur: Double, t: Double) {
        val off = 16f
        for ((dx, matrix) in listOf(
            -off to floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f),
            off to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f),
        )) {
            val p = Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(matrix)) }
            c.saveLayer(0f, 0f, W, H, p)
            c.translate(dx, 0f)
            drawScene(c, index, lt, dur, t, alpha = 1f, scale = 1f)
            c.restore()
        }
    }

    private fun hookOverlay(c: Canvas, t: Double) {
        if (t > HOOK_FX_END + 0.5) return
        val cx = W / 2
        val cy = zoneMid - 60f
        // Confetti burst.
        val ct = t - 0.05
        if (ct in 0.0..1.8) {
            val a = (1 - clamp((ct - 1.1) / 0.7)).toFloat()
            for (b in confetti) {
                val d = b.speed * (1 - kotlin.math.exp(-ct * 3.2)) / 3.2
                val x = cx + (d * kotlin.math.cos(b.angle)).toFloat()
                val y = cy + (d * sin(b.angle)).toFloat() + (380 * ct * ct).toFloat()
                fill.shader = null
                fill.color = withAlpha(b.color, (255 * a).toInt())
                c.save()
                c.translate(x, y)
                c.rotate((b.spin * ct * 60).toFloat())
                when (b.shape) {
                    0 -> c.drawCircle(0f, 0f, b.size / 2, fill)
                    1 -> c.drawRect(-b.size / 2, -b.size / 4, b.size / 2, b.size / 4, fill)
                    else -> { path.reset(); path.moveTo(0f, -b.size / 2); path.lineTo(b.size / 2, b.size / 2); path.lineTo(-b.size / 2, b.size / 2); path.close(); c.drawPath(path, fill) }
                }
                c.restore()
            }
        }
        // Shockwave rings: at the start and when the key word lands.
        for ((at, col) in listOf(0.05 to hookColors[0], hookWordAt to hookColors[1])) {
            val rt = t - at
            if (rt !in 0.0..0.6) continue
            val k = (rt / 0.6).toFloat()
            stroke.shader = null
            stroke.strokeWidth = 26f * (1 - k) + 2f
            stroke.color = withAlpha(col, (230 * (1 - k)).toInt())
            c.drawCircle(cx, cy, 120f + 900f * outCubic(k.toDouble()).toFloat(), stroke)
        }
        // Key-word sticker: slams in, tilted, in a loud colour, and stays through the hook.
        val st = t - hookWordAt
        val hookEnd = timeline.spans.firstOrNull()?.end ?: 0.0
        if (hookWord.isNotBlank() && st >= 0 && t < hookEnd + 0.1) {
            val k = outBack(st / 0.22).toFloat()
            tp.typeface = black
            tp.textSize = 96f
            tp.shader = null
            tp.clearShadowLayer()
            val w = tp.measureText(hookWord) + 70f
            val h = 140f
            c.save()
            c.translate(cx, ZONE_TOP + 40f)
            c.rotate(-7f + 3f * sin(t * 3).toFloat())
            val sc = 2.2f - 1.2f * k
            c.scale(sc, sc)
            shadowPaint.color = Color.argb(120, 0, 0, 0)
            rect.set(-w / 2 + 10f, -h / 2 + 14f, w / 2 + 10f, h / 2 + 14f)
            c.drawRoundRect(rect, 26f, 26f, shadowPaint)
            fill.shader = null
            fill.color = hookColors[1]
            rect.set(-w / 2, -h / 2, w / 2, h / 2)
            c.drawRoundRect(rect, 26f, 26f, fill)
            stroke.strokeWidth = 8f
            stroke.color = Color.rgb(20, 20, 20)
            c.drawRoundRect(rect, 26f, 26f, stroke)
            tp.color = Color.rgb(20, 20, 20)
            tp.textAlign = Paint.Align.CENTER
            c.drawText(hookWord, 0f, 34f, tp)
            tp.textAlign = Paint.Align.LEFT
            c.restore()
        }
        // Impact flash on the second frame (frame 1 stays clean for the feed preview).
        if (t in 0.03..0.2) {
            fill.shader = null
            fill.color = Color.argb((150 * (1 - (t - 0.03) / 0.17)).toInt(), 255, 255, 255)
            c.drawRect(0f, 0f, W, H, fill)
        }
        // Colour-stripe wipe across the cut from the hook to the next scene.
        if (timeline.spans.size > 1) {
            val wt = t - (timeline.spans[1].start - 0.14)
            val dur = 0.42
            if (wt in 0.0..dur) {
                val k = (wt / dur).toFloat()
                for (i in 0 until 3) {
                    val lag = i * 0.12f
                    val p = ((k - lag) / (1 - 0.24f)).coerceIn(0f, 1f)
                    if (p <= 0f || p >= 1f) continue
                    val x = -W * 1.2f + p * W * 3.2f
                    c.save()
                    c.rotate(-18f, W / 2, H / 2)
                    fill.shader = null
                    fill.color = hookColors[i + 2]
                    c.drawRect(x - 260f, -400f, x + 260f, H + 400f, fill)
                    c.restore()
                }
            }
        }
    }

    // ---------------- Paper sketch look ----------------

    /** Repeatable "hand" wobble: the same shape wobbles the same way every frame (no flicker). */
    private fun wob(seed: Int, i: Int, amp: Float): Float {
        val x = sin(seed * 12.9898 + i * 78.233) * 43758.5453
        return ((x - kotlin.math.floor(x)) * 2 - 1).toFloat() * amp
    }

    private fun paperBackground(c: Canvas, t: Double) {
        fill.color = Color.WHITE
        fill.shader = LinearGradient(0f, 0f, 0f, H, palette.bgTop, palette.bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, fill)
        fill.shader = null
        stroke.shader = null
        stroke.strokeWidth = 2f
        stroke.color = Color.argb(38, 29, 78, 216)
        var y = 168f
        while (y < H) { c.drawLine(0f, y, W, y, stroke); y += 72f }
        stroke.color = Color.argb(70, 230, 57, 70)
        c.drawLine(92f, 0f, 92f, H, stroke)
    }

    /** A card drawn by hand: soft paper fill and a double, slightly wobbly ink outline. */
    private fun sketchCard(c: Canvas, l: Float, t: Float, r: Float, b: Float, k: Float = 1f) {
        if (k <= 0.01f) return
        val seed = (l * 7 + t * 13).toInt()
        fill.shader = null
        fill.color = Color.argb((170 * k).toInt(), 255, 255, 255)
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, 18f, 18f, fill)
        stroke.shader = null
        stroke.strokeWidth = 4f
        stroke.strokeJoin = Paint.Join.ROUND
        for (pass in 0..1) {
            stroke.color = withAlpha(palette.text, ((if (pass == 0) 220 else 120) * k).toInt())
            path.reset()
            path.moveTo(l + wob(seed, pass * 8, 5f), t + wob(seed, pass * 8 + 1, 5f))
            path.lineTo(r + wob(seed, pass * 8 + 2, 5f), t + wob(seed, pass * 8 + 3, 5f))
            path.lineTo(r + wob(seed, pass * 8 + 4, 5f), b + wob(seed, pass * 8 + 5, 5f))
            path.lineTo(l + wob(seed, pass * 8 + 6, 5f), b + wob(seed, pass * 8 + 7, 5f))
            path.close()
            c.drawPath(path, stroke)
        }
    }

    /** A marker circle drawn on around a number, overlapping where it closes, like a real pen stroke. */
    private fun scribbleCircle(c: Canvas, cx: Float, cy: Float, r: Float, k: Float) {
        if (k <= 0f) return
        stroke.shader = null
        stroke.strokeWidth = 14f
        stroke.color = palette.accent
        path.reset()
        val steps = (72 * 1.12f * k).toInt().coerceAtLeast(2)
        for (i in 0..steps) {
            val a = -Math.PI / 2 + i * (2 * Math.PI / 72)
            val rr = r * (1 + 0.04f * sin(i * 0.35).toFloat() + i * 0.0009f)
            val x = cx + (rr * kotlin.math.cos(a)).toFloat()
            val y = cy + (rr * 0.92f * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, stroke)
    }

    /** A wavy marker underline drawn from left to right. */
    private fun wavyLine(c: Canvas, x0: Float, x1: Float, y: Float, k: Float) {
        if (k <= 0f) return
        stroke.shader = null
        stroke.strokeWidth = 10f
        stroke.color = palette.accent
        path.reset()
        var x = x0
        path.moveTo(x, y)
        while (x < x0 + (x1 - x0) * k) {
            x += 12f
            path.lineTo(x, y + 9f * sin((x - x0) / 34.0).toFloat())
        }
        c.drawPath(path, stroke)
    }

    /** Marker captions: ink words; the spoken word gets a yellow highlighter swipe, punch words go red. */
    private fun markerCaptions(c: Canvas, t: Double) {
        val g = timeline.captionAt(t) ?: return
        val texts = g.words.map { it.text.trim() }
        tp.typeface = display
        tp.textSize = 80f
        tp.style = Paint.Style.FILL
        tp.shader = null
        tp.clearShadowLayer()
        val space = tp.measureText(" ")
        var widths = texts.map { tp.measureText(it) }
        var total = widths.sum() + (texts.size - 1) * space
        if (total > zoneW) {
            tp.textSize *= zoneW / total
            widths = texts.map { tp.measureText(it) }
            total = widths.sum() + (texts.size - 1) * space
        }
        val size = tp.textSize
        var x = W / 2 - total / 2
        texts.forEachIndexed { i, word ->
            val start = g.words[i].start
            val nextStart = g.words.getOrNull(i + 1)?.start ?: g.end
            val active = t >= start && t < nextStart
            val emph = emphasisTimes.any { kotlin.math.abs(it - start) < 1e-6 }
            if (active) {
                val k = outCubic((t - start) / 0.14).toFloat()
                fill.shader = null
                fill.color = Color.argb(185, 255, 224, 102)
                path.reset()
                val top = CAPTION_Y - size * 0.72f
                val bot = CAPTION_Y + size * 0.16f
                val right = x - 10f + (widths[i] + 20f) * k
                path.moveTo(x - 12f, top + 6f); path.lineTo(right, top); path.lineTo(right + 4f, bot - 4f); path.lineTo(x - 8f, bot)
                path.close()
                c.drawPath(path, fill)
            }
            tp.color = if (emph) palette.accent else palette.text
            c.drawText(word, x, CAPTION_Y, tp)
            x += widths[i] + space
        }
    }

    // ---------------- Kinetic typography look ----------------

    private fun kineticBackground(c: Canvas, t: Double) {
        fill.shader = null
        fill.color = Color.rgb(9, 9, 14)
        c.drawRect(0f, 0f, W, H, fill)
        val x = W / 2 + (180 * sin(0.3 * t)).toFloat()
        fill.shader = RadialGradient(x, H * 0.42f, 900f, intArrayOf(withAlpha(palette.accent, 46), 0), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, H * 0.42f, 900f, fill)
        fill.shader = null
    }

    /** The spoken words of this scene stack up huge, one slamming in as it is said; older lines scroll up. */
    private fun kineticScene(c: Canvas, index: Int, lt: Double) {
        val span = timeline.spans[index]
        val t = span.start + lt
        val words = timeline.words.filter { it.scene == index }
        if (words.isEmpty()) { statement(c, scenes[index], lt); return }
        val spoken = words.filter { it.start <= t + 0.02 }.ifEmpty { words.take(1) }
        // Lines of 1-2 words; long words and punch words get a line of their own.
        val lines = ArrayList<MutableList<TimedWord>>()
        for (w in spoken) {
            val emph = emphasisTimes.any { kotlin.math.abs(it - w.start) < 1e-6 }
            val last = lines.lastOrNull()
            if (last == null || emph || last.size >= 2 || w.text.length > 8 ||
                emphasisTimes.any { e -> last.any { kotlin.math.abs(e - it.start) < 1e-6 } }) lines.add(mutableListOf(w)) else last.add(w)
        }
        tp.typeface = display
        tp.style = Paint.Style.FILL
        tp.shader = null
        tp.textAlign = Paint.Align.CENTER
        val layout = lines.mapIndexed { i, line ->
            val emph = line.any { w -> emphasisTimes.any { kotlin.math.abs(it - w.start) < 1e-6 } }
            val text = upper(line.joinToString(" ") { it.text.trim(',', '.', '!', '?', ';', ':') })
            var size = if (emph) 230f else if (i % 2 == 0) 170f else 130f
            tp.textSize = size
            val w = tp.measureText(text)
            if (w > zoneW) size *= zoneW / w
            Triple(text, size, emph)
        }
        val gap = 18f
        val heights = layout.map { it.second * 1.02f + gap }
        var total = heights.sum()
        // Keep the newest line inside the zone: older ones scroll up and fade out.
        var y = (zoneMid - total / 2).coerceAtMost(ZONE_BOTTOM - total)
        val newest = spoken.last()
        layout.forEachIndexed { i, (text, size, emph) ->
            val h = heights[i]
            val baseline = y + size * 0.82f
            y += h
            val fade = clamp(((baseline - ZONE_TOP + 120) / 220).toDouble()).toFloat()
            if (fade <= 0f) return@forEachIndexed
            val isNew = lines[i].contains(newest)
            val k = if (isNew) outBack((t - newest.start) / 0.16).toFloat() else 1f
            tp.textSize = size
            tp.color = withAlpha(if (emph) palette.accent else if (i % 2 == 0) Color.WHITE else palette.accent2, (255 * fade).toInt())
            c.save()
            c.translate(W / 2, baseline - size * 0.35f)
            val sc = if (isNew) 1.45f - 0.45f * k else 1f
            c.scale(sc, sc)
            if (isNew) c.rotate((1 - k) * -4f)
            c.drawText(text, 0f, size * 0.35f, tp)
            c.restore()
        }
        tp.textAlign = Paint.Align.LEFT
    }

    // ---------------- Tier list ----------------

    private val tierColors = mapOf(
        "S" to Color.rgb(255, 77, 77), "A" to Color.rgb(255, 159, 67), "B" to Color.rgb(255, 212, 59),
        "C" to Color.rgb(81, 207, 102), "D" to Color.rgb(77, 171, 247),
    )

    /** S/A/B/C/D rows; each item pops into its row in the order the script lists them. */
    private fun tier(c: Canvas, s: Scene, lt: Double, dur: Double) {
        val parsed = s.items.take(6).mapNotNull { item ->
            val i = item.indexOf(':')
            if (i <= 0) null else item.substring(0, i).trim().uppercase(Locale.ROOT).take(1) to item.substring(i + 1).trim()
        }.filter { it.first in tierColors && it.second.isNotBlank() }
        if (parsed.size < 2) { list(c, s, lt, dur, numbered = true); return }
        var top = ZONE_TOP + 10f
        if (s.text.isNotBlank()) {
            val title = block("tiertitle", upper(s.text), display, 80f, zoneW, 190f, palette.accent)
            drawBlock(c, title, W / 2, top + title.height / 2f, outCubic(lt / 0.3).toFloat())
            top += title.height + 40f
        }
        val tiers = listOf("S", "A", "B", "C", "D").filter { t -> t != "D" || parsed.any { it.first == "D" } }
        val rowH = min((ZONE_BOTTOM - top) / tiers.size - 14f, 170f)
        val delays = itemDelays(parsed.size, dur)
        val used = HashMap<String, Float>()
        tiers.forEachIndexed { r, t ->
            val y = top + r * (rowH + 14f)
            val a = outCubic((lt - r * 0.06) / 0.3).toFloat()
            card(c, SIDE - 40f, y, W - SIDE + 40f, y + rowH, withAlpha(palette.card, (Color.alpha(palette.card) * a).toInt()), 24f)
            fill.shader = null
            fill.color = withAlpha(tierColors.getValue(t), (255 * a).toInt())
            rect.set(SIDE - 40f, y, SIDE - 40f + rowH, y + rowH)
            c.drawRoundRect(rect, 24f, 24f, fill)
            tp.typeface = black; tp.textSize = rowH * 0.55f; tp.color = withAlpha(Color.rgb(20, 20, 20), (255 * a).toInt())
            tp.textAlign = Paint.Align.CENTER
            c.drawText(t, SIDE - 40f + rowH / 2, y + rowH / 2 + rowH * 0.2f, tp)
            tp.textAlign = Paint.Align.LEFT
            used[t] = SIDE - 40f + rowH + 20f
        }
        parsed.forEachIndexed { i, (t, name) ->
            val k = outBack((lt - delays[i]) / 0.35).toFloat()
            if (k <= 0f) return@forEachIndexed
            val r = tiers.indexOf(t)
            val y = top + r * (rowH + 14f)
            tp.typeface = bold
            tp.textSize = min(48f, rowH * 0.34f)
            val label = TextUtils.ellipsize(name, tp, 420f, TextUtils.TruncateAt.END).toString()
            val w = tp.measureText(label) + 44f
            val x = used.getValue(t)
            if (x + w > W - SIDE + 30f) return@forEachIndexed
            used[t] = x + w + 14f
            c.save()
            c.scale(k, k, x + w / 2, y + rowH / 2)
            fill.shader = null
            fill.color = withAlpha(tierColors.getValue(t), 60)
            rect.set(x, y + 18f, x + w, y + rowH - 18f)
            c.drawRoundRect(rect, 20f, 20f, fill)
            tp.color = palette.text
            c.drawText(label, x + 22f, y + rowH / 2 + tp.textSize * 0.35f, tp)
            c.restore()
        }
    }

    // ---------------- Liquid glass look ----------------

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = android.graphics.BlurMaskFilter(34f, android.graphics.BlurMaskFilter.Blur.NORMAL) }

    /** Frosted glass panel: soft shadow, translucent body with a top highlight, bright hairline border. */
    private fun glassCard(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float = 44f, k: Float = 1f) {
        if (k <= 0.01f) return
        val dark = luminance(palette.text) > 0.5
        shadowPaint.color = if (dark) Color.argb((110 * k).toInt(), 0, 0, 0) else Color.argb((46 * k).toInt(), 90, 60, 170)
        rect.set(l + 8f, t + 26f, r - 8f, b + 26f)
        c.drawRoundRect(rect, radius, radius, shadowPaint)
        rect.set(l, t, r, b)
        fill.color = Color.WHITE
        fill.shader = LinearGradient(
            0f, t, 0f, b,
            if (dark) Color.argb((52 * k).toInt(), 255, 255, 255) else Color.argb((215 * k).toInt(), 255, 255, 255),
            if (dark) Color.argb((16 * k).toInt(), 255, 255, 255) else Color.argb((130 * k).toInt(), 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(rect, radius, radius, fill)
        fill.shader = null
        stroke.shader = null
        stroke.strokeWidth = 2.5f
        stroke.color = if (dark) Color.argb((110 * k).toInt(), 200, 170, 255) else Color.argb((235 * k).toInt(), 255, 255, 255)
        c.drawRoundRect(rect, radius, radius, stroke)
    }

    private val glassBlobs = listOf(
        Color.rgb(232, 220, 255), Color.rgb(255, 214, 232), Color.rgb(208, 235, 255), Color.rgb(255, 243, 214),
    )

    private fun glassTheme(c: Canvas, t: Double, dark: Boolean) {
        val p = if (dark) Palettes.GLASS_DARK else Palettes.GLASS_LIGHT
        fill.color = Color.WHITE
        fill.shader = LinearGradient(0f, 0f, 0f, H, p.bgTop, p.bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, fill)
        val colors = if (dark) listOf(Color.argb(90, 124, 77, 255), Color.argb(60, 255, 77, 141)) else glassBlobs
        colors.forEachIndexed { i, col ->
            val a = 0.25 * t + i * 1.7
            val x = (W * (0.2f + 0.6f * ((i % 2)))) + (160 * sin(a)).toFloat()
            val y = (H * (0.18f + 0.22f * i)) + (140 * cos(0.8 * a)).toFloat()
            val r = if (dark) 760f else 720f
            fill.shader = RadialGradient(x, y, r, intArrayOf(if (dark) col else withAlpha(col, 245), withAlpha(col, 0)), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, fill)
        }
        fill.shader = null
    }

    /** Pastel mesh (light) or midnight violet (dark); a theme change sweeps in as a radial wipe. */
    private fun glassBackground(c: Canvas, t: Double) {
        val span = timeline.sceneAt(t)
        val lt = t - span.start
        val dark = darkScene(span.index)
        val wipe = TRANSITION * 1.5
        if (span.index > 0 && lt < wipe && darkScene(span.index - 1) != dark) {
            glassTheme(c, t, !dark)
            c.save()
            path.reset()
            path.addCircle(W / 2, H * 0.45f, 20f + outCubic(lt / wipe).toFloat() * H * 1.1f, Path.Direction.CW)
            c.clipPath(path)
            glassTheme(c, t, dark)
            c.restore()
        } else glassTheme(c, t, dark)
    }

    /** A pointer that glides from (fx, fy) to (tx, ty), presses at [clickAt] seconds and leaves a ripple. */
    private fun cursorTo(c: Canvas, lt: Double, fx: Float, fy: Float, tx: Float, ty: Float, clickAt: Double) {
        if (lt < 0) return
        val k = outCubic(lt / clickAt).toFloat()
        val x = fx + (tx - fx) * k
        val y = fy + (ty - fy) * k
        val since = lt - clickAt
        if (since in 0.0..0.5) {
            val rk = (since / 0.5).toFloat()
            stroke.shader = null
            stroke.strokeWidth = 5f
            stroke.color = withAlpha(palette.accent, (200 * (1 - rk)).toInt())
            c.drawCircle(tx, ty, 20f + 70f * rk, stroke)
        }
        val press = if (since in 0.0..0.15) 0.82f else 1f
        c.save()
        c.translate(x, y)
        c.scale(press * 1.5f, press * 1.5f)
        path.reset()
        path.moveTo(0f, 0f); path.lineTo(0f, 44f); path.lineTo(11f, 34f); path.lineTo(19f, 52f)
        path.lineTo(27f, 48f); path.lineTo(19f, 31f); path.lineTo(33f, 31f); path.close()
        shadowPaint.color = Color.argb(70, 0, 0, 0)
        c.save(); c.translate(3f, 5f); c.drawPath(path, shadowPaint); c.restore()
        fill.shader = null
        fill.color = Color.WHITE
        c.drawPath(path, fill)
        stroke.strokeWidth = 2.5f
        stroke.color = Color.rgb(31, 26, 46)
        c.drawPath(path, stroke)
        c.restore()
    }

    /** iOS-style switch: [k] 0 = left (red), 1 = right (green), with a soft glow. */
    private fun toggle(c: Canvas, cx: Float, cy: Float, k: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        val w = 190f; val h = 92f
        val col = blend(palette.bad, palette.good, k)
        shadowPaint.color = withAlpha(col, (120 * alpha).toInt())
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(rect, h / 2, h / 2, shadowPaint)
        fill.shader = null
        fill.color = withAlpha(col, (255 * alpha).toInt())
        c.drawRoundRect(rect, h / 2, h / 2, fill)
        fill.color = withAlpha(Color.WHITE, (255 * alpha).toInt())
        c.drawCircle(cx - w / 2 + h / 2 + (w - h) * k, cy, h / 2 - 9f, fill)
    }

    private fun blend(a: Int, b: Int, k: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
    )

    /** The photo plays inside a floating phone, with the text on a glass pill above it. */
    private fun phoneMockup(c: Canvas, s: Scene, frame: Bitmap, lt: Double) {
        val k = spring(lt / 0.6).toFloat()
        val pw = 500f; val ph = 880f
        val cx = W / 2
        val cy = 850f + (sin(2.2 * lt) * 14).toFloat() + (1 - k) * 260f
        c.save()
        c.rotate((sin(1.3 * lt) * 1.5).toFloat(), cx, cy)
        shadowPaint.color = Color.argb(80, 70, 40, 140)
        rect.set(cx - pw / 2 + 20f, cy - ph / 2 + 50f, cx + pw / 2 - 20f, cy + ph / 2 + 50f)
        c.drawRoundRect(rect, 90f, 90f, shadowPaint)
        fill.shader = null
        fill.color = Color.rgb(20, 18, 30)
        rect.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
        c.drawRoundRect(rect, 90f, 90f, fill)
        val inset = 18f
        rect.set(cx - pw / 2 + inset, cy - ph / 2 + inset, cx + pw / 2 - inset, cy + ph / 2 - inset)
        c.save()
        path.reset()
        path.addRoundRect(rect, 74f, 74f, Path.Direction.CW)
        c.clipPath(path)
        val zoom = max(rect.width() / frame.width, rect.height() / frame.height) * (1.02f + 0.05f * clamp(lt / 3.0).toFloat())
        val w = frame.width * zoom; val h = frame.height * zoom
        fill.isFilterBitmap = true
        c.drawBitmap(frame, Rect(0, 0, frame.width, frame.height), RectF(cx - w / 2, rect.centerY() - h / 2, cx + w / 2, rect.centerY() + h / 2), fill)
        c.restore()
        fill.color = Color.rgb(20, 18, 30)
        rect.set(cx - 70f, cy - ph / 2 + 34f, cx + 70f, cy - ph / 2 + 70f)
        c.drawRoundRect(rect, 18f, 18f, fill)
        c.restore()
        if (s.text.isNotBlank()) {
            val text = block("phoneText", upper(s.text), display, 84f, zoneW - 60f, 220f)
            val a = clamp((lt - 0.15) / 0.3).toFloat()
            val top = 260f
            glassCard(c, SIDE - 20f, top, W - SIDE + 20f, top + text.height + 70f, 40f, a)
            drawBlock(c, text, W / 2, top + (text.height + 70f) / 2, a)
        }
    }

    /** Ending: a notification drops in, then a comment box gets typed and sent by the cursor. */
    private fun glassOutro(c: Canvas, s: Scene, lt: Double) {
        val e = s.emoji.ifBlank { scenes.firstOrNull()?.emoji ?: "" }.ifBlank { "💬" }
        emoji(c, e, W / 2, 600f + (sin(4 * lt) * 10).toFloat(), outBack((lt - 0.2) / 0.45).toFloat() * 190f)
        drawBlock(c, block("outro", upper(s.text.ifBlank { s.say }), display, 100f, zoneW, 300f), W / 2, 870f, clamp((lt - 0.25) / 0.3).toFloat())
        // Notification banner.
        val drop = spring((lt - OUTRO_BANNER) / 0.5).toFloat()
        if (lt >= OUTRO_BANNER) {
            val top = -200f + 350f * drop
            glassCard(c, 60f, top, W - 60f, top + 170f, 48f)
            fill.shader = LinearGradient(100f, top + 40f, 190f, top + 130f, palette.accent, palette.accent2, Shader.TileMode.CLAMP)
            rect.set(100f, top + 40f, 190f, top + 130f)
            c.drawRoundRect(rect, 24f, 24f, fill)
            fill.shader = null
            emoji(c, "💬", 145f, top + 85f, 54f)
            tp.typeface = bold; tp.textSize = 42f; tp.color = palette.text; tp.textAlign = Paint.Align.LEFT
            c.drawText("New comment", 220f, top + 78f, tp)
            tp.typeface = bold; tp.textSize = 34f; tp.color = palette.muted
            c.drawText("now", W - 170f, top + 78f, tp)
            tp.textSize = 38f; tp.color = palette.text
            c.drawText(TextUtils.ellipsize(s.text.ifBlank { s.say }, tp, W - 320f, TextUtils.TruncateAt.END).toString(), 220f, top + 128f, tp)
        }
        // Comment box with the cursor tapping send.
        val a = clamp((lt - 0.5) / 0.3).toFloat()
        if (a > 0f) {
            val top = 1070f
            glassCard(c, SIDE - 20f, top, W - SIDE + 20f, top + 110f, 55f, a)
            tp.typeface = bold; tp.textSize = 44f; tp.color = withAlpha(palette.muted, (Color.alpha(palette.muted) * a).toInt())
            c.drawText("Add a comment…", SIDE + 30f, top + 70f, tp)
            val bx = W - SIDE - 30f; val by = top + 55f
            fill.color = withAlpha(palette.accent, (255 * a).toInt())
            c.drawCircle(bx, by, 38f, fill)
            tp.typeface = black; tp.textSize = 44f; tp.color = Color.WHITE; tp.textAlign = Paint.Align.CENTER
            c.drawText("↑", bx, by + 16f, tp)
            tp.textAlign = Paint.Align.LEFT
            cursorTo(c, lt - 0.5, W * 0.6f, H * 0.98f, bx + 8f, by + 12f, OUTRO_CLICK - 0.5)
        }
    }

    /** Blur-in kinetic captions on a glass pill: words slide up out of a blur; punch words get a gradient. */
    private fun glassCaptions(c: Canvas, t: Double) {
        val g = timeline.captionAt(t) ?: return
        val texts = g.words.map { it.text.trim() }
        tp.typeface = bold
        tp.textSize = 76f
        tp.style = Paint.Style.FILL
        tp.clearShadowLayer()
        val space = tp.measureText(" ")
        var widths = texts.map { tp.measureText(it) }
        var total = widths.sum() + (texts.size - 1) * space
        if (total > zoneW - 40f) {
            tp.textSize *= (zoneW - 40f) / total
            widths = texts.map { tp.measureText(it) }
            total = widths.sum() + (texts.size - 1) * space
        }
        val size = tp.textSize
        val pillK = outCubic((t - g.start) / 0.2).toFloat()
        glassCard(c, W / 2 - total / 2 - 44f, CAPTION_Y - size * 1.05f, W / 2 + total / 2 + 44f, CAPTION_Y + size * 0.5f, 40f, pillK)
        var x = W / 2 - total / 2
        texts.forEachIndexed { i, word ->
            val start = g.words[i].start
            val e = outCubic((t - start + 0.04) / 0.2).toFloat()
            val emph = emphasisTimes.any { kotlin.math.abs(it - start) < 1e-6 }
            val nextStart = g.words.getOrNull(i + 1)?.start ?: g.end
            val active = t >= start && t < nextStart
            if (e > 0f) {
                val y = CAPTION_Y + 26f * (1 - e)
                if (e < 0.95f) { // motion-blur ghosts while the word arrives
                    tp.shader = null
                    tp.color = withAlpha(palette.text, (60 * (1 - e)).toInt())
                    c.drawText(word, x, y + 14f, tp)
                    c.drawText(word, x, y - 10f, tp)
                }
                if (emph || active) {
                    tp.color = Color.WHITE
                    tp.shader = LinearGradient(x, 0f, x + widths[i], 0f, intArrayOf(palette.accent, Color.rgb(255, 122, 89), palette.accent2), null, Shader.TileMode.CLAMP)
                } else {
                    tp.shader = null
                    tp.color = palette.text
                }
                tp.alpha = (255 * e).toInt()
                c.drawText(word, x, y, tp)
                tp.shader = null
            }
            x += widths[i] + space
        }
    }

    companion object {
        /** When each list, chart or timeline item appears (s into the scene); the sound design uses the same times. */
        /** Per caption style: chip, colour-pop, boxed, glow. All SIL Open Font License (licences in assets/fonts). */
        /** Glass micro-event times (seconds into the scene); the sound mix puts a click on each. */
        const val HOOK_CLICK = 0.75

        /** The hook intro effects run over the first seconds; frames 1+ get the impact glitch. */
        const val HOOK_FX_END = 5.0
        val GLITCH_A = 0.03..0.17
        val GLITCH_CUT = -0.05..0.08

        /** Scenes the kinetic look turns into slamming word stacks. */
        val KINETIC_TYPES = setOf("statement", "question", "emoji")
        const val OUTRO_BANNER = 0.35
        const val OUTRO_CLICK = 1.25

        val DISPLAY_FONTS = listOf("Poppins-ExtraBold.ttf", "Anton-Regular.ttf", "ArchivoBlack-Regular.ttf", "BebasNeue-Regular.ttf")

        fun itemDelays(n: Int, dur: Double): List<Double> {
            if (n <= 1) return List(n) { 0.0 }
            val spread = min(0.65 * dur, n * 0.9)
            return (0 until n).map { it * spread / (n - 1) * 0.9 }
        }

        /** Length of the fade from the last frame back into the first, for seamless loops. */
        const val LOOP = 0.45

        const val W = 1080f
        const val H = 1920f
        // Safe zones for TikTok, Reels and Shorts on 1080x1920: their buttons cover roughly the
        // right 120-180 px, the top 130-240 px and the bottom 320-660 px (caption, music, CTA).
        // Graphics live in 300-1150; captions sit around 1330, above the bottom overlay.
        const val SIDE = 110f
        const val ZONE_TOP = 300f
        const val ZONE_BOTTOM = 1150f
        const val CAPTION_Y = 1330f
        /** Length of the zoom-push between scenes, in seconds. */
        const val TRANSITION = 0.28
    }
}
