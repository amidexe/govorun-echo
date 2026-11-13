package com.product.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight

internal val LocalProductRowGroup = staticCompositionLocalOf { false }

/** Wrap existing row content, including conditional/custom rows, in connected surfaces. */
@Composable
internal fun ProductRowGroup(content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    CompositionLocalProvider(LocalProductRowGroup provides true) {
        SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
            val gap = ProductTokens.Spacing.Xxs.roundToPx()
            val rows = subcompose("rows", content).map { it.measure(constraints.copy(minHeight = 0)) }
                .filter { it.height > 0 }
            val width = constraints.maxWidth
            val shapes = rows.indices.map { index ->
                val top = if (index == 0) ProductTokens.Radius.RowGroup else ProductTokens.Radius.GroupedRow
                val bottom = if (index == rows.lastIndex) ProductTokens.Radius.RowGroup else ProductTokens.Radius.GroupedRow
                RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
            }
            val backgrounds = subcompose("backgrounds") {
                shapes.forEach { shape -> Box(Modifier.background(color, shape)) }
            }.mapIndexed { index, background -> background.measure(Constraints.fixed(width, rows[index].height)) }
            val height = rows.sumOf { it.height } + gap * (rows.size - 1).coerceAtLeast(0)
            layout(width, constraints.constrainHeight(height)) {
                var y = 0
                rows.forEachIndexed { index, row ->
                    backgrounds[index].placeRelative(0, y)
                    row.placeRelativeWithLayer(0, y) {
                        shape = shapes[index]
                        clip = true
                    }
                    y += row.height + gap
                }
            }
        }
    }
}

@Composable
internal fun ProductRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    minHeight: Dp = ProductTokens.Size.SettingsRow,
    color: Color = MaterialTheme.colorScheme.onSurface,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight)
            .padding(horizontal = ProductTokens.Spacing.Screen, vertical = ProductTokens.Spacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.RowContent),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(ProductTokens.Size.GroupedRowIcon), tint = iconColor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (!summary.isNullOrEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}
