package com.govorun.echo.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.govorun.echo.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Standard Material range picker; the surrounding app's Russian copy, typography and dynamic colors stay consistent. */
@Composable
internal fun PeriodCalendarSheet(filter: NotesFilter, onDismiss: () -> Unit, onSelected: (LocalDate, LocalDate) -> Unit) {
    val context = LocalContext.current
    val current = LocalConfiguration.current
    val configuration = remember(current) { Configuration(current).apply { setLocale(Locale.forLanguageTag("ru")) } }
    val localized = remember(context, configuration) { context.createConfigurationContext(configuration) }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, LocalResources provides localized.resources) {
        StandardRangePicker(filter, onDismiss, onSelected)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StandardRangePicker(filter: NotesFilter, onDismiss: () -> Unit, onSelected: (LocalDate, LocalDate) -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = filter.from?.let(::pickerUtcMillis),
        initialSelectedEndDateMillis = filter.through?.let(::pickerUtcMillis),
    )
    val localizedContext = LocalContext.current
    val localizedConfiguration = LocalConfiguration.current
    val localizedResources = LocalResources.current
    val height = (LocalConfiguration.current.screenHeightDp.dp - 170.dp).coerceIn(260.dp, 560.dp)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 12.dp),
        confirmButton = {
            TextButton(enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null, onClick = {
                val from = state.selectedStartDateMillis?.let(::pickerDay)
                val through = state.selectedEndDateMillis?.let(::pickerDay)
                if (from != null && through != null) onSelected(from, through)
            }) { Text(stringResource(R.string.filter_date_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        // Dialog installs its own Android composition locals; reapply localized resources inside it.
        CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides localizedConfiguration, LocalResources provides localizedResources) {
            val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", Locale.forLanguageTag("ru"))
            val start = state.selectedStartDateMillis?.let { pickerDay(it).format(dateFormat) }
            val end = state.selectedEndDateMillis?.let { pickerDay(it).format(dateFormat) }
            val headline = if (start == null) stringResource(R.string.filter_dates) else if (end == null) "$start — …" else "$start — $end"
            DateRangePicker(
                state = state, showModeToggle = true, modifier = Modifier.fillMaxWidth().height(height),
                title = { Text(stringResource(R.string.filter_group_period), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
                headline = { Text(headline, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp)) },
            )
        }
    }
}

/** Material date pickers use UTC date keys; never interpret them in the phone's local timezone. */
internal fun pickerUtcMillis(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun pickerDay(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
