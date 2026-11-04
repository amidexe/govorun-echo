package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.product.ui.ProductTokens
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CloudSlash
import com.govorun.echo.NetworkGate
import com.govorun.echo.smart.GovorunSmart
import com.govorun.echo.smart.Smart

internal fun isOfflineReason(reason: String?): Boolean =
    reason == NetworkGate.OFFLINE || reason == NetworkGate.ROAMING ||
        reason == GovorunSmart.OFFLINE || reason == GovorunSmart.ROAMING

/** A quiet status in the existing action space, with no navigation or second network control. */
@Composable
internal fun NetworkNotice(reason: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val rowModifier = if (compact) modifier else modifier.heightIn(min = ProductTokens.Size.Row)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Sm)) {
        if (isOfflineReason(reason)) Icon(PhosphorIcons.Regular.CloudSlash, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(if (compact) ProductTokens.Size.SlotIcon else ProductTokens.Size.ActiveIcon))
        Text(stringResource(smartStateText(reason).line), style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SmartNetworkNotice(): Boolean {
    val revision by Smart.changes.collectAsState()
    val enabled = remember(revision) { Smart.enabled() }
    if (enabled) return false
    NetworkNotice(Smart.disabledReason())
    return true
}
