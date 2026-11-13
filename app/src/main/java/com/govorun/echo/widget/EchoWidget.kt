package com.govorun.echo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import com.govorun.echo.AppEntry
import com.govorun.echo.R
import com.govorun.echo.CapturePurpose
import kotlin.math.roundToInt

/** A small recording glyph, with the whole launcher cell as its touch target. */
open class EchoWidget : AppWidgetProvider() {
    internal open val action = WidgetAction.RECORD

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it, action) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id, action)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        WidgetAppearance.remove(context, ids)
    }

    companion object {
        internal fun update(context: Context, manager: AppWidgetManager, id: Int, action: WidgetAction) {
            val appearance = WidgetAppearance.load(context, id)
            val options = manager.getAppWidgetOptions(id)
            val cellSize = listOf(
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            ).filter { it > 0 }.minOrNull() ?: WidgetAppearance.MAX_SIZE
            val size = appearance.sizeDp.coerceAtMost(cellSize)
            val sizePx = (size * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
            val record = PendingIntent.getActivity(context, 0, AppEntry.recording(context, purpose = action.purpose)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AppEntry.WIDGET_RECORDING_EXTRA, true).setIdentifier("widget-${action.purpose.id}"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val widget = RemoteViews(context.packageName, R.layout.echo_widget).apply {
                // A host may reuse the existing ImageView when the app is updated.
                setColorStateList(R.id.widget_record_icon, "setImageTintList", null)
                setInt(R.id.widget_record_icon, "setBackgroundResource", 0)
                setViewLayoutWidth(R.id.widget_record_icon, size.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutHeight(R.id.widget_record_icon, size.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                setImageViewBitmap(R.id.widget_record_icon, appearance.icon(context, sizePx, action.glyph))
                setContentDescription(R.id.widget_record, context.getString(action.description))
                setOnClickPendingIntent(R.id.widget_record, record)
            }
            manager.updateAppWidget(id, widget)
        }
    }
}

class CommandWidget : EchoWidget() {
    internal override val action = WidgetAction.COMMAND
}

internal enum class WidgetAction(val purpose: CapturePurpose, val glyph: Int, val description: Int, val title: Int) {
    RECORD(CapturePurpose.NOTE, R.drawable.ic_launcher_monochrome, R.string.widget_start_recording, R.string.widget_appearance_title),
    COMMAND(CapturePurpose.COMMAND, R.drawable.ic_shortcut_command, R.string.shortcut_command_short, R.string.widget_command_appearance_title);

    companion object {
        fun forProvider(className: String?): WidgetAction? = when (className) {
            EchoWidget::class.java.name -> RECORD
            CommandWidget::class.java.name -> COMMAND
            else -> null
        }
    }
}
