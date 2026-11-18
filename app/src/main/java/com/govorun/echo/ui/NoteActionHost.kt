package com.govorun.echo.ui

import android.app.Activity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteTrash
import com.govorun.echo.playback.NotePlayer
import com.govorun.echo.record.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MainActivity.splitNote(
    id: String,
    playingId: () -> String?,
    player: NotePlayer,
    scope: CoroutineScope,
    onDone: () -> Unit,
) {
    val activity = this

    confirmDialog(
        R.string.dialog_split_title,
        R.string.dialog_split_text,
        R.string.action_split,
    ) {
        if (playingId() == id) player.stop()
        scope
            .launch(Dispatchers.IO) {
                com.govorun.echo.notes.NoteSplit.split(
                        NoteStore.root(activity),
                        id,
                    )
                    ?.forEach {
                        com.govorun.echo.smart.itogiByItself(activity, it)
                    }
            }
            .invokeOnCompletion {
                onDone()
            }
    }
}

internal fun MainActivity.mergeNotes(ids: List<String>, scope: CoroutineScope, onDone: () -> Unit) {
    val activity = this

    confirmDialog(
        R.string.dialog_merge_title,
        R.string.dialog_merge_text,
        R.string.action_merge,
    ) {
        scope
            .launch(Dispatchers.IO) {
                NoteStore.merge(NoteStore.root(activity), ids)?.let {
                    com.govorun.echo.smart.itogiByItself(activity, it)
                }
            }
            .invokeOnCompletion {
                onDone()
            }
    }
}

internal fun Activity.deleteNotes(
    notes: List<NoteStore.Entry>,
    ids: List<String>,
    playingId: String?,
    player: NotePlayer,
    scope: CoroutineScope,
    deleteBatch: DeleteBatch,
    snackbar: SnackbarHostState,
    onChanged: () -> Unit,
    onRefresh: () -> Unit,
) {
    val activity = this
    if (playingId in ids) player.stop()
    // A note deleted while it is recognized stops at once: Govorun gets no more
    // of its sound.
    notes
        .filter { it.id in ids && it.state == NoteStore.STATE_RECOGNIZING }
        .forEach {
            runCatching {
                startService(RecordingService.postponeIntent(this, it.id))
            }
        }
    scope.launch {
        val (root, trash, moved) = withContext(Dispatchers.IO) {
            val root = NoteStore.root(activity)
            val trash = NoteTrash.root(activity)
            Triple(root, trash, NoteTrash.move(root, trash, ids))
        }
        onChanged()
        if (moved.isEmpty()) return@launch
        ActionSounds.play(activity, ActionSounds.DELETE)
        com.govorun.echo.smart.FairQueue.deleted(activity, moved)
        deleteBatch.deleted(
            moved,
            root,
            trash,
            snackbar,
            {
                if (it == 1) getString(R.string.deleted_one)
                else getString(R.string.deleted_many, it)
            },
            getString(R.string.action_undo),
            onRefresh,
        )
    }
}

internal suspend fun Activity.appendRecording(
    id: String,
    notes: () -> List<NoteStore.Entry>,
    snackbar: SnackbarHostState,
    onAuthorize: () -> Unit,
) {
    val activity = this

    // Right after a stop the note's text is still being finished (usually
    // under a second): wait for it, so the
    // new recording continues this note instead of starting another one.
    val dir = NoteStore.noteDir(activity, id)
    val ready =
        kotlinx.coroutines.withTimeoutOrNull(APPEND_WAIT_MS) {
            while (!NoteStore.isAppendable(dir)) kotlinx.coroutines.delay(150)
        } != null
    if (ready) {
        startForegroundService(
            RecordingService.startIntent(
                activity,
                RecordingService.MODE_LONG,
                appendTo = id,
            )
        )
    } else {
        // A note that failed because Govorun has not allowed Echo is not
        // «getting ready»: say what it waits for.
        val failed = notes().firstOrNull { it.id == id }?.takeIf { it.waitsForGovorun() }
        if (failed != null && failed.error == "needs_authorization") {
            val answer =
                snackbar.showSnackbar(
                    getString(com.govorun.echo.errorLabel(failed.error)),
                    getString(R.string.action_allow),
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
            if (answer == androidx.compose.material3.SnackbarResult.ActionPerformed) onAuthorize()
        } else
            snackbar.showSnackbar(
                getString(
                    failed?.let { com.govorun.echo.errorLabel(it.error) } ?: R.string.append_wait
                )
            )
    }
}

private const val APPEND_WAIT_MS = 5_000L
