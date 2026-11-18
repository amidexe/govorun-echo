package com.govorun.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Waveform
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteText
import com.govorun.echo.playback.NotePlayer
import com.product.ui.ProductTokens

@Composable
internal fun NoteTextCard(
    note: NoteStore.Entry,
    player: NotePlayer.State?,
    actions: NotesActions,
    modifier: Modifier,
    onTags: () -> Unit,
    wordsRead: Boolean,
    textDraft: Boolean,
    find: FindInNote?,
    fairState: FairState,
    /** «Расшифровка · Чистовик» at the top of the card, when the note has a fair copy. */
    tabs:
        (@Composable
        (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) -> Unit)? =
        null,
) {
    if (textDraft) {
        val context = LocalContext.current
        val changed by rememberUpdatedState(actions.onChanged)
        // The card's effect is disposed after its fields have flushed the last edits.
        DisposableEffect(note.id) {
            onDispose {
                val root = NoteStore.root(context)
                if (!NoteText.dropIfEmpty(root, note.id)) NoteText.keep(root, note.id)
                changed()
            }
        }
    }
    val scroll = rememberScrollState()
    // While the note plays the text follows the spoken word; a finger scrolling away to read ahead
    // stops that until «К звуку».
    var follow by remember(note.id) { mutableStateOf(true) }
    val playing = player?.playing == true && player.noteId == note.id
    val listening = player?.noteId == note.id
    LaunchedEffect(playing) { if (playing) follow = true }
    val readingStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
    var naming by remember(note.id) { mutableStateOf(false) }
    // Text still arriving: stay at its end, so what is being said now is in view; not while the
    // note is being named at the top.
    LaunchedEffect(note.text, note.isInProgress(), naming, listening) {
        if (note.isInProgress() && !naming && !listening) scroll.animateScrollTo(scroll.maxValue)
    }
    // The card runs to the bottom edge of the screen, behind the record button; the text keeps
    // clear of it.
    Box(
        modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(
                    topStart = ProductTokens.Radius.Card,
                    topEnd = ProductTokens.Radius.Card,
                ),
            )
    ) {
        Column(
            Modifier.fillMaxSize()
                .pointerInput(note.id) { watchFinger { follow = false } }
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (textDraft) 16.dp else DOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            tabs?.invoke {}
            if (note.state == NoteStore.STATE_RECOGNIZING) {
                val gate by
                    com.govorun.echo.recognition.RecognitionStatus.pausedReason.collectAsState()
                com.product.ui.ThinProgress(
                    recognitionFraction(note),
                    resting = gate == com.govorun.echo.recognition.RecognitionStatus.RECORDING,
                )
            }
            NoteTextHeader(note, actions, onEditing = { naming = it })
            when {
                // Only a new typed/shared draft is authored here. Kept originals stay read-only,
                // even after audio is removed.
                textDraft -> TextNoteBody(note, actions)
                note.text.isBlank() -> {
                    val gate by
                        com.govorun.echo.recognition.RecognitionStatus.pausedReason.collectAsState()
                    Text(
                        stringResource(
                            if (note.mode == NoteText.MODE) R.string.text_empty
                            else statusLabel(note).also { gate }
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val problem by com.govorun.echo.record.RecordingBus.liveProblem.collectAsState()
                    // While recording with no text coming, say why instead of leaving a silent
                    // card.
                    if (note.state == NoteStore.STATE_RECORDING)
                        problem?.let {
                            Text(
                                stringResource(liveProblemLabel(it)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
                // Its word times are still being read (a moment): no plain text first, so it does
                // not jump into paragraphs.
                !wordsRead -> Unit
                note.phrases.isNotEmpty() && note.audio.isNotEmpty() -> {
                    // The text is selected and copied as anywhere else (hold a word, stretch the
                    // handles) and fixed from there; a tap plays a word.
                    ReadingText(note, player, actions, readingStyle, find, follow) { follow = true }
                    // A note being continued: what is said now follows the saved paragraphs until
                    // it is saved too.
                    if (note.state == NoteStore.STATE_RECORDING && note.live.isNotBlank()) {
                        Text(
                            note.live,
                            modifier = Modifier.padding(start = 52.dp),
                            style = readingStyle,
                        )
                    } else {
                        Text(
                            stringResource(R.string.phrase_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> ReadingText(note, player, actions, readingStyle, find)
            }
            GovorunProblem(note, actions)
            stopLabel(note)?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The tags close the note, quiet as a line of text: «#Работа #Дом + тег».
            NoteTagLine(note.tags, onTags)
        }
        if (playing && !follow) {
            androidx.compose.material3.Surface(
                onClick = { follow = true },
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                shape = RoundedCornerShape(ProductTokens.Radius.Xl),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 2.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        PhosphorIcons.Regular.Waveform,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(R.string.follow_sound),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * Calls [onDrag] when a finger moves the text by more than a touch slop: the person scrolls to read
 * on. It only watches (the scroll itself goes on as usual), so the text's own moves after the sound
 * do not count.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.watchFinger(
    onDrag: () -> Unit
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var moved = 0f
    while (true) {
        val change =
            awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                ?: break
        moved += change.position.y - change.previousPosition.y
        if (kotlin.math.abs(moved) > viewConfiguration.touchSlop) {
            onDrag()
            break
        }
        if (!change.pressed) break
    }
}

/**
 * Why no text comes from this note, said where the text is and with the way out beside it. A note
 * that already has its text showed nothing at all while Govorun had not allowed Echo in (the person
 * talked into the void and found the note «not ready» afterwards); the card on the list that asks
 * for the access is easy to miss.
 */
@Composable
private fun GovorunProblem(
    note: NoteStore.Entry,
    actions: NotesActions,
) {
    val live by com.govorun.echo.record.RecordingBus.liveProblem.collectAsState()
    val reason =
        when {
            note.state == NoteStore.STATE_RECORDING -> live
            note.waitsForGovorun() -> note.error
            else -> null
        } ?: return
    val unauthorized = reason in setOf("unauthorized", "govorun_1", "needs_authorization")
    val label =
        if (note.state == NoteStore.STATE_RECORDING) liveProblemLabel(reason)
        else com.govorun.echo.errorLabel(reason)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A note with no text yet already says it in its status line; the way out is what is
        // missing there.
        if (note.text.isNotBlank())
            Text(
                stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        if (unauthorized)
            androidx.compose.material3.TextButton(onClick = actions.onAuthorizeGovorun) {
                Text(stringResource(R.string.action_allow))
            }
    }
}
