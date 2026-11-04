package com.govorun.echo.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.web.ComputerSelection
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.web.ComputerSession
import com.govorun.echo.web.WebExport

/** Where the page lives; the same address the QR code's link opens. */
internal const val COMPUTER_ADDRESS = "echo.govorun.club"

/**
 * «На компьютере»: what it is and how to open it, in three short steps under a small picture, with the address to send oneself;
 * while a computer shows the notes, that window first, with the time it opened and a way to close it. One window at a time: opening
 * on another computer makes the first tab forget.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComputerSheet(
    onScan: (ComputerSelection) -> Unit,
    onSendNow: () -> Unit,
    onCloseWindow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val info by ComputerSession.info.collectAsState()
    val progress by ComputerSession.progress.collectAsState()
    val network by com.govorun.echo.NetworkPolicy.state.collectAsState()
    val active = info.link
    val transfer = progress?.takeIf { it.id == active?.id }
    val preparing = active != null && active.deliveredAt == 0L && active.dirty && info.failures == 0 && !info.held
    var resumed by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    var connection by remember(active?.id) { mutableStateOf<WebExport.Result?>(null) }
    LaunchedEffect(active?.id, network.reason, transfer != null, preparing, resumed) {
        connection = null
        if (active != null && network.reason == null && transfer == null && !preparing && resumed) {
            while (true) {
                connection = ComputerSession.check(active.id)
                kotlinx.coroutines.delay(3_000)
            }
        }
    }
    var details by remember(active?.id) { mutableStateOf(false) }
    var selection by remember { mutableStateOf(ComputerSelection()) }
    var picking by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(emptyList<NoteStore.Entry>()) }
    LaunchedEffect(Unit) {
        ComputerSession.retryClosing()
        entries = withContext(Dispatchers.IO) { NoteStore.list(context) }
    }
    val tags = (entries.flatMap { it.tags } + com.govorun.echo.AppSettings.tags(context)).distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    if (picking) ComputerTagPicker(tags, selection, { selection = it; picking = false }, { picking = false })
    if (details && active != null) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.computer_session_info)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.computer_started, computerDate(active.openedAt)), style = MaterialTheme.typography.bodyMedium)
                if (active.deliveredAt > 0) Text(stringResource(R.string.computer_last_sent, computerDate(active.deliveredAt)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.computer_receive_rule), style = MaterialTheme.typography.bodyMedium)
                if (info.closing.isNotEmpty()) Text(stringResource(R.string.computer_closing_previous), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.computer_close_rule), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.action_done)) } },
        dismissButton = { TextButton(enabled = network.reason == null, onClick = { details = false; onScan(active.selection) }) { Text(stringResource(R.string.computer_scan_other)) } },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ComputerPicture(Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
            if (active != null) {
                val title = when {
                    network.reason != null -> R.string.computer_entry
                    transfer != null || preparing -> if (transfer == null || transfer.total == 0) R.string.computer_preparing else R.string.computer_transferring
                    info.held -> R.string.computer_entry
                    connection == null -> R.string.computer_checking
                    connection != WebExport.Result.Sent -> R.string.computer_not_responding
                    info.failures > 0 -> R.string.computer_delivery_paused
                    else -> R.string.computer_mode_enabled
                }
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                // Keep the initial sheet's anchors stable even when the first request fails immediately.
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (transfer != null || preparing) {
                        if (transfer != null && transfer.total > 0 && transfer.sent > 0) com.product.ui.ThinProgress(transfer.sent.toFloat() / transfer.total)
                        else androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(listOf(active.pairing.browser, active.pairing.os).filter(String::isNotBlank).joinToString(" · ").ifBlank { stringResource(R.string.computer_unknown_browser) }, style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { details = true }) { Icon(PhosphorIcons.Regular.Info, contentDescription = stringResource(R.string.computer_session_info), modifier = Modifier.size(com.product.ui.ProductTokens.Size.ActiveIcon)) }
                }
                Text(stringResource(R.string.computer_scope, active.selection.tags?.sorted()?.joinToString(", ") ?: stringResource(R.string.computer_all)), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (info.held && network.reason == null) Text(stringResource(R.string.computer_delivery_held), style = MaterialTheme.typography.bodyMedium)
                if (network.reason != null) NetworkNotice(network.reason!!)
                else Button(onClick = onSendNow, enabled = transfer == null && !preparing, modifier = Modifier.fillMaxWidth().height(48.dp), colors = com.product.ui.productButtonColors()) { Text(stringResource(R.string.computer_send_updates)) }
                TextButton(onClick = onCloseWindow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.computer_close)) }
                return@Column
            }
            Text(
                stringResource(R.string.computer_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (info.closing.isNotEmpty()) Text(stringResource(R.string.computer_close_pending), style = MaterialTheme.typography.bodyMedium)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Step(1) {
                    Text(stringResource(R.string.computer_step_open), style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(COMPUTER_ADDRESS, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://$COMPUTER_ADDRESS")
                            context.startActivity(Intent.createChooser(send, null))
                        }) {
                            Icon(PhosphorIcons.Regular.ShareNetwork, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.computer_send_link), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Step(2) { Text(stringResource(R.string.computer_step_scan), style = MaterialTheme.typography.bodyLarge) }
                Step(3) { Text(stringResource(R.string.computer_step_read), style = MaterialTheme.typography.bodyLarge) }
            }
            Text(
                stringResource(R.string.computer_sheet_private),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.computer_scope, selection.tags?.sorted()?.joinToString(", ") ?: stringResource(R.string.computer_all)), modifier = Modifier.weight(1f))
                Text("›")
            }
            if (network.reason != null) NetworkNotice(network.reason!!)
            else Button(onClick = { onScan(selection) }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = com.product.ui.productButtonColors()) {
                Text(stringResource(R.string.computer_scan))
            }
        }
    }
}

@Composable
private fun Step(
    number: Int,
    content: @Composable () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.labelLarge) }
        }
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

/** A computer's screen with the code on it and the phone held up to it: the whole feature in one look. */
@Composable
private fun ComputerPicture(modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.size(width = 200.dp, height = 112.dp)) {
        val u = size.height / 112f
        val stroke = Stroke(width = 2.5f * u)
        // The screen and its stand.
        drawRoundRect(paper, Offset(8 * u, 6 * u), Size(132 * u, 84 * u), CornerRadius(8 * u))
        drawRoundRect(line, Offset(8 * u, 6 * u), Size(132 * u, 84 * u), CornerRadius(8 * u), style = stroke)
        drawLine(line, Offset(74 * u, 90 * u), Offset(74 * u, 102 * u), strokeWidth = 2.5f * u)
        drawLine(line, Offset(54 * u, 104 * u), Offset(94 * u, 104 * u), strokeWidth = 2.5f * u)
        // The code: three corner squares and a few modules between them.
        val left = 52 * u
        val top = 22 * u
        val cell = 6.5f * u
        listOf(0 to 0, 5 to 0, 0 to 5).forEach { (x, y) ->
            drawRect(accent, Offset(left + x * cell, top + y * cell), Size(2 * cell, 2 * cell), style = Stroke(width = cell * 0.55f))
        }
        listOf(3 to 1, 3 to 3, 5 to 3, 4 to 5, 6 to 6, 3 to 6, 6 to 4).forEach { (x, y) ->
            drawRect(accent, Offset(left + x * cell, top + y * cell), Size(cell, cell))
        }
        // The phone held up to it.
        drawRoundRect(paper, Offset(138 * u, 30 * u), Size(46 * u, 80 * u), CornerRadius(9 * u))
        drawRoundRect(line, Offset(138 * u, 30 * u), Size(46 * u, 80 * u), CornerRadius(9 * u), style = stroke)
        drawRoundRect(accent, Offset(149 * u, 52 * u), Size(24 * u, 24 * u), CornerRadius(4 * u), style = Stroke(width = 2f * u))
    }
}

