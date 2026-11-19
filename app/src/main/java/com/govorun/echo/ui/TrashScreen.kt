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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens
import com.govorun.echo.formatDuration
import com.govorun.echo.notes.NoteTrash
import com.govorun.echo.record.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The trash: what was deleted in the last 30 days, with a way back for every note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashScreen(
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<NoteTrash.TrashedNote>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var clearing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deletionResult by remember { mutableStateOf(NoteTrash.PurgeResult.COMPLETE) }
    var retryIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var retryFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val (result, remaining) = withContext(Dispatchers.IO) {
            val trash = NoteTrash.root(context)
            NoteTrash.finishInterruptedDeletions(NoteStore.root(context), trash) to NoteTrash.list(trash)
        }
        deletionResult = result
        items = remaining
        loading = false
    }
    fun purge(ids: List<String>? = null, retry: Boolean = false) {
        if (deleting) return
        deleting = true
        scope.launch {
            val (result, remaining, requested) = withContext(Dispatchers.IO) {
                synchronized(NoteStore) {
                    val trash = NoteTrash.root(context)
                    val cleanup = NoteTrash.finishInterruptedDeletions(NoteStore.root(context), trash)
                    val targets = ids ?: NoteTrash.list(trash)?.map { it.id }
                    val deleted = targets?.let { NoteTrash.purge(trash, it) } ?: NoteTrash.PurgeResult.INCOMPLETE
                    Triple(cleanup.combine(deleted), NoteTrash.list(trash), targets.orEmpty())
                }
            }
            // A retry only applies to the failed request, even if more notes have since arrived.
            val present = remaining?.mapTo(HashSet()) { it.id }
            retryIds = if (present == null) requested else requested.filter { it in present }
            deletionResult = result
            items = remaining
            retryFailed = retry && (result != NoteTrash.PurgeResult.COMPLETE || remaining == null)
            deleting = false
            onChanged()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                navigationIcon = {
                    com.product.ui.ProductNavigationButton(
                        PhosphorIcons.Regular.ArrowLeft, stringResource(R.string.action_back), onBack,
                    )
                },
                title = { Text(stringResource(R.string.screen_trash)) },
                actions = {
                    if (!loading && (deletionResult != NoteTrash.PurgeResult.COMPLETE || items == null)) {
                        TextButton(enabled = !deleting, onClick = { purge(retryIds, retry = true) }) { Text(stringResource(R.string.trash_retry)) }
                    } else if (!items.isNullOrEmpty()) {
                        TextButton(enabled = !deleting, onClick = { clearing = true }) { Text(stringResource(R.string.trash_clear)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(24.dp))
            } else if (items?.isEmpty() == true && deletionResult == NoteTrash.PurgeResult.COMPLETE) {
                Text(stringResource(R.string.trash_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        val message = when {
                            deletionResult == NoteTrash.PurgeResult.ACCESS_DENIED -> R.string.trash_delete_denied
                            deletionResult == NoteTrash.PurgeResult.INCOMPLETE || items == null -> R.string.trash_delete_failed
                            else -> R.string.trash_hint
                        }
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                if (retryFailed) stringResource(R.string.trash_support) else stringResource(message),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (message != R.string.trash_hint) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                    items(items.orEmpty(), key = { it.id }) { note ->
                        Surface(shape = RoundedCornerShape(ProductTokens.Radius.Card), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                                Text(
                                    if (!note.readable) stringResource(R.string.trash_unreadable)
                                    else "${dayLabel(context, dayOf(note.startedAtMs))}, ${formatTime(note.startedAtMs)}" + if (note.durationMs > 0) " · ${formatDuration(note.durationMs)}" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(note.text.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        enabled = !deleting,
                                        onClick = { purge(listOf(note.id)) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) {
                                        Text(stringResource(R.string.trash_forever))
                                    }
                                    if (note.readable) {
                                        TextButton(enabled = !deleting, onClick = {
                                            deleting = true
                                            scope.launch {
                                                val (restored, remaining) = withContext(Dispatchers.IO) {
                                                    val trash = NoteTrash.root(context)
                                                    NoteTrash.restore(NoteStore.root(context), trash, listOf(note.id)) to NoteTrash.list(trash)
                                                }
                                                items = remaining
                                                deleting = false
                                                if (restored.isEmpty()) {
                                                    Toast.makeText(context, R.string.trash_restore_failed, Toast.LENGTH_LONG).show()
                                                } else if (note.unrecognized) {
                                                    runCatching { context.startForegroundService(RecordingService.transcribeIntent(context, note.id)) }
                                                }
                                                onChanged()
                                            }
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
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.trash_clear_title)) },
            text = { Text(stringResource(R.string.trash_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    clearing = false
                    purge()
                }) { Text(stringResource(R.string.trash_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
