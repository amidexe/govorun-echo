package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CloudSlash
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsLinkRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.govorun.echo.R
import com.govorun.echo.update.AppUpdates
import com.product.ui.FeedCard
import com.product.ui.ProductTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "A new version is available" at the top of the list, in the test build only; it asks the website when the app is opened, at most every six hours. */
@Composable
internal fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Every return to the app looks again; the website is asked at most every six hours, otherwise the last answer is shown.
    val lifecycle = visibleLifecycle()
    LaunchedEffect(lifecycle) { lifecycle.whileVisible { withContext(Dispatchers.IO) { AppUpdates.checkForUpdate(context) } } }
    val network by com.govorun.echo.NetworkPolicy.state.collectAsState()
    val offer = AppUpdates.offer ?: return
    FeedCard(Modifier.padding(bottom = 8.dp)) {
        Column(Modifier.padding(ProductTokens.Spacing.Section), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md)) {
            Text(stringResource(R.string.update_title, offer.versionName), style = MaterialTheme.typography.titleMedium)
            if (offer.notes.isNotBlank()) Text(offer.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (AppUpdates.phase) {
                AppUpdates.Phase.DOWNLOADING -> LinearProgressIndicator(progress = { AppUpdates.progress })
                AppUpdates.Phase.FAILED -> Text(stringResource(R.string.update_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            if (AppUpdates.phase != AppUpdates.Phase.DOWNLOADING) {
                if (network.reason != null) NetworkNotice(network.reason!!)
                else OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { AppUpdates.downloadAndInstall(context) } }) {
                    Text(stringResource(if (AppUpdates.phase == AppUpdates.Phase.FAILED) R.string.update_retry else R.string.update_install))
                }
            }
        }
    }
}

/** About screen action: check for an update or install the available version. */
@Composable
internal fun UpdateSettingsRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    val lifecycle = visibleLifecycle()
    LaunchedEffect(lifecycle) { lifecycle.whileVisible { withContext(Dispatchers.IO) { AppUpdates.checkForUpdate(context) } } }
    val network by com.govorun.echo.NetworkPolicy.state.collectAsState()
    val offer = AppUpdates.offer
    val title = stringResource(if (offer == null) R.string.update_row_title else R.string.update_install)
    val summary =
        when {
            network.reason != null -> stringResource(smartStateText(network.reason!!).line)
            AppUpdates.phase == AppUpdates.Phase.DOWNLOADING -> stringResource(R.string.update_downloading, (AppUpdates.progress * 100).toInt())
            AppUpdates.phase == AppUpdates.Phase.FAILED -> stringResource(R.string.update_failed)
            checking -> stringResource(R.string.update_checking)
            offer != null -> stringResource(R.string.update_title, offer.versionName)
            unreachable -> stringResource(R.string.update_unreachable)
            result == false -> stringResource(R.string.update_latest)
            else -> null
        }
    if (network.reason != null) {
        SettingsInfoRow(
            if (isOfflineReason(network.reason)) PhosphorIcons.Regular.CloudSlash else PhosphorIcons.Regular.ArrowsClockwise,
            title, summary,
        )
        return
    }
    SettingsLinkRow(
        PhosphorIcons.Regular.ArrowsClockwise,
        title,
        summary,
        enabled = network.reason == null && !checking && AppUpdates.phase != AppUpdates.Phase.DOWNLOADING,
        onClick = {
            scope.launch(Dispatchers.IO) {
                if (offer != null) {
                    AppUpdates.downloadAndInstall(context)
                } else {
                    checking = true
                    val found = AppUpdates.checkForUpdate(context, force = true)
                    unreachable = found == null
                    result = found
                    checking = false
                }
            }
        },
    )
}
