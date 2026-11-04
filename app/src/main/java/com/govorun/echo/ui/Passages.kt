package com.govorun.echo.ui

import androidx.compose.runtime.Immutable
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.SearchWords
import com.govorun.echo.notes.WordForms
import com.govorun.echo.notes.wordRanges

/**
 * Where a search is answered inside a long note, for its card in the list: the sentence with the found words, and when it was
 * said, so a tap can open the note right there.
 */
@Immutable
internal class Passage(
    /** The words as the card shows them, with «…» where the sentence was cut. */
    val text: String,
    /** Where the found words stand in [text]. */
    val marks: List<IntRange>,
    /** When the first word of [text] was said, from the start of the recording; null for a note without timed words or sound. */
    val atMs: Long?,
    /** The words of the request that [text] holds, by their place in the request. */
    val holds: Set<Int>,
)

/** [NoteWords.passage] of [note] for [query], its last word taken as being typed when the note does not hold it whole. */
internal fun passageOf(
    note: NoteStore.Entry,
    query: String,
): Passage? = if (WordForms.request(query).isEmpty()) null else NoteWords.of(note).passage(SearchWords.of(query, WordForms.plain(note.text)))

/** Where in [text] the words answering [search] stand, each as the letters that answer: a card lights them in its own lines. */
internal fun foundIn(
    text: String,
    search: SearchWords,
): List<IntRange> {
    val plain = WordForms.plain(text)
    if (plain.length != text.length) return emptyList()
    val found = mutableListOf<IntRange>()
    var start = -1
    for (i in 0..plain.length) {
        val inWord = i < plain.length && plain[i].isLetterOrDigit()
        if (inWord && start < 0) start = i
        if (!inWord && start >= 0) {
            if (search.answers(plain.substring(start, i)) >= 0) found += start until i
            start = -1
        }
    }
    return found
}

/** Whether [shown], the start of the note's text that its card already shows, holds every word the passage holds. */
internal fun Passage.shownIn(
    shown: String,
    search: SearchWords,
): Boolean = com.govorun.echo.notes.NoteIndex.wordsOf(WordForms.plain(shown)).mapTo(HashSet()) { search.answers(it) }.containsAll(holds)

