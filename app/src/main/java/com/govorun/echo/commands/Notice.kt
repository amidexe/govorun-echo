package com.govorun.echo.commands

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.govorun.echo.R

/** A quiet note that says what became of a command, with a link to its saved source and result. */
internal object Notice {
    private const val CHANNEL = "command_result"
    private const val ID = 7_002
    private const val OPEN_ID = 7_004
    private const val SHOWN_MS = 8_000L

    fun result(context: Context, noteId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val file = java.io.File(com.govorun.echo.notes.NoteStore.noteDir(context, noteId), "meta.json")
        val receipt = runCatching { org.json.JSONObject(file.readText()).getJSONObject("command") }.getOrNull() ?: return
        val target = if (receipt.optBoolean("ok") && receipt.optLong("eventId") > 0) {
            Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, receipt.getLong("eventId")))
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, receipt.optLong("eventStartMs"))
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, receipt.optLong("eventEndMs"))
        } else com.govorun.echo.AppEntry.command(context, noteId)
        val open = PendingIntent.getActivity(context, noteId.hashCode(), target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hidden = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(R.string.commands_result_title)).build()
        val locked = com.govorun.echo.AppSettings.appLock(context) && context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(if (locked) context.getString(R.string.commands_result_title) else receipt.optJSONObject("parameters")?.optString("title").orEmpty().ifBlank { receipt.optString("name") })
            .setContentText(if (locked) "" else listOf(receipt.optString("label"), receipt.optString("detail")).filter(String::isNotBlank).joinToString(" · "))
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden).build()
        runCatching { manager.notify(noteId, ID, notification) }
    }

    fun offerOpen(context: Context, noteId: String, target: Intent): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(context, manager)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return false
        }
        val open = PendingIntent.getActivity(
            context, OPEN_ID, CommandOpenActivity.intent(context, noteId).putExtra(CommandOpenActivity.EXTRA_TARGET, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val action = Notification.Action.Builder(null, context.getString(R.string.commands_android_open), open)
            .setAuthenticationRequired(true)
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(R.string.commands_android_ready))
            .setContentText(context.getString(R.string.commands_android_deferred))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(15 * 60_000L)
            .addAction(action)
            .build()
        return runCatching { manager.notify(noteId, OPEN_ID, notification) }.isSuccess
    }

    fun cancelOpen(context: Context, noteId: String) {
        PendingIntent.getActivity(
            context, OPEN_ID, CommandOpenActivity.intent(context, noteId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )?.cancel()
        context.getSystemService(NotificationManager::class.java)?.cancel(noteId, OPEN_ID)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.commands_notice_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun show(
        context: Context,
        title: String,
        text: String,
        ok: Boolean,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_recording)
                .setContentTitle(title)
                .setContentText(text)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(if (ok) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
                .setTimeoutAfter(SHOWN_MS)
                .setAutoCancel(true)
                .build()
        runCatching { manager.notify(ID, notification) }
    }
}
