package com.govorun.echo.ui

import android.content.Context
import androidx.annotation.StringRes
import com.govorun.echo.R
import com.govorun.echo.notes.NoteImport
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.WordForms
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.govorun.echo.errorLabel
import com.govorun.echo.TextOptions

internal enum class Screen {
    Notes, Note, Settings, Commands, AdvancedCommands, CommandEdit, CommandCreate, CommandHistory,
    Journal, Trash, Licenses, License, About, Experiments,
}

/** What stands between the owner and a working note, in the order it should be fixed. */
internal data class SetupState(
    val microphone: Boolean,
    val govorun: GovorunState,
)

internal enum class GovorunState { Checking, Ready, NotInstalled, TooOld, NeedsAccess, ModelUnavailable, Error }

internal fun NoteStore.Entry.isInProgress() =
    state == NoteStore.STATE_RECORDING || state == NoteStore.STATE_RECOGNIZING || archiving

/**
 * Failed only because Govorun was missing, not yet allowed or without a model: worth retrying once it is ready. Such a note just
 * waits, quietly: the list's one banner says why, not every note.
 */
internal fun NoteStore.Entry.waitsForGovorun() = state == NoteStore.STATE_FAILED && error in setOf("not_installed", "needs_authorization", "model_unavailable", "unreachable", "busy")

/** A note whose text did not come (failed, never started, or still waiting) can always be asked for again. */
internal fun NoteStore.Entry.canRetry() = state == NoteStore.STATE_FAILED || state == NoteStore.STATE_RECORDED || state == NoteStore.STATE_RECOGNIZING

/**
 * A long job under way, 0 to 1, for [com.product.ui.ThinProgress]; null when there is nothing to count. An imported file shows one
 * line from the start: its first tenth is the file being made ready, the rest is recognition, so the card never jumps.
 */
internal fun recognitionFraction(note: NoteStore.Entry): Float? {
    if (note.state != NoteStore.STATE_RECOGNIZING || note.totalMs <= 0) return null
    val ready = if (note.mode == NoteImport.MODE) 0.1f else 0f
    return if (note.error == NoteImport.PREPARING) ready * note.preparedMs / note.totalMs else ready + (1f - ready) * note.recognizedMs / note.totalMs
}

@StringRes
internal fun statusLabel(note: NoteStore.Entry): Int =
    when (note.state) {
        NoteStore.STATE_DONE -> if (note.mode == "merged") R.string.status_merged else if (note.mode == NoteImport.MODE) R.string.status_imported else R.string.status_done
        NoteStore.STATE_RECORDING -> if (com.govorun.echo.record.RecordingBus.pausedAt.value != null) R.string.status_paused else R.string.status_recording
        NoteStore.STATE_RECOGNIZING -> if (note.error == "unreachable") R.string.status_waiting_unreachable else if (note.error == "busy") R.string.status_waiting_govorun else if (note.error == NoteImport.PREPARING) R.string.status_preparing else if (note.queued) R.string.status_queued else when (com.govorun.echo.recognition.RecognitionStatus.pausedReason.value) {
            com.govorun.echo.recognition.RecognitionStatus.RECORDING -> R.string.status_held_recording
            else -> R.string.status_recognizing
        }
        NoteStore.STATE_FAILED -> if (note.waitsForGovorun()) R.string.status_waiting else errorLabel(note.error)
        else -> R.string.status_waiting
    }

internal fun formatTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** When a note was said, for a list without day headers: the time today, else the day and the time. */
internal fun formatDayTime(context: Context, ms: Long): String {
    val day = dayOf(ms)
    val today = LocalDate.now()
    return when {
        day == today -> formatTime(ms)
        day.year == today.year -> DateTimeFormatter.ofPattern("d MMM").format(day) + ", " + formatTime(ms)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(day)
    }
}

internal fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun dayLabel(
    context: Context,
    day: LocalDate,
): String {
    val today = LocalDate.now()
    return when (day) {
        today -> context.getString(R.string.day_today)
        today.minusDays(1) -> context.getString(R.string.day_yesterday)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(day)
    }
}

/** Text for copy and share: finished notes only, oldest first, separated by a blank line. */
private val stampFormat = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
private val shortStampFormat = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

