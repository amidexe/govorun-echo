package com.govorun.echo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Crop
import com.adamglin.phosphoricons.regular.FilePlus
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.Scissors
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.govorun.echo.R
import com.govorun.echo.notes.AudioPeaks

/**
 * The player panel while a piece of the note is chosen. Play stays where it always is; the record button gives its place to the
 * scale of the whole note, which keeps clear of the screen edge; under it, where speed and time were, the piece's start, length and
 * end. On a long note the scale is coarse; the text's handles move an edge word by word.
 */
@Composable
internal fun FragmentPanel(
    bars: ByteArray?,
    totalMs: Long,
    pick: FragmentSelection,
    playing: Boolean,
    positionMs: Long?,
    onPick: (FragmentSelection) -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DockButton(
                icon = if (playing) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play,
                description = stringResource(if (playing) R.string.action_pause else R.string.fragment_listen),
                enabled = totalMs > 0,
                filled = true,
                size = 52.dp,
                onClick = onPlay,
            )
            // The end of the scale stays a thumb's width from the screen edge, as a system slider's does.
            FragmentScale(bars, totalMs, pick, if (playing) positionMs else null, onPick, Modifier.weight(1f).padding(end = 16.dp))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = MaterialTheme.typography.labelLarge
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(fragmentTime(pick.startMs), modifier = Modifier.weight(1f), style = label, color = muted)
            Text(if (pick.lengthMs > 0) fragmentLength(pick.lengthMs) else "", style = label, color = MaterialTheme.colorScheme.onSurface)
            Text(fragmentTime(pick.endMs), modifier = Modifier.weight(1f), style = label, color = muted, textAlign = TextAlign.End)
        }
    }
}

/**
 * The whole note with the piece tinted. A touch takes the nearer edge and moves it with the finger to any moment; a tap puts that
 * edge there. The system back gesture is kept off the scale, so a drag near the screen edge stays a drag.
 */
@Composable
private fun FragmentScale(
    bars: ByteArray?,
    totalMs: Long,
    pick: FragmentSelection,
    playhead: Long?,
    onPick: (FragmentSelection) -> Unit,
    modifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val tint = accent.copy(alpha = 0.16f)
    val text = MaterialTheme.colorScheme.onSurface
    val current by rememberUpdatedState(pick)
    val change by rememberUpdatedState(onPick)
    val total = totalMs.coerceAtLeast(1L)
    val knob = 9.dp
    Canvas(
        modifier
            .height(52.dp)
            .systemGestureExclusion()
            .pointerInput(total) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // The scale runs between the knob centres, so both ends can be reached without touching the screen edge.
                    val inset = knob.toPx()
                    val at = { x: Float -> (((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f) * total).toLong() }
                    val startX = inset + current.startMs.toFloat() / total * (size.width - 2 * inset)
                    val endX = inset + current.endMs.toFloat() / total * (size.width - 2 * inset)
                    val edge = if (kotlin.math.abs(down.position.x - startX) < kotlin.math.abs(down.position.x - endX) || (startX == endX && down.position.x < startX)) Edge.START else Edge.END
                    change(current.moved(edge, at(down.position.x), total))
                    drag(down.id) { moved ->
                        change(current.moved(edge, at(moved.position.x), total))
                        moved.consume()
                    }
                }
            },
    ) {
        val inset = knob.toPx()
        val width = size.width - 2 * inset
        val barsTop = 2.dp.toPx()
        val barsHeight = 34.dp.toPx()
        val x = { ms: Long -> inset + ms.toFloat() / total * width }
        drawRect(tint, Offset(x(pick.startMs), barsTop), Size((x(pick.endMs) - x(pick.startMs)), barsHeight))
        translate(left = inset) { bars(bars, 0L, total, total, barsTop, barsHeight, accent, rest, width = width) { it in pick.startMs until pick.endMs } }
        playhead?.let { drawRect(text, Offset(x(it) - 0.75.dp.toPx(), barsTop), Size(1.5.dp.toPx(), barsHeight)) }
        val knobY = size.height - knob.toPx()
        listOf(Edge.START, Edge.END).forEach { edge ->
            val ex = x(pick.edge(edge))
            val line = 2.5.dp.toPx()
            drawRect(accent, Offset(ex - line / 2, 0f), Size(line, knobY))
            drawCircle(accent, if (edge == pick.active) knob.toPx() else 7.5.dp.toPx(), Offset(ex, knobY))
            drawCircle(onAccent, 2.5.dp.toPx(), Offset(ex, knobY))
        }
    }
}

/**
 * Loudness bars of [from]-[to] across the canvas, from the note's bars (one per 100 ms), drawn like the player's waveform and scaled
 * to the loudest of the note, as the player does.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.bars(
    peaks: ByteArray?,
    from: Long,
    to: Long,
    total: Long,
    top: Float,
    height: Float,
    played: Color,
    rest: Color,
    width: Float = size.width,
    inside: (Long) -> Boolean,
) {
    val barWidth = 3.dp.toPx()
    val gap = 2.dp.toPx()
    val count = (width / (barWidth + gap)).toInt().coerceAtLeast(1)
    val step = AudioPeaks.STEP_MS
    fun peakAt(ms: Long) = peaks?.getOrNull((ms / step).toInt())?.toInt()?.and(0xFF) ?: 0
    val loudest = if (peaks == null || peaks.isEmpty()) 1 else maxOf(peaks.maxOf { it.toInt() and 0xFF }, 90)
    val minHeight = 3.dp.toPx()
    for (bar in 0 until count) {
        val a = from + (to - from) * bar / count
        val b = from + (to - from) * (bar + 1) / count
        var level = 0
        if (b > 0 && a < total) {
            var ms = a.coerceAtLeast(0L)
            while (ms < b.coerceAtMost(total)) {
                level = maxOf(level, peakAt(ms))
                ms += step
            }
        }
        val h = maxOf(minHeight, level.toFloat() / loudest * height)
        drawRoundRect(
            color = if (inside((a + b) / 2)) played else rest,
            topLeft = Offset(bar * (barWidth + gap), top + (height - h) / 2),
            size = Size(barWidth, h),
            cornerRadius = CornerRadius(barWidth / 2),
        )
    }
}

/**
 * The four things to do with the piece, along the bottom of the screen. Cutting and keeping change the note, so a joined note or
 * one whose text does not follow its words offers only the two that leave it as it is.
 */
@Composable
internal fun FragmentActions(
    canEdit: Boolean,
    hasPiece: Boolean,
    wholeNote: Boolean,
    busy: Boolean,
    onSeparate: () -> Unit,
    onCut: () -> Unit,
    onKeep: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
        if (busy) {
            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            FragmentAction(PhosphorIcons.Regular.FilePlus, R.string.fragment_separate, enabled = hasPiece && !wholeNote, onClick = onSeparate)
            if (canEdit) {
                FragmentAction(PhosphorIcons.Regular.Scissors, R.string.fragment_cut, enabled = hasPiece && !wholeNote, onClick = onCut)
                FragmentAction(PhosphorIcons.Regular.Crop, R.string.fragment_keep, enabled = hasPiece && !wholeNote, onClick = onKeep)
            }
            FragmentAction(PhosphorIcons.Regular.ShareNetwork, R.string.action_share, enabled = hasPiece, onClick = onShare)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FragmentAction(
    icon: ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else color)
    }
}

