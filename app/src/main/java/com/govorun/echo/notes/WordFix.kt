package com.govorun.echo.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fixing the words Govorun heard wrong, right in a note's text. The words of a piece are typed anew; only what differs from the old
 * ones changes (the longest run of words the two share stays as it is, with its own times). New words take the time of the words
 * they replace, shared by their length as estimated times are; a word only added shares the time of the word after it (of the one
 * before it at the end of the piece). The sound is never touched, so the lit word, a tap on a word, «Без пауз», «Фрагмент», the
 * subtitles and the video stay in step with it. The text, the words, the marks of continuations and a joined note's passport of
 * parts change together; [undo] puts them back while nothing else has changed the note.
 */
internal object WordFix {
    /** Above this many pairs of words to compare, what differs is taken as one change (the text is still right, the times rougher). */
    private const val MAX_COMPARED = 1_000_000L

    /** Words [from, to) of a note (counted through all its words) replaced by [words]. */
    data class Change(
        val from: Int,
        val to: Int,
        val words: List<String>,
    )

    /** A fix that was made: the state before it and what it wrote, for [undo]. */
    class Done internal constructor(
        val text: String,
        val segments: String?,
        val appends: String?,
        val parts: String?,
        val modifiedAtMs: Long?,
        val newText: String,
        val newSegments: String?,
    )

    fun tokens(text: String): List<String> = wordRanges(text).map { text.substring(it) }

