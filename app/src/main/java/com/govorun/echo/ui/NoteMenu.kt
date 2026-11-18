package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Archive
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.adamglin.phosphoricons.regular.ArrowsMerge
import com.adamglin.phosphoricons.regular.ArrowsSplit
import com.adamglin.phosphoricons.regular.Copy
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Envelope
import com.adamglin.phosphoricons.regular.EnvelopeOpen
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.Scissors
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.SpeakerSlash
import com.adamglin.phosphoricons.regular.Star
import com.adamglin.phosphoricons.regular.Tag
import com.adamglin.phosphoricons.regular.Trash
import com.govorun.echo.R
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.govorun.echo.notes.NoteFavorites
import com.govorun.echo.notes.NoteSeen
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductActionMenu
import com.product.ui.ProductMenuAction

/** Card actions address the whole note. The opened note also gives access to its content. */
@Composable
internal fun NoteMenu(
    note: NoteStore.Entry,
    actions: NotesActions,
    onTags: () -> Unit,
    onFragment: (() -> Unit)? = null,
    onNoteScreen: Boolean = false,
    onFairPrevious: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    copyAvailable: Boolean = note.text.isNotBlank(),
    onItogi: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val ids = listOf(note.id)
    val failureMark = stringResource(R.string.note_seen_failed)
    val failureFavorite = stringResource(R.string.favorite_failed)
    fun act(block: () -> Unit) = { onDismiss(); block() }

    val managing = buildList {
        add(menuAction(PhosphorIcons.Regular.Tag, R.string.action_tags, onClick = act(onTags)))
        if (NoteSeen.ready(note.state) || note.pinned) {
            add(menuAction(PhosphorIcons.Regular.Star, if (note.pinned) R.string.action_unpin else R.string.action_pin) {
                onDismiss()
                if (NoteFavorites.set(NoteStore.root(context), note.id, !note.pinned)) actions.onChanged()
                else Toast.makeText(context, failureFavorite, Toast.LENGTH_LONG).show()
            })
        }
        if (onNoteScreen && NoteSeen.ready(note.state)) {
            val unread = note.unseen == true
            add(menuAction(
                if (unread) PhosphorIcons.Regular.EnvelopeOpen else PhosphorIcons.Regular.Envelope,
                if (unread) R.string.note_mark_seen else R.string.note_mark_unseen,
            ) {
                onDismiss()
                if (NoteSeen.set(NoteStore.root(context), note.id, !unread)) actions.onChanged()
                else Toast.makeText(context, failureMark, Toast.LENGTH_SHORT).show()
            })
        }
    }
    val content = buildList {
        if (onFairPrevious != null) {
            add(menuAction(PhosphorIcons.Regular.ArrowUUpLeft, R.string.fair_previous, onClick = act(onFairPrevious)))
        }
        if (onItogi != null) {
            add(menuAction(PhosphorIcons.Regular.Sparkle, if (note.itogi != null) R.string.itogi_title else R.string.itogi_go, onClick = act(onItogi)))
        }
        if (onFragment != null) {
            add(menuAction(PhosphorIcons.Regular.Scissors, R.string.fragment_title, onClick = act(onFragment)))
        }
        if (onNoteScreen && note.canSplit) {
            add(menuAction(PhosphorIcons.Regular.ArrowsSplit, R.string.action_split, onClick = act { actions.onSplit(note.id) }))
        }
        if (note.state == NoteStore.STATE_FAILED && note.error == "postponed") {
            add(menuAction(PhosphorIcons.Regular.Play, R.string.action_resume, onClick = act { actions.onRetry(note.id) }))
        } else if (note.canRetry()) {
            add(menuAction(PhosphorIcons.Regular.ArrowClockwise, R.string.action_retry, onClick = act { actions.onRetry(note.id) }))
        }
        if (note.state == NoteStore.STATE_RECOGNIZING && note.error != com.govorun.echo.notes.NoteImport.PREPARING) {
            add(menuAction(PhosphorIcons.Regular.Pause, R.string.action_postpone, onClick = act { actions.onPostpone(note.id) }))
        }
        if (onNoteScreen && note.audio.isNotEmpty()) {
            add(menuAction(PhosphorIcons.Regular.SpeakerSlash, R.string.action_delete_audio, onClick = act { actions.onDeleteAudio(note.id) }))
        }
    }
    val lifecycle = buildList {
        if (note.state == NoteStore.STATE_DONE || note.state == NoteStore.STATE_FAILED) {
            add(menuAction(
                if (note.archived) PhosphorIcons.Regular.ArrowUUpLeft else PhosphorIcons.Regular.Archive,
                if (note.archived) R.string.action_unarchive else R.string.action_archive,
                onClick = act { actions.onArchive(ids, !note.archived) },
            ))
        }
        add(menuAction(PhosphorIcons.Regular.Trash, R.string.action_delete_note, destructive = true, onClick = act { actions.onDelete(ids) }))
    }

    ProductActionMenu(
        title = stringResource(R.string.note_actions_title),
        quick = noteOutputActions(
            share = if (!onNoteScreen && copyAvailable) act { actions.onShare(ids) } else null,
            copy = if (copyAvailable) act { onCopy?.invoke() ?: actions.onCopy(ids) } else null,
            file = if (note.audio.isNotEmpty()) act { actions.onExport(ids) } else null,
        ),
        groups = listOf(managing, content, lifecycle),
        detail = if (onNoteScreen && note.silencedMs >= 2_000) stringResource(R.string.note_audio_gap, note.silencedMs / 1_000) else null,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun SelectionMenu(
    ids: List<String>,
    canMerge: Boolean,
    actions: NotesActions,
    onDismiss: () -> Unit,
) {
    fun act(block: () -> Unit) = { onDismiss(); block() }
    ProductActionMenu(
        title = stringResource(R.string.selected_count, ids.size),
        quick = noteOutputActions(
            share = act { actions.onShare(ids) },
            copy = act { actions.onCopy(ids) },
            file = act { actions.onExport(ids) },
        ),
        groups = listOf(
            listOf(
                menuAction(PhosphorIcons.Regular.EnvelopeOpen, R.string.notes_mark_seen, onClick = act { actions.onSetSeen(ids, false) }),
                menuAction(PhosphorIcons.Regular.Envelope, R.string.notes_mark_unseen, onClick = act { actions.onSetSeen(ids, true) }),
            ),
            buildList {
                if (canMerge) add(menuAction(PhosphorIcons.Regular.ArrowsMerge, R.string.action_merge, onClick = act { actions.onMerge(ids) }))
                add(menuAction(PhosphorIcons.Regular.Trash, if (ids.size == 1) R.string.action_delete_note else R.string.action_delete_notes, destructive = true, onClick = act { actions.onDelete(ids) }))
            },
        ),
        onDismiss = onDismiss,
    )
}

@Composable
private fun noteOutputActions(
    share: (() -> Unit)?,
    copy: (() -> Unit)?,
    file: (() -> Unit)?,
): List<ProductMenuAction> = buildList {
    if (share != null) add(menuAction(PhosphorIcons.Regular.ShareNetwork, R.string.action_share_text, R.string.action_share_caption, onClick = share))
    if (copy != null) add(menuAction(PhosphorIcons.Regular.Copy, R.string.action_copy_text, R.string.action_copy_caption, onClick = copy))
    if (file != null) add(menuAction(PhosphorIcons.Regular.DownloadSimple, R.string.action_export, R.string.action_file_caption, onClick = file))
}

@Composable
private fun menuAction(
    icon: ImageVector,
    label: Int,
    caption: Int = label,
    destructive: Boolean = false,
    onClick: () -> Unit,
): ProductMenuAction = ProductMenuAction(icon, stringResource(label), stringResource(caption), destructive, onClick)
