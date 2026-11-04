package com.govorun.echo.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.Sparkle
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore

/** Metadata stays outside the text editor; its only editing entry lives beside the source tabs. */
@Composable
internal fun NoteTextHeader(note: NoteStore.Entry, actions: NotesActions, onEditing: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    NoteHeader(note, { actions.onSetTitle(note.id, it) }, onEditing = onEditing, onAbout = { NoteStore.setAbout(NoteStore.root(context), note.id, it); actions.onChanged() }) {}
}

/** One pencil, always direct: edit the saved linked text, or start a draft from the original. */
@Composable
internal fun NoteEditTool(note: NoteStore.Entry, state: FairState, onEnter: () -> Unit = {}) {
    val context = LocalContext.current
    if (note.state == NoteStore.STATE_DONE && (note.text.isNotBlank() || note.mode == com.govorun.echo.notes.NoteText.MODE) && !state.making && !com.govorun.echo.notes.NoteText.isDraft(NoteStore.root(context), note.id)) {
        FairTool(PhosphorIcons.Regular.PencilSimple, R.string.text_edit) {
            val player = com.govorun.echo.playback.NotePlayer.get(context)
            if (player.state.value.noteId == note.id) player.pause()
            onEnter()
            state.edit(note, context)
        }
    }
}

/** Improvement is a preview inside the editor; only the explicit Save commits it. */
@Composable
internal fun RowScope.FairEditorAction(note: NoteStore.Entry, state: FairState) {
    if (state.editing && com.govorun.echo.smart.Smart.chosen() && (state.draft.text.isNotBlank() || note.text.isNotBlank()) && !state.busy) {
        FairTool(PhosphorIcons.Regular.Sparkle, R.string.fair_action, MaterialTheme.colorScheme.primary) { state.choosing = true }
    }
}

/** The editor can survive a tab change; leaving the note must explicitly discard an unsaved draft. */
@Composable
internal fun FairEditGuard(note: NoteStore.Entry, state: FairState, itogi: ItogiState, onChanged: () -> Unit) {
    val context = LocalContext.current
    if (state.leaving) AlertDialog(
        onDismissRequest = { state.leaving = false; state.shown = true; itogi.shown = false },
        title = { Text(stringResource(R.string.fair_leave_title)) },
        text = { Text(stringResource(R.string.fair_leave_text)) },
        confirmButton = {
            TextButton(enabled = !state.busy, onClick = { if (state.save(context, note, onChanged)) itogi.shown = false }) { Text(stringResource(R.string.fair_save)) }
        },
        dismissButton = {
            TextButton(onClick = { state.cancel(context, note) }) { Text(stringResource(R.string.fair_discard)) }
        },
    )
}