/** A note's words in the order its screen reads them, each with the time it was said when the note has timed words and sound. */
@Immutable
internal class NoteWords private constructor(
    private val texts: List<String>,
    private val times: List<Long>?,
    /** Whether a paragraph begins at each word. */
    private val opens: BooleanArray,
) {
    private val size = texts.size

    /** The words as written, one space apart, and where each begins in it. */
    private val shown = texts.joinToString(" ")
    private val shownAt = IntArray(size).also { at -> (1 until size).forEach { at[it] = at[it - 1] + texts[it - 1].length + 1 } }

    /** The same in [WordForms.plain] form, where the search looks; a letter that changes length in lower case is rare enough to cost a slower way. */
    private val plain = WordForms.plain(shown).takeIf { it.length == shown.length } ?: texts.joinToString(" ") { WordForms.plain(it) }
    private val starts = if (plain.length == shown.length) shownAt else IntArray(size).also { at -> (1 until size).forEach { at[it] = at[it - 1] + WordForms.plain(texts[it - 1]).length + 1 } }

    /** The sentence of each word, counted from 0: a sentence ends at «.», «?», «!» or «…» and where a paragraph ends. */
    private val sentences = IntArray(size).also { of -> (1 until size).forEach { of[it] = of[it - 1] + if (opens[it] || endsSentence(texts[it - 1])) 1 else 0 } }

    /**
     * The first sentence that holds the most words of the request, found as [matchesQuery] finds them ([SearchWords]: a word in
     * another form, never a piece of another word), «ё» as «е», the common words of a question passed over when there are others.
     * The words are those the note screen reads (the timed words in paragraphs), so the time is the time of the first word shown and
     * the screen lights that same word. A find deep in a long sentence is shown from a few words before it, a short sentence with
     * what follows it. Null when the text holds none of the words: the note was found by its name, tags, status or date.
     */
    fun passage(search: SearchWords): Passage? {
        val hits = find(search)
        if (hits.isEmpty()) return null
        // Of the sentences with the most different words of the request, the first.
        val (sentence, inSentence) = hits.groupBy { sentences[it.word] }.entries.maxWith(compareBy<Map.Entry<Int, List<Hit>>> { e -> e.value.distinctBy { it.request }.size }.thenByDescending { it.key })
        val first = inSentence.minOf { it.word }
        var start = first
        while (start > 0 && sentences[start - 1] == sentence) start--
        var end = first
        while (end + 1 < size && sentences[end + 1] == sentence) end++
        // The card has two lines for it: a find far into its sentence is shown from a few words before it.
        var from = start
        if (length(start, first) - texts[first].length > LEAD_CHARS) {
            from = first
            while (from > start && length(from - 1, first) - texts[first].length <= BEFORE_CHARS) from--
        }
        // A short sentence takes the next words of its paragraph along; a long one stops some way after the find.
        var to = end
        while (to + 1 < size && !opens[to + 1] && length(from, to) < FILL_CHARS) to++
        while (to > first && length(from, to) > MOST_CHARS) to--
        val cutBefore = from > 0 && !opens[from] && !endsSentence(texts[from - 1])
        val cutAfter = to + 1 < size && !opens[to + 1] && !endsSentence(texts[to])
        val shift = (if (cutBefore) ELLIPSIS.length else 0) - shownAt[from]
        val inside = hits.filter { it.word in from..to }
        val marks =
            inside
                .map { hit ->
                    // In the rare word whose letters change length in lower case, the whole word is marked.
                    val word = if (starts === shownAt) hit.range else shownAt[hit.word]..(shownAt[hit.word] + texts[hit.word].length - 1)
                    (word.first + shift)..(word.last + shift)
                }.sortedBy { it.first }
                .fold(mutableListOf<IntRange>()) { merged, mark ->
                    val last = merged.lastOrNull()
                    if (last != null && mark.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, mark.last) else merged += mark
                    merged
                }
        val text = (if (cutBefore) ELLIPSIS else "") + shown.substring(shownAt[from], shownAt[to] + texts[to].length) + (if (cutAfter) ELLIPSIS else "")
        return Passage(text, marks, times?.get(from), inside.mapTo(HashSet()) { it.request })
    }

    /** The characters from word [from] to word [to], both shown, one space apart. */
    private fun length(
        from: Int,
        to: Int,
    ) = shownAt[to] + texts[to].length - shownAt[from]

    /** Every word of the text that answers a word of the request, by [SearchWords.answers]: the runs of letters and digits in each word. */
    private fun find(search: SearchWords): List<Hit> {
        val hits = mutableListOf<Hit>()
        for (word in 0 until size) {
            // The words stand one space apart in the plain text.
            val end = if (word + 1 < size) starts[word + 1] - 1 else plain.length
            var start = -1
            for (i in starts[word]..end) {
                val inWord = i < end && plain[i].isLetterOrDigit()
                if (inWord && start < 0) start = i
                if (!inWord && start >= 0) {
                    val answers = search.answers(plain.substring(start, i))
                    if (answers >= 0) hits += Hit(word, answers, start until i)
                    start = -1
                }
            }
        }
        return hits
    }

    /** One find: the word it is in, which word of the request it answers, and where it stands in the plain text. */
    private class Hit(
        val word: Int,
        val request: Int,
        val range: IntRange,
    )

    companion object {
        fun of(note: NoteStore.Entry): NoteWords {
            val texts = mutableListOf<String>()
            val opens = mutableListOf<Boolean>()
            if (note.phrases.isEmpty() || note.audio.isEmpty()) {
                // No timed words: the note screen reads its text, and a new line begins a paragraph.
                var last = 0
                wordRanges(note.text).forEach { word ->
                    opens += texts.isEmpty() || '\n' in note.text.substring(last, word.first)
                    texts += note.text.substring(word)
                    last = word.last + 1
                }
                return NoteWords(texts, null, opens.toBooleanArray())
            }
            // As the note screen builds its text (DraftText.of): the timed words, in paragraphs at the pauses.
            val times = mutableListOf<Long>()
            paragraphsOf(note.phrases.filter { it.words.isNotEmpty() }).forEach { paragraph ->
                paragraph.flatMap { it.words }.forEachIndexed { i, word ->
                    opens += i == 0
                    texts += word.text
                    times += word.startMs
                }
            }
            return NoteWords(texts, times, opens.toBooleanArray())
        }
    }
}

/** How much of a long sentence is shown before its first find, and how long a passage is at least and at most, in characters. */
private const val LEAD_CHARS = 40
private const val BEFORE_CHARS = 20
private const val FILL_CHARS = 100
private const val MOST_CHARS = 220
private const val ELLIPSIS = "…"

private fun endsSentence(word: String) = word.trimEnd('»', '"', '”', ')', ']').lastOrNull()?.let { it in ".?!…" } == true
