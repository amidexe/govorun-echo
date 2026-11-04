package com.govorun.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.govorun.echo.R
import com.product.ui.ProductTokens

/**
 * A card's tags in one line, each after a dot of its colour, as many as fit; the rest are counted («+2») instead of a name cut off in
 * the middle. A single tag too long for the line is shortened with an ellipsis.
 */
@Composable
internal fun TagsInLine(
    tags: List<String>,
    styles: Map<String, TagStyle>,
    modifier: Modifier = Modifier,
) {
    val style = noteSupportingStyle()
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    SubcomposeLayout(modifier) { constraints ->
        val gap = 12.dp.roundToPx()
        val loose = Constraints()
        val labels = subcompose("tags") { tags.forEach { TagLabel(it, tagColor(it, styles[it]), style, quiet, tagDisplayName(it, styles[it])) } }.map { it.measure(loose) }
        val probe = subcompose("probe") { Text("+${tags.size}", style = style) }.firstOrNull()?.measure(loose)?.width ?: 0
        val max = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val fit = fittingCount(labels.map { it.width }, gap, probe, max)
        // Not even one fits: the first is shortened to leave room for the count of the others.
        val placed =
            if (fit > 0 || tags.isEmpty()) {
                labels.take(fit)
            } else {
                val room = (max - if (tags.size > 1) gap + probe else 0).coerceAtLeast(0)
                subcompose("first") { TagLabel(tags.first(), tagColor(tags.first(), styles[tags.first()]), style, quiet, tagDisplayName(tags.first(), styles[tags.first()])) }.map { it.measure(Constraints(maxWidth = room)) }
            }
        val rest = tags.size - placed.size
        val more = if (rest > 0) subcompose("more") { Text("+$rest", style = style, color = quiet) }.firstOrNull()?.measure(loose) else null
        val height = (placed.map { it.height } + listOfNotNull(more?.height)).maxOrNull() ?: 0
        val width = placed.sumOf { it.width } + gap * (placed.size - 1).coerceAtLeast(0) + (more?.let { gap + it.width } ?: 0)
        layout(if (constraints.hasBoundedWidth) constraints.maxWidth else width, height) {
            var x = 0
            placed.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
            more?.placeRelative(x, (height - more.height) / 2)
        }
    }
}

@Composable
private fun TagLabel(
    tag: String,
    dot: Color,
    style: TextStyle,
    color: Color,
    displayName: String = tag,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(displayName, modifier = Modifier.semantics { contentDescription = tag }, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * How many items of these widths fit one after another in [max] with [gap] between them. When not all of them fit, room is kept
 * after a gap for a «+N» as wide as [more].
 */
internal fun fittingCount(
    widths: List<Int>,
    gap: Int,
    more: Int,
    max: Int,
): Int {
    if (widths.sum() + gap * (widths.size - 1).coerceAtLeast(0) <= max) return widths.size
    var used = 0
    widths.forEachIndexed { i, width ->
        val next = used + (if (i > 0) gap else 0) + width
        if (next + gap + more > max) return i
        used = next
    }
    return widths.size
}

/**
 * The note's tags at the end of its text, as quiet as the text around them: «#Работа #Дом» and «+ тег», no colours and no pills, so
 * they do not take the eye from what was said. A tap opens the note's tags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteTagLine(
    tags: List<String>,
    onClick: () -> Unit,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        Modifier
            .clip(RoundedCornerShape(ProductTokens.Radius.Sm))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(R.string.action_tags), onClick = onClick)
            .padding(vertical = ProductTokens.Spacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xs),
    ) {
        tags.forEach { Text("#$it", style = noteSupportingStyle(), color = quiet) }
        Text(stringResource(R.string.tags_add_quiet), style = noteSupportingStyle(), color = quiet.copy(alpha = 0.7f))
    }
}
