package com.product.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun <T> ProductChoiceSheet(
    title: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    onDismissRequest: () -> Unit,
    optionDescription: (T) -> String? = { null },
    optionEnabled: (T) -> Boolean = { true },
    optionIcon: (T) -> ImageVector? = { null },
) {
    ProductSheet(title, onDismissRequest) {
        Column(Modifier.selectableGroup()) {
            ProductRowGroup {
                options.forEach { option ->
                    ProductChoiceRow(
                        title = optionLabel(option),
                        description = optionDescription(option),
                        selected = option == selected,
                        enabled = optionEnabled(option),
                        icon = optionIcon(option),
                        onClick = {
                            onSelected(option)
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductSheet(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    ProductModalSheet(onDismissRequest) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = ProductTokens.Spacing.Section,
                    end = ProductTokens.Spacing.Section,
                    bottom = ProductTokens.Spacing.Screen,
                ),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Xxl),
        ) {
            ProductSheetTitle(title)
            content()
        }
    }
}

@Composable
internal fun ProductSheetTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = ProductTokens.Spacing.Lg, bottom = ProductTokens.Spacing.Xxs)
            .semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductModalSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        properties = properties,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = ProductTokens.Radius.Sheet, topEnd = ProductTokens.Radius.Sheet),
        content = content,
    )
}

@Composable
private fun ProductChoiceRow(
    title: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = ProductTokens.Size.SheetAction)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = ProductTokens.Spacing.Screen, vertical = ProductTokens.Spacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.RowContent),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.55f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