/** "== 17.10.2026 14:05 ==" above a text: when it was said. With [modifiedAtMs] and the mark on, also when it was written to later. */
internal fun stampOf(
    startedAtMs: Long,
    modifiedAtMs: Long = 0L,
    markAppended: Boolean = false,
): String {
    val later = if (markAppended && modifiedAtMs - startedAtMs > NoteStore.CONTINUED_AFTER_MS) ", дописано ${shortStampFormat.format(Instant.ofEpochMilli(modifiedAtMs))}" else ""
    return "== ${stampFormat.format(Instant.ofEpochMilli(startedAtMs))}$later =="
}

/** "== 21.10.2026 15:30, дописано ==" above a piece said into a note later. */
private fun laterStampOf(
    atMs: Long,
    markAppended: Boolean,
) = "== ${stampFormat.format(Instant.ofEpochMilli(atMs))}${if (markAppended) ", дописано" else ""} =="

/** The continuations that begin in [text] at or after [from], in text order; a place that is not the start of a paragraph is not trusted. */
private fun cutsOf(
    text: String,
    from: Int,
    appends: List<NoteStore.Append>,
) = appends.filter { it.textAt in from..text.length && (it.textAt == 0 || text[it.textAt - 1] == '\n') }.sortedBy { it.textAt }

/** The text from each cut to the next under the time it was said. */
private fun piecesAt(
    text: String,
    cuts: List<NoteStore.Append>,
    markAppended: Boolean,
): List<String> =
    cuts.mapIndexedNotNull { i, cut ->
        val end = cuts.getOrNull(i + 1)?.textAt ?: text.length
        text.substring(cut.textAt, end).trim().takeIf { it.isNotEmpty() }?.let { "${laterStampOf(cut.atMs, markAppended)}\n$it" }
    }

/**
 * A note's text under the time it was said, and every piece added to it later under the time that piece was said, right where it
 * begins: the order of thoughts stays as it was, and a reader or an AI does not tie together what was said hours apart. A note
 * continued before the places were kept can only say in its heading when it was last written to.
 */
internal fun stampedText(
    startedAtMs: Long,
    modifiedAtMs: Long,
    text: String,
    appends: List<NoteStore.Append>,
    markAppended: Boolean,
): String {
    val body = text.trim()
    val cuts = cutsOf(body, 0, appends)
    if (cuts.isEmpty()) return "${stampOf(startedAtMs, modifiedAtMs, markAppended)}\n$body"
    val first = body.substring(0, cuts.first().textAt).trim().takeIf { it.isNotEmpty() }?.let { "${stampOf(startedAtMs)}\n$it" }
    return (listOfNotNull(first) + piecesAt(body, cuts, markAppended)).joinToString("\n\n")
}

/**
 * The text of a note as it leaves the app (copy, share, export). A plain note is its text. A joined note carries the time of each
 * of its parts, because the order it was joined in may not be the order things were said in; a continued note carries the time of
 * each continuation where it begins. A named note begins with its name.
 */
internal fun exportTextOf(
    note: NoteStore.Entry,
    options: TextOptions = TextOptions(),
): String = note.titled(bodyOf(note, options))

/** The name as the first paragraph of the text that leaves the app, for a note named by hand; one from its own words would repeat it. */
private fun NoteStore.Entry.titled(body: String) = if (title.isBlank() || titleAuto) body else "${title.trim()}\n\n$body"

private fun bodyOf(
    note: NoteStore.Entry,
    options: TextOptions,
): String {
    if (!options.stamps || (note.parts.isEmpty() && !note.continued)) return note.text.trim()
    if (note.parts.isEmpty()) return stampedText(note.startedAtMs, note.modifiedAtMs, note.text, note.appends, options.markAppended)
    val parts = note.parts.filter { it.text.isNotBlank() }.map { stampedText(it.startedAtMs, it.modifiedAtMs, it.text, it.appends, options.markAppended) }
    // A joined note's text begins with its parts' texts; whatever follows was said into it after the join.
    val whole = note.text.trim()
    val joined = note.parts.joinToString("\n\n") { it.text.trim() }
    val added =
        when {
            whole.length <= joined.length || !whole.startsWith(joined) -> emptyList()
            else -> cutsOf(whole, joined.length, note.appends).let { cuts -> if (cuts.isEmpty()) listOf("${laterStampOf(note.modifiedAtMs, options.markAppended)}\n${whole.substring(joined.length).trim()}") else piecesAt(whole, cuts, options.markAppended) }
        }
    return (parts + added).joinToString("\n\n")
}

