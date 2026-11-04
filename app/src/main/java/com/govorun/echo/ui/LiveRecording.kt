package com.govorun.echo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Stop
import com.govorun.echo.R
import com.govorun.echo.notes.AudioPeaks
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.govorun.echo.formatDuration

/**
 * The same tape as the player, now writing: bars of the same width, gap, height and loudness scale, with the same playhead,
 * in red. A new recording starts empty and grows to the right; a continued note keeps what it already has on the left as the
 * quiet bars of the player and the new part grows after it, on the same time scale. When the strip is full the whole tape
 * is squeezed, as the player shows the whole note.
 */
@Composable
internal fun LiveRecording(
    note: NoteStore.Entry,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val live by RecordingBus.state.collectAsState()
    val sessionStart = (live as? RecordingState.Recording)?.startedAtMs ?: note.startedAtMs
    var existing by remember(note.id) { mutableStateOf<ByteArray?>(null) }
    // Only the bars already saved: a note without them shows no past while it is recorded, and is measured once the recording is done.
    LaunchedEffect(note.id) { if (note.audio.isNotEmpty()) existing = withContext(Dispatchers.IO) { AudioPeaks.saved(NoteStore.noteDir(context, note.id)) } }
    val history = remember(note.id, sessionStart) { mutableStateListOf<Float>() }
    val lifecycle = visibleLifecycle()
    LaunchedEffect(sessionStart) {
        lifecycle.whileVisible {
            while (true) {
                history.add(RecordingBus.level.value)
                if (history.size > MAX_LIVE) history.removeAt(0)
                delay(AudioPeaks.STEP_MS.toLong())
            }
        }
    }
    val newColor = MaterialTheme.colorScheme.error
    val oldColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val bar = 3.dp.toPx()
        val step = 5.dp.toPx()
        val slots = (size.width / step).toInt().coerceAtLeast(1)
        val past = existing?.takeIf { it.isNotEmpty() }
        val stepMs = AudioPeaks.STEP_MS
        val pastMs = (past?.size ?: 0) * stepMs
        val total = pastMs + history.size * stepMs
        // One time scale for the old and the new part: a bar is a fixed span, until the strip is full and every bar widens its span.
        val barMs = maxOf(MIN_BAR_MS, (total + slots - 1) / slots)
        val count = ((total + barMs - 1) / barMs).coerceIn(1, slots)
        val ref = maxOf((past?.maxOf { it.toInt() and 0xFF } ?: 0) / 255f, 0.35f)
        val corner = CornerRadius(bar / 2)
        for (i in 0 until count) {
            val from = i * barMs
            val to = from + barMs
            var level = 0f
            if (past != null && from < pastMs) {
                for (k in from / stepMs until minOf(to, pastMs).div(stepMs).coerceAtMost(past.size)) level = maxOf(level, (past[k].toInt() and 0xFF) / 255f)
            }
            if (to > pastMs) {
                for (k in maxOf(from - pastMs, 0) / stepMs until ((to - pastMs) / stepMs).coerceAtMost(history.size)) level = maxOf(level, history[k])
            }
            val h = maxOf(3.dp.toPx(), (level / ref).coerceAtMost(1f) * size.height)
            drawRoundRect(if (from < pastMs) oldColor else newColor, Offset(i * step, (size.height - h) / 2), Size(bar, h), corner)
        }
        // The playhead sits at the writing edge, drawn like the player's.
        val head = (count * step).coerceAtMost(size.width - 2.dp.toPx())
        drawRoundRect(newColor, Offset(head - 1.25.dp.toPx(), 0f), Size(2.5.dp.toPx(), size.height), CornerRadius(1.25.dp.toPx()))
        drawCircle(newColor, 5.dp.toPx(), Offset(head, 5.dp.toPx()))
    }
}

/** The running time of a recording, shown in the card's header where a finished note has its length. [startedAtMs] is when this recording session began. */
@Composable
internal fun ElapsedText(
    startedAtMs: Long,
    baseMs: Long = 0L,
) {
    var elapsed by remember(startedAtMs) { mutableLongStateOf(com.govorun.echo.record.RecordingBus.recordedMs(startedAtMs)) }
    val lifecycle = visibleLifecycle()
    LaunchedEffect(startedAtMs) {
        lifecycle.whileVisible {
            while (true) {
                // A pause stops the clock: it shows what the note holds.
                elapsed = com.govorun.echo.record.RecordingBus.recordedMs(startedAtMs)
                delay(500)
            }
        }
    }
    // A note that is being continued counts from where it stopped: what it already holds plus this session.
    Text(formatDuration(baseMs + elapsed), style = noteSupportingStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val MAX_LIVE = 72_000
private const val MIN_BAR_MS = 200
