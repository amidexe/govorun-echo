package com.govorun.echo.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import com.govorun.echo.R
import com.govorun.echo.AppEntry
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.shownTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps playback alive outside the app and puts the usual controls in the shade and on the lock screen, under the note's name. The
 * name is private content: on a locked screen Android shows it or the plain «Воспроизведение записи», as the person chose in its
 * own lock-screen setting for sensitive notifications.
 */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private val player get() = NotePlayer.get(this)

    /** The note that plays and its name, read once a note. */
    private var named: Pair<String, String>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW),
        )
        session =
            MediaSession(this, "echo-playback").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() = player.play()

                        override fun onPause() = player.pause()

                        override fun onStop() = player.stop()

                        override fun onSeekTo(pos: Long) = player.seekTo(pos)

                        override fun onSkipToNext() = player.skip(SKIP_MS)

                        override fun onSkipToPrevious() = player.skip(-SKIP_MS)
                    },
                )
                setSessionActivity(PendingIntent.getActivity(this@PlaybackService, 0, AppEntry.intent(this@PlaybackService), PendingIntent.FLAG_IMMUTABLE))
                isActive = true
            }
        startForeground(ID, notification(player.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        scope.launch {
            // Only a change of note, play/pause or a jump needs a new notification; the bar moves by itself.
            player.state.distinctUntilChangedBy { Triple(it.noteId, it.playing, it.positionMs / JUMP_MS) }.collect { state ->
                val id = state.noteId
                if (id != null && named?.first != id) {
                    named = id to withContext(Dispatchers.IO) { runCatching { NoteStore.list(this@PlaybackService).firstOrNull { it.id == id }?.shownTitle() }.getOrNull().orEmpty() }
                }
                render(state)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player.playPause()
            ACTION_BACK -> player.skip(-SKIP_MS)
            ACTION_FORWARD -> player.skip(SKIP_MS)
            ACTION_STOP -> player.stop()
        }
        return START_NOT_STICKY
    }

    private fun render(state: NotePlayer.State) {
        if (state.noteId == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        session.setMetadata(
            MediaMetadata
                .Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title(state))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.totalMs)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState
                .Builder()
                .setActions(ACTIONS)
                .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, state.positionMs, if (state.playing) 1f else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
        // Playing keeps the service in front; paused, the card can be swiped away like any other player.
        if (state.playing) {
            startForeground(ID, notification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            stopForeground(STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java)?.notify(ID, notification(state))
        }
    }

    private fun notification(state: NotePlayer.State): Notification {
        fun action(
            icon: Int,
            title: Int,
            name: String,
        ) = Notification.Action
            .Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon),
                getString(title),
                PendingIntent.getService(this, name.hashCode(), Intent(this, PlaybackService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build()
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title(state))
            .setContentIntent(PendingIntent.getActivity(this, 0, AppEntry.intent(this), PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(getString(R.string.playback_title)).build(),
            )
            .setOngoing(state.playing)
            .addAction(action(android.R.drawable.ic_media_rew, R.string.playback_back, ACTION_BACK))
            .addAction(
                if (state.playing) {
                    action(android.R.drawable.ic_media_pause, R.string.playback_pause, ACTION_PLAY_PAUSE)
                } else {
                    action(android.R.drawable.ic_media_play, R.string.playback_play, ACTION_PLAY_PAUSE)
                },
            ).addAction(action(android.R.drawable.ic_media_ff, R.string.playback_forward, ACTION_FORWARD))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, R.string.playback_stop, ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    /** The playing note's name, or «Воспроизведение записи» while it is not read yet or has none. */
    private fun title(state: NotePlayer.State): String = named?.takeIf { it.first == state.noteId }?.second?.trim()?.take(TITLE_CHARS)?.ifBlank { null } ?: getString(R.string.playback_title)

    override fun onDestroy() {
        scope.cancel()
        player.stop()
        session.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val ID = 7
        private const val SKIP_MS = 15_000L
        private const val JUMP_MS = 2_000L
        private const val TITLE_CHARS = 80
        private const val ACTION_PLAY_PAUSE = "com.govorun.echo.PLAY_PAUSE"
        private const val ACTION_BACK = "com.govorun.echo.BACK"
        private const val ACTION_FORWARD = "com.govorun.echo.FORWARD"
        private const val ACTION_STOP = "com.govorun.echo.STOP_PLAYBACK"
        private const val ACTIONS =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