/** The notes' texts one after another in the order given, each under its time when there are several. */
internal fun joinedText(
    notes: List<NoteStore.Entry>,
    options: TextOptions = TextOptions(),
): String {
    val withText = notes.filter { it.text.isNotBlank() }
    return when {
        withText.size == 1 || !options.stamps -> withText.joinToString("\n\n") { exportTextOf(it, options) }
        else -> withText.joinToString("\n\n") { note -> if (note.parts.isEmpty()) note.titled(stampedText(note.startedAtMs, note.modifiedAtMs, note.text, note.appends, options.markAppended)) else exportTextOf(note, options) }
    }
}

/** The notes with these ids, oldest first: the order copy, share, export and merge use, with the time of each piece in the text. */
internal fun List<NoteStore.Entry>.chronologicalOf(ids: List<String>): List<NoteStore.Entry> = filter { it.id in ids }.sortedBy { it.startedAtMs }

/** Why a recording ended by itself; null when the owner stopped it or it hit nothing unusual. */
@StringRes
internal fun stopLabel(note: NoteStore.Entry): Int? =
    when (note.stopReason) {
        "silence" -> R.string.stop_silence_note
        "battery" -> R.string.stop_battery
        "storage" -> R.string.stop_storage
        "cap" -> R.string.stop_cap
        else -> null
    }

internal fun formatSize(bytes: Long): String =
    when {
        bytes >= 1_048_576 -> "%.1f МБ".format(bytes / 1_048_576.0)
        else -> "%d КБ".format((bytes + 1_023) / 1_024)
    }

private val MONTHS =
    listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/**
 * Search over text and dates. Every word must match something: the note's name or text, its status, or its date ("17", "17.10",
 * "2026", "октябрь", "вчера"); tags are found as tags, by their names under the search field. Several words narrow the result; with [anyWord] one of them is enough,
 * for a list ordered by how well each note answers ([rankedNotes]). A word is found in any of its forms («лекции» finds
 * «лекцию») and never as a piece of another word ([com.govorun.echo.notes.SearchWords]), «ё» counts as «е», and the common words
 * of a spoken request («что», «когда», «мне») are passed over when it has others.
 */
internal fun matchesQuery(
    note: NoteStore.Entry,
    query: String,
    today: LocalDate = LocalDate.now(),
    statusText: String = "",
    /** The index's answer for this request: a note it knows as it is now is looked up there instead of in its whole text. */
    found: com.govorun.echo.notes.NoteIndex.Found? = null,
    anyWord: Boolean = false,
    aliasOf: (String) -> String = { "" },
): Boolean {
    val words = WordForms.request(query)
    if (words.isEmpty()) return query.trim().trimStart('#').isBlank() || note.tags.any { aliasOf(it).isNotBlank() && tagMatches(aliasOf(it), query) }
    val day = dayOf(note.startedAtMs)
    if (found != null && note.id in found.covered) {
        val status = WordForms.plain(statusText)
        // A tag answers like a word of the note: «рецепты» finds the note tagged «рецепт» whatever its text says.
        val tagged = tagWordsHeld(note, found.words, aliasOf)
        val holds = { i: Int -> found.scores.getOrNull(i)?.containsKey(note.id) == true || i in tagged || status.contains(words[i]) || dateMatches(day, words[i], today) }
        return if (anyWord) words.indices.any(holds) else words.indices.all(holds)
    }
    // A note the index does not know as it is: its name and text are read here, by the same rule.
    val text = WordForms.plain(note.title + "\n" + note.text)
    val search = found?.words ?: com.govorun.echo.notes.SearchWords.of(query, text)
    val held = com.govorun.echo.notes.NoteIndex.wordsOf(text).mapTo(HashSet()) { search.answers(it) } + tagWordsHeld(note, search, aliasOf)
    val status = WordForms.plain(statusText)
    val holds = { i: Int -> i in held || status.contains(words[i]) || dateMatches(day, words[i], today) }
    return if (anyWord) words.indices.any(holds) else words.indices.all(holds)
}

