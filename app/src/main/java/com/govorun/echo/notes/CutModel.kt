package com.govorun.echo.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What a cut does to a note, worked out without touching the sound: where the kept moments land, and which text, words, marks of
 * continuations and waveform bars stay. The same model shows a «Фрагмент» draft on the screen and makes the saved note.
 */
internal object CutModel {
    /** Where the moments of the old note land in the new one: kept moments move left by what was cut before them. */
    class Timeline(
        val kept: List<NoteCut.Span>,
    ) {
        private val starts = kept.runningFold(0.0) { sum, span -> sum + span.length }
        val total: Double get() = starts.last()

        /** A cut moment lands where the next kept sound begins. */
        fun map(old: Double): Double {
            kept.forEachIndexed { i, span ->
                if (old < span.fromMs) return starts[i]
                if (old <= span.toMs) return starts[i] + (old - span.fromMs)
            }
            return total
        }

        fun keeps(old: Double) = kept.any { old >= it.fromMs && old < it.toMs }
    }

    /** A phrase as `segments.json` holds it; [timed] tells whether its words have their own times or are estimated. */
    data class RawPhrase(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val words: List<NoteStore.Word>,
        val timed: Boolean,
    )

    fun readPhrases(dir: File): List<RawPhrase> =
        runCatching {
            val array = JSONArray(File(dir, "segments.json").readText())
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val start = item.getLong("s")
                val end = item.getLong("e")
                val text = item.getString("t")
                val w = item.optJSONArray("w")
                val words = w?.let { a -> (0 until a.length()).map { k -> a.getJSONArray(k).let { NoteStore.Word(it.getLong(0), it.getLong(1), it.getString(2)) } } }
                RawPhrase(start, end, text, words ?: estimatedWords(text, start, end), words != null)
            }
        }.getOrDefault(emptyList())

    fun writePhrases(
        dir: File,
        phrases: List<RawPhrase>,
    ) {
        val file = File(dir, "segments.json")
        if (phrases.isEmpty()) {
            file.delete()
            return
        }
        val array = JSONArray()
        phrases.forEach { phrase ->
            val item = JSONObject().put("s", phrase.startMs).put("e", phrase.endMs).put("t", phrase.text)
            if (phrase.timed) item.put("w", JSONArray().also { w -> phrase.words.forEach { w.put(JSONArray().put(it.startMs).put(it.endMs).put(it.text)) } })
            array.put(item)
        }
        writeAtomically(file, array.toString())
    }

    /** What is left of [wanted] inside 0-[totalMs], in order, overlaps joined, slivers under a millisecond dropped. */
    fun normalized(
        wanted: List<NoteCut.Span>,
        totalMs: Long,
    ): List<NoteCut.Span> {
        val clipped = wanted.map { NoteCut.Span(it.fromMs.coerceIn(0.0, totalMs.toDouble()), it.toMs.coerceIn(0.0, totalMs.toDouble())) }.filter { it.length >= 1.0 }.sortedBy { it.fromMs }
        val joined = mutableListOf<NoteCut.Span>()
        clipped.forEach { span ->
            val last = joined.lastOrNull()
            if (last != null && span.fromMs <= last.toMs) joined[joined.lastIndex] = NoteCut.Span(last.fromMs, maxOf(last.toMs, span.toMs)) else joined += span
        }
        return joined
    }

    /** The phrases after a cut: words whose middle is kept stay with their new times; a phrase with no word left goes. */
    fun cutPhrases(
        phrases: List<RawPhrase>,
        line: Timeline,
    ): List<RawPhrase> =
        phrases.mapNotNull { phrase ->
            if (phrase.words.isEmpty()) {
                if (!line.keeps((phrase.startMs + phrase.endMs) / 2.0)) return@mapNotNull null
                return@mapNotNull phrase.copy(startMs = line.map(phrase.startMs.toDouble()).roundToLong(), endMs = line.map(phrase.endMs.toDouble()).roundToLong())
            }
            val kept = phrase.words.filter { line.keeps((it.startMs + it.endMs) / 2.0) }
            if (kept.isEmpty()) return@mapNotNull null
            val words = kept.map { NoteStore.Word(line.map(it.startMs.toDouble()).roundToLong(), line.map(it.endMs.toDouble()).roundToLong(), it.text) }
            if (kept.size == phrase.words.size) {
                RawPhrase(line.map(phrase.startMs.toDouble()).roundToLong(), line.map(phrase.endMs.toDouble()).roundToLong(), phrase.text, words, phrase.timed)
            } else {
                RawPhrase(words.first().startMs, words.last().endMs, words.joinToString(" ") { it.text }, words, phrase.timed)
            }
        }

    /** The words of a text and where each begins. */
    private fun tokens(text: String): List<IntRange> = wordRanges(text)

    /** Whether the text is exactly the timed words one after another, so cutting the sound can cut the text word for word. */
    fun textFollowsWords(
        text: String,
        words: List<String>,
    ): Boolean {
        val found = tokens(text)
        return found.size == words.size && found.indices.all { text.substring(found[it]) == words[it] }
    }

    /**
     * The text that stays: the kept words with the spaces and paragraph breaks between them as they were; where words were cut, the
     * strongest break among them stays, so a continuation keeps its own paragraph. Also gives, for every old word, where it now
     * begins (-1 when cut). Null when the text is not the timed words one after another.
     */
    fun cutText(
        text: String,
        words: List<String>,
        kept: BooleanArray,
    ): Pair<String, IntArray>? {
        if (!textFollowsWords(text, words)) return null
        val found = tokens(text)
        val out = StringBuilder()
        val at = IntArray(found.size) { -1 }
        var last = -1
        for (i in found.indices) {
            if (!kept[i]) continue
            if (last >= 0) {
                val between = (last + 1..i).map { text.substring(found[it - 1].last + 1, found[it].first) }
                out.append(
                    when {
                        i == last + 1 -> between.single()
                        between.any { "\n\n" in it } -> "\n\n"
                        between.any { '\n' in it } -> "\n"
                        else -> " "
                    },
                )
            }
            at[i] = out.length
            out.append(text, found[i].first, found[i].last + 1)
            last = i
        }
        return out.toString() to at
    }

    /**
     * The marks of continuations after a cut: each moves to where its first remaining word now begins and to where its sound now
     * begins; a mark whose words are all gone goes, and so does one that now stands at the very start of the text.
     */
    fun cutAppends(
        appends: JSONArray?,
        text: String,
        at: IntArray,
        line: Timeline,
    ): JSONArray {
        val result = JSONArray()
        if (appends == null) return result
        val found = tokens(text)
        val marks = (0 until appends.length()).mapNotNull { appends.optJSONObject(it) }.sortedBy { it.optInt("textAt") }
        marks.forEachIndexed { k, mark ->
            val from = mark.optInt("textAt")
            val to = marks.getOrNull(k + 1)?.optInt("textAt") ?: Int.MAX_VALUE
            val first = found.indices.firstOrNull { found[it].first in from until to && at[it] >= 0 } ?: return@forEachIndexed
            if (at[first] == 0) return@forEachIndexed
            val moved = JSONObject(mark.toString()).put("textAt", at[first])
            if (mark.has("fromMs")) moved.put("fromMs", line.map(mark.optLong("fromMs").toDouble()).roundToLong())
            result.put(moved)
        }
        return result
    }

    /** The waveform bars (one byte per [AudioPeaks.STEP_MS]) of the kept spans, one after another. */
    fun cutPeaks(
        bars: ByteArray,
        kept: List<NoteCut.Span>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        kept.forEach { span ->
            val from = (span.fromMs / AudioPeaks.STEP_MS).roundToInt().coerceIn(0, bars.size)
            val to = (span.toMs / AudioPeaks.STEP_MS).roundToInt().coerceIn(from, bars.size)
            out.write(bars, from, to - from)
        }
        return out.toByteArray()
    }
}

/** Writes [text] to [file] through a temporary file, so a crash never leaves half a file. */
internal fun writeAtomically(
    file: File,
    text: String,
) {
    val tmp = File(file.path + ".tmp")
    tmp.writeText(text)
    check(tmp.renameTo(file)) { "rename_failed:${file.name}" }
}