    /**
     * What turns the words [old] into [new], as runs of words to replace (indices into [old]). The words both keep in the same order
     * (their longest common subsequence) stay; a run that only adds words takes in the old word after it, or before it at the end, so
     * every word has a time of its own.
     */
    fun changes(
        old: List<String>,
        new: List<String>,
    ): List<Change> {
        var start = 0
        while (start < old.size && start < new.size && old[start] == new[start]) start++
        var oldEnd = old.size
        var newEnd = new.size
        while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) {
            oldEnd--
            newEnd--
        }
        val n = oldEnd - start
        val m = newEnd - start
        if (n == 0 && m == 0) return emptyList()
        val runs = mutableListOf<Change>()
        if (n == 0 || m == 0 || n.toLong() * m > MAX_COMPARED) {
            runs += Change(start, oldEnd, new.subList(start, newEnd))
        } else {
            // common[i * w + j]: how many words old[start + i ..] and new[start + j ..] share in order.
            val w = m + 1
            val common = IntArray((n + 1) * w)
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    common[i * w + j] = if (old[start + i] == new[start + j]) common[(i + 1) * w + j + 1] + 1 else maxOf(common[(i + 1) * w + j], common[i * w + j + 1])
                }
            }
            var i = 0
            var j = 0
            var runI = -1
            var runJ = -1
            while (i < n || j < m) {
                if (i < n && j < m && old[start + i] == new[start + j]) {
                    if (runI >= 0) runs += Change(start + runI, start + i, new.subList(start + runJ, start + j))
                    runI = -1
                    i++
                    j++
                    continue
                }
                if (runI < 0) {
                    runI = i
                    runJ = j
                }
                if (j < m && (i == n || common[i * w + j + 1] >= common[(i + 1) * w + j])) j++ else i++
            }
            if (runI >= 0) runs += Change(start + runI, start + i, new.subList(start + runJ, start + j))
        }
        val result = mutableListOf<Change>()
        for (run in runs) {
            val change =
                when {
                    run.from < run.to || old.isEmpty() -> run
                    run.to < old.size -> Change(run.from, run.to + 1, run.words + old[run.to])
                    else -> Change(run.from - 1, run.to, listOf(old[run.from - 1]) + run.words)
                }
            val last = result.lastOrNull()
            // Words added on both sides of one old word: one change with that word between them.
            if (last != null && change.from < last.to) result[result.lastIndex] = Change(last.from, change.to, last.words + change.words.drop(last.to - change.from)) else result += change
        }
        return result
    }

    /** A text after its changes: [leads] tells, for every old word, which new word it now leads to ([count] when none). */
    class Edited(
        val text: String,
        val leads: IntArray,
        private val starts: IntArray,
    ) {
        val count: Int get() = starts.size

        fun startOf(word: Int): Int = starts[word]
    }

    /**
     * The text with [changes] made (sorted, not overlapping). New words stand where the first word they replace stood, a space apart;
     * a break between the old words (a paragraph, a continuation) moves after them; where words only go, the strongest break among
     * the gaps closed stays, as a cut leaves it. Everything else keeps its exact spaces and breaks.
     */
    fun edit(
        text: String,
        changes: List<Change>,
    ): Edited {
        val found = wordRanges(text)
        // gap(i): the text before word i; gap(found.size): the text after the last word.
        fun gap(i: Int) = text.substring(if (i == 0) 0 else found[i - 1].last + 1, if (i < found.size) found[i].first else text.length)
        val out = StringBuilder()
        val leads = IntArray(found.size) { -1 }
        val starts = mutableListOf<Int>()
        val gaps = mutableListOf<String>()
        val waiting = mutableListOf<Int>()

        fun write(word: String) {
            out.append(joint(gaps, first = starts.isEmpty()))
            gaps.clear()
            waiting.forEach { leads[it] = starts.size }
            waiting.clear()
            starts += out.length
            out.append(word)
        }
        var i = 0
        var c = 0
        while (i < found.size) {
            val change = changes.getOrNull(c)
            if (change == null || i < change.from) {
                gaps += gap(i)
                waiting += i
                write(text.substring(found[i]))
                i++
                continue
            }
            gaps += gap(change.from)
            if (change.words.isEmpty()) {
                for (k in change.from + 1 until change.to) gaps += gap(k)
                for (k in change.from until change.to) waiting += k
            } else {
                waiting += change.from
                write(change.words.first())
                change.words.drop(1).forEach { word ->
                    out.append(' ')
                    starts += out.length
                    out.append(word)
                }
                for (k in change.from + 1 until change.to) {
                    if ('\n' in gap(k)) gaps += gap(k)
                    waiting += k
                }
            }
            i = change.to
            c++
        }
        if (starts.isEmpty()) return Edited("", IntArray(found.size), IntArray(0))
        out.append(gap(found.size))
        waiting.forEach { leads[it] = starts.size }
        return Edited(out.toString(), leads, starts.toIntArray())
    }

    /** The gap before a word: the old one where nothing was closed, else the strongest break among those closed, as a cut keeps it. */
    private fun joint(
        gaps: List<String>,
        first: Boolean,
    ): String {
        if (gaps.size == 1) return gaps.single()
        if (first) return ""
        val level = gaps.maxOf { levelOf(it) }
        return if (levelOf(gaps.last()) == level) gaps.last() else listOf(" ", "\n", "\n\n")[level]
    }

    private fun levelOf(gap: String) =
        when {
            "\n\n" in gap -> 2
            '\n' in gap -> 1
            else -> 0
        }

    /**
     * The marks of continuations after the edit: each goes to the start of the word its first word now leads to; one whose words are
     * all gone, or that now stands at the very start, goes. Null for no marks.
     */
    fun marks(
        marks: JSONArray?,
        text: String,
        edited: Edited,
    ): JSONArray? {
        if (marks == null) return null
        val starts = wordRanges(text).map { it.first }
        val result = JSONArray()
        for (k in 0 until marks.length()) {
            val mark = marks.optJSONObject(k) ?: continue
            val word = starts.indexOfFirst { it >= mark.optInt("textAt") }.takeIf { it >= 0 } ?: continue
            val lead = edited.leads[word]
            if (lead >= edited.count || edited.startOf(lead) == 0) continue
            result.put(JSONObject(mark.toString()).put("textAt", edited.startOf(lead)))
        }
        return result
    }

    /**
     * The phrases after [changes]: new words go into the phrase of the first word they replace, which reaches over them; a later
     * phrase that gave words to them begins no earlier than they end; a phrase left without words goes. Every other phrase is kept
     * exactly, its spoken span is Govorun's.
     */
    fun phrases(
        phrases: List<CutModel.RawPhrase>,
        changes: List<Change>,
    ): List<CutModel.RawPhrase> {
        val all = phrases.flatMap { it.words }
        val owner = IntArray(all.size)
        var k = 0
        phrases.forEachIndexed { p, phrase -> repeat(phrase.words.size) { owner[k++] = p } }
        val words = Array(phrases.size) { mutableListOf<NoteStore.Word>() }
        val changed = BooleanArray(phrases.size)
        val notBefore = LongArray(phrases.size) { Long.MIN_VALUE }
        var i = 0
        var c = 0
        while (i < all.size) {
            val change = changes.getOrNull(c)
            if (change == null || i < change.from) {
                words[owner[i]] += all[i]
                i++
                continue
            }
            val fresh = timed(change.words, all[change.from].startMs, all[change.to - 1].endMs)
            val home = owner[change.from]
            words[home] += fresh
            for (j in change.from until change.to) {
                changed[owner[j]] = true
                if (owner[j] != home && fresh.isNotEmpty()) notBefore[owner[j]] = maxOf(notBefore[owner[j]], fresh.last().endMs)
            }
            i = change.to
            c++
        }
        return phrases.mapIndexedNotNull { p, phrase ->
            val list = words[p]
            when {
                !changed[p] -> phrase
                list.isEmpty() -> null
                else ->
                    phrase.copy(
                        startMs = maxOf(phrase.startMs, minOf(notBefore[p], list.first().startMs)),
                        endMs = maxOf(phrase.endMs, list.last().endMs),
                        text = list.joinToString(" ") { it.text },
                        words = list,
                    )
            }
        }
    }

    /** New words over [startMs]-[endMs], each by its length as estimated words are; all at its start when the span has no length. */
    private fun timed(
        words: List<String>,
        startMs: Long,
        endMs: Long,
    ): List<NoteStore.Word> = estimatedWords(words.joinToString(" "), startMs, endMs).takeIf { it.size == words.size } ?: words.map { NoteStore.Word(startMs, maxOf(startMs, endMs), it) }

    /**
     * A joined note's passport with the same changes made in its parts' texts and marks, so splitting it and the text that leaves the
     * app show the fixed words too. Null when a change reaches across two parts or would leave a part without words.
     */
    fun parts(
        parts: JSONArray,
        changes: List<Change>,
    ): JSONArray? {
        val result = JSONArray()
        var offset = 0
        for (k in 0 until parts.length()) {
            val part = JSONObject(parts.getJSONObject(k).toString())
            val text = part.optString("text")
            val count = tokens(text).size
            val own = changes.filter { it.from < offset + count && it.to > offset }
            if (own.any { it.from < offset || it.to > offset + count }) return null
            if (own.isNotEmpty()) {
                val edited = edit(text, own.map { Change(it.from - offset, it.to - offset, it.words) })
                if (edited.count == 0) return null
                marks(part.optJSONArray("appends"), text, edited)?.let { part.put("appends", it) }
                part.put("text", edited.text)
            }
            offset += count
            result.put(part)
        }
        return result
    }

    /**
     * Fixes the note in [dir]: its words from [from] on, which must still be [expected] (what the screen showed, so nothing else is
     * ever changed), become the words of [typed]. Null when nothing changes or the note cannot take it now: still being written,
     * busy with another edit, a text that is not its words one after another, or a fix that would leave it without words.
     */
    fun apply(
        dir: File,
        from: Int,
        expected: List<String>,
        typed: String,
    ): Done? {
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return null
        if (meta.optString("state") != NoteStore.STATE_DONE || meta.has("appendFromMs") || NoteStore.pcmFile(dir).exists()) return null
        val text = runCatching { File(dir, "text.txt").readText() }.getOrNull() ?: return null
        val segments = File(dir, "segments.json").takeIf { it.exists() }?.readText()
        val phrases = if (segments != null) CutModel.readPhrases(dir) else emptyList()
        // The words on the screen are the note's timed words, or its text's own when it has none.
        if (phrases.isNotEmpty() && !CutModel.textFollowsWords(text, phrases.flatMap { p -> p.words.map { it.text } })) return null
        val words = tokens(text)
        if (expected.isEmpty() || from < 0 || from + expected.size > words.size || words.subList(from, from + expected.size) != expected) return null
        val changes = changes(expected, tokens(typed)).map { Change(it.from + from, it.to + from, it.words) }
        if (changes.isEmpty()) return null
        val edited = edit(text, changes)
        if (edited.count == 0) return null
        val parts = meta.optJSONArray("parts")?.let { parts(it, changes) ?: return null }
        val oldMarks = meta.optJSONArray("appends")
        val marks = marks(oldMarks, text, edited)
        val ok =
            NoteCut.rewrite(dir) {
                writeAtomically(File(dir, "text.txt"), edited.text)
                if (phrases.isNotEmpty()) CutModel.writePhrases(dir, phrases(phrases, changes))
                NoteStore.updateMeta(dir) {
                    if (parts != null) put("parts", parts)
                    if (marks != null) {
                        if (marks.length() > 0) put("appends", marks) else remove("appends")
                        // A continuation whose words all went no longer makes the note written to later, as after a cut.
                        if (marks.length() < oldMarks!!.length()) {
                            val last = (0 until marks.length()).maxOfOrNull { marks.getJSONObject(it).optLong("atMs") }
                            if (last != null) put("modifiedAtMs", last) else remove("modifiedAtMs")
                        }
                    }
                }
            }
        if (!ok) return null
        return Done(
            text = text,
            segments = segments,
            appends = oldMarks?.toString(),
            parts = meta.optJSONArray("parts")?.toString(),
            modifiedAtMs = if (meta.has("modifiedAtMs")) meta.optLong("modifiedAtMs") else null,
            newText = edited.text,
            newSegments = File(dir, "segments.json").takeIf { it.exists() }?.readText(),
        )
    }

    /** Puts the note back as it was before [done], if nothing else has changed its text or words since. */
    fun undo(
        dir: File,
        done: Done,
    ): Boolean {
        val text = runCatching { File(dir, "text.txt").readText() }.getOrNull()
        val segments = File(dir, "segments.json").takeIf { it.exists() }?.readText()
        if (text != done.newText || segments != done.newSegments) return false
        return NoteCut.rewrite(dir) {
            writeAtomically(File(dir, "text.txt"), done.text)
            if (done.segments != null) writeAtomically(File(dir, "segments.json"), done.segments) else File(dir, "segments.json").delete()
            NoteStore.updateMeta(dir) {
                if (done.appends != null) put("appends", JSONArray(done.appends)) else remove("appends")
                if (done.parts != null) put("parts", JSONArray(done.parts)) else remove("parts")
                if (done.modifiedAtMs != null) put("modifiedAtMs", done.modifiedAtMs) else remove("modifiedAtMs")
            }
        }
    }
}
