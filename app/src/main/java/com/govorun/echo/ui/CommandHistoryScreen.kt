package com.govorun.echo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.Lightning
import com.govorun.echo.R
import com.govorun.echo.commands.Dispatcher
import com.govorun.echo.notes.CommandHistory
import com.govorun.echo.notes.NoteChanges
import com.product.ui.*
import java.text.DateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext

/** A chronological list of command results. */
@Composable
internal fun CommandHistoryScreen(onBack: () -> Unit, initialId: String? = null) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<CommandHistory.Entry>?>(null) }
    val list = rememberLazyListState()
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LaunchedEffect(Unit) {
        NoteChanges.events.onSubscription { emit(Unit) }.collect {
            entries = withContext(Dispatchers.IO) { CommandHistory.entries(context) }
        }
    }
    val entryIndex = entries?.indexOfFirst { it.id == initialId } ?: -1
    var positioned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialId, entryIndex) {
        if (!positioned && entryIndex >= 0) {
            list.scrollToItem(entryIndex)
            positioned = true
        }
    }
    ProductScaffold(title = stringResource(R.string.commands_history), onBack = onBack) { padding ->
        val loaded = entries ?: return@ProductScaffold
        LazyColumn(Modifier.padding(padding), state = list, contentPadding = productScreenPadding()) {
            if (loaded.isEmpty()) item {
                SettingsInfoRow(PhosphorIcons.Regular.Lightning, stringResource(R.string.commands_history_empty))
            }
            items(loaded, key = { it.id }) { entry ->
                val result = entry.result
                val reminder = when {
                    result.reminderMinutes > 0 -> stringResource(R.string.commands_reminder_before, result.reminderMinutes)
                    result.reminderMinutes == 0 -> stringResource(R.string.commands_reminder_at)
                    else -> ""
                }
                SettingsInfoRow(
                    icon = PhosphorIcons.Regular.Lightning,
                    title = result.title.ifBlank { result.name },
                    summary = listOf(format.format(entry.atMs), result.label, result.detail.ifBlank { result.reply }, reminder)
                        .filter(String::isNotBlank).joinToString(" · "),
                    trailing = {
                        if (!result.ok && result.phase == "failed" && result.kind != "command") {
                            IconButton(onClick = { Dispatcher.onNoteDoneAsync(context, entry.id, force = true) }) {
                                Icon(PhosphorIcons.Regular.ArrowClockwise, stringResource(R.string.action_retry_command))
                            }
                        }
                    },
                )
                ProductDivider()
            }
        }
    }
}
