package com.govorun.echo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import com.govorun.echo.notes.NoteStore

/** While a note is still being spoken, shows its last lines (with a leading ellipsis), not the first ones. */
@Composable
internal fun TailText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    lines: Int,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints {
        val shown =
            remember(text, constraints.maxWidth) {
                val layout = measurer.measure(text, style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = constraints.maxWidth))
                if (layout.lineCount <= lines) text else "…" + text.substring(layout.getLineStart(layout.lineCount - lines)).trimStart()
            }
        Text(shown, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The note with its word times, read from its folder in the background (the list does not hold them); null while they are read.
 * A note without word times comes back at once. After the note changes the last words read stay until the new ones are in.
 */
@Composable
internal fun rememberWords(note: NoteStore.Entry): NoteStore.Entry? {
    if (note.state == NoteStore.STATE_RECOGNIZING) {
        val preview by com.govorun.echo.recognition.RecognitionPreview.current.collectAsState()
        val showing = remember(note, preview) { com.govorun.echo.recognition.RecognitionPreview.forNote(note, preview) }
        showing?.let { return it }
    }
    if (!note.timed || note.phrases.isNotEmpty()) return note
    val context = androidx.compose.ui.platform.LocalContext.current
    val read by androidx.compose.runtime.produceState<Pair<String, List<NoteStore.Phrase>>?>(null, note) {
        value = note.id to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NoteStore.readPhrases(NoteStore.noteDir(context, note.id)) }
    }
    val phrases = read?.takeIf { it.first == note.id }?.second ?: return null
    return remember(note, phrases) { note.copy(phrases = phrases) }
}

/** The note text as the player sees it, with the character range of the word being spoken. */
internal class SpokenWord(
    val text: String,
    val start: Int,
    val end: Int,
)

internal fun spokenWord(
    note: NoteStore.Entry,
    positionMs: Long,
): SpokenWord? {
    if (note.phrases.isEmpty() || note.audio.isEmpty()) return null
    val builder = StringBuilder()
    var start = -1
    var end = -1
    val current = note.phrases.lastOrNull { it.startMs <= positionMs } ?: note.phrases.first()
    note.phrases.forEachIndexed { index, phrase ->
        if (phrase.words.isEmpty()) {
            if (phrase === current) {
                start = builder.length
                end = builder.length + phrase.text.length
            }
            builder.append(phrase.text)
        } else {
            val word = if (phrase === current) phrase.words.lastOrNull { it.startMs <= positionMs } ?: phrase.words.first() else null
            phrase.words.forEachIndexed { i, w ->
                if (w === word) {
                    start = builder.length
                    end = builder.length + w.text.length
                }
                builder.append(w.text)
                if (i < phrase.words.lastIndex) builder.append(' ')
            }
        }
        if (index < note.phrases.lastIndex) builder.append(' ')
    }
    return if (start < 0) null else SpokenWord(builder.toString(), start, end)
}

/** Subtitles in pages of three lines: the page changes when the spoken word moves past its last line. */
@Composable
internal fun SubtitleText(
    spoken: SpokenWord,
    style: androidx.compose.ui.text.TextStyle,
    lines: Int,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val highlight = MaterialTheme.colorScheme.primaryContainer
    androidx.compose.foundation.layout.BoxWithConstraints {
        val width = constraints.maxWidth
        val layout = remember(spoken.text, width) { measurer.measure(spoken.text, style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width)) }
        val line = layout.getLineForOffset(spoken.start.coerceAtMost(spoken.text.length))
        val first = line / lines * lines
        val last = minOf(layout.lineCount - 1, first + lines - 1)
        val from = layout.getLineStart(first)
        val to = layout.getLineEnd(last)
        val prefix = if (first > 0) "…" else ""
        val suffix = if (last < layout.lineCount - 1) "…" else ""
        val shown =
            androidx.compose.ui.text.buildAnnotatedString {
                append(prefix)
                append(spoken.text.substring(from, to).trimEnd())
                append(suffix)
                val offset = prefix.length - from
                addStyle(androidx.compose.ui.text.SpanStyle(background = highlight), (spoken.start + offset).coerceIn(0, length), (spoken.end + offset).coerceIn(0, length))
            }
        Text(shown, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}
