package com.govorun.echo.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteText

/** Restore is visible beside the text it changes; undo and Save still belong to the editor. */
@Composable
internal fun EditorOriginalAction(note: NoteStore.Entry, state: FairState) {
    val context = LocalContext.current
    var confirming by remember(note.id) { mutableStateOf(false) }
    if (note.mode != NoteText.MODE && state.draft.text.toString().trim() != note.text.trim()) {
        TextButton(enabled = !state.busy, onClick = { confirming = true }) {
            Text(stringResource(R.string.fair_reset_original))
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.fair_reset_title)) },
        text = { Text(stringResource(R.string.fair_reset_warning)) },
        confirmButton = {
            TextButton(enabled = !state.busy, onClick = { confirming = false; state.resetOriginal(context, note) }) {
                Text(stringResource(R.string.fair_reset_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.fair_cancel)) } },
    )
}
