package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteText

/** The visible layer used by both toolbar sharing and the note menu. */
internal fun displayedNoteMarkdown(
    note: NoteStore.Entry,
    fair: FairState,
    itogi: ItogiState,
): String? =
    itogi.of(note)?.takeIf { itogi.shown }?.markdown()
        ?: fair.of(note)?.takeIf { fair.shown }?.markdown()

@Composable
internal fun NoteScreenMenu(
    note: NoteStore.Entry,
    actions: NotesActions,
    fairState: FairState,
    itogiState: ItogiState,
    onTags: () -> Unit,
    onFragment: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    NoteMenu(
        note,
        actions,
        onTags = onTags,
        onFragment = onFragment,
        onNoteScreen = true,
        copyAvailable = (displayedNoteMarkdown(note, fairState, itogiState) ?: note.text).isNotBlank(),
        onCopy = {
            displayedNoteMarkdown(note, fairState, itogiState)?.let {
                (context as? android.app.Activity)?.copyText(it)
            } ?: actions.onCopy(listOf(note.id))
        },
        onFairPrevious =
            fairState
                .of(note)
                ?.takeIf { note.mode != NoteText.MODE && !itogiState.shown }
                ?.before
                ?.let { { fairState.restore(context, note, actions.onChanged) } },
        onItogi =
            if (
                !itogiState.tab(context, note) && com.govorun.echo.smart.SmartItogi.fits(context, note)
            )
                ({
                    fairState.shown = false
                    itogiState.start(context, note, actions.onChanged)
                })
            else null,
    ) {
        onDismiss()
    }
}
