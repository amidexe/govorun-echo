package com.govorun.echo.ui

import java.time.LocalDate

/** Date search always uses creation, not a later edit or the order of the list. */
internal fun matchesCreatedPeriod(atMs: Long, filter: NotesFilter, today: LocalDate): Boolean {
    if (filter.period == PeriodFilter.All) return true
    if (atMs <= 0) return false
    val day = dayOf(atMs)
    return when (filter.period) {
        PeriodFilter.All -> true
        PeriodFilter.Today -> day == today
        PeriodFilter.LastSeven -> day >= today.minusDays(6) && day <= today
        PeriodFilter.Custom -> filter.from != null && filter.through != null && day >= filter.from && day <= filter.through
    }
}

/** Uses retained playable chunks, not the origin or remembered duration of a recording. */
internal fun matchesAudio(note: com.govorun.echo.notes.NoteStore.Entry, audio: AudioFilter): Boolean = when (audio) {
    AudioFilter.All -> true
    AudioFilter.WithAudio -> note.audio.isNotEmpty()
    AudioFilter.WithoutAudio -> note.audio.isEmpty()
}

internal fun NotesFilter.clearConditions() = copy(status = StatusFilter.All, period = PeriodFilter.All, sort = SortBy.Created, tagColor = null, untagged = false, unreadOnly = false, audio = AudioFilter.All, from = null, through = null)
