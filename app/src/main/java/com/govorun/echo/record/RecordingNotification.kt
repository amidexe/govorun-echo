package com.govorun.echo.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.govorun.echo.CapturePurpose
import com.govorun.echo.R
import com.govorun.echo.AppEntry

/** Recording controls and timer, or a plain line while text is prepared. */
internal object RecordingNotification {
    private const val NOTIFICATION_ID = 1
    private const val OLD_CHANNEL_ID = "recording"
    private const val CHANNEL_ID = "recording_active"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Silent (low importance) notifications are hidden on the lock screen by default,
        // so the recording notification is default importance with sound and vibration off.
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.recording_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
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
        val activeNote = if (recording) (RecordingBus.state.value as? RecordingState.Recording)
            ?.takeUnless { it.purpose == CapturePurpose.COMMAND } else null
        val openIntent = activeNote?.let { AppEntry.note(context, it.noteId).setIdentifier(it.noteId) }
            ?: AppEntry.intent(context)
        val open =
            PendingIntent.getActivity(
                context,
                1,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification
                .Builder(context, CHANNEL_ID)
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
                intent: Intent,
            ): Notification.Action {
                val pending = PendingIntent.getService(context, 0,
                    intent.setIdentifier(startedAtWall.toString()), PendingIntent.FLAG_IMMUTABLE)
                return Notification.Action.Builder(null, context.getString(label), pending)
                    .setAuthenticationRequired(false).build()
            }
            if (pausedAt == null) {
                // The clock counts only what was recorded: the pauses so far move its start.
                builder.setWhen(startedAtWall + RecordingBus.pausedMs.value).setShowWhen(true).setUsesChronometer(true)
            } else {
                builder
                    .setContentTitle(context.getString(R.string.recording_paused))
                    .setContentText(context.getString(R.string.recording_paused_text, clock(RecordingBus.recordedMs(startedAtWall))))
                    .setShowWhen(false)
            }
            // Keep the stop and cancel positions fixed when the middle action changes.
            builder.addAction(action(R.string.recording_stop, RecordingService.stopIntent(context)))
            when {
                pausedAt != null && activeNote != null ->
                    builder.addAction(action(R.string.recording_keep, RecordingService.pauseIntent(context, false)))
                silenceWarning && pausedAt == null -> {
                    builder.setContentTitle(context.getString(R.string.recording_silence_warning))
                    val keep = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_KEEP)
                    builder.addAction(action(R.string.recording_keep, keep))
                }
                activeNote != null ->
                    builder.addAction(action(R.string.recording_pause, RecordingService.pauseIntent(context, true)))
            }
            if (activeNote != null) {
                val cancel = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_CANCEL_RECORDING)
                builder.addAction(action(R.string.recording_cancel, cancel))
            }
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

    fun refresh(context: Context, notification: Notification) {
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    fun refreshWhenActive(context: Context, active: RecordingState.Recording, notification: () -> Notification) {
        context.mainExecutor.execute {
            if (RecordingBus.state.value == active) refresh(context, notification())
        }
    }

    /** Puts the recording [service] in the foreground with [notification]; false when the system does not allow it now. */
    fun enterForeground(
        service: android.app.Service,
        notification: Notification,
    ): Boolean =
        try {
            ensureChannel(service)
            service.startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            true
        } catch (error: Exception) {
            android.util.Log.w("Echo", "event=foreground_start_denied", error)
            false
        }
}
