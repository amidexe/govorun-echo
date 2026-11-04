package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.adamglin.phosphoricons.regular.Trash
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens
import com.govorun.echo.formatDuration
import com.govorun.echo.notes.NoteTrash
import com.govorun.echo.record.RecordingService

/** The trash: what was deleted in the last 30 days, with a way back for every note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashScreen(
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val items = remember(version) { NoteTrash.list(NoteTrash.root(context)) }
    var clearing by remember { mutableStateOf(false) }
    val changed = {
        version++
        onChanged()
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                navigationIcon = { IconButton(onClick = onBack) { Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = stringResource(R.string.action_back), modifier = Modifier.size(ProductTokens.Size.TopBarNavigationIcon)) } },
                title = { Text(stringResource(R.string.screen_trash)) },
                actions = { if (items.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text(stringResource(R.string.trash_clear)) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (items.isEmpty()) {
                Text(stringResource(R.string.trash_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(stringResource(R.string.trash_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
                    items(items, key = { it.id }) { note ->
                        Surface(shape = RoundedCornerShape(ProductTokens.Radius.Card), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                                Text("${dayLabel(context, dayOf(note.startedAtMs))}, ${formatTime(note.startedAtMs)}" + if (note.durationMs > 0) " · ${formatDuration(note.durationMs)}" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(note.text.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        NoteTrash.purge(NoteTrash.root(context), listOf(note.id))
                                        changed()
                                    }) { Text(stringResource(R.string.trash_forever), color = MaterialTheme.colorScheme.error) }
                                    TextButton(onClick = {
                                        NoteTrash.restore(NoteStore.root(context), NoteTrash.root(context), listOf(note.id))
                                        // A recording cancelled with «Отмена» went there unrecognized: brought back, it is recognized now.
                                        if (note.unrecognized) runCatching { context.startForegroundService(RecordingService.transcribeIntent(context, note.id)) }
                                        changed()
                                    }) {
                                        Icon(PhosphorIcons.Regular.ArrowUUpLeft, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text(stringResource(R.string.trash_restore), modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.trash_clear_title)) },
            text = { Text(stringResource(R.string.trash_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    NoteTrash.purge(NoteTrash.root(context))
                    clearing = false
                    changed()
                }) { Text(stringResource(R.string.trash_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
