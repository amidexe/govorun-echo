package com.govorun.echo.recognition

import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.estimatedWords

/**
 * Govorun answers a whole file with the text (punctuated) and, separately, the words with times. The text is cut into phrases at
 * sentence ends and at pauses, and each word of the text gets its time ([alignedWords]).
 */
internal fun phrasesFromWords(
    text: String,
    starts: LongArray,
    ends: LongArray,
    words: Array<String>? = null,
    pauseMs: Long = 1_000L,
): List<NoteStore.Phrase> {
    val aligned = alignedWords(text, starts, ends, words)
    if (aligned.isEmpty()) return emptyList()
    val phrases = mutableListOf<NoteStore.Phrase>()
    var from = 0
    for (i in aligned.indices) {
        val last = i == aligned.lastIndex
        val sentenceEnd = aligned[i].text.last() in ".?!…"
        val pause = !last && aligned[i + 1].startMs - aligned[i].endMs >= pauseMs
        if (last || sentenceEnd || pause) {
            val part = aligned.subList(from, i + 1)
            phrases += NoteStore.Phrase(part.first().startMs, part.last().endMs, part.joinToString(" ") { it.text }, part.toList())
            from = i + 1
        }
    }
    return phrases
}

/**
 * A time for every word of the punctuated [text]. Govorun gives the recognized [words] with their times separately: they are
 * matched to the text one by one (letters only, any case, ё as е), and a text word with no match (a number in digits, a dash)
 * shares the gap between its matched neighbours. Without [words], equal counts are taken one to one and anything else is placed
 * by position. Either way a mismatch stays where it is instead of moving the times of a whole file.
 */
internal fun alignedWords(
    text: String,
    starts: LongArray,
    ends: LongArray,
    words: Array<String>? = null,
): List<NoteStore.Word> {
    val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val n = starts.size
    if (tokens.isEmpty() || n == 0 || ends.size != n) return emptyList()
    if (words == null || words.size != n) {
        if (tokens.size == n) return tokens.mapIndexed { i, token -> NoteStore.Word(starts[i], ends[i], token) }
        // No words to match: each text word goes to the timed word at the same relative place, and the text words that land on
        // the same one share its time by their length.
        val slot = tokens.indices.map { (it.toLong() * n / tokens.size).toInt() }
        return tokens.indices.groupBy { slot[it] }.toSortedMap().flatMap { (k, group) ->
            estimatedWords(group.joinToString(" ") { tokens[it] }, starts[k], ends[k]).ifEmpty { group.map { NoteStore.Word(starts[k], ends[k], tokens[it]) } }
        }
    }
    val match = arrayOfNulls<Int>(tokens.size)
    var next = 0
    tokens.forEachIndexed { i, token ->
        val key = plain(token)
        if (key.isEmpty() || next >= n) return@forEachIndexed
        val k = (next until minOf(n, next + LOOKAHEAD)).firstOrNull { plain(words[it]) == key }
        if (k != null) {
            match[i] = k
            next = k + 1
        }
    }
    val result = ArrayList<NoteStore.Word>(tokens.size)
    var i = 0
    while (i < tokens.size) {
        val k = match[i]
        if (k != null) {
            result += NoteStore.Word(starts[k], ends[k], tokens[i])
            i++
            continue
        }
        var j = i
        while (j < tokens.size && match[j] == null) j++
        val from = result.lastOrNull()?.endMs ?: starts[0]
        val to = maxOf(from, if (j < tokens.size) starts[match[j]!!] else ends[n - 1])
        val gap = estimatedWords(tokens.subList(i, j).joinToString(" "), from, to)
        for (m in i until j) result += gap.getOrNull(m - i) ?: NoteStore.Word(from, from, tokens[m])
        i = j
    }
    return result
}

/** A word as Govorun spells it in its list: letters and digits only, lower case, ё as е. */
private fun plain(word: String) = word.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

/** How far ahead a text word looks for its match: Govorun may split a word in two or drop a filler. */
private const val LOOKAHEAD = 4
