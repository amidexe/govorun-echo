package com.govorun.echo.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import com.govorun.echo.notes.NoteCut
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.wordRanges
import kotlin.math.roundToLong
import com.govorun.echo.notes.CutModel

/**
 * «Фрагмент» works on a draft: which stretches of the note's own sound stay. Nothing on disk changes until «Сохранить»; the scale,
 * the text and ▶ show the note as it will be, and every cut or keep is a step «Отменить шаг» takes back. Times on the screen are
 * the draft's; [source] turns them back into the note's own.
 */
@Immutable
internal class FragmentDraft private constructor(
    private val notePhrases: List<NoteStore.Phrase>,
    val kept: List<NoteCut.Span>,
    private val steps: List<List<NoteCut.Span>>,
) {
    private val line = CutModel.Timeline(kept)
    val totalMs: Long = line.total.roundToLong()

    /** Whether there is anything to save or to take back. */
    val changed: Boolean get() = steps.isNotEmpty()

    /** The note's phrases as they will be: what was cut is gone and the rest moved up. */
    val phrases: List<NoteStore.Phrase> =
        CutModel
            .cutPhrases(notePhrases.map { CutModel.RawPhrase(it.startMs, it.endMs, it.text, it.words, true) }, line)
            .map { NoteStore.Phrase(it.startMs, it.endMs, it.text, it.words) }

    /** The stretches of the note's own sound that [fromMs]-[toMs] of the draft is made of. */
    fun source(
        fromMs: Long,
        toMs: Long,
    ): List<NoteCut.Span> {
        val result = mutableListOf<NoteCut.Span>()
        var at = 0.0
        kept.forEach { span ->
            val from = maxOf(fromMs.toDouble(), at)
            val to = minOf(toMs.toDouble(), at + span.length)
            if (to > from) result += NoteCut.Span(span.fromMs + (from - at), span.fromMs + (to - at))
            at += span.length
        }
        return result
    }

    /** [fromMs]-[toMs] of the draft taken out. */
    fun cut(
        fromMs: Long,
        toMs: Long,
    ): FragmentDraft {
        val rest = source(0L, fromMs) + source(toMs, totalMs)
        // Nothing cut, or nothing left: no step.
        val left = rest.sumOf { it.length }
        return if (left >= line.total - 0.5 || left < 1.0) this else FragmentDraft(notePhrases, rest, steps + listOf(kept))
    }

    /** Only [fromMs]-[toMs] of the draft left. */
    fun keepOnly(
        fromMs: Long,
        toMs: Long,
    ): FragmentDraft {
        val rest = source(fromMs, toMs)
        return if (rest.isEmpty() || rest.sumOf { it.length } >= line.total - 0.5) this else FragmentDraft(notePhrases, rest, steps + listOf(kept))
    }

    /** The draft one step back. */
    fun undo(): FragmentDraft = if (steps.isEmpty()) this else FragmentDraft(notePhrases, steps.last(), steps.dropLast(1))

    /** The waveform bars of the draft, from the note's own (one per 100 ms). */
    fun bars(note: ByteArray?): ByteArray? = note?.let { CutModel.cutPeaks(it, kept) }

    companion object {
        fun of(note: NoteStore.Entry): FragmentDraft {
            val total = note.audio.sumOf { it.ms }.takeIf { it > 0 } ?: note.durationMs
            return FragmentDraft(note.phrases, listOf(NoteCut.Span(0.0, total.toDouble())), emptyList())
        }
    }
}

/**
 * The draft's text as the selectable text shows it: the words of each paragraph with single spaces, paragraphs apart by an empty
 * line, as the note screen groups them. Knows where every word is, so a selection of the text and a stretch of time can follow each
 * other.
 */
@Immutable
internal class DraftText private constructor(
    val text: String,
    val words: List<NoteStore.Word>,
    private val starts: IntArray,
    /** Where each paragraph begins in the text and the time it begins at. */
    val paragraphs: List<Pair<Int, Long>>,
) {
    private fun endOf(i: Int) = starts[i] + words[i].text.length

    /** The words a selection touches (first to last index); null for a selection that touches none or is empty. */
    fun wordsIn(range: TextRange): IntRange? {
        if (range.collapsed || words.isEmpty()) return null
        val first = (words.indices).firstOrNull { endOf(it) > range.min } ?: return null
        val last = (words.indices).lastOrNull { starts[it] < range.max } ?: return null
        return if (last < first) null else first..last
    }

    /** Where word [i] stands in the text. */
    fun rangeOf(i: Int): TextRange = TextRange(starts[i], endOf(i))

    /** The word an offset falls in, its end included; null between words. */
    fun wordAround(offset: Int): Int? = words.indices.firstOrNull { starts[it] <= offset && offset <= endOf(it) }

    /** The word a cursor stands in or right before. */
    fun wordAt(offset: Int): Int? = if (words.isEmpty()) null else (words.indices).firstOrNull { endOf(it) >= offset } ?: words.lastIndex

    /** The text a stretch of time holds: whole words whose middle is inside; a cursor where nothing is. */
    fun rangeFor(selection: FragmentSelection): TextRange {
        val inside = words.indices.filter { selection.holds(words[it]) }
        if (inside.isEmpty()) {
            val next = words.indices.firstOrNull { words[it].startMs >= selection.startMs }
            return TextRange(next?.let { starts[it] } ?: text.length)
        }
        return TextRange(starts[inside.first()], endOf(inside.last()))
    }

    companion object {
        /** A text without word times (no sound, or no phrases from Govorun): its own words where they stand, to select and fix. */
        fun ofText(text: String): DraftText {
            val found = wordRanges(text)
            return DraftText(text, found.map { NoteStore.Word(0L, 0L, text.substring(it)) }, found.map { it.first }.toIntArray(), emptyList())
        }

        fun of(phrases: List<NoteStore.Phrase>): DraftText {
            val out = StringBuilder()
            val words = mutableListOf<NoteStore.Word>()
            val starts = mutableListOf<Int>()
            val paragraphs = mutableListOf<Pair<Int, Long>>()
            paragraphsOf(phrases.filter { it.words.isNotEmpty() }).forEach { paragraph ->
                if (out.isNotEmpty()) out.append("\n\n")
                paragraphs += out.length to paragraph.first().startMs
                paragraph.flatMap { it.words }.forEachIndexed { i, word ->
                    if (i > 0) out.append(' ')
                    starts += out.length
                    words += word
                    out.append(word.text)
                }
            }
            return DraftText(out.toString(), words, starts.toIntArray(), paragraphs)
        }
    }
}
