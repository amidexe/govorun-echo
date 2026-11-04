package com.govorun.echo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.govorun.echo.R
import com.govorun.echo.formatDuration
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingState
import com.product.ui.ProductTokens
import kotlinx.coroutines.delay

/**
 * The note being recorded, in the row of «+», from the moment it starts — the same bar as [NowPlayingBar], so recording and
 * playing look alike: the card's round button (pause and go on), the time running and what is being said, and a tap on the text
 * opens the note. «Стоп» stays the big button beside it, as on the card.
 */
@Composable
internal fun RecordingBar(
    notes: List<NoteStore.Entry>,
    state: RecordingState,
    visible: Boolean,
    actions: NotesActions,
    modifier: Modifier = Modifier,
) {
    val recording = state as? RecordingState.Recording
    AnimatedVisibility(
        visible = visible && recording != null,
        modifier = modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp + BAR_HEIGHT + 12.dp, bottom = 16.dp),
        enter = fadeIn(tween(FADE_MS)),
        exit = fadeOut(tween(FADE_MS)),
    ) {
        val shown = recording ?: return@AnimatedVisibility
        val note = notes.firstOrNull { it.id == shown.noteId }
        val paused by RecordingBus.pausedAt.collectAsState()
        val stopping by RecordingBus.stopping.collectAsState()
        // The time runs as on the card, a tick a second; paused, it stands.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(shown.noteId) {
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
        Surface(Modifier.fillMaxWidth().height(BAR_HEIGHT), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 4.dp) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !stopping) { actions.onPauseRecording(paused == null) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(
                            if (paused == null) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play,
                            contentDescription = stringResource(if (paused == null) R.string.action_pause else R.string.action_continue_recording),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp, end = 16.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.onCardClick(shown.noteId) },
                    verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xxs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (paused == null && !stopping) RecordingIndicator()
                        Text(
                            if (stopping) stringResource(R.string.tile_saving) else stringResource(if (paused == null) R.string.recording_bar_on else R.string.recording_bar_paused, formatDuration(RecordingBus.recordedMs(shown.startedAtMs, now))),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    // What is being said, its latest words; before the first ones, the note's name if it has one.
                    val said = note?.live?.takeIf { it.isNotBlank() }?.let { tailOf(it) } ?: note?.title.orEmpty()
                    if (said.isNotBlank()) Text(said, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The last words of the live text that fit one line: the bar shows what is said now, not how the note began. */
private fun tailOf(text: String): String = if (text.length <= TAIL) text else "…" + text.takeLast(TAIL).substringAfter(' ')

private const val TAIL = 48
private val BAR_HEIGHT = 56.dp
private const val FADE_MS = 200
