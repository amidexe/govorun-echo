package com.govorun.echo.ui

import com.govorun.echo.notes.shownTitle
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.playback.NotePlayer
import com.govorun.echo.notes.NoteStore
import com.product.ui.ProductTokens
import com.govorun.echo.formatDuration

/**
 * The note that plays, in the row of «+», while its card is out of sight: scrolled away, its day folded, filtered out or in the
 * other tab. Pause and stop stay at hand anywhere in the list, as in the mini player of music apps and the voice message bar of
 * WhatsApp and Telegram. While the card's own button is on screen the card is the player, so the bar never stands beside it.
 * A tap on the text opens the note with its waveform.
 */
@Composable
internal fun NowPlayingBar(
    notes: List<NoteStore.Entry>,
    state: NotePlayer.State,
    visible: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // After a stop the bar fades out with the note it showed, not as an empty one.
    var last by remember { mutableStateOf(state) }
    SideEffect { if (state.noteId != null) last = state }
    val shown = if (state.noteId != null) state else last
    AnimatedVisibility(
        visible = visible,
        // The same height and bottom edge as «+», with the usual gap before it.
        modifier = modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp + BAR_HEIGHT + 12.dp, bottom = 16.dp),
        enter = fadeIn(tween(FADE_MS)),
        exit = fadeOut(tween(FADE_MS)),
    ) {
        val context = LocalContext.current
        val player = remember { NotePlayer.get(context) }
        val note = notes.firstOrNull { it.id == shown.noteId }
        Surface(
            Modifier.fillMaxWidth().height(BAR_HEIGHT),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 4.dp,
        ) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // The round button of the playing card, a size up for the thumb.
                Box(
                    Modifier.size(48.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { player.playPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(
                            if (shown.playing) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play,
                            contentDescription = stringResource(if (shown.playing) R.string.action_pause else R.string.action_play),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = note != null) { note?.let { onOpen(it.id) } },
                    verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xxs),
                ) {
                    // The card's header line: when the note was made, then how far it has played.
                    Row(horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md), verticalAlignment = Alignment.CenterVertically) {
                        if (note != null) {
                            Text(formatTime(note.startedAtMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("·", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(playedOf(shown.positionMs, shown.totalMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    // The note's name when it has one, otherwise how its text begins.
                    if (note != null && (note.title.isNotBlank() || note.text.isNotBlank())) {
                        Text(note.shownTitle().ifBlank { note.text }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { player.stop() }) {
                    Icon(PhosphorIcons.Regular.X, contentDescription = stringResource(R.string.playback_stop), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * Whether the round button of the playing note's row is on screen. It is read from the list's layout, so the screen is redrawn
 * only when the answer changes, not on every scrolled pixel.
 */
@Composable
internal fun rememberRowButtonInView(
    list: LazyListState,
    noteId: String?,
): State<Boolean> {
    val density = LocalDensity.current
    val top = with(density) { ROW_BUTTON_TOP.roundToPx() }
    val bottom = with(density) { ROW_BUTTON_BOTTOM.roundToPx() }
    return remember(list, noteId, top, bottom) {
        derivedStateOf {
            val info = list.layoutInfo
            rowButtonInView(info.visibleItemsInfo.firstOrNull { it.key == noteId }?.offset, info.viewportStartOffset, info.viewportEndOffset, top, bottom)
        }
    }
}

/**
 * Whether a row's round button lies wholly between [viewportStart] and [viewportEnd]. [rowTop] is the row's offset in the list,
 * null when the row is not laid out (scrolled far away, its day folded, filtered out, in the other tab).
 */
internal fun rowButtonInView(
    rowTop: Int?,
    viewportStart: Int,
    viewportEnd: Int,
    buttonTop: Int,
    buttonBottom: Int,
): Boolean = rowTop != null && rowTop + buttonTop >= viewportStart && rowTop + buttonBottom <= viewportEnd

/** The bar is there while a note is loaded, playing or paused, and its card's own button is out of sight. */
internal fun showsNowPlaying(
    state: NotePlayer.State,
    rowButtonInView: Boolean,
): Boolean = state.noteId != null && !rowButtonInView

/** How far the note has played, as in the card's header: "2:30:02 / 5:00:00". */
internal fun playedOf(
    positionMs: Long,
    totalMs: Long,
): String = "${formatDuration(positionMs.coerceIn(0L, maxOf(totalMs, 0L)))} / ${formatDuration(totalMs)}"

/** The height of «+», which the bar shares. */
private val BAR_HEIGHT = 56.dp

/** Where the round button lies in a row of the list (NoteRow): a 1 dp divider, 4 and 8 dp of padding, then the 36 dp button. */
private val ROW_BUTTON_TOP = 13.dp
private val ROW_BUTTON_BOTTOM = 49.dp

private const val FADE_MS = 150
