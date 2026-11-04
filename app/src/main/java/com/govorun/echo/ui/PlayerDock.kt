package com.govorun.echo.ui

import androidx.compose.ui.draw.clip
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Spacer
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.Plus as BoldPlus
import com.adamglin.phosphoricons.regular.ClockClockwise
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.ArrowsInLineHorizontal
import com.adamglin.phosphoricons.regular.Stop
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.playback.NotePlayer
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.formatDuration

/** Room the list leaves at its bottom so the last note can scroll above the record button and the player. */
internal val DOCK_CLEARANCE = 88.dp

/** The record button in the right corner of every screen, as usual; while recording it turns into "stop". */
@Composable
internal fun RecordButton(
    recording: Boolean,
    actions: NotesActions,
    modifier: Modifier = Modifier,
    onRecord: () -> Unit = actions.onRecord,
    // Standard circle/square actions are shared with Quick Settings.
    recordIcon: androidx.compose.ui.graphics.vector.ImageVector = recordingGlyph(),
    recordLabel: Int = R.string.action_new_recording,
) {
    Box(modifier.navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp)) {
        val stopping by com.govorun.echo.record.RecordingBus.stopping.collectAsState()
        // A tap records; a hold, only on the main record button, starts a note of text instead.
        val holdForText = !recording && recordLabel == R.string.action_new_recording
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val tap = { if (recording && stopping) Unit else if (recording) actions.onStop() else onRecord() }
        androidx.compose.material3.Surface(
            modifier =
                Modifier.size(56.dp).clip(CircleShape).combinedClickable(
                    onClick = tap,
                    onLongClick = if (holdForText) ({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); actions.onNewText() }) else null,
                ),
            shape = CircleShape,
            color = RecordingColors.Container,
            contentColor = RecordingColors.Content,
            shadowElevation = 4.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
            if (recording && stopping) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = RecordingColors.Content)
            } else {
                if (!recording && recordLabel == R.string.action_append) {
                    BadgedIcon(recordIcon, stringResource(recordLabel), RecordingColors.Container)
                } else {
                    Crossfade(targetState = recording, animationSpec = tween(180), label = "recordAction") { active ->
                        Icon(
                            if (active) recordingStopGlyph() else recordIcon,
                            contentDescription = stringResource(if (active) R.string.action_stop else recordLabel),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * The player. In the list it lives in the card of the note that plays: just the track (play and pause are the round
 * button on the card, the counter is in the card's header). On the note screen it is always there: play and the track.
 */
@Composable
internal fun InlinePlayer(
    note: NoteStore.Entry,
    player: NotePlayer.State,
    actions: NotesActions,
    modifier: Modifier = Modifier,
    standalone: Boolean = false,
    onRecord: (() -> Unit)? = null,
    recordIcon: androidx.compose.ui.graphics.vector.ImageVector = recordingGlyph(),
    recordLabel: Int = R.string.action_append,
) {
    // Right after the stop the sound is not in the note yet: the length of the recording stands in, so nothing jumps when it arrives.
    val total = note.audio.sumOf { it.ms }.takeIf { it > 0 } ?: note.durationMs.coerceAtLeast(1L)
    val loaded = player.noteId == note.id
    val position = if (loaded) player.positionMs.coerceIn(0L, total) else 0L
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) drag else position.toFloat() / total
    // A note that is not loaded yet starts from the chosen place instead of seeking.
    val seek = { ms: Long -> if (loaded) actions.onSeek(ms) else actions.onPlayFrom(note.id, ms) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var peaks by remember(note.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(note.id, note.audio) { peaks = com.govorun.echo.notes.AudioPeaks.load(NoteStore.noteDir(context, note.id), note.audio) }
    val track = @Composable {
        Waveform(
            peaks = peaks,
            fraction = fraction,
            onDrag = { dragging = true; drag = it },
            onDragEnd = { dragging = false; seek((drag * total).toLong()) },
            onTap = { seek((it * total).toLong()) },
            height = 44.dp,
        )
    }
    if (!standalone) {
        Box(modifier.fillMaxWidth().padding(top = 10.dp)) { track() }
    } else {
        val writing = note.state == NoteStore.STATE_RECORDING
        val preparing = !writing && note.audio.isEmpty()
        val stopping by com.govorun.echo.record.RecordingBus.stopping.collectAsState()
        val live by com.govorun.echo.record.RecordingBus.state.collectAsState()
        val sessionStart = (live as? com.govorun.echo.record.RecordingState.Recording)?.startedAtMs ?: note.startedAtMs
        // Play on the left, record on the right, the tape between them: while writing, they are pause and stop, the tape the recording.
        Column(modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (preparing) {
                // The sound is being made ready: a spinner says so where play will be, instead of a button that does nothing.
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                // While writing, the play button pauses the recording and goes on with it; stop is on the right.
                val paused by com.govorun.echo.record.RecordingBus.pausedAt.collectAsState()
                DockButton(
                    icon = if (writing) (if (paused == null) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play) else if (loaded && player.playing) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play,
                    description = stringResource(if (writing) (if (paused == null) R.string.action_pause else R.string.action_continue_recording) else if (loaded && player.playing) R.string.action_pause else R.string.action_play),
                    enabled = !(writing && stopping),
                    filled = !writing,
                    size = 52.dp,
                ) { if (writing) actions.onPauseRecording(paused == null) else actions.onPlay(note.id) }
            }
            Box(Modifier.weight(1f)) { if (writing) LiveRecording(note) else track() }
            if (onRecord != null) {
                DockButton(
                    icon = if (writing) recordingStopGlyph() else recordIcon,
                    description = stringResource(if (writing) R.string.action_stop else recordLabel),
                    enabled = true,
                    filled = true,
                    recordAction = true,
                    size = 52.dp,
                    busy = writing && stopping,
                    badge = !writing && recordLabel == R.string.action_append,
                ) { if (writing) actions.onStop() else onRecord() }
            }
        }
        // Under the tape, across the whole card: speed on the left, the counter in the middle, "skip pauses" on the right.
        // The side slots are equal, so the counter stays in the middle; while recording only the counter is left.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Start) { if (!writing && !preparing) SpeedChip() }
            if (writing) {
                ElapsedText(sessionStart, if (note.appending) note.durationMs else 0L)
                // As on the card: the standing clock says why it stands.
                val pausedNow by com.govorun.echo.record.RecordingBus.pausedAt.collectAsState()
                if (pausedNow != null) Text(" · " + stringResource(R.string.status_paused), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    if (preparing) stringResource(R.string.player_preparing, formatDuration(total)) else "${formatDuration(if (dragging) (drag * total).toLong() else position)} / ${formatDuration(total)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // "Skip pauses" moves through the text, so a note without text has no such mode.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { if (!writing && !preparing && note.timed) SkipPausesChip() }
        }
        }
    }
}

/** A round accent button, used for play: the same look as the record button. */
@Composable
internal fun DockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    filled: Boolean,
    danger: Boolean = false,
    recordAction: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    busy: Boolean = false,
    /** A small plus on the icon: "add to this note" rather than "new". */
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (recordAction) RecordingColors.Container else if (danger) scheme.error else if (filled) scheme.primaryFixed else scheme.secondaryContainer
    val content = if (recordAction) RecordingColors.Content else if (danger) scheme.onError else if (filled) scheme.onPrimaryFixed else scheme.onSecondaryContainer
    androidx.compose.material3.FilledIconButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.size(size),
        shape = CircleShape,
        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = content)
        } else if (badge) {
            BadgedIcon(icon, description, container)
        } else {
            Icon(icon, contentDescription = description, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * An icon with a small Phosphor plus at its lower right: a disc in the icon's colour with the plus in the button's colour, the
 * usual badge of an "add to" action. [container] is the button's colour, so the badge reads in every theme.
 */
@Composable
internal fun BadgedIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    container: androidx.compose.ui.graphics.Color,
) {
    Box(Modifier.size(26.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(26.dp))
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 5.dp, y = 4.dp)
                .size(15.dp)
                .background(container, CircleShape)
                .padding(1.5.dp)
                .background(androidx.compose.material3.LocalContentColor.current, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PhosphorIcons.Bold.BoldPlus, contentDescription = null, tint = container, modifier = Modifier.size(9.dp))
        }
    }
}

/** Loudness bars for the whole note: the played part is coloured, silence stays a thin line. A tap or a drag seeks. */
@Composable
internal fun Waveform(
    peaks: ByteArray?,
    fraction: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: (Float) -> Unit,
    height: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    // Quiet recordings are stretched to the full height, but background noise is not.
    val scale = maxOf(peaks?.maxOfOrNull { it.toInt() and 0xFF } ?: 0, 90).toFloat()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) { detectTapGestures { onTap((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onDrag((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, _ -> onDrag((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val barWidth = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val count = (size.width / (barWidth + gap)).toInt().coerceAtLeast(1)
        val minHeight = 3.dp.toPx()
        val corner = CornerRadius(barWidth / 2)
        for (bar in 0 until count) {
            val level =
                if (peaks == null || peaks.isEmpty()) {
                    0f
                } else {
                    val from = bar * peaks.size / count
                    val to = maxOf(from + 1, (bar + 1) * peaks.size / count).coerceAtMost(peaks.size)
                    var peak = 0
                    for (i in from until to) peak = maxOf(peak, peaks[i].toInt() and 0xFF)
                    peak / scale
                }
            val height = maxOf(minHeight, level * size.height)
            val x = bar * (barWidth + gap)
            drawRoundRect(
                color = if ((bar + 0.5f) / count <= fraction) played else rest,
                topLeft = Offset(x, (size.height - height) / 2),
                size = Size(barWidth, height),
                cornerRadius = corner,
            )
        }
        // Playhead: a line with a round knob, so the position reads at a glance.
        val head = (fraction.coerceIn(0f, 1f) * size.width).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
        drawRoundRect(played, Offset(head - 1.25.dp.toPx(), 0f), Size(2.5.dp.toPx(), size.height), CornerRadius(1.25.dp.toPx()))
        drawCircle(played, 5.dp.toPx(), Offset(head, 0f).copy(y = 5.dp.toPx()))
    }
}


/** Speed: a tap goes to the next one; kept between sessions. */
@Composable
private fun SpeedChip() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember { NotePlayer.get(context) }
    val speed by player.speed.collectAsState()
    EchoChip(selected = speed != 1f, onClick = { player.setSpeed(SPEEDS[(SPEEDS.indexOf(speed).let { if (it < 0) 0 else it } + 1) % SPEEDS.size]) }, label = { Text(speedLabel(speed), style = MaterialTheme.typography.labelMedium) })
}

/** "Skip pauses"; kept between sessions. */
@Composable
private fun SkipPausesChip() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember { NotePlayer.get(context) }
    val skip by player.skipSilence.collectAsState()
    EchoChip(
        selected = skip,
        onClick = { player.setSkipSilence(!skip) },
        leadingIcon = { Icon(PhosphorIcons.Regular.ArrowsInLineHorizontal, contentDescription = null, modifier = Modifier.size(18.dp)) },
        label = { Text(stringResource(R.string.player_skip_pauses), style = MaterialTheme.typography.labelMedium) },
    )
}

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

private fun speedLabel(speed: Float): String = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().replace('.', ',')) + "×"
