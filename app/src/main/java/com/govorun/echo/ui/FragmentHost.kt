package com.govorun.echo.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.govorun.echo.R
import com.govorun.echo.playback.NotePlayer
import com.govorun.echo.notes.NoteCut
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.WordFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.govorun.echo.notes.CutSound

/** What a note's own screen changes on disk: «Фрагмент» (saved, made a note of its own, shared) and its words fixed. */
internal interface NoteEdits {
    fun save(
        id: String,
        kept: List<NoteCut.Span>,
        done: (Boolean) -> Unit,
    )

    fun separate(
        id: String,
        spans: List<NoteCut.Span>,
        done: () -> Unit,
    )

    fun share(
        id: String,
        spans: List<NoteCut.Span>,
        done: () -> Unit,
    )

    /** Words of the note from [from] on, which the screen showed as [words], typed anew as [typed]. */
    fun fix(
        id: String,
        from: Int,
        words: List<String>,
        typed: String,
    )

    /** Changes nothing: a screen shown without the app behind it. */
    object None : NoteEdits {
        override fun save(
            id: String,
            kept: List<NoteCut.Span>,
            done: (Boolean) -> Unit,
        ) = done(false)

        override fun separate(
            id: String,
            spans: List<NoteCut.Span>,
            done: () -> Unit,
        ) = done()

        override fun share(
            id: String,
            spans: List<NoteCut.Span>,
            done: () -> Unit,
        ) = done()

        override fun fix(
            id: String,
            from: Int,
            words: List<String>,
            typed: String,
        ) = Unit
    }
}

/**
 * What a note's screen does on disk, off the main thread: save a «Фрагмент» draft (the cut sound is deleted for good), make a new
 * note of a piece, send a piece's sound and text through the share sheet, and fix words.
 */
internal class FragmentHost(
    private val activity: ComponentActivity,
    private val player: NotePlayer,
) {
    /** These edits for the running screen: its coroutines, its messages and how it reads the notes again. */
    fun bind(
        scope: CoroutineScope,
        snackbar: SnackbarHostState,
        reload: () -> Unit,
    ): NoteEdits =
        object : NoteEdits {
            override fun save(
                id: String,
                kept: List<NoteCut.Span>,
                done: (Boolean) -> Unit,
            ) = this@FragmentHost.save(scope, snackbar, id, kept, reload, done)

            override fun separate(
                id: String,
                spans: List<NoteCut.Span>,
                done: () -> Unit,
            ) = this@FragmentHost.separate(scope, snackbar, id, spans, reload, done)

            override fun share(
                id: String,
                spans: List<NoteCut.Span>,
                done: () -> Unit,
            ) = this@FragmentHost.share(scope, snackbar, id, spans, done)

            override fun fix(
                id: String,
                from: Int,
                words: List<String>,
                typed: String,
            ) = this@FragmentHost.fix(scope, snackbar, id, from, words, typed, reload)
        }

    /**
     * «Исправить»: words of a note typed anew ([WordFix]). The message after it offers «Отменить», which puts them back while nothing
     * else has changed the note; «Без пауз» and the copy in the folder follow the words either way.
     */
    fun fix(
        scope: CoroutineScope,
        snackbar: SnackbarHostState,
        id: String,
        from: Int,
        words: List<String>,
        typed: String,
        reload: () -> Unit,
    ) {
        val dir = NoteStore.noteDir(activity, id)
        scope.launch {
            val made = withContext(Dispatchers.IO) { WordFix.apply(dir, from, words, typed) }
            snackbar.currentSnackbarData?.dismiss()
            if (made == null) {
                snackbar.showSnackbar(activity.getString(R.string.fix_failed))
                return@launch
            }
            reload()
            player.phrasesChanged(dir)
            com.govorun.echo.backup.BackupFolder.syncAsync(activity)
            val answer = snackbar.showSnackbar(activity.getString(R.string.fix_done), activity.getString(R.string.action_undo), duration = SnackbarDuration.Long)
            if (answer != SnackbarResult.ActionPerformed) return@launch
            val undone = withContext(Dispatchers.IO) { WordFix.undo(dir, made) }
            reload()
            if (!undone) {
                snackbar.showSnackbar(activity.getString(R.string.fix_undo_failed))
                return@launch
            }
            player.phrasesChanged(dir)
            com.govorun.echo.backup.BackupFolder.syncAsync(activity)
        }
    }

    /** «Сохранить»: only [kept] of the note stays, for good; the cut sound is deleted and the copy in the folder follows. */
    fun save(
        scope: CoroutineScope,
        snackbar: SnackbarHostState,
        id: String,
        kept: List<NoteCut.Span>,
        reload: () -> Unit,
        done: (Boolean) -> Unit,
    ) {
        val dir = NoteStore.noteDir(activity, id)
        if (player.state.value.noteId == id) player.stop()
        scope.launch {
            val result = withContext(Dispatchers.IO) { NoteCut.save(dir, kept)?.also { com.govorun.echo.smart.itogiByItself(activity, id) } }
            done(result != null)
            reload()
            snackbar.showSnackbar(activity.getString(if (result != null) R.string.fragment_saved else R.string.fragment_failed))
            if (result != null) com.govorun.echo.backup.BackupFolder.syncAsync(activity)
        }
    }

    fun separate(
        scope: CoroutineScope,
        snackbar: SnackbarHostState,
        id: String,
        spans: List<NoteCut.Span>,
        reload: () -> Unit,
        done: () -> Unit,
    ) {
        scope.launch {
            val made = withContext(Dispatchers.IO) { NoteCut.separate(NoteStore.root(activity), id, spans)?.also { com.govorun.echo.smart.itogiByItself(activity, it) } }
            done()
            reload()
            snackbar.showSnackbar(activity.getString(if (made != null) R.string.fragment_separated else R.string.fragment_failed))
            if (made != null) com.govorun.echo.backup.BackupFolder.syncAsync(activity)
        }
    }

    fun share(
        scope: CoroutineScope,
        snackbar: SnackbarHostState,
        id: String,
        spans: List<NoteCut.Span>,
        done: () -> Unit,
    ) {
        scope.launch {
            val prepared =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = NoteStore.noteDir(activity, id)
                        val exports = com.govorun.echo.export.ExportCache.create(activity)
                        var ready = false
                        try {
                            val name = nameFormat.format(Instant.ofEpochMilli(NoteCut.saidAt(dir, spans.first().fromMs.toLong())))
                            val file = CutSound.soundFile(dir, spans, File(exports, "$name.ogg")) ?: error("no_sound")
                            (file to NoteCut.textOf(dir, spans)).also { ready = true }
                        } finally {
                            com.govorun.echo.export.ExportCache.finish(exports, handedOff = ready)
                        }
                    }.getOrNull()
                }
            done()
            if (prepared == null) {
                snackbar.showSnackbar(activity.getString(R.string.fragment_failed))
                return@launch
            }
            val (file, text) = prepared
            val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType(com.govorun.echo.audio.AudioFormats.mimeOf(file.extension))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (text.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, text)
            send.clipData = ClipData.newRawUri("", uri)
            activity.startActivity(Intent.createChooser(send, null))
        }
    }

    private companion object {
        val nameFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm").withZone(ZoneId.systemDefault())
    }
}
