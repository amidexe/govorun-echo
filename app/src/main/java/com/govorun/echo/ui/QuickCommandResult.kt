package com.govorun.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Alarm
import com.adamglin.phosphoricons.regular.Calendar
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.Lightning
import com.adamglin.phosphoricons.regular.Timer
import com.govorun.echo.AppEntry
import com.govorun.echo.R

@Composable
internal fun QuickCommandResult(result: CaptureResult) {
    val (icon, type) = when (result.kind) {
        "alarm" -> PhosphorIcons.Regular.Alarm to R.string.commands_preset_alarm
        "timer" -> PhosphorIcons.Regular.Timer to R.string.commands_preset_timer
        "command" -> PhosphorIcons.Regular.Lightning to R.string.widget_command_label
        "yandex" -> PhosphorIcons.Regular.Lightning to R.string.commands_yandex_title
        else -> PhosphorIcons.Regular.Calendar to R.string.commands_preset_calendar
    }
    val context = LocalContext.current
    val hasPreview = result.ok && (result.title.isNotBlank() || result.detail.isNotBlank())
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(36.dp), shadowElevation = 12.dp) {
        Column(Modifier.background(captureSurfaceBrush()).padding(horizontal = 8.dp)) {
            Text(result.label, Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                style = MaterialTheme.typography.titleLarge)
            if (hasPreview) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(40.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.heightIn(min = 146.dp).padding(horizontal = 36.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(icon, null, Modifier.size(18.dp))
                            Text(stringResource(type), style = MaterialTheme.typography.titleLarge)
                        }
                        Column {
                            if (result.title.isNotBlank()) Text(result.title, style = MaterialTheme.typography.titleLarge)
                            if (result.detail.isNotBlank()) Text(result.detail, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (result.detail.isNotBlank()) {
                Text(result.detail, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result.commandId.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(if (hasPreview) 66.dp else 48.dp), contentAlignment = Alignment.CenterStart) {
                    IconButton(onClick = { context.startActivity(AppEntry.command(context, result.commandId)) }) {
                        Icon(PhosphorIcons.Regular.FileText, stringResource(R.string.commands_history))
                    }
                }
            }
        }
    }
}
