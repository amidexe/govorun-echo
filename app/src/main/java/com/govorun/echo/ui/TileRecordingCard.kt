package com.govorun.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.govorun.echo.R
import com.govorun.echo.formatDuration
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingState
import kotlinx.coroutines.delay

/** Same theme, voice background and card shape as VolumeKeyEntry. No stored note content is read here. */
@Composable
internal fun TileRecordingCard(
    state: RecordingState,
    stopping: Boolean,
    failed: Boolean,
    unlocked: Boolean,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpen: () -> Unit,
) {
    val recording = state as? RecordingState.Recording
    val saved = state is RecordingState.Saved || state is RecordingState.Discarded
    val error = failed || state is RecordingState.Failed
    val paused by RecordingBus.pausedAt.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = visibleLifecycle()
    LaunchedEffect(recording?.noteId) {
        if (recording != null) lifecycle.whileVisible {
            while (true) { now = System.currentTimeMillis(); delay(500) }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), shadowElevation = 8.dp) {
            Box {
                VoiceFlow(Modifier.matchParentSize())
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(when {
                            error -> R.string.recording_entry_failed
                            stopping -> R.string.tile_saving
                            state is RecordingState.Discarded -> R.string.recording_entry_discarded
                            saved -> R.string.volume_key_saved
                            recording != null && paused != null -> R.string.recording_paused
                            recording != null -> R.string.recording_title
                            else -> R.string.tile_starting
                        }), style = MaterialTheme.typography.titleMedium)
                    if (recording != null) Text(formatDuration(RecordingBus.recordedMs(recording.startedAtMs, now)), style = MaterialTheme.typography.headlineMedium)
                    if (error) Text(stringResource(R.string.tile_start_failed), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (unlocked) TextButton(onClick = onOpen) { Text(stringResource(R.string.recording_entry_open)) }
                        if (error) TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                        if (!error && !saved) Button(
                            onClick = onStop,
                            enabled = recording != null && !stopping,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        ) { Text(stringResource(R.string.recording_stop)) }
                    }
                }
            }
        }
    }
}
