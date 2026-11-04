package com.govorun.echo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.govorun.echo.notes.NoteStore

/** UI state for the root composition; only the initial named tag comes from preferences. */
internal class NotesUiState(initialTag: String? = null) {
    var screen by mutableStateOf(Screen.Notes)
    var notes by mutableStateOf(emptyList<NoteStore.Entry>())
    var changes by mutableIntStateOf(0)
    var selected by mutableStateOf(emptySet<String>())
    var selectMode by mutableStateOf(false)
    var openId by mutableStateOf<String?>(null)
    var archiveDeleteConfirmed by mutableStateOf(false)
    var notesActionsRef by mutableStateOf<NotesActions?>(null)
    var tab by mutableStateOf(NotesTab.All)
    var filter by mutableStateOf(NotesFilter(tag = initialTag))
    var filtersOpen by mutableStateOf(false)
    var query by mutableStateOf<String?>(null)
}
