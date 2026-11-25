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
    private val palette: Palette,
    private val seriesName: String,
    /** Stock photo or video clip per photo scene, loaded only while its scene is on screen. */
    private val media: Map<Int, SceneMedia.Lazy>,
    /** Brand logos by scene index: the logo scene's subject, or a badge on any scene naming a brand. */
    private val logos: Map<Int, Logo> = emptyMap(),
) {
    private val black: Typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/Inter-Black.otf") }
        .getOrElse { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
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
                setShadowLayer(size * 0.12f, 0f, size * 0.05f, Color.argb(150, 0, 0, 0))
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
            c.clipRect(
                layout.getLineLeft(i) - 40, layout.getLineTop(i) - 20f,
                layout.getLineRight(i) + 40, layout.getLineBottom(i) + 20f
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
        emojiPaint.textSize = size
        emojiPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val fm = emojiPaint.fontMetrics
        c.drawText(e, cx, cy - (fm.ascent + fm.descent) / 2, emojiPaint)
    }

    private fun card(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 44f) {
        fill.shader = null
        fill.color = color
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, radius, radius, fill)
    }

    fun draw(c: Canvas, t: Double, captions: Boolean = true) {
        background(c, t)
        val span = timeline.sceneAt(t)
        manageMedia(span.index, t - span.start)
        val lt = t - span.start
        if (span.index > 0 && lt < TRANSITION) {
            // Zoom-push transition: the old scene grows and fades away, the new one settles in.
            val k = outCubic(lt / TRANSITION).toFloat()
            val prev = timeline.spans[span.index - 1]
            drawScene(c, prev.index, t - prev.start, prev.end - prev.start, alpha = 1f - k, scale = 1f + 0.10f * k, dy = -70f * k)
            drawScene(c, span.index, lt, span.end - span.start, alpha = k, scale = 0.90f + 0.10f * k, dy = 90f * (1 - k))
        } else {
            drawScene(c, span.index, lt, span.end - span.start, alpha = 1f, scale = 1f, dy = 0f)
        }
        chrome(c, t, span.index)
        c.drawRect(0f, 0f, W, H, vignette)
        grainShift.setTranslate(((t * 97) % 256).toFloat(), ((t * 61) % 256).toFloat())
        grainPaint.shader.setLocalMatrix(grainShift)
        c.drawRect(0f, 0f, W, H, grainPaint)
        if (captions) captions(c, t)
    }

    /** One scene, with a slow push-in "camera" over its whole length. */
    private fun drawScene(c: Canvas, index: Int, lt: Double, durRaw: Double, alpha: Float, scale: Float, dy: Float) {
        if (alpha <= 0.01f) return
        val scene = scenes[index]
        val dur = durRaw.coerceAtLeast(0.3)
        val drift = 1f + 0.035f * clamp(lt / dur).toFloat()
        c.saveLayerAlpha(0f, 0f, W, H, (alpha * 255).toInt())
        c.translate(0f, dy)
        c.scale(scale * drift, scale * drift, W / 2, H * 0.42f)
        when (scene.type.lowercase(Locale.ROOT)) {
            "hook" -> hook(c, scene, lt)
            "stat" -> stat(c, scene, lt)
            "list" -> list(c, scene, lt, dur, numbered = false)
            "steps" -> list(c, scene, lt, dur, numbered = true)
            "compare" -> compare(c, scene, lt, dur)
            "emoji" -> emojiScene(c, scene, lt)
            "question" -> question(c, scene, lt)
            "photo" -> photo(c, scene, index, lt, dur)
            "logo" -> logoScene(c, scene, index, lt)
            "outro" -> outro(c, scene, lt)
            else -> statement(c, scene, lt)
        }
        badge(c, scene, index, lt)
        c.restore()
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
        c.drawRect(0f, 0f, W, H, bgPaint)
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

    private fun chrome(c: Canvas, t: Double, sceneIndex: Int) {
        val progress = clamp(t / timeline.duration).toFloat()
        fill.shader = null
        fill.color = Color.argb(40, 255, 255, 255)
        c.drawRect(0f, 0f, W, 12f, fill)
        fill.color = palette.accent
        c.drawRect(0f, 0f, progress * W, 12f, fill)
        if (seriesName.isBlank() || sceneIndex != 0) return
        val s = outBack(t / 0.4).toFloat()
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

    /** Word-by-word captions; the word being spoken sits on an accent-coloured chip. */
    private fun captions(c: Canvas, t: Double) {
        val g = timeline.captionAt(t) ?: return
        val pop = outBack((t - g.start) / 0.18).toFloat()
        tp.typeface = black
        tp.textSize = 82f
        val texts = g.words.map { upper(it.text.trim()) }
        val space = tp.measureText(" ")
        var widths = texts.map { tp.measureText(it) }
        var total = widths.sum() + (texts.size - 1) * space
        if (total > zoneW) {
            tp.textSize = tp.textSize * (zoneW / total)
            widths = texts.map { tp.measureText(it) }
            total = widths.sum() + (texts.size - 1) * space
        }
        c.save()
        c.translate(W / 2, CAPTION_Y)
        val s = 0.85f + 0.15f * pop
        c.scale(s, s)
        var x = -total / 2
        texts.forEachIndexed { i, word ->
            val nextStart = g.words.getOrNull(i + 1)?.start ?: g.end
            val active = t >= g.words[i].start && t < nextStart
            if (active) {
                // The spoken word pops on its chip.
                val pop = 1f + 0.12f * (1f - spring((t - g.words[i].start) / 0.25).toFloat()).coerceAtLeast(-0.2f)
                c.save()
                c.scale(pop, pop, x + widths[i] / 2, -0.28f * tp.textSize)
                card(c, x - 18f, -0.86f * tp.textSize, x + widths[i] + 18f, tp.textSize * 0.3f, palette.accent, 22f)
            } else {
                tp.style = Paint.Style.STROKE
                tp.strokeWidth = 14f
                tp.strokeJoin = Paint.Join.ROUND
                tp.color = Color.argb(200, 0, 0, 0)
                c.drawText(word, x, 0f, tp)
            }
            tp.style = Paint.Style.FILL
            tp.color = if (active) Color.BLACK else Color.WHITE
            c.drawText(word, x, 0f, tp)
            if (active) c.restore()
            x += widths[i] + space
        }
        c.restore()
    }

    private fun hook(c: Canvas, s: Scene, lt: Double) {
        val words = upper(s.text.ifBlank { s.say }).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val hasEmoji = s.emoji.isNotBlank()
        if (hasEmoji) emoji(c, s.emoji, W / 2, 480f + (sin(4 * lt) * 12).toFloat(), outBack(lt / 0.45).toFloat() * 230f)
        // Words appear one by one, 70 ms apart; the last word is highlighted.
        val shown = words.indices.count { lt >= it * 0.07 }
        val full = block(
            "hook", highlighted(words.joinToString(" "), words.lastOrNull() ?: "", palette.text, palette.accent),
            black, 150f, zoneW, if (hasEmoji) 600f else 820f
        )
        val pop = outBack(lt / 0.35).toFloat()
        val cy = zoneMid + if (hasEmoji) 150f else 0f
        if (shown >= words.size) {
            drawBlock(c, full, W / 2, cy, 1f, 0.9f + 0.1f * pop)
        } else {
            val part = block(
                "hookpart", highlighted(words.take(max(1, shown)).joinToString(" "), "", palette.text, palette.accent),
                black, full.paint.textSize, zoneW, 820f
            )
            drawBlock(c, part, W / 2, cy, 1f, 0.9f + 0.1f * pop)
        }
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
        stroke.color = Color.argb(40, 255, 255, 255)
        c.drawCircle(W / 2, cy, r, stroke)
        stroke.color = palette.accent
        rect.set(W / 2 - r, cy - r, W / 2 + r, cy + r)
        c.drawArc(rect, -90f, (360 * outCubic(lt / 1.0)).toFloat(), false, stroke)
        // Size the counting number by the final value so it does not jump while counting.
        val size = block("statfinal", final, black, 220f, r * 1.6f, r * 1.1f, palette.accent).paint.textSize
        drawBlock(c, block("stat", shown, black, size, r * 1.8f, r * 1.3f, palette.accent), W / 2, cy, 1f, outBack(lt / 0.4).toFloat())
        if (s.label.isBlank()) return
        val a = clamp((lt - 0.35) / 0.3).toFloat()
        drawBlock(c, block("statlabel", s.label, bold, 64f, zoneW, 220f), W / 2, cy + r + 140f - 30f * a, a)
    }

    private fun list(c: Canvas, s: Scene, lt: Double, dur: Double, numbered: Boolean) {
        val items = s.items.take(4).ifEmpty { listOf(s.text.ifBlank { s.say }) }
        var top = ZONE_TOP + 20f
        if (s.text.isNotBlank() && s.items.isNotEmpty()) {
            val title = block("listtitle", upper(s.text), black, 84f, zoneW, 210f, palette.accent)
            drawBlock(c, title, W / 2, top + title.height / 2f, outCubic(lt / 0.3).toFloat())
            top += title.height + 50f
        }
        val gap = 34f
        val room = ZONE_BOTTOM - top
        val rowH = min((room - (items.size - 1) * gap) / items.size, 230f)
        val y0 = top + (room - (items.size * rowH + (items.size - 1) * gap)) / 2
        val spread = min(0.65 * dur, items.size * 0.9)
        items.forEachIndexed { i, item ->
            val delay = if (items.size == 1) 0.0 else i * spread / (items.size - 1).coerceAtLeast(1) * 0.9
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
    }

    private fun compare(c: Canvas, s: Scene, lt: Double, dur: Double) {
        val second = (dur * 0.42).coerceIn(0.5, 2.2)
        val gap = 50f
        val h = (ZONE_BOTTOM - ZONE_TOP - gap) / 2
        compareHalf(c, lt, h, second, ZONE_TOP, s.left.ifBlank { "Myth" }, s.leftText, palette.bad, "✕", 0.0, strike = true)
        compareHalf(c, lt, h, second, ZONE_TOP + h + gap, s.right.ifBlank { "Fact" }, s.rightText, palette.good, "✓", second, strike = false)
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
            black, 118f, zoneW, 760f
        )
        fill.shader = null
        fill.color = palette.accent2
        val top = zoneMid - text.height / 2f
        c.drawRect(60f, top, 74f, top + text.height * a, fill)
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
        drawBlock(c, block("emo", upper(s.text), black, 100f, zoneW, 300f), W / 2, cy + 360f, clamp((lt - 0.2) / 0.3).toFloat())
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
        drawBlock(c, block("q", upper(s.text.ifBlank { s.say }), black, 104f, zoneW, 360f), W / 2, 950f, a)
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
        val zoom = max(W / frame.width, H / frame.height) * (1.04f + 0.08f * clamp(lt / dur.coerceAtLeast(1.0)).toFloat())
        val w = frame.width * zoom
        val h = frame.height * zoom
        val left = (W - w) / 2 - (6 * lt).toFloat()
        val top = (H - h) / 2
        fill.shader = null
        fill.alpha = 255
        fill.isFilterBitmap = true
        c.drawBitmap(frame, Rect(0, 0, frame.width, frame.height), RectF(left, top, left + w, top + h), fill)
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
        drawBlock(c, block("photo", upper(s.text), black, 96f, zoneW, 300f), W / 2, ZONE_TOP + 160f, a, 0.85f + 0.15f * pop)
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
        drawBlock(c, block("logoname", upper(name), black, 108f, zoneW, 160f), W / 2, cy + 400f, a)
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

    private fun outro(c: Canvas, s: Scene, lt: Double) {
        val e = s.emoji.ifBlank { scenes.firstOrNull()?.emoji ?: "" }.ifBlank { "💬" }
        emoji(c, e, W / 2, 490f + (sin(4 * lt) * 10).toFloat(), outBack(lt / 0.45).toFloat() * 220f)
        drawBlock(
            c, block("outro", upper(s.text.ifBlank { s.say }), black, 112f, zoneW, 520f),
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

    companion object {
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
