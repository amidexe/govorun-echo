package com.govorun.echo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.govorun.echo.formatDuration
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductRow
import com.product.ui.ProductRowGroup
import com.product.ui.ProductSheet
import com.product.ui.ProductTokens

/**
 * Everything a note can do, in one sheet, like the card menu in the dictaphone apps we compared with. On the note's own screen it
 * leaves out what that screen already has at hand, as an overflow menu does, so the sheet does not grow with every new action.
 */
@Composable
internal fun NoteMenu(
    note: NoteStore.Entry,
    actions: NotesActions,
    onTags: () -> Unit,
    /** «Фрагмент», on the note's own screen: choose a piece of it there. */
    onFragment: (() -> Unit)? = null,
    /** Opened on the note's own screen: its bar shares the text and its microphone continues the note; copying lives here. */
    onNoteScreen: Boolean = false,
    onFairPrevious: (() -> Unit)? = null,
    /** What «Копировать» copies on the note's screen: the tab that is open, the transcript or the «Текст». */
    onCopy: (() -> Unit)? = null,
    copyAvailable: Boolean = note.text.isNotBlank(),
    /** «Подвести итоги» (or «Итоги» once made), on the note's own screen for a long finished note. */
    onItogi: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ProductSheet(stringResource(R.string.note_actions_title), onDismiss) {
        if (!onNoteScreen) {
            Text(
                listOfNotNull(
                    formatDayTime(context, note.startedAtMs),
                    formatDuration(note.durationMs).takeIf { note.durationMs > 0 },
                    stringResource(statusLabel(note)).takeIf { note.mode == "import" || note.mode == "merged" },
                ).joinToString(" · "),
                modifier = Modifier.padding(horizontal = ProductTokens.Spacing.Lg),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        fun act(block: () -> Unit) = { onDismiss(); block() }
        ProductRowGroup {
            if (copyAvailable) {
                // Copying is a quiet second to «Поделиться»: in this menu, not a button of its own in the note's bar.
                if (!onNoteScreen) MenuRow(PhosphorIcons.Regular.ShareNetwork, R.string.action_share_text, R.string.action_share_hint, onClick = act { actions.onShare(listOf(note.id)) })
                MenuRow(PhosphorIcons.Regular.Copy, R.string.action_copy_text, null, onClick = act { onCopy?.invoke() ?: actions.onCopy(listOf(note.id)) })
            }
            if (note.audio.isNotEmpty()) {
                MenuRow(PhosphorIcons.Regular.DownloadSimple, R.string.action_export, R.string.action_export_hint, onClick = act { actions.onExport(listOf(note.id)) })
            }
            MenuRow(PhosphorIcons.Regular.Tag, R.string.action_tags, null, onClick = act { onTags() })
            if (com.govorun.echo.notes.NoteSeen.ready(note.state) || note.pinned) {
                val failureMessage = stringResource(R.string.favorite_failed)
                MenuRow(PhosphorIcons.Regular.Star, if (note.pinned) R.string.action_unpin else R.string.action_pin, null, onClick = act {
                    if (com.govorun.echo.notes.NoteFavorites.set(com.govorun.echo.notes.NoteStore.root(context), note.id, !note.pinned)) actions.onChanged()
                    else android.widget.Toast.makeText(context, failureMessage, android.widget.Toast.LENGTH_LONG).show()
                })
            }
            if (com.govorun.echo.notes.NoteSeen.ready(note.state)) MenuRow(
                if (note.unseen == true) PhosphorIcons.Regular.EnvelopeOpen else PhosphorIcons.Regular.Envelope,
                if (note.unseen == true) R.string.note_mark_seen else R.string.note_mark_unseen, null, onClick = act {
                    if (com.govorun.echo.notes.NoteSeen.set(com.govorun.echo.notes.NoteStore.root(context), note.id, note.unseen != true)) actions.onChanged()
                    else android.widget.Toast.makeText(context, R.string.note_seen_failed, android.widget.Toast.LENGTH_SHORT).show()
                },
            )
        }
        ProductRowGroup {
            if (onFairPrevious != null) MenuRow(PhosphorIcons.Regular.ArrowUUpLeft, R.string.fair_previous, R.string.fair_previous_hint, onClick = act { onFairPrevious() })
            if (onItogi != null) {
                MenuRow(PhosphorIcons.Regular.Sparkle, if (note.itogi != null) R.string.itogi_title else R.string.itogi_go, R.string.itogi_go_hint, onClick = act { onItogi() })
            }
            if (onFragment != null) {
                MenuRow(PhosphorIcons.Regular.Scissors, R.string.fragment_title, R.string.fragment_menu_hint, onClick = act { onFragment() })
            }
            if (note.state == NoteStore.STATE_DONE && note.audio.isNotEmpty() && !onNoteScreen) {
                MenuRow(recordingGlyph(), R.string.action_append, null, onClick = act { actions.onAppend(note.id) })
            }
            if (note.canSplit) {
                MenuRow(PhosphorIcons.Regular.ArrowsSplit, R.string.action_split, R.string.action_split_hint, onClick = act { actions.onSplit(note.id) })
            }
            if (note.state == NoteStore.STATE_FAILED && note.error == "postponed") {
                MenuRow(PhosphorIcons.Regular.Play, R.string.action_resume, R.string.action_resume_hint, onClick = act { actions.onRetry(note.id) })
            } else if (note.canRetry()) {
                MenuRow(PhosphorIcons.Regular.ArrowClockwise, R.string.action_retry, R.string.action_retry_hint, onClick = act { actions.onRetry(note.id) })
            }
            // A long file or a wait for Govorun can be put off: the sound and what was heard stay, «Распознать заново» goes on.
            if (note.state == NoteStore.STATE_RECOGNIZING && note.error != com.govorun.echo.notes.NoteImport.PREPARING) {
                MenuRow(PhosphorIcons.Regular.Pause, R.string.action_postpone, R.string.action_postpone_hint, onClick = act { actions.onPostpone(note.id) })
            }
            if (note.state == NoteStore.STATE_DONE || note.state == NoteStore.STATE_FAILED) {
                if (note.archived) {
                    MenuRow(PhosphorIcons.Regular.ArrowUUpLeft, R.string.action_unarchive, R.string.action_unarchive_hint, onClick = act { actions.onArchive(listOf(note.id), false) })
                } else {
                    MenuRow(PhosphorIcons.Regular.Archive, R.string.action_archive, R.string.action_archive_hint, onClick = act { actions.onArchive(listOf(note.id), true) })
                }
            }
            if (note.audio.isNotEmpty()) {
                MenuRow(PhosphorIcons.Regular.SpeakerSlash, R.string.action_delete_audio, R.string.action_delete_audio_hint, onClick = act { actions.onDeleteAudio(note.id) })
            }
            MenuRow(PhosphorIcons.Regular.Trash, R.string.action_delete, R.string.action_delete_note_hint, danger = true, onClick = act { actions.onDelete(listOf(note.id)) })
        }
    }
}

/** Additional selection actions use the same grouped sheet as a single note's menu. */
@Composable
internal fun SelectionMenu(
    ids: List<String>,
    canMerge: Boolean,
    actions: NotesActions,
    onDismiss: () -> Unit,
) {
    ProductSheet(stringResource(R.string.selected_count, ids.size), onDismiss) {
        fun act(block: () -> Unit) = { onDismiss(); block() }
        ProductRowGroup {
            MenuRow(PhosphorIcons.Regular.ShareNetwork, R.string.action_share_text, R.string.action_share_hint, onClick = act { actions.onShare(ids) })
            MenuRow(PhosphorIcons.Regular.Copy, R.string.action_copy_text, null, onClick = act { actions.onCopy(ids) })
            MenuRow(PhosphorIcons.Regular.DownloadSimple, R.string.action_export, R.string.action_export_hint, onClick = act { actions.onExport(ids) })
            MenuRow(PhosphorIcons.Regular.EnvelopeOpen, R.string.notes_mark_seen, null, onClick = act { actions.onSetSeen(ids, false) })
            MenuRow(PhosphorIcons.Regular.Envelope, R.string.notes_mark_unseen, null, onClick = act { actions.onSetSeen(ids, true) })
        }
        ProductRowGroup {
            if (canMerge) MenuRow(PhosphorIcons.Regular.ArrowsMerge, R.string.action_merge, R.string.action_merge_hint, onClick = act { actions.onMerge(ids) })
            MenuRow(PhosphorIcons.Regular.Trash, R.string.action_delete, R.string.action_delete_note_hint, danger = true, onClick = act { actions.onDelete(ids) })
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: Int,
    hint: Int?,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ProductRow(
        title = stringResource(title),
        summary = hint?.let { stringResource(it) },
        icon = icon,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        minHeight = ProductTokens.Size.SheetAction,
        color = if (danger) colors.error else colors.onSurface,
        iconColor = if (danger) colors.error else colors.onSurfaceVariant,
    )
}
