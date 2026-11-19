package com.govorun.echo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ExportNotePicker(notes: List<NoteStore.Entry>, initial: Set<String>, done: (Set<String>) -> Unit, dismiss: () -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val found = remember(notes, query) {
        notes.filter { query.isBlank() || it.title.contains(query, true) || it.text.contains(query, true) || it.tags.any { tag -> tag.contains(query, true) } }
    }
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    ShelfFrame(onDismiss = dismiss, header = {
        IconButton(onClick = dismiss) { Icon(PhosphorIcons.Regular.X, stringResource(R.string.action_cancel)) }
        Text(stringResource(R.string.folder_select_notes), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(enabled = selected.isNotEmpty(), onClick = { done(selected) }) { Text(stringResource(R.string.action_done)) }
    }, query = query, onQuery = { query = it }, searchHint = stringResource(R.string.notes_search_hint), onEnter = { it() }) {
        if (found.isNotEmpty()) {
            val all = found.all { it.id in selected }
            TextButton(onClick = { val ids = found.map { it.id }.toSet(); selected = if (all) selected - ids else selected + ids }) {
                Text(stringResource(if (all) R.string.folder_clear_selection else R.string.folder_select_all))
            }
        } else Text(stringResource(R.string.folder_no_matches))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(found, key = { it.id }) { note ->
                val date = dates.format(Date(note.startedAtMs))
                val label = note.title.ifBlank { note.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }.take(100)
                ShelfLine(if (label.isBlank()) date else "$label · $date", MaterialTheme.colorScheme.onSurfaceVariant, query, false,
                    note.id in selected, { selected = if (note.id in selected) selected - note.id else selected + note.id }, Role.Checkbox) {}
            }
        }
    }
}
