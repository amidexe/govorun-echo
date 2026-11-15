package com.govorun.echo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.setValue
import com.govorun.echo.notes.NoteStore
import java.time.LocalDate

/** The library view is restored as a unit with its list position. A new task uses the last tag. */
internal class NotesUiState(initialTag: String? = null) {
    var notes by mutableStateOf(emptyList<NoteStore.Entry>())
    var changes by mutableIntStateOf(0)
    var selected by mutableStateOf(emptySet<String>())
    var selectMode by mutableStateOf(false)
    var archiveDeleteConfirmed by mutableStateOf(false)
    var notesActionsRef by mutableStateOf<NotesActions?>(null)
    var tab by mutableStateOf(NotesTab.All)
    var filter by mutableStateOf(NotesFilter(tag = initialTag))
    var filtersOpen by mutableStateOf(false)
    var query by mutableStateOf<String?>(null)
    var searchFocusRequested = false

    companion object {
        val Saver = mapSaver(
            save = { ui: NotesUiState ->
                val filter = ui.filter
                mapOf(
                    "tab" to ui.tab.name, "query" to ui.query, "tag" to filter.tag,
                    "status" to filter.status.name, "period" to filter.period.name,
                    "sort" to filter.sort.name, "tagColor" to filter.tagColor,
                    "untagged" to filter.untagged, "unreadOnly" to filter.unreadOnly,
                    "audio" to filter.audio.name, "from" to filter.from?.toEpochDay(),
                    "through" to filter.through?.toEpochDay(),
                )
            },
            restore = { saved ->
                NotesUiState().apply {
                    tab = NotesTab.valueOf(saved.getValue("tab") as String)
                    query = saved["query"] as String?
                    filter = NotesFilter(
                        status = StatusFilter.valueOf(saved.getValue("status") as String),
                        period = PeriodFilter.valueOf(saved.getValue("period") as String),
                        tag = saved["tag"] as String?,
                        sort = SortBy.valueOf(saved.getValue("sort") as String),
                        tagColor = saved["tagColor"] as Int?,
                        untagged = saved.getValue("untagged") as Boolean,
                        unreadOnly = saved.getValue("unreadOnly") as Boolean,
                        audio = AudioFilter.valueOf(saved.getValue("audio") as String),
                        from = (saved["from"] as Long?)?.let(LocalDate::ofEpochDay),
                        through = (saved["through"] as Long?)?.let(LocalDate::ofEpochDay),
                    )
                }
            },
        )
    }
}