/** The words of the request the note's tags answer, in any of their forms. */
private fun tagWordsHeld(
    note: NoteStore.Entry,
    search: com.govorun.echo.notes.SearchWords,
    aliasOf: (String) -> String,
): Set<Int> {
    val aliases = note.tags.map(aliasOf).filter { it.isNotBlank() }
    val text = WordForms.plain((note.tags + aliases).joinToString("\n"))
    val held = com.govorun.echo.notes.NoteIndex.wordsOf(text).mapTo(HashSet()) { search.answers(it) }
    search.words.forEachIndexed { index, word ->
        if (com.govorun.echo.notes.NoteIndex.wordsOf(word).isEmpty() && aliases.any { searchForm(it).contains(searchForm(word)) }) held += index
    }
    return held
}

/** Whether a search lists the notes holding any word of [query] rather than all: a request of two words or more, in a list by relevance. */
internal fun anyWordFor(query: String?, order: String): Boolean = order != com.govorun.echo.AppSettings.SEARCH_DAYS && query != null && WordForms.request(query).size >= 2

private fun dateMatches(
    day: LocalDate,
    word: String,
    today: LocalDate,
): Boolean {
    when (word) {
        "сегодня" -> return day == today
        "вчера" -> return day == today.minusDays(1)
    }
    val dotted = word.split('.', '/', '-')
    if (dotted.size >= 2 && dotted.all { it.toIntOrNull() != null }) {
        val (a, b) = dotted.map { it.toInt() }
        val year = dotted.getOrNull(2)?.toInt()
        return a == day.dayOfMonth && b == day.monthValue && (year == null || year == day.year || year + 2000 == day.year)
    }
    word.toIntOrNull()?.let { n -> return n == day.dayOfMonth || n == day.year }
    val index = MONTHS.indexOfFirst { word.startsWith(it) }
    return word.length >= 3 && index >= 0 && index + 1 == day.monthValue
}

internal enum class NotesTab { All, Favorites, Archive }

internal enum class PeriodFilter { All, Today, LastSeven, Custom }

internal enum class AudioFilter { All, WithAudio, WithoutAudio }

internal enum class StatusFilter { All, Done, Failed }

/** How the list is ordered: by the day it was said or last written to (in days), or the longest first (one list, to find them). */
internal enum class SortBy { Created, Modified, Longest }

/** The moment a note is placed by: when it began, or when it was last written to. */
internal fun NoteStore.Entry.placedAt(sort: SortBy): Long = if (sort == SortBy.Modified) maxOf(modifiedAtMs, startedAtMs) else startedAtMs

internal data class NotesFilter(
    val status: StatusFilter = StatusFilter.All,
    val period: PeriodFilter = PeriodFilter.All,
    val tag: String? = null,
    val sort: SortBy = SortBy.Created,
    /** Notes with any tag of this colour, a place in the palette; chosen under «#». */
    val tagColor: Int? = null,
    /** Notes without any tag; viewing and importance are independent. */
    val untagged: Boolean = false,
    val unreadOnly: Boolean = false,
    val audio: AudioFilter = AudioFilter.All,
    val from: LocalDate? = null,
    val through: LocalDate? = null,
) {
    val active: Boolean get() = status != StatusFilter.All || period != PeriodFilter.All || sort != SortBy.Created || tagColor != null || untagged || unreadOnly || audio != AudioFilter.All

    /** Nothing is chosen in the tag row: no tag or colour. */
    val noTag: Boolean get() = tag == null && tagColor == null

    fun withoutTags() = copy(tag = null, tagColor = null, untagged = false)
}

