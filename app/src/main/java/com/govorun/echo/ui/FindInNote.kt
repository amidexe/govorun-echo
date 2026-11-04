package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.govorun.echo.notes.NoteIndex
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.SearchWords
import com.govorun.echo.notes.WordForms

/**
 * A search carried into the note it opened, as a browser finds in a page: every word of the note's text that answers it, by the
 * list's own rule ([SearchWords]), where each stands in the text the note screen shows ([DraftText]), and the one shown now. A note
 * opened at a found passage starts at the find the player stands at.
 */
@Stable
internal class FindInNote(
    val query: String,
    val finds: List<Find>,
    current: Int,
) {
    /** A found word: its place among the text's words, the letters of it that answer, and when it is said (0 without times). */
    class Find(
        val word: Int,
        val range: TextRange,
        val ms: Long,
    )

    var current by mutableIntStateOf(current)

    val size get() = finds.size

    fun move(by: Int) {
        current = (current + by).mod(finds.size)
    }

    companion object {
        /** The finds of [query] in [model], the text of [note] as its screen shows it; null when it holds none (found by its date or status). */
        fun of(
            note: NoteStore.Entry,
            model: DraftText,
            query: String,
            atMs: Long?,
        ): FindInNote? {
            val search = SearchWords.of(query, WordForms.plain(note.text))
            if (search.words.isEmpty()) return null
            val finds =
                model.words.indices.mapNotNull { i ->
                    val text = model.words[i].text
                    val plain = WordForms.plain(text)
                    val word = NoteIndex.wordsOf(plain).firstOrNull { search.answers(it) >= 0 } ?: return@mapNotNull null
                    val whole = model.rangeOf(i)
                    // The letters that answer, without the word's punctuation («бэкапы.» lights «бэкапы»); the whole word where lower case changes its length.
                    val from = plain.indexOf(word)
                    val range = if (plain.length == text.length && from >= 0) TextRange(whole.min + from, whole.min + from + word.length) else whole
                    Find(i, range, model.words[i].startMs)
                }
            if (finds.isEmpty()) return null
            val start = atMs?.let { ms -> finds.indexOfFirst { it.ms >= ms }.takeIf { it >= 0 } } ?: 0
            return FindInNote(query, finds, start)
        }
    }
}

/** The text of [note] as its screen shows it: its timed words in paragraphs, or its own text without them. */
internal fun noteText(note: NoteStore.Entry): DraftText = if (note.phrases.isNotEmpty() && note.audio.isNotEmpty()) DraftText.of(note.phrases) else DraftText.ofText(note.text)

/**
 * The finds of the search [query] in the open [note], once its words are read; null without a search or with nothing to light. The
 * first is the one at the player's place when the note was opened at a found passage ([atMs]).
 */
@Composable
internal fun rememberFindInNote(
    note: NoteStore.Entry,
    wordsRead: Boolean,
    query: String?,
    atMs: Long?,
): FindInNote? =
    remember(note.id, note.phrases, note.text, wordsRead, query) {
        if (query.isNullOrBlank() || !wordsRead) null else FindInNote.of(note, noteText(note), query, atMs)
    }
