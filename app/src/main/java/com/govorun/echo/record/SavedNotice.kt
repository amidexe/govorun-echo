package com.govorun.echo.record

import com.govorun.echo.CapturePurpose
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.govorun.echo.AppEntry
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import org.json.JSONObject
import java.io.File

/**
 * «Заметка готова» in the shade, when the person asked for it in Settings: the last note whose text has come, its name and first
 * words, one tap away, so a note said in passing is not forgotten, with «Поделиться» and «Удалить» right there. Only the last one
 * stays; nothing while Echo itself is on screen, and the lock screen shows no words of it. With «Вход по отпечатку» on, the shade
 * says only that a note is ready: its words and buttons wait behind the finger, as everything else in Echo does.
 */
internal object SavedNotice {
    private const val CHANNEL = "note_ready"
    private const val ID = 7_003
    private const val EXTRA_NOTE = AppEntry.NOTE_EXTRA
    private const val NAME_CHARS = 60

    fun show(
        context: Context,
        noteId: String,
    ) {
        if (!AppSettings.savedNotice(context) || onScreen()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val dir = File(NoteStore.root(context), noteId)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return
        val purpose = CapturePurpose.read(meta)
        if (purpose == CapturePurpose.COMMAND) return
        val text = runCatching { File(dir, "text.txt").readText().trim() }.getOrDefault("")
        if (text.isEmpty() || purpose == CapturePurpose.QUICK &&
            com.govorun.echo.commands.Matcher.find(text, com.govorun.echo.commands.CommandStore(context).commands()) != null) return
        val locked = AppSettings.appLock(context) && context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
        val title = meta.optString("title")
        val name = title.ifBlank { text.lineSequence().first().take(NAME_CHARS) }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.saved_notice_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open =
            PendingIntent.getActivity(
                context,
                ID,
                open(context, noteId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val hidden =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_recording)
                .setContentTitle(context.getString(R.string.saved_notice_title))
                .build()
        val notification =
            if (locked) {
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_recording).setContentTitle(context.getString(R.string.saved_notice_title))
                    .setContentIntent(open).setAutoCancel(true).build()
            } else {
                val share =
                    PendingIntent.getActivity(
                        context,
                        ID + 1,
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val delete =
                    PendingIntent.getBroadcast(
                        context,
                        ID + 2,
                        Intent(context, NoticeActions::class.java).setAction(NoticeActions.DELETE).putExtra(EXTRA_NOTE, noteId),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                NotificationCompat
                    .Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_recording)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(hidden)
                    .setContentIntent(open)
                    .addAction(0, context.getString(R.string.action_share), share)
                    .addAction(0, context.getString(R.string.action_delete), delete)
                    .setAutoCancel(true)
                    .build()
            }
        runCatching { manager.notify(ID, notification) }
    }

    /** Echo opened on note [noteId]: from the notice, or right after a text was shared in. */
    fun open(
        context: Context,
        noteId: String,
        byUser: Boolean = true,
    ): Intent = AppEntry.note(context, noteId, byUser)

    /** The note a tap on the notice, or a shared text, asks to open, if this start came from one. */
    data class Open(val id: String, val byUser: Boolean)
    fun noteOf(intent: Intent?): Open? = intent?.getStringExtra(EXTRA_NOTE)?.let { Open(it, !intent.getBooleanExtra("echo_auto_open", false)) }

    /** «Удалить» on the notice: the note goes to the trash, as from the list, and the notice goes. */
    fun delete(
        context: Context,
        noteId: String,
    ) {
        val moved = com.govorun.echo.notes.NoteTrash.move(NoteStore.root(context), com.govorun.echo.notes.NoteTrash.root(context), listOf(noteId))
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
        if (moved.isNotEmpty()) android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(context, R.string.deleted_one, android.widget.Toast.LENGTH_SHORT).show() }
        com.govorun.echo.backup.Snapshot.write(context)
    }

    internal fun noteExtra(intent: Intent): String? = intent.getStringExtra(EXTRA_NOTE)

    private fun onScreen(): Boolean = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}
