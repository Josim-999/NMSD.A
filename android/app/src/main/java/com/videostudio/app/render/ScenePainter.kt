package com.videostudio.app.render

import android.content.Context
import android.graphics.Bitmap
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
    private val photos: Map<Int, Bitmap>,
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
        val scene = scenes[span.index]
        val dur = (span.end - span.start).coerceAtLeast(0.3)
        val lt = t - span.start
        // Quick fade out at the end of every scene but the last.
        val fade = if (span.index < scenes.size - 1) clamp((span.end - t) / 0.15).toFloat() else 1f
        c.saveLayerAlpha(0f, 0f, W, H, (fade * 255).toInt())
        when (scene.type.lowercase(Locale.ROOT)) {
            "hook" -> hook(c, scene, lt)
            "stat" -> stat(c, scene, lt)
            "list" -> list(c, scene, lt, dur, numbered = false)
            "steps" -> list(c, scene, lt, dur, numbered = true)
            "compare" -> compare(c, scene, lt, dur)
            "emoji" -> emojiScene(c, scene, lt)
            "question" -> question(c, scene, lt)
            "photo" -> photo(c, scene, span.index, lt, dur)
            "outro" -> outro(c, scene, lt)
            else -> statement(c, scene, lt)
        }
        c.restore()
        chrome(c, t, span.index)
        if (captions) captions(c, t)
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
            val y = y0 + i * (rowH + gap)
            c.save()
            c.translate(-(1 - a) * 160f, 0f)
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
        val h = 435f
        compareHalf(c, lt, h, second, ZONE_TOP, s.left.ifBlank { "Myth" }, s.leftText, palette.bad, "✕", 0.0, strike = true)
        compareHalf(c, lt, h, second, ZONE_TOP + h + 60f, s.right.ifBlank { "Fact" }, s.rightText, palette.good, "✓", second, strike = false)
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
        drawBlock(c, block("q", upper(s.text.ifBlank { s.say }), black, 104f, zoneW, 440f), W / 2, 1020f, a)
    }

    private fun photo(c: Canvas, s: Scene, index: Int, lt: Double, dur: Double) {
        val bmp = photos[index]
        if (bmp == null) {
            emojiScene(c, s.copy(emoji = s.emoji.ifBlank { "📸" }), lt)
            return
        }
        val a = outCubic(lt / 0.4).toFloat()
        c.save()
        val slide = (1 - a) * 80f
        rect.set(SIDE, ZONE_TOP + slide, W - SIDE, ZONE_TOP + 700f + slide)
        path.reset()
        path.addRoundRect(rect, 48f, 48f, Path.Direction.CW)
        c.clipPath(path)
        // Slow zoom and pan (Ken Burns).
        val zoom = max(rect.width() / bmp.width, rect.height() / bmp.height) *
            (1.08f + 0.12f * clamp(lt / dur.coerceAtLeast(1.0)).toFloat())
        val w = bmp.width * zoom
        val h = bmp.height * zoom
        val left = rect.centerX() - w / 2 - (8 * lt).toFloat()
        val top = rect.centerY() - h / 2
        fill.shader = null
        fill.alpha = (255 * a).toInt()
        c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(left, top, left + w, top + h), fill)
        fill.alpha = 255
        c.restore()
        if (s.text.isBlank()) return
        drawBlock(c, block("photo", upper(s.text), black, 92f, zoneW, 200f), W / 2, 1150f, clamp((lt - 0.25) / 0.3).toFloat())
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
            card(c, W / 2 - half, 1170f, W / 2 + half, 1270f, withAlpha(palette.accent, alpha), 50f)
            tp.color = Color.BLACK
            tp.alpha = alpha
            tp.textAlign = Paint.Align.CENTER
            c.drawText(label, W / 2, 1238f, tp)
            tp.textAlign = Paint.Align.LEFT
        }
    }

    companion object {
        const val W = 1080f
        const val H = 1920f
        const val SIDE = 90f
        const val ZONE_TOP = 330f
        const val ZONE_BOTTOM = 1260f
        const val CAPTION_Y = 1420f
    }
}
