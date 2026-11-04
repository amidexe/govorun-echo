package com.govorun.echo.clip

import java.util.Locale

/** A word of the note with its time: the video lights it while it is said, the subtitles cue it. */
data class ClipWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** One picture of the video, from [atMs] on: [lit] is the word being said (-1 for none), words up to [said] are already said. */
data class ClipFrame(
    val atMs: Long,
    val lit: Int,
    val said: Int,
)

/** A subtitle: one or two lines shown from [startMs] to [endMs]. */
data class Cue(
    val startMs: Long,
    val endMs: Long,
    val lines: List<String>,
)

/**
 * Where the words of a note go in time: the frames of the video (a new picture only when a word begins or a pause begins, so a
 * minute of speech is about a hundred pictures and costs little) and the subtitles (as subtitle guides have them: up to two lines
 * of [LINE_CHARS] characters, up to [MAX_CUE_MS] on screen, cut at pauses and at the ends of sentences).
 */
object Captions {
    const val PAUSE_MS = 600L
    const val LINE_CHARS = 42
    const val MAX_CUE_MS = 7_000L
    private const val PAUSE_AFTER_MS = 150L
    private const val TAIL_MS = 300L
    private const val GAP_MS = 50L
    private const val SENTENCE_MIN_CHARS = 16

    // A break after a punctuation mark is worth a line this many characters longer: subtitle guides cut at the natural pause.
    private const val PUNCTUATION_BONUS = 8

    fun frames(
        words: List<ClipWord>,
        durationMs: Long,
    ): List<ClipFrame> {
        val out = mutableListOf(ClipFrame(0, -1, -1))
        fun add(frame: ClipFrame) {
            if (frame.atMs >= durationMs) return
            // Never back in time; two pictures at one moment: the later one stays.
            val at = maxOf(frame.atMs, out.last().atMs)
            if (at == out.last().atMs) out[out.lastIndex] = frame.copy(atMs = at) else out += frame.copy(atMs = at)
        }
        words.forEachIndexed { i, word ->
            add(ClipFrame(word.startMs.coerceAtLeast(0), i, i))
            val next = words.getOrNull(i + 1)?.startMs ?: durationMs
            if (next - word.endMs > PAUSE_MS) add(ClipFrame(word.endMs + PAUSE_AFTER_MS, -1, i))
        }
        return out
    }

    fun cues(
        words: List<ClipWord>,
        durationMs: Long,
    ): List<Cue> {
        val groups = mutableListOf<List<ClipWord>>()
        var current = mutableListOf<ClipWord>()
        words.forEachIndexed { i, word ->
            current += word
            val next = words.getOrNull(i + 1)
            val text = current.joinToString(" ") { it.text }
            val full = next != null && text.length + 1 + next.text.length > 2 * LINE_CHARS
            val long = next != null && next.endMs - current.first().startMs > MAX_CUE_MS
            val pause = next != null && next.startMs - word.endMs > PAUSE_MS
            val sentence = word.text.last() in ".?!…" && text.length >= SENTENCE_MIN_CHARS
            if (next == null || full || long || pause || sentence) {
                groups += current
                current = mutableListOf()
            }
        }
        return groups.mapIndexed { k, group ->
            val start = group.first().startMs
            val next = groups.getOrNull(k + 1)?.first()?.startMs ?: durationMs
            val end = minOf(group.last().endMs + TAIL_MS, next - GAP_MS).coerceAtLeast(start + 1)
            Cue(start, end, lines(group.joinToString(" ") { it.text }))
        }
    }

    /** One line, or two cut at the space that makes the longer one shortest, after a punctuation mark where that costs little. */
    fun lines(text: String): List<String> {
        if (text.length <= LINE_CHARS) return listOf(text)
        val best =
            text.indices.filter { text[it] == ' ' }.minByOrNull { at ->
                val longer = maxOf(at, text.length - at - 1)
                longer - if (text[at - 1] in ",.;:?!…—") PUNCTUATION_BONUS else 0
            } ?: return listOf(text)
        return listOf(text.substring(0, best), text.substring(best + 1))
    }

    fun srt(cues: List<Cue>): String = cues.mapIndexed { i, cue -> "${i + 1}\n${time(cue.startMs)} --> ${time(cue.endMs)}\n${cue.lines.joinToString("\n")}\n" }.joinToString("\n")

    private fun time(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)
}
