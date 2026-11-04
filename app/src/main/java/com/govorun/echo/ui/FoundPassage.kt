package com.govorun.echo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.govorun.echo.R
import com.govorun.echo.formatDuration
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.playback.NotePlayer
import com.product.ui.ProductTokens

/**
 * In a search, the place inside a long note where the words are, under the card's own lines: its time in the recording and the
 * sentence, the found words lit. A tap opens the note there: the player stands at that time and the note screen, which keeps the
 * word being played in view, shows the passage with its first word lit. Nothing is added when the card's lines already show the
 * words. While notes are being selected, a tap selects, as anywhere on the card.
 *
 * The list holds no word times, so the card looks in its own text first; only a card that shows a passage reads its note's word
 * times, in the background, and the passage waits for them rather than appear without its time and change.
 */
@Composable
internal fun FoundPassage(
    note: NoteStore.Entry,
    search: com.govorun.echo.notes.SearchWords,
    /** How many lines of the note's text the card shows above; 0 when it shows something else there (the subtitles of its sound). */
    lines: Int,
    style: TextStyle,
    selecting: Boolean,
    actions: NotesActions,
    displayedText: String = note.text,
    heading: String = "",
) {
    if (search.words.isEmpty()) return
    val context = LocalContext.current
    // The words the card has are read once; each letter typed only looks through them again.
    val own = remember(note.phrases, note.audio, note.text) { NoteWords.of(note) }
    val inText = remember(own, search) { own.passage(search) } ?: return
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val shown = remember(displayedText, heading, lines, width, style) { heading + " " + shownLines(measurer, displayedText, style, lines, width) }
        if (inText.shownIn(shown, search)) return@BoxWithConstraints
        val passage = if (inText.atMs != null || !note.timed || note.audio.isEmpty()) inText else timedPassage(note, search) ?: return@BoxWithConstraints
        if (passage.shownIn(shown, search)) return@BoxWithConstraints
        PassageRow(passage, style, onLongClick = { actions.onCardLongClick(note.id) }) {
            val at = passage.atMs
            if (!selecting && at != null) NotePlayer.get(context).placeAt(note.id, NoteStore.noteDir(context, note.id), note.audio, at)
            actions.onCardClick(note.id)
        }
    }
}

/** The passage in the note's timed words, read from its folder; null while they are read. */
@Composable
private fun timedPassage(
    note: NoteStore.Entry,
    search: com.govorun.echo.notes.SearchWords,
): Passage? {
    val full = rememberWords(note) ?: return null
    val words = remember(full) { NoteWords.of(full) }
    return remember(words, search) { words.passage(search) }
}

/**
 * A passage under a card: its time and its words, the found ones lit; a tap does [onClick], which opens the note there.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PassageRow(
    passage: Passage,
    style: TextStyle,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val text = remember(passage, highlight) { buildAnnotatedString { append(passage.text); passage.marks.forEach { addStyle(SpanStyle(background = highlight), it.first, it.last + 1) } } }
    val at = passage.atMs
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProductTokens.Radius.Md))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.search_open_passage),
                onLongClick = onLongClick,
                onClick = onClick,
            ).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md),
    ) {
        if (at != null) Text(formatDuration(at), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The start of [text] that [lines] lines of [width] show; a word the last line's «…» may cover is left out. */
private fun shownLines(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    lines: Int,
    width: Int,
): String {
    if (lines <= 0 || width <= 0) return ""
    // Lines that hold more than this are wider than any phone's; the rest of a long text is not laid out.
    val head = text.take(lines * 200)
    val layout = measurer.measure(head, style, constraints = Constraints(maxWidth = width))
    if (layout.lineCount <= lines) return head
    val lineStart = layout.getLineStart(lines - 1)
    val end = layout.getLineEnd(lines - 1, visibleEnd = true)
    return head.substring(0, (end - 1 downTo lineStart).firstOrNull { head[it].isWhitespace() } ?: lineStart)
}
