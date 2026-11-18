package com.govorun.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.govorun.echo.formatDuration
import com.product.ui.ProductTokens

/**
 * The draft's text while a piece is chosen: Android's own text selection on a read-only text, so a
 * long press takes a word, the handles stretch it, the system magnifier shows where they are and
 * the text scrolls when a handle reaches its edge. A selection made here takes whole words (its
 * edges go into the pauses around them); a piece moved on the scale shows here as the words inside
 * it. The time of each paragraph stands on the left, as on the note screen.
 */
@Composable
internal fun FragmentText(
    text: DraftText,
    edges: FragmentEdges,
    pick: FragmentSelection,
    /** Whether [pick] was made in this text: then the text is left as the person holds it. */
    pickedHere: Boolean,
    onPick: (FragmentSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(text) { TextFieldState(text.text) }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val current by rememberUpdatedState(pick)
    val change by rememberUpdatedState(onPick)
    // The handles show only on a focused field; a read-only one brings no keyboard with it.
    LaunchedEffect(state) { runCatching { focus.requestFocus() } }
    // A piece set on the scale or from the menu: its words are selected here and the edge being
    // moved is kept in view.
    LaunchedEffect(pick, text, layout) {
        if (pickedHere) return@LaunchedEffect
        val range = text.rangeFor(pick)
        if (state.selection != range) state.edit { selection = range }
        val result = layout ?: return@LaunchedEffect
        val offset =
            (if (pick.active == Edge.START) range.min else range.max).coerceIn(0, text.text.length)
        val line = result.getLineForOffset(offset)
        val top = result.getLineTop(line).toInt()
        val bottom = result.getLineBottom(line).toInt()
        val viewport = scroll.viewportSize
        if (viewport > 0 && (top < scroll.value || bottom > scroll.value + viewport))
            scroll.scrollTo((top - viewport / 3).coerceIn(0, scroll.maxValue))
    }
    // A selection made here: the piece runs from the pause before its first word to the pause after
    // its last.
    LaunchedEffect(state, text) {
        snapshotFlow { state.selection }
            .collect { selection ->
                if (selection == text.rangeFor(current)) return@collect
                val words = text.wordsIn(selection)
                if (words == null) {
                    // A tap leaves a cursor: the piece shrinks to that moment, where ▶ starts
                    // playing.
                    val at =
                        text.wordAt(selection.start)?.let { edges.cursorAt(text.words[it]) }
                            ?: current.startMs
                    if (at != current.startMs || current.lengthMs > 0)
                        change(FragmentSelection(at, at, Edge.END))
                    return@collect
                }
                val active =
                    if (selection.start != text.rangeFor(current).start) Edge.START else Edge.END
                change(edges.forWords(text.words[words.first], text.words[words.last], active))
            }
    }
    val style =
        MaterialTheme.typography.bodyLarge.copy(
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    val timeStyle = MaterialTheme.typography.labelMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    Row(modifier.fillMaxWidth()) {
        // The paragraph times, at the height of each paragraph's first line, moving with the text.
        Box(Modifier.width(52.dp).fillMaxSize().clipToBounds()) {
            val result = layout
            if (result != null) {
                text.paragraphs.forEach { (offset, ms) ->
                    if (offset > result.layoutInput.text.length) return@forEach
                    val y =
                        result.getLineTop(result.getLineForOffset(offset)) - scroll.value +
                            with(density) { 3.dp.toPx() }
                    Text(
                        formatDuration(ms),
                        modifier = Modifier.offset { IntOffset(0, y.toInt()) },
                        style = timeStyle,
                        color = muted,
                    )
                }
            }
        }
        BasicTextField(
            state = state,
            modifier = Modifier.weight(1f).fillMaxSize().focusRequester(focus),
            readOnly = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            onTextLayout = { result -> layout = result() },
            scrollState = scroll,
        )
    }
}

/** Fragment text uses the same note surface; selection and draft stay with the screen. */
@Composable
internal fun FragmentTextCard(
    text: DraftText,
    edges: FragmentEdges,
    pick: FragmentSelection,
    pickedHere: Boolean,
    onPick: (FragmentSelection) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(
                    topStart = ProductTokens.Radius.Card,
                    topEnd = ProductTokens.Radius.Card,
                ),
            )
    ) {
        FragmentText(
            text,
            edges,
            pick,
            pickedHere,
            onPick = onPick,
            modifier =
                Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 16.dp,
                ),
        )
    }
}