private fun computerDate(at: Long): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(at))

/** The existing tag shelf, restricted to choosing the computer's scope; no tag edits or note counts. */
@Composable
private fun ComputerTagPicker(
    tags: List<String>,
    initial: ComputerSelection,
    onDone: (ComputerSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(initial.tags) }
    var query by remember { mutableStateOf("") }
    val styles = remember {
        val aliases = AppSettings.tagAliases(context)
        val saved = AppSettings.tagStyles(context)
        (saved.keys + aliases.keys).associateWith { tag -> TagStyle(saved[tag]?.first, saved[tag]?.second == true, aliases[tag].orEmpty()) }
    }
    val pinned = remember(tags) {
        orderedTags(tags.map { it to 0 }, AppSettings.tagOrder(context)).map { it.first }.filter { styles[it]?.pinned == true }
    }
    val rest = remember(tags) { byName(tags.filter { it !in pinned }) }
    val found = searchTags(pinned + rest, query) { styles[it]?.shortName.orEmpty() }
    ShelfFrame(
        onDismiss = onDismiss,
        header = {
            IconButton(onClick = onDismiss) { Icon(PhosphorIcons.Regular.X, contentDescription = stringResource(R.string.action_cancel), modifier = Modifier.size(com.product.ui.ProductTokens.Size.ActiveIcon)) }
            Text(stringResource(R.string.computer_pick_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onDone(ComputerSelection(selected)) }, enabled = selected == null || selected!!.isNotEmpty()) { Text(stringResource(R.string.action_done)) }
        },
        query = query,
        searchHint = stringResource(R.string.computer_tag_search),
        onQuery = { query = it },
        onEnter = { hideKeyboard -> hideKeyboard() },
    ) {
        ShelfLine(stringResource(R.string.computer_all), MaterialTheme.colorScheme.onSurfaceVariant, "", lit = false, checked = selected == null, role = Role.Checkbox, onClick = { selected = null }) {}
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (query.isBlank()) {
                if (pinned.isNotEmpty()) item { TagSection(stringResource(R.string.tags_pinned)) }
                items(pinned, key = { it }) { tag -> ComputerTagLine(tag, styles[tag], query, selected) { selected = it } }
                if (rest.isNotEmpty()) item { TagSection(stringResource(if (pinned.isEmpty()) R.string.tags_title else R.string.tags_others)) }
                items(rest, key = { it }) { tag -> ComputerTagLine(tag, styles[tag], query, selected) { selected = it } }
            } else {
                items(found, key = { it }) { tag -> ComputerTagLine(tag, styles[tag], query, selected) { selected = it } }
            }
            if (found.isEmpty()) item { Text(stringResource(R.string.computer_tags_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun ComputerTagLine(tag: String, style: TagStyle?, query: String, selected: Set<String>?, onSelect: (Set<String>) -> Unit) {
    ShelfLine(tag, tagColor(tag, style), query, lit = false, checked = selected?.contains(tag) == true, role = Role.Checkbox, onClick = {
        val next = selected.orEmpty().toMutableSet()
        if (!next.add(tag)) next.remove(tag)
        onSelect(next)
    }) {}
}
