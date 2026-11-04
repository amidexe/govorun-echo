package com.govorun.echo.ui

import androidx.compose.runtime.Immutable
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.formatDuration

internal enum class Edge { START, END }

/** The piece of a note being worked on: from [startMs] to [endMs]; [active] is the edge last moved, the one the text keeps in view. */
@Immutable
internal data class FragmentSelection(
    val startMs: Long,
    val endMs: Long,
    val active: Edge = Edge.END,
) {
    val lengthMs: Long get() = endMs - startMs

    fun edge(edge: Edge) = if (edge == Edge.START) startMs else endMs

    /** Whether the word is inside the piece: its middle is. */
    fun holds(word: NoteStore.Word) = (word.startMs + word.endMs) / 2 in startMs until endMs

    /**
     * An edge moved on the scale to [ms]: it follows the finger to any moment, words or not (noise can be cut too), and stops at the
     * other edge instead of passing it.
     */
    fun moved(
        edge: Edge,
        ms: Long,
        totalMs: Long,
    ): FragmentSelection =
        if (edge == Edge.START) copy(startMs = ms.coerceIn(0L, endMs), active = Edge.START) else copy(endMs = ms.coerceIn(startMs, totalMs), active = Edge.END)
}

/**
 * Where a piece chosen by words begins and ends. A start sits in the pause before a word and an end in the pause after one, as far
 * into the pause as "skip pauses" keeps ([LEAD_MS] before a word, [TRAIL_MS] after it) and never past its middle, so no syllable is
 * cut; the times are Govorun's. The scale is not bound to words: there an edge goes wherever the finger takes it.
 */
internal class FragmentEdges(
    phrases: List<NoteStore.Phrase>,
    val totalMs: Long,
) {
    private val phrases = phrases.filter { it.words.isNotEmpty() }.sortedBy { it.startMs }
    val words: List<NoteStore.Word> = this.phrases.flatMap { it.words }.sortedBy { it.startMs }
    private val starts = LongArray(words.size) { before(it) }
    private val ends = LongArray(words.size) { after(it) }

    /** Where a piece that begins with word [i] begins. */
    private fun before(i: Int): Long {
        val word = words[i]
        val gap = if (i == 0) word.startMs else word.startMs - words[i - 1].endMs
        return (word.startMs - minOf(LEAD_MS, maxOf(0L, if (i == 0) gap else gap / 2))).coerceIn(0L, totalMs)
    }

    /** Where a piece that ends with word [i] ends. */
    private fun after(i: Int): Long {
        val word = words[i]
        val gap = if (i == words.lastIndex) totalMs - word.endMs else words[i + 1].startMs - word.endMs
        return (word.endMs + minOf(TRAIL_MS, maxOf(0L, if (i == words.lastIndex) gap else gap / 2))).coerceIn(0L, totalMs)
    }

    /** A piece of exactly one word, as a long press on it starts. */
    fun forWord(word: NoteStore.Word): FragmentSelection {
        val i = indexOf(word)
        return FragmentSelection(starts[i], ends[i], Edge.END)
    }

    /**
     * Where to begin when the piece is asked for from the menu: the phrase being played, else the first phrase; a note without
     * words starts with all of it, to be narrowed on the scale.
     */
    fun initial(positionMs: Long?): FragmentSelection {
        if (words.isEmpty()) return FragmentSelection(0L, totalMs, Edge.END)
        val phrase = positionMs?.let { at -> phrases.lastOrNull { it.startMs <= at } } ?: phrases.first()
        return FragmentSelection(starts[indexOf(phrase.words.first())], ends[indexOf(phrase.words.last())], Edge.END)
    }

    /** A piece from the first to the last of the words a text selection touches, its edges in the pauses around them. */
    fun forWords(
        first: NoteStore.Word,
        last: NoteStore.Word,
        active: Edge,
    ): FragmentSelection = FragmentSelection(starts[indexOf(first)], ends[indexOf(last)], active)

    /** A cursor in the text: the moment just before that word. */
    fun cursorAt(word: NoteStore.Word): Long = starts[indexOf(word)]

    private fun indexOf(word: NoteStore.Word): Int = words.indexOfFirst { it === word }.takeIf { it >= 0 } ?: words.indexOfFirst { it.startMs == word.startMs && it.text == word.text }.coerceAtLeast(0)

    companion object {
        /** The same room "skip pauses" leaves around speech: 0.2 s before a word and 0.25 s after it. */
        const val LEAD_MS = 200L
        const val TRAIL_MS = 250L
    }
}

/** A fragment's time: "7:40,3", "1:02:07,5". */
internal fun fragmentTime(ms: Long): String = "${formatDuration(ms)},${(ms % 1_000) / 100}"

/** A fragment's length: "5,4 с", "41 с", "2 мин 14 с", "1 ч 5 мин". */
internal fun fragmentLength(ms: Long): String {
    val seconds = ms / 1_000.0
    return when {
        seconds < 10 -> "%.1f с".format(java.util.Locale.ROOT, seconds).replace('.', ',')
        seconds < 60 -> "${seconds.toInt()} с"
        seconds < 3_600 -> "${(seconds / 60).toInt()} мин ${(seconds % 60).toInt()} с"
        else -> "${(seconds / 3_600).toInt()} ч ${((seconds % 3_600) / 60).toInt()} мин"
    }
}
