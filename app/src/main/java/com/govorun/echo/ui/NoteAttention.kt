package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore

/** The list gives space only to work still going on or a problem, not the origin of a completed note. */
@Composable
internal fun NoteAttention(note: NoteStore.Entry, failed: Boolean) {
    if (note.state != NoteStore.STATE_DONE) {
        val paused by com.govorun.echo.record.RecordingBus.pausedAt.collectAsState()
        val gate by com.govorun.echo.recognition.RecognitionStatus.pausedReason.collectAsState()
        val status = if (paused != null && note.state == NoteStore.STATE_RECORDING) R.string.status_paused else statusLabel(note).also { gate }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (note.state == NoteStore.STATE_RECOGNIZING && status in setOf(R.string.status_recognizing, R.string.status_preparing)) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
            Text(stringResource(status), style = noteSupportingStyle(), color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    note.command?.takeIf { !it.ok }?.let {
        Text("${it.name}: ${it.label}", style = noteSupportingStyle(), color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
