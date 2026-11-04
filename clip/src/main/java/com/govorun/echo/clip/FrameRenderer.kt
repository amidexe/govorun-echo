package com.govorun.echo.clip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Draws the pictures of a video: a page of the note's text on the plate of a still [Backdrop], the word being said on a mark of the
 * palette's accent, the words already said white and the rest dim, as the app's own subtitles show them. The page turns when the
 * words go past it. The backdrop is drawn once; a frame redraws only the page.
 */
internal class FrameRenderer(
    words: List<ClipWord>,
    style: ClipStyle,
    val size: Int = SIZE,
) {
    private val scale = size / SIZE.toFloat()
    private val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val starts = IntArray(words.size)
    private val ends = IntArray(words.size)
    private val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = style.typeface
            fontVariationSettings = "'wght' 420"
            textSize = TEXT_PX * scale
        }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.palette.accent }
    private val plate = RectF(PLATE_SIDE_PX * scale, PLATE_TOP_PX * scale, size - PLATE_SIDE_PX * scale, PLATE_BOTTOM_PX * scale)
    private val left = plate.left + PLATE_PAD_PX * scale
    private val top = plate.top + PLATE_TOP_PAD_PX * scale
    private val width = plate.width() - 2 * PLATE_PAD_PX * scale
    private val background = Backdrop.draw(style, size, plate)
    private val layout: StaticLayout

    init {
        // Words in a row; a pause of [PARAGRAPH_MS] or more starts a new line, as the note's paragraphs do.
        val text = StringBuilder()
        words.forEachIndexed { i, word ->
            if (i > 0) text.append(if (word.startMs - words[i - 1].endMs >= PARAGRAPH_MS) '\n' else ' ')
            starts[i] = text.length
            text.append(word.text)
            ends[i] = text.length
        }
        layout =
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                // Exactly [LINE_PX] from line to line, so [LINES] lines fill the plate whatever the font's own spacing.
                .setLineSpacing(LINE_PX * scale - paint.fontSpacing, 1f)
                .setIncludePad(false)
                .build()
    }

    /** The picture of [frame]; the same bitmap is reused for the next one. */
    fun draw(frame: ClipFrame): Bitmap {
        canvas.drawBitmap(background, 0f, 0f, null)
        if (starts.isEmpty()) return bitmap
        val focus = if (frame.lit >= 0) frame.lit else (frame.said + 1).coerceIn(0, starts.lastIndex)
        val firstLine = layout.getLineForOffset(starts[focus]) / LINES * LINES
        val lastLine = minOf(firstLine + LINES, layout.lineCount) - 1
        val shift = top - layout.getLineTop(firstLine)
        canvas.save()
        canvas.clipRect(0f, top, size.toFloat(), top + layout.getLineBottom(lastLine) - layout.getLineTop(firstLine))
        canvas.translate(left, shift)
        if (frame.lit >= 0) {
            val line = layout.getLineForOffset(starts[frame.lit])
            val pad = MARK_PAD_PX * scale
            val baseline = layout.getLineBaseline(line).toFloat()
            canvas.drawRoundRect(
                RectF(layout.getPrimaryHorizontal(starts[frame.lit]) - pad, baseline + paint.ascent() - pad, layout.getPrimaryHorizontal(ends[frame.lit]) + pad, baseline + paint.descent() + pad),
                MARK_RADIUS_PX * scale,
                MARK_RADIUS_PX * scale,
                mark,
            )
        }
        paint.color = AHEAD
        layout.draw(canvas)
        if (frame.said >= 0) {
            // What is already said is drawn again, bright: every line before the last said word and that line up to its end.
            val line = layout.getLineForOffset(ends[frame.said])
            val x = layout.getPrimaryHorizontal(ends[frame.said])
            paint.color = SAID
            canvas.save()
            canvas.clipRect(-left, layout.getLineTop(firstLine).toFloat(), size.toFloat(), layout.getLineTop(line).toFloat())
            layout.draw(canvas)
            canvas.restore()
            canvas.save()
            canvas.clipRect(-left, layout.getLineTop(line).toFloat(), x, layout.getLineBottom(line).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
        return bitmap
    }

    companion object {
        const val SIZE = 720
        const val LINES = 7
        const val PARAGRAPH_MS = 2_000L
        private const val TEXT_PX = 36f
        private const val LINE_PX = 58f
        private const val PLATE_SIDE_PX = 32f
        private const val PLATE_TOP_PX = 124f
        private const val PLATE_BOTTOM_PX = 576f
        private const val PLATE_PAD_PX = 28f
        private const val PLATE_TOP_PAD_PX = 22f
        private const val MARK_PAD_PX = 6f
        private const val MARK_RADIUS_PX = 10f
        private const val SAID = 0xFFFFFFFF.toInt()
        private const val AHEAD = 0x6BFFFFFF
    }
}
