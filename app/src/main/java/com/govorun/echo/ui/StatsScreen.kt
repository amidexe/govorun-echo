package com.govorun.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.govorun.echo.R
import com.govorun.echo.formatDuration
import com.govorun.echo.notes.ActivityDiary
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Quiet numbers with explicit scopes: what happened in Echo, and what is kept in the library. */
@Composable
internal fun StatsScreen(notes: List<NoteStore.Entry>, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var diary by remember { mutableStateOf<Map<LocalDate, ActivityDiary.Day>?>(null) }
    var askReset by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    LaunchedEffect(notes) {
        diary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ActivityDiary.update(context, notes)
            ActivityDiary.days(context)
        }
    }
    val stats = remember(notes, diary) { EchoStats.of(notes, diary.orEmpty()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(PhosphorIcons.Regular.ArrowLeft, stringResource(R.string.action_back), Modifier.size(ProductTokens.Size.TopBarNavigationIcon)) }
                    Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                }
                ActivityCard(stats, diary != null) { info = true }
                Column(Modifier.padding(top = 20.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(ProductTokens.Radius.Card)).padding(16.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { library = !library }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.stats_library_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.stats_library_short), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (library) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown, contentDescription = stringResource(if (library) R.string.stats_hide_library else R.string.stats_show_library), modifier = Modifier.size(20.dp))
                    }
                    if (library) {
                        StatRow(stringResource(R.string.stats_notes_label), grouped(stats.notes.toLong()))
                        StatRow(stringResource(R.string.stats_words_label), grouped(stats.words.toLong()))
                        StatRow(stringResource(R.string.stats_library_audio), formatDuration(stats.libraryMs))
                        StatRow(stringResource(R.string.stats_longest_label), formatDuration(stats.longestMs))
                    }
                }
                if (library) TextButton(onClick = { askReset = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.stats_reset), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (info) AlertDialog(
                onDismissRequest = { info = false }, title = { Text(stringResource(R.string.stats_activity_title)) },
                text = { Text(stringResource(R.string.stats_activity_scope)) },
                confirmButton = { TextButton(onClick = { info = false }) { Text(stringResource(R.string.stats_info_close)) } },
            )
            if (askReset) AlertDialog(
                onDismissRequest = { askReset = false },
                title = { Text(stringResource(R.string.stats_reset_title)) },
                text = { Text(stringResource(R.string.stats_reset_text)) },
                confirmButton = { TextButton(onClick = {
                    askReset = false
                    ActivityDiary.reset(context, notes)
                    diary = ActivityDiary.days(context)
                }) { Text(stringResource(R.string.stats_reset_confirm)) } },
                dismissButton = { TextButton(onClick = { askReset = false }) { Text(stringResource(R.string.fair_cancel)) } },
            )
        }
    }
}

/** One volume measure for short thoughts and long talks; no inference of actual voiced time. */
internal fun recordedTimeLabel(ms: Long): String {
    if (ms <= 0) return "0 мин"
    if (ms < 60_000) return "${(ms / 1_000).coerceAtLeast(1)} с"
    val minutes = ms / 60_000 + if (ms % 60_000 >= 30_000) 1 else 0
    return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
}

@Composable
private fun ActivityCard(stats: EchoStats, loaded: Boolean, onInfo: () -> Unit) {
    fun shown(ms: Long) = if (loaded) recordedTimeLabel(ms) else "—"
    Column(Modifier.padding(top = 20.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(ProductTokens.Radius.Card)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stats_activity_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onInfo, modifier = Modifier.size(48.dp)) { Icon(PhosphorIcons.Regular.Info, contentDescription = stringResource(R.string.stats_activity_info), modifier = Modifier.size(20.dp)) }
        }
        Text(stringResource(R.string.stats_last_week), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val weekMs = stats.week.sumOf { it.second }
        Text(shown(weekMs), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp, lineHeight = 44.sp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).clearAndSetSemantics { contentDescription = if (loaded) formatDuration(weekMs) else "—" })
        WeekChart(stats)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf(R.string.stats_today to (stats.week.lastOrNull()?.second ?: 0L), R.string.stats_all_time to stats.spokenMs).forEach { (label, ms) ->
                Column(Modifier.weight(1f)) {
                    Text(stringResource(label), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(shown(ms), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    }
}

/** Slim strokes with no full-height tracks; the day and exact duration remain available to TalkBack. */
@Composable
private fun WeekChart(stats: EchoStats) {
    val most = stats.week.maxOf { it.second }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
        stats.week.forEachIndexed { i, (day, ms) ->
            val label = day.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, Locale("ru")).take(2).replaceFirstChar { it.uppercase() }
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "$day, ${formatDuration(ms)}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.height(96.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(12.dp).height(if (ms > 0) (96f * ms / most).coerceAtLeast(3f).dp else 2.dp).background(
                        if (ms == 0L) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary.copy(alpha = if (i == 6) 1f else 0.55f), RoundedCornerShape(6.dp),
                    ))
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

private fun grouped(value: Long): String = java.text.NumberFormat.getIntegerInstance(Locale("ru")).format(value)