/** Everything the list shows: section (all / today / archive), filter and search; newest first. */
internal fun visibleNotes(
    notes: List<NoteStore.Entry>,
    tab: NotesTab,
    filter: NotesFilter,
    query: String?,
    today: LocalDate = LocalDate.now(),
    statusText: (NoteStore.Entry) -> String = { "" },
    /** A tag's place in the palette, for a colour chosen under «#». */
    colorOf: (String) -> Int? = { null },
    found: com.govorun.echo.notes.NoteIndex.Found? = null,
    anyWord: Boolean = false,
    aliasOf: (String) -> String = { "" },
): List<NoteStore.Entry> =
    notes
        .filter { note ->
            val inTab = matchesCreatedPeriod(note.startedAtMs, filter, today)
            val inStatus =
                when (filter.status) {
                    StatusFilter.All -> true
                    StatusFilter.Done -> note.state == NoteStore.STATE_DONE
                    StatusFilter.Failed -> note.state == NoteStore.STATE_FAILED
                }
            val inTags =
                (filter.tag == null || filter.tag in note.tags) && (!filter.untagged || note.tags.isEmpty()) &&
                    (filter.tagColor == null || note.tags.any { colorOf(it) == filter.tagColor })
            (when (tab) { NotesTab.Archive -> note.archived; NotesTab.Favorites -> note.pinned; NotesTab.All -> !note.archived || !query.isNullOrBlank() || filter.period != PeriodFilter.All }) && inTab && inStatus && inTags && (!filter.unreadOnly || note.unseen == true) && matchesAudio(note, filter.audio) && (query.isNullOrBlank() || matchesQuery(note, query, today, statusText(note), found, anyWord, aliasOf))
        }.let { shown ->
            // Saved recording length; typed-only notes follow, ordered by their text length.
            if (filter.sort == SortBy.Longest) shown.sortedWith(compareByDescending<NoteStore.Entry> { it.durationMs }.thenByDescending { it.text.length }) else shown.sortedByDescending { it.placedAt(filter.sort) }
        }

/** The tags in the owner's order; those he never placed follow, by use. */
internal fun orderedTags(
    tags: List<Pair<String, Int>>,
    order: List<String>,
): List<Pair<String, Int>> = tags.sortedBy { (tag, _) -> order.indexOf(tag).let { if (it < 0) Int.MAX_VALUE else it } }

/** A tag that no note carries any more (the last one was deleted) is no longer a filter. */
internal fun NotesFilter.withoutStaleTag(
    notes: List<NoteStore.Entry>,
    made: Set<String> = emptySet(),
): NotesFilter = if (tag != null && tag !in made && notes.none { tag in it.tags }) copy(tag = null) else this

/** Tags of the notes the list would show without the tag filter, most used first; the count follows a search. Tags made with "+" show up empty. */
internal fun tagCounts(
    notes: List<NoteStore.Entry>,
    tab: NotesTab,
    filter: NotesFilter,
    query: String?,
    made: Set<String> = emptySet(),
    found: com.govorun.echo.notes.NoteIndex.Found? = null,
    statusText: (NoteStore.Entry) -> String = { "" },
    anyWord: Boolean = false,
    aliasOf: (String) -> String = { "" },
): List<Pair<String, Int>> {
    val counts = visibleNotes(notes, tab, filter.withoutTags(), query, statusText = statusText, found = found, anyWord = anyWord, aliasOf = aliasOf).flatMap { it.tags }.groupingBy { it }.eachCount().toMutableMap()
    if (query.isNullOrBlank()) made.forEach { counts.putIfAbsent(it, 0) }
    return counts.toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
}

/** Splits a note's phrases into paragraphs where the speaker paused or the text grows long. */
internal fun paragraphsOf(
    phrases: List<NoteStore.Phrase>,
    gapMs: Long = 2_000L,
    maxChars: Int = 450,
): List<List<NoteStore.Phrase>> {
    val result = mutableListOf<MutableList<NoteStore.Phrase>>()
    var chars = 0
    phrases.forEach { phrase ->
        val last = result.lastOrNull()?.lastOrNull()
        if (last == null || phrase.startMs - last.endMs >= gapMs || chars >= maxChars) {
            result += mutableListOf(phrase)
            chars = phrase.text.length
        } else {
            result.last() += phrase
            chars += phrase.text.length
        }
    }
    return result
}

/** Why the text does not appear while recording, in a few plain words; the final text still comes after the stop. */
@StringRes
internal fun liveProblemLabel(reason: String): Int =
    when (reason) {
        "not_installed" -> R.string.live_problem_not_installed
        "unauthorized", "govorun_1" -> R.string.live_problem_unauthorized
        "protocol", "no_continuous", "govorun_6" -> R.string.live_problem_old
        "govorun_3" -> R.string.live_problem_model
        "govorun_9", "govorun_2" -> R.string.live_problem_busy
        "not_ready" -> R.string.live_problem_slow
        else -> R.string.live_problem_other
    }
