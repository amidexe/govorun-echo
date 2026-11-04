package com.govorun.echo.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.govorun.echo.R
import com.govorun.echo.formatDuration
import com.govorun.echo.notes.CutModel
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.WordFix
import com.govorun.echo.playback.NotePlayer

/**
 * A note's text to read and listen to. It is Android's own read-only text: a long press takes a word, the handles stretch the
 * selection, and the menu over it copies it and, in a settled note, offers «Исправить»: the words chosen are typed anew in a small
 * window, where ▶ plays them again, and a note that was playing goes on from where it stopped. With timed words a tap on a word
 * plays from it, the word being spoken is lit and kept in view, and each paragraph has its time on the left.
 */
@Composable
internal fun ReadingText(
    note: NoteStore.Entry,
    player: NotePlayer.State?,
    actions: NotesActions,
    style: TextStyle,
    /** The search the note was opened from, while it is shown: its words lit, the current one brighter and kept in view. */
    find: FindInNote? = null,
    /** Whether the text keeps the spoken word in view; false once the person scrolled away to read ahead (TextCard). */
    follow: Boolean = true,
    /** A word or a minute was tapped to play: the text follows the sound again. */
    onFollow: () -> Unit = {},
) {
    val context = LocalContext.current
    val timed = note.phrases.isNotEmpty() && note.audio.isNotEmpty()
    val model = remember(note.phrases, note.text, timed) { noteText(note) }
    // A settled note whose text is the words shown, one after another: the fix lands exactly on them.
    val fixable =
        remember(model, note.text, note.state, note.appending, note.archiving) {
            note.state == NoteStore.STATE_DONE && !note.appending && !note.archiving && model.words.isNotEmpty() &&
                (!timed || CutModel.textFollowsWords(note.text, model.words.map { it.text }))
        }
    val state = remember(model) { TextFieldState(model.text) }
    var layout by remember(model) { mutableStateOf<TextLayoutResult?>(null) }
    val position = player?.positionMs?.takeIf { timed }
    val spoken = position?.let { at -> model.words.indexOfLast { it.startMs <= at }.takeIf { it >= 0 } }
    val active = position?.let { at -> model.paragraphs.indexOfLast { it.second <= at }.takeIf { it >= 0 } }
    val notePlayer = remember { NotePlayer.get(context) }
    val focus = LocalFocusManager.current
    val current by rememberUpdatedState(player)
    val act by rememberUpdatedState(actions)
    // The words being fixed, how the player stood when the window opened (to stand so again) and whether they were played in it.
    var fixing by remember(note.id) { mutableStateOf<IntRange?>(null) }
    var before by remember(note.id) { mutableStateOf<NotePlayer.State?>(null) }
    var heard by remember(note.id) { mutableStateOf(false) }
    val startFix = { words: IntRange ->
        before = current
        heard = false
        if (current?.playing == true) notePlayer.pause()
        fixing = words
    }
    val endFix = {
        val was = before
        fixing = null
        when {
            was == null -> if (heard) notePlayer.stop()
            heard && was.playing -> act.onPlayFrom(note.id, was.positionMs)
            heard -> notePlayer.cue(note.id, NoteStore.noteDir(context, note.id), note.audio, was.positionMs)
            was.playing -> notePlayer.play()
        }
    }
    // A long note is taller than the screen: keep the word being spoken in view as the audio goes on.
    val requester = remember { BringIntoViewRequester() }
    val margin = with(LocalDensity.current) { 96.dp.toPx() }
    LaunchedEffect(spoken, layout, follow) {
        if (!follow) return@LaunchedEffect
        val result = layout ?: return@LaunchedEffect
        val word = spoken ?: return@LaunchedEffect
        val at = model.rangeOf(word).min
        if (at < result.layoutInput.text.length) requester.bringIntoView(result.getBoundingBox(at).inflate(margin))
    }
    // The find being shown is kept in view as the arrows step through them.
    val shownFind = find?.let { it.finds.getOrNull(it.current)?.range }
    LaunchedEffect(shownFind, layout) {
        val result = layout ?: return@LaunchedEffect
        val range = shownFind ?: return@LaunchedEffect
        if (range.min < result.layoutInput.text.length) requester.bringIntoView(result.getBoundingBox(range.min).inflate(margin))
    }
    val lit by rememberUpdatedState(spoken)
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val found = MaterialTheme.colorScheme.tertiaryContainer
    val foundNow = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
    val fixLabel = stringResource(R.string.fix_title)
    val selectAllLabel = stringResource(android.R.string.selectAll)
    val processors = remember { runCatching { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0) }.getOrDefault(emptyList()) }
    Box(Modifier.fillMaxWidth()) {
        BasicTextField(
            state = state,
            modifier =
                Modifier
                    .padding(start = if (timed) 52.dp else 0.dp)
                    .fillMaxWidth()
                    .bringIntoViewRequester(requester)
                    .drawBehind {
                        val result = layout ?: return@drawBehind
                        val length = result.layoutInput.text.length
                        lit?.let { model.rangeOf(it) }?.let { range -> if (range.max <= length) drawPath(result.getPathForRange(range.min, range.max), highlight) }
                        // The search's words over the spoken one: they are what was asked for.
                        find?.let { f ->
                            f.finds.forEachIndexed { k, it -> if (it.range.max <= length) drawPath(result.getPathForRange(it.range.min, it.range.max), if (k == f.current) foundNow else found) }
                        }
                    }.pointerInput(model, timed) {
                        // A tap, seen before the text takes it: it plays from the word and leaves any selection; a long press goes on
                        // to the text, which selects.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(PointerEventPass.Initial) } ?: return@awaitEachGesture
                            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                            up.consume()
                            focus.clearFocus()
                            val result = layout ?: return@awaitEachGesture
                            if (!timed) return@awaitEachGesture
                            val line = result.getLineForVerticalPosition(up.position.y)
                            if (up.position.x < result.getLineLeft(line) || up.position.x > result.getLineRight(line)) return@awaitEachGesture
                            val word = model.wordAround(result.getOffsetForPosition(up.position)) ?: return@awaitEachGesture
                            onFollow()
                            act.onPlayFrom(note.id, model.words[word].startMs)
                        }
                    }.filterTextContextMenuComponents { it.key != TextContextMenuKeys.SelectAllKey && it.key !is ProcessTextKey }
                    .appendTextContextMenuComponents {
                        // Android's menu is copy, select all, then the apps that work on a selected text (read aloud, translate):
                        // «Исправить» goes right after copy, in the first row, and the rest follow it.
                        val selection = state.selection
                        val chosen = model.wordsIn(selection)
                        if (fixable && chosen != null) {
                            item(key = FixWords, label = fixLabel) {
                                close()
                                focus.clearFocus()
                                startFix(chosen)
                            }
                        }
                        if (selection.length < state.text.length) item(key = SelectAll, label = selectAllLabel) { state.edit { this.selection = TextRange(0, length) } }
                        if (!selection.collapsed) {
                            processors.forEach { app ->
                                item(key = app.activityInfo.name, label = app.loadLabel(context.packageManager).toString()) {
                                    close()
                                    val send =
                                        Intent(Intent.ACTION_PROCESS_TEXT)
                                            .setType("text/plain")
                                            .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                                            .putExtra(Intent.EXTRA_PROCESS_TEXT, state.text.substring(selection.min, selection.max))
                                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                                    runCatching { context.startActivity(send) }
                                }
                            }
                        }
                    },
            readOnly = true,
            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(Color.Transparent),
            onTextLayout = { result -> layout = result() },
        )
        val result = layout
        if (timed && result != null) {
            // Each paragraph's time beside its first line; a tap on it plays from there.
            model.paragraphs.forEachIndexed { index, (offset, ms) ->
                if (offset > result.layoutInput.text.length) return@forEachIndexed
                val y = result.getLineTop(result.getLineForOffset(offset)).toInt()
                Text(
                    formatDuration(ms),
                    modifier =
                        Modifier
                            .offset { IntOffset(0, y) }
                            .width(40.dp)
                            .padding(top = 3.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFollow(); actions.onPlayFrom(note.id, ms) },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    fixing?.let { range ->
        val shown = model.words.subList(range.first, range.last + 1).map { it.text }
        val piecePlaying = player?.playing == true && player.rangeEndMs != null
        FixWordsDialog(
            words = shown.joinToString(" "),
            playing = piecePlaying,
            onListen =
                if (!timed) {
                    null
                } else {
                    {
                        if (piecePlaying) {
                            notePlayer.pause()
                        } else {
                            // The words are heard in their phrase, as Govorun split the speech: one word alone is too short to tell.
                            val first = model.words[range.first]
                            val last = model.words[range.last]
                            val from = note.phrases.firstOrNull { p -> p.words.any { it === first } }?.startMs ?: first.startMs
                            val to = note.phrases.lastOrNull { p -> p.words.any { it === last } }?.endMs ?: last.endMs
                            actions.onPlayPieces(note.id, listOf(from to to), from, note.audio.sumOf { it.ms })
                            heard = true
                        }
                    }
                },
            onDone = { typed ->
                if (WordFix.tokens(typed) != shown) actions.edits.fix(note.id, range.first, shown, typed)
                endFix()
            },
            onDismiss = endFix,
        )
    }
}

private object FixWords

private object SelectAll

/**
 * The words chosen, to be typed anew: all of them selected, so typing replaces them; an empty field takes them away. ▶ plays the
 * phrase they are in once more.
 */
@Composable
private fun FixWordsDialog(
    words: String,
    playing: Boolean,
    onListen: (() -> Unit)?,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(words, TextRange(0, words.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fix_title)) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = MaterialTheme.typography.bodyLarge,
                maxLines = 6,
                trailingIcon =
                    onListen?.let { listen ->
                        {
                            IconButton(onClick = listen) {
                                Icon(if (playing) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play, contentDescription = stringResource(if (playing) R.string.action_pause else R.string.fix_listen))
                            }
                        }
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(value.text) }) { Text(stringResource(R.string.action_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
