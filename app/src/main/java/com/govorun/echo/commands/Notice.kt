package com.govorun.echo.commands

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.govorun.echo.R

/** A quiet note that says what became of a command, even on the lock screen; it goes away by itself. */
internal object Notice {
    private const val CHANNEL = "command_result"
    private const val ID = 7_002
    private const val SHOWN_MS = 8_000L

    fun show(
        context: Context,
        title: String,
        text: String,
        ok: Boolean,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.commands_notice_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
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
