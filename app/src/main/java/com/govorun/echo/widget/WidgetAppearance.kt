package com.govorun.echo.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import com.govorun.echo.R
import kotlin.math.roundToInt

/** A device-local choice tied to a launcher widget, rather than to the application theme. */
internal data class WidgetAppearance(
    val sizeDp: Int = 40,
    val backgroundOpacity: Int = 100,
    val whiteBird: Boolean = false,
) {
    fun save(context: Context, id: Int) {
        prefs(context).edit()
            .putInt("$id.size", sizeDp)
            .putInt("$id.opacity", backgroundOpacity)
            .putBoolean("$id.white", whiteBird)
            .apply()
    }

    fun icon(context: Context, sizePx: Int, glyph: Int): Bitmap {
        val icon = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(icon)
        if (backgroundOpacity > 0) {
            val radius = sizePx / 2f
            val circle = Path().apply { addCircle(radius, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(circle)
            requireNotNull(context.getDrawable(R.drawable.ic_launcher_background)).mutate().apply {
                alpha = (backgroundOpacity * 255 / 100f).roundToInt()
                setBounds(0, 0, sizePx, sizePx)
                draw(canvas)
            }
            canvas.restore()
        }
        requireNotNull(context.getDrawable(glyph)).mutate().apply {
            setTint(if (whiteBird) Color.WHITE else 0xff2d211e.toInt())
            setBounds(0, 0, sizePx, sizePx)
            draw(canvas)
        }
        return icon
    }

    companion object {
        const val MIN_SIZE = 28
        const val MAX_SIZE = 56
        private fun prefs(context: Context) = context.getSharedPreferences("device_widget_appearance", Context.MODE_PRIVATE)

        fun load(context: Context, id: Int): WidgetAppearance = prefs(context).let {
            WidgetAppearance(
                it.getInt("$id.size", 40).coerceIn(MIN_SIZE, MAX_SIZE),
                it.getInt("$id.opacity", 100).coerceIn(0, 100),
                it.getBoolean("$id.white", false),
            )
        }

        fun remove(context: Context, ids: IntArray) {
            val editor = prefs(context).edit()
            ids.forEach { id -> editor.remove("$id.size").remove("$id.opacity").remove("$id.white") }
            editor.apply()
        }
    }
}
