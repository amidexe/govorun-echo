package com.govorun.echo.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.AppEntry

/** The notification of the recording service: a timer, «Стоп», the pause and «Отмена» while recording, a plain line while text is prepared. */
internal object RecordingNotification {
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Silent (low importance) notifications are hidden on the lock screen by default,
        // so the recording notification is default importance with sound and vibration off.
        manager.deleteNotificationChannel(RecordingService.OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(RecordingService.CHANNEL_ID, context.getString(R.string.recording_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    fun build(
        context: Context,
        title: String,
        recording: Boolean,
        startedAtWall: Long,
        silenceWarning: Boolean = false,
    ): Notification {
        val stop =
            PendingIntent.getService(
                context,
                0,
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val open =
            PendingIntent.getActivity(
                context,
                1,
                AppEntry.intent(context),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification
                .Builder(context, RecordingService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_recording)
                .setContentTitle(title)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(
                    Notification.VISIBILITY_PUBLIC,
                ).setCategory(Notification.CATEGORY_SERVICE)
        if (recording) {
            val pausedAt = RecordingBus.pausedAt.value
            fun action(
                label: Int,
                intent: PendingIntent,
            ) = Notification.Action.Builder(null, context.getString(label), intent).setAuthenticationRequired(false).build()
            fun service(
                action: String,
                code: Int,
            ) = PendingIntent.getService(context, code, Intent(context, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
            if (pausedAt == null) {
                // The clock counts only what was recorded: the pauses so far move its start.
                builder.setWhen(startedAtWall + RecordingBus.pausedMs.value).setShowWhen(true).setUsesChronometer(true)
            } else {
                builder
                    .setContentTitle(context.getString(R.string.recording_paused))
                    .setContentText(context.getString(R.string.recording_paused_text, clock(RecordingBus.recordedMs(startedAtWall))))
                    .setShowWhen(false)
            }
            // «Стоп» always first, so the button under the finger never turns into it; «Отмена» last, away from it.
            builder.addAction(action(R.string.recording_stop, stop))
            when {
                pausedAt != null -> builder.addAction(action(R.string.recording_keep, service(RecordingService.ACTION_CONTINUE_RECORDING, 3)))
                silenceWarning -> {
                    // In the title: a folded notification shows only that line, and the tap sent the person to look.
                    builder.setContentTitle(context.getString(R.string.recording_silence_warning))
                    builder.addAction(action(R.string.recording_keep, service(RecordingService.ACTION_KEEP, 2)))
                }
                else -> builder.addAction(action(R.string.recording_pause, service(RecordingService.ACTION_PAUSE_RECORDING, 4)))
            }
            builder.addAction(action(R.string.recording_cancel, service(RecordingService.ACTION_CANCEL_RECORDING, 5)))
            requestPromotedOngoing(builder)
        }
        return builder.build()
    }

    /** "4:05" or "1:02:03". */
    private fun clock(ms: Long): String {
        val s = ms / 1_000
        return if (s >= 3_600) "%d:%02d:%02d".format(s / 3_600, s % 3_600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Android 16 QPR+ shows ongoing timers as a status-bar chip and on the lock screen. */
    private fun requestPromotedOngoing(builder: Notification.Builder) {
        if (Build.VERSION.SDK_INT < 36) return
        runCatching {
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
        }
    }

    /** Puts the recording [service] in the foreground with [notification]; false when the system does not allow it now. */
    fun enterForeground(
        service: android.app.Service,
        notification: Notification,
    ): Boolean =
        try {
            ensureChannel(service)
            service.startForeground(RecordingService.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            true
        } catch (error: Exception) {
            android.util.Log.w("Echo", "event=foreground_start_denied", error)
            false
        }
}
