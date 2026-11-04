package com.govorun.echo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.govorun.echo.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Always the creation day, regardless of sorting, later edits or import. Old years must stay distinguishable. */
internal fun noteDateLabel(atMs: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (atMs <= 0) return "—"
    val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    return day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM uuuu", Locale("ru")))
}

internal fun noteMomentLabel(atMs: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String =
    if (atMs > 0 && Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate() == today) noteTimeLabel(atMs, zone) else noteDateLabel(atMs, today, zone)

@Composable
internal fun NoteCreatedDate(atMs: Long, unseen: Boolean = false) {
    val date = if (atMs > 0) Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()) else null
    val description = if (date == null) stringResource(R.string.note_date_unknown) else stringResource(R.string.note_created_on, date.format(DateTimeFormatter.ofPattern("d MMMM uuuu, HH:mm", Locale("ru"))))
    Text(noteMomentLabel(atMs), style = noteSupportingStyle().echoWeight(if (unseen) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal), color = if (unseen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.semantics { contentDescription = description })
}

internal fun noteTimeLabel(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (atMs <= 0) "—" else Instant.ofEpochMilli(atMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale("ru")))

@Composable
internal fun NoteCreatedTime(atMs: Long) {
    val time = noteTimeLabel(atMs)
    val description = stringResource(R.string.note_created_time, time)
    Text(time, style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.semantics { contentDescription = description })
}

/** Compact Russian list caption: at most four digits; seconds stop mattering once a recording is an hour long. */
internal fun noteDurationLabel(ms: Long): String? {
    if (ms <= 0) return null
    val seconds = ms / 1_000
    val hours = seconds / 3_600
    return when {
        hours == 0L -> String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        hours < 100 -> String.format(Locale.ROOT, "%d ч %02d мин", hours, seconds / 60 % 60)
        hours < 10_000 -> "$hours ч"
        hours / 24 < 10_000 -> "${hours / 24} дн."
        else -> "Долгая запись"
    }
}

@Composable
internal fun NoteListDuration(ms: Long) {
    val label = noteDurationLabel(ms) ?: return
    val description = stringResource(R.string.note_duration_label, com.govorun.echo.formatDuration(ms))
    Text(label, style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.semantics { contentDescription = description })
}
