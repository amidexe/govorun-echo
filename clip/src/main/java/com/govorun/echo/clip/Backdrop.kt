package com.govorun.echo.clip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint

/**
 * A deep, calm pair of colours for the background and the colour of the word being said. White text stands at 12:1 or more on
 * every background, and the white word on its accent at 3.2:1 or more, enough for text this large.
 */
class Palette(
    val from: Int,
    val to: Int,
    val accent: Int,
) {
    companion object {
        val ALL =
            listOf(
                Palette(0xFF0F3D40.toInt(), 0xFF0A1416.toInt(), 0xFF2E9A9F.toInt()),
                Palette(0xFF3A1F3E.toInt(), 0xFF120C16.toInt(), 0xFFB97AA3.toInt()),
                Palette(0xFF1C2B4B.toInt(), 0xFF0A0F1C.toInt(), 0xFF648DDE.toInt()),
                Palette(0xFF1F3B2D.toInt(), 0xFF0B140F.toInt(), 0xFF589E6F.toInt()),
                Palette(0xFF45211F.toInt(), 0xFF140A0A.toInt(), 0xFFD37657.toInt()),
                Palette(0xFF2C3541.toInt(), 0xFF0E1116.toInt(), 0xFF7991B3.toInt()),
            )

        /** The same note always gets the same colours, different notes different ones. */
        fun of(seed: String): Palette = ALL[Math.floorMod(seed.hashCode(), ALL.size)]
    }
}

/** How the video looks besides its words: the app's font, its mark and name, when the note was said, its colours and loudness. */
class ClipStyle(
    val typeface: Typeface,
    val brand: String,
    val label: String,
    val logo: Bitmap?,
    val palette: Palette,
    /** The note's loudness, one byte per 100 ms, as its waveform: drawn still and faint along the bottom; null for none. */
    val loudness: ByteArray? = null,
)

/**
 * The still part of every picture, drawn once, like a TV frame: the gradient with a soft light and the rings of an echo, the
 * mark and name in the corner as a channel's, the date opposite, the plate the text stands on and the note's own waveform under
 * it. Nothing here moves, so it costs the video nothing after the first picture.
 */
internal object Backdrop {
    fun draw(
        look: ClipStyle,
        size: Int,
        plate: RectF,
    ): Bitmap {
        val s = size / 720f
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), look.palette.from, look.palette.to, Shader.TileMode.CLAMP) })
        c.drawPaint(Paint().apply { shader = RadialGradient(560 * s, 120 * s, 520 * s, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP) })
        val logo = RectF(40 * s, 36 * s, 84 * s, 80 * s)
        val rings = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * s
            color = 0x0BFFFFFF
        }
        var r = 40 * s
        while (r < 1_100 * s) {
            c.drawCircle(logo.centerX(), logo.centerY(), r, rings)
            r += 34 * s
        }
        look.loudness?.takeIf { it.isNotEmpty() }?.let { waveform(c, it, s) }
        look.logo?.let { c.drawBitmap(it, null, logo, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)) }
        val text =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = look.typeface
                fontVariationSettings = "'wght' 600"
                textSize = 24 * s
                color = 0xFFFFFFFF.toInt()
            }
        val x = if (look.logo != null) logo.right + 12 * s else logo.left
        c.drawText(look.brand, x, logo.centerY() - (text.ascent() + text.descent()) / 2, text)
        text.fontVariationSettings = "'wght' 450"
        text.textSize = 20 * s
        text.color = 0x9EFFFFFF.toInt()
        c.drawText(look.label, size - 40 * s - text.measureText(look.label), logo.centerY() - (text.ascent() + text.descent()) / 2, text)
        c.drawRoundRect(plate, 28 * s, 28 * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4D000000 })
        c.drawRoundRect(plate, 28 * s, 28 * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1 * s
            color = 0x12FFFFFF
        })
        return out
    }

    /** The loudness of the whole note in 120 bars across the bottom, the way the player draws it, faint enough to read past. */
    private fun waveform(
        c: Canvas,
        loudness: ByteArray,
        s: Float,
    ) {
        val bars = 120
        val left = 60 * s
        val step = 600 * s / bars
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
        val loudest = maxOf(loudness.maxOf { it.toInt() and 0xFF }, 1)
        for (i in 0 until bars) {
            val from = i * loudness.size / bars
            val to = maxOf((i + 1) * loudness.size / bars, from + 1)
            val level = (from until minOf(to, loudness.size)).maxOfOrNull { loudness[it].toInt() and 0xFF } ?: 0
            val h = (4 + 70f * level / loudest) * s
            c.drawRoundRect(RectF(left + i * step + s, 650 * s - h / 2, left + (i + 1) * step - s, 650 * s + h / 2), 2 * s, 2 * s, paint)
        }
    }
}
