package com.product.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class ProductSectionItem<T>(
    val key: T,
    val title: String,
    val icon: ImageVector,
)

@Composable
fun <T> ProductSettingsShell(
    title: String,
    items: List<ProductSectionItem<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    ProductScaffold(title = title, onBack = onBack) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ProductSectionPicker(items, selected, onSelected)
            key(selected) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = productScreenPadding(top = ProductTokens.Spacing.RowContent),
                    verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Screen),
                    content = content,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ProductSectionPicker(
    items: List<ProductSectionItem<T>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    val active = items.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val colors = MaterialTheme.colorScheme
    val tabs: @Composable () -> Unit = {
        items.forEachIndexed { index, item ->
            Tab(
                selected = index == active,
                onClick = { onSelected(item.key) },
                selectedContentColor = colors.primary,
                unselectedContentColor = colors.onSurfaceVariant,
            ) {
                Column(
                    Modifier.heightIn(min = ProductTokens.Size.SettingsRow)
                        .padding(horizontal = ProductTokens.Spacing.Sm, vertical = ProductTokens.Spacing.Md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Sm, Alignment.CenterVertically),
                ) {
                    Icon(item.icon, null, Modifier.size(ProductTokens.Size.TopBarNavigationIcon))
                    Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 350.dp * LocalDensity.current.fontScale || items.size > 4) {
            PrimaryScrollableTabRow(
                selectedTabIndex = active,
                containerColor = colors.surfaceContainerLow,
                edgePadding = ProductTokens.Spacing.Lg,
                tabs = tabs,
            )
        } else {
            PrimaryTabRow(selectedTabIndex = active, containerColor = colors.surfaceContainerLow, tabs = tabs)
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    showTitle: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column {
        if (showTitle) {
            Text(
                title,
                Modifier.fillMaxWidth().padding(start = ProductTokens.Spacing.Lg, bottom = ProductTokens.Spacing.Lg)
                    .semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ProductRowGroup(content)
    }
}

@Composable
fun SettingsLinkRow(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val support = when {
        value == null -> summary
        summary == null -> value
        else -> "$value\n$summary"
    }
    ProductRow(
        title = title,
        summary = support,
        icon = icon,
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
    )
}

@Composable
fun SettingsInfoRow(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) = ProductRow(title = title, summary = summary, icon = icon, trailing = trailing)

@Composable
fun SettingsSummaryRow(title: String, value: String) = ProductRow(title = title, summary = value)

@Composable
fun SettingsSwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    ProductRow(
        title = title,
        summary = summary,
        icon = icon,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else 0.55f),
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
