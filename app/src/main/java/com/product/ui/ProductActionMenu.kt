package com.product.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal data class ProductMenuAction(
    val icon: ImageVector,
    val label: String,
    val caption: String = label,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

private val MenuMaxWidth = 640.dp
private val QuickPadding = ProductTokens.Spacing.Lg

/** Short menus fit as a whole. Only the separate screen scrolls when labels or window size demand it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductActionMenu(
    title: String,
    quick: List<ProductMenuAction>,
    groups: List<List<ProductMenuAction>>,
    onDismiss: () -> Unit,
    detail: String? = null,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val window = LocalWindowInfo.current.containerSize
    val safe = WindowInsets.safeDrawing
    val keyboard = WindowInsets.ime.getBottom(density)
    val usableHeight = with(density) {
        (window.height - safe.getTop(density) - maxOf(safe.getBottom(density), keyboard)).coerceAtLeast(0).toDp()
    }
    val usableWidth = with(density) {
        (window.width - safe.getLeft(density, direction) -
            safe.getRight(density, direction)).coerceAtLeast(0).toDp()
    }
    val width = min(MenuMaxWidth, usableWidth) - ProductTokens.Spacing.Section * 2
    val textWidth = width - ProductTokens.Spacing.Screen * 2 -
        ProductTokens.Size.GroupedRowIcon - ProductTokens.Spacing.RowContent
    val typography = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    val openGroups = groups.filter { it.isNotEmpty() }
    val fittedHeight = with(density) {
        val rowLimits = Constraints(maxWidth = textWidth.roundToPx().coerceAtLeast(1))
        val rowHeight = openGroups.flatten().sumOf { action ->
            val text = measurer.measure(action.label, typography.bodyLarge, constraints = rowLimits)
            max(ProductTokens.Size.TouchTarget, text.size.height.toDp() + ProductTokens.Spacing.Xl * 2).value.toDouble()
        }.toFloat().dp
        var total = rowHeight + ProductTokens.Spacing.Xxs * openGroups.sumOf { it.size - 1 }
        if (quick.isNotEmpty()) {
            val cell = ((width - ProductTokens.Spacing.Xxs * (quick.size - 1)) / quick.size) - QuickPadding * 2
            val caption = quick.maxOf { action ->
                measurer.measure(action.caption, typography.labelMedium, constraints = Constraints(maxWidth = cell.roundToPx().coerceAtLeast(1))).size.height.toDp()
            }
            total += max(ProductTokens.Size.TouchTarget, QuickPadding * 2 + ProductTokens.Size.GroupedRowIcon + ProductTokens.Spacing.Sm + caption)
        }
        if (detail != null) {
            total += measurer.measure(detail, typography.bodySmall, constraints = Constraints(maxWidth = width.roundToPx().coerceAtLeast(1))).size.height.toDp()
        }
        val sections = openGroups.size + (if (quick.isEmpty()) 0 else 1) + (if (detail == null) 0 else 1)
        total + ProductTokens.Spacing.Xxl * (sections - 1).coerceAtLeast(0)
    }
    // Room for the drag handle, safe spacing at the top and bottom, and measurement rounding.
    val chrome = ProductTokens.Size.TouchTarget + ProductTokens.Spacing.Screen + ProductTokens.Spacing.Lg * 2
    val fits = usableWidth >= 290.dp && fittedHeight + chrome <= usableHeight
    val contents: @Composable (Boolean) -> Unit = { screen ->
        Column(
            Modifier.widthIn(max = MenuMaxWidth).fillMaxWidth()
                .then(if (screen) Modifier.verticalScroll(rememberScrollState()) else Modifier.semantics { paneTitle = title })
                .padding(horizontal = ProductTokens.Spacing.Section)
                .padding(top = if (screen) ProductTokens.Spacing.Lg else 0.dp, bottom = ProductTokens.Spacing.Screen),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xxl),
        ) {
            if (quick.isNotEmpty()) QuickMenuActions(quick)
            openGroups.forEach { group ->
                ProductRowGroup {
                    group.forEach { action ->
                        val colors = MaterialTheme.colorScheme
                        ProductRow(
                            title = action.label,
                            icon = action.icon,
                            modifier = Modifier.clickable(role = Role.Button, onClick = action.onClick),
                            minHeight = ProductTokens.Size.TouchTarget,
                            color = if (action.destructive) colors.error else colors.onSurface,
                            iconColor = if (action.destructive) colors.error else colors.onSurfaceVariant,
                        )
                    }
                }
            }
            if (detail != null) {
                Text(detail, style = typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (fits) {
        ProductModalSheet(onDismiss, sheetGesturesEnabled = false) { contents(false) }
    } else {
        Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            ProductScaffold(title, onBack = onDismiss) { insets ->
                Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) { contents(true) }
            }
        }
    }
}

@Composable
private fun QuickMenuActions(actions: List<ProductMenuAction>) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(ProductTokens.Radius.RowGroup)),
        horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xxs),
    ) {
        actions.forEach { action ->
            Column(
                Modifier.weight(1f).fillMaxHeight().heightIn(min = ProductTokens.Size.TouchTarget)
                    .background(colors.surfaceContainerHigh)
                    .clickable(role = Role.Button, onClickLabel = action.label, onClick = action.onClick)
                    .padding(QuickPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Sm, Alignment.CenterVertically),
            ) {
                Icon(action.icon, null, Modifier.size(ProductTokens.Size.GroupedRowIcon), tint = colors.onSurfaceVariant)
                Text(action.caption, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
