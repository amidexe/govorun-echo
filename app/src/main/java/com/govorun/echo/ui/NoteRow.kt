package com.govorun.echo.ui

import com.govorun.echo.notes.shownTitle
import com.govorun.echo.notes.aboutLine
import com.govorun.echo.notes.smartName
import com.govorun.echo.notes.typedTitle
import com.govorun.echo.notes.listText
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.TextAa
import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.adamglin.phosphoricons.regular.Archive
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.Microphone
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Star
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.Warning
import com.govorun.echo.R
import com.govorun.echo.playback.NotePlayer
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens
import com.govorun.echo.formatDuration

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteRow(
    note: NoteStore.Entry,
    last: Boolean,
    selected: Boolean,
    selecting: Boolean,
    player: NotePlayer.State?,
    actions: NotesActions,
    modifier: Modifier = Modifier,
    /** What the search looks for, while searching. */
    search: com.govorun.echo.notes.SearchWords? = null,
    first: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unseen = note.unseen == true && com.govorun.echo.notes.NoteSeen.ready(note.state)
    val attentionLabel = stringResource(if (note.unseen == true) R.string.note_unseen else R.string.note_seen)
    val shape = if (selected) RoundedCornerShape(ProductTokens.Radius.Card) else RoundedCornerShape(topStart = if (first) ProductTokens.Radius.Card else 0.dp, topEnd = if (first) ProductTokens.Radius.Card else 0.dp, bottomStart = if (last) ProductTokens.Radius.Card else 0.dp, bottomEnd = if (last) ProductTokens.Radius.Card else 0.dp)
    val failed = note.state == NoteStore.STATE_FAILED && !note.waitsForGovorun() && note.error != "postponed"
    var menu by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    // A file being recognized is an ordinary note: it can be swiped away (that stops it) or archived; only a recording cannot.
    val canSwipe = !selecting && note.state != NoteStore.STATE_RECORDING && !note.archiving
    val dismiss =
        rememberSwipeToDismissBoxState(
            confirmValueChange = {
                when (it) {
                    SwipeToDismissBoxValue.EndToStart -> actions.onDelete(listOf(note.id))
                    SwipeToDismissBoxValue.StartToEnd -> actions.onArchive(listOf(note.id), !note.archived)
                    else -> Unit
                }
                false
            },
        )
    SwipeToDismissBox(
        state = dismiss,
        modifier = modifier.clip(shape),
        enableDismissFromStartToEnd = canSwipe,
        enableDismissFromEndToStart = canSwipe,
        backgroundContent = {
            val toDelete = dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (toDelete) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (toDelete) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Icon(
                    if (toDelete) PhosphorIcons.Regular.Trash else if (note.archived) PhosphorIcons.Regular.ArrowUUpLeft else PhosphorIcons.Regular.Archive,
                    contentDescription = null,
                    tint = if (toDelete) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(ProductTokens.Size.ActiveIcon),
                )
            }
        },
    ) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .animateContentSize(),
    ) {
        if (!first && !selected) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = ProductTokens.Alpha.Divider)))
        Row(
            Modifier
                .clip(RoundedCornerShape(ProductTokens.Radius.Md))
                .background(if (note.state == NoteStore.STATE_RECORDING) MaterialTheme.colorScheme.error.copy(alpha = 0.16f) else Color.Transparent)
                .semantics { if (note.unseen != null) stateDescription = attentionLabel }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { actions.onCardClick(note.id) },
                    onLongClick = { actions.onCardLongClick(note.id) },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Duration never measures this column: play, text and selection share one fixed axis.
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                Leading(note, selected, selecting, player, actions)
            }
            if (menu) NoteMenu(note, actions, onTags = { tagging = true }) { menu = false }
            if (tagging) NoteTagsSheet(listOf(note), actions, { tag, add -> actions.onSetTag(listOf(note.id), tag, add) }) { tagging = false }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xs)) {
                if (note.state == NoteStore.STATE_RECOGNIZING) {
                    val gate by com.govorun.echo.recognition.RecognitionStatus.pausedReason.collectAsState()
                    com.product.ui.ThinProgress(recognitionFraction(note), Modifier.padding(vertical = 2.dp), resting = gate == com.govorun.echo.recognition.RecognitionStatus.RECORDING)
                }
                // A named card shows its name first and two quieter lines under it, as a mail list shows a subject over its preview;
                // a note nobody named shows three lines of its text, and the row keeps its height. A name is the owner's, or the one
                // the smart functions gave; the text stays under it.
                val cardText = remember(note) { note.listText() }
                val name = cardText.title
                val named = name.isNotBlank()
                if (named) {
                    Text(name, style = MaterialTheme.typography.titleSmall.echoWeight(if (unseen) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (note.text.isNotBlank() && (cardText.preview.isNotBlank() || player != null || note.isInProgress())) {
                    val lines = 2
                    val body = if (named) MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant) else MaterialTheme.typography.bodyLarge.echoWeight(if (unseen) FontWeight.Bold else FontWeight.Normal)
                    // The playing note's row follows the spoken word: only its word times are read, the list holds none.
                    val timed = if (player != null) rememberWords(note) else null
                    // In a search the card lights only what was found: the word being played is lit the same way and would pass for a find.
                    val spoken = if (search == null && timed != null && player != null && (player.playing || player.positionMs > 0)) spokenWord(timed, player.positionMs) else null
                    val listeningToImport = note.mode == com.govorun.echo.notes.NoteImport.MODE && !note.appending && spoken != null
                    if (note.isInProgress() && !listeningToImport) {
                        TailText(note.text, body, lines = lines)
                    } else if (spoken != null) {
                        SubtitleText(spoken, MaterialTheme.typography.bodyMedium, lines = lines)
                    } else if (search == null && cardText.preview.isNotBlank()) {
                        // What the note is about instead of how it begins: from its итоги, else its fair copy or «Кратко».
                        Text(cardText.preview, style = body, maxLines = lines, overflow = TextOverflow.Ellipsis)
                    } else if (search != null) {
                        // In a search the card's own lines light the words found in them, as its passage does; a long text is read only as far as the lines go.
                        val highlight = MaterialTheme.colorScheme.primaryContainer
                        val lit =
                            remember(cardText.preview, search, lines, highlight) {
                                val head = cardText.preview.take(lines * 200)
                                androidx.compose.ui.text.buildAnnotatedString {
                                    append(head)
                                    foundIn(head, search).forEach { addStyle(androidx.compose.ui.text.SpanStyle(background = highlight), it.first, it.last + 1) }
                                }
                            }
                        Text(lit, style = body, maxLines = lines, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(
                            cardText.preview,
                            style = body,
                            maxLines = lines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // In a search, a long note also shows where in it the words are; a tap opens it there.
                    if (search != null && !note.isInProgress()) {
                        FoundPassage(timed ?: note, search, if (spoken == null) lines else 0, body, selecting, actions, cardText.preview, name)
                    }
                }
                NoteAttention(note, failed)
                if (search != null && note.archived) Text(stringResource(R.string.search_in_archive), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Each tag in its own colour, the same one it has in the row above the list; those that do not fit are counted.
                if (note.durationMs > 0 || note.tags.isNotEmpty() || note.state == NoteStore.STATE_RECORDING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (note.state == NoteStore.STATE_RECORDING) {
                            val live by com.govorun.echo.record.RecordingBus.state.collectAsState()
                            ElapsedText((live as? com.govorun.echo.record.RecordingState.Recording)?.startedAtMs ?: note.startedAtMs, if (note.appending) note.durationMs else 0L)
                        } else NoteListDuration(note.durationMs)
                        if (note.tags.isNotEmpty()) TagsInLine(note.tags, actions.tagStyles, Modifier.weight(1f))
                    }
                }
                if (player != null && note.audio.isNotEmpty() && !selecting) InlinePlayer(note, player, actions)
                stopLabel(note)?.let {
                    Text(stringResource(it), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (note.silencedMs >= 2_000) {
                    Text(
                        stringResource(R.string.mic_was_busy, formatDuration(note.silencedMs)),
                        style = noteSupportingStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (note.pinned) Icon(PhosphorIcons.Fill.Star, contentDescription = stringResource(R.string.note_pinned), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                    NoteCreatedDate(note.startedAtMs, unseen)
                    if (unseen) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
                }
                if (!selecting) IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(PhosphorIcons.Regular.DotsThree, contentDescription = stringResource(R.string.action_more), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Spacer(Modifier.size(40.dp))
            }
        }
    }
    }
}

/** The circle in front of a row, where Dayplan has its checkbox: play for recordings, a mark otherwise. */
@Composable
private fun Leading(
    note: NoteStore.Entry,
    selected: Boolean,
    selecting: Boolean,
    player: NotePlayer.State?,
    actions: NotesActions,
) {
    val tonal = MaterialTheme.colorScheme.surfaceContainerHighest
    when {
        selecting ->
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { com.product.ui.ChoiceMark(selected) }
        note.state == NoteStore.STATE_RECORDING -> {
            // Pause and go on here, as in the notification; «Стоп» is the big button at the bottom.
            val stopping by com.govorun.echo.record.RecordingBus.stopping.collectAsState()
            val paused by com.govorun.echo.record.RecordingBus.pausedAt.collectAsState()
            Box(
                Modifier
                    .size(36.dp)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !stopping) { actions.onPauseRecording(paused == null) },
                contentAlignment = Alignment.Center,
            ) {
                // The tap is answered at once: a spinner until the recording is saved.
                if (stopping) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                } else {
                    Icon(
                        if (paused == null) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play,
                        contentDescription = stringResource(if (paused == null) R.string.action_pause else R.string.action_continue_recording),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        note.audio.isNotEmpty() -> {
            Box(
                Modifier
                    .size(36.dp)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(if (player != null) MaterialTheme.colorScheme.primaryContainer else tonal)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.onPlay(note.id) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (player?.playing == true) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play,
                    contentDescription = stringResource(if (player?.playing == true) R.string.action_pause else R.string.action_play),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        else ->
            Box(Modifier.size(32.dp).clip(CircleShape).background(tonal), contentAlignment = Alignment.Center) {
                Icon(
                    when {
                        note.state == NoteStore.STATE_FAILED && !note.waitsForGovorun() -> PhosphorIcons.Regular.Warning
                        // Its sound deleted: a note of text only.
                        note.state == NoteStore.STATE_DONE -> PhosphorIcons.Regular.TextAa
                        else -> PhosphorIcons.Regular.Microphone
                    },
                    contentDescription = null,
                    tint = if (note.state == NoteStore.STATE_FAILED && !note.waitsForGovorun()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
    }
}
