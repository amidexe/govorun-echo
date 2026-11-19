package com.govorun.echo.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Database
import com.govorun.echo.R
import com.govorun.echo.notes.LocalStorageUsage
import com.govorun.echo.notes.NoteChanges
import com.product.ui.ProductDivider
import com.product.ui.ProductSheet
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
@Composable
internal fun StorageUsageSetting(noteIds: Set<String>?, onTrash: () -> Unit, onTransfer: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var usage by remember { mutableStateOf<LocalStorageUsage?>(null) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycle, noteIds) {
        if (noteIds == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            NoteChanges.events.debounce(750).onStart { emit(Unit) }.collect {
                usage = withContext(Dispatchers.IO) {
                    LocalStorageUsage.measure(context.dataDir, context.filesDir, context.cacheDir, context.codeCacheDir, noteIds)
                }
            }
        }
    }
    val title = stringResource(R.string.storage_local_files)
    val total = usage?.let {
        if (it.complete) formatSize(it.total) else stringResource(R.string.storage_at_least, formatSize(it.total))
    } ?: stringResource(R.string.storage_counting)
    SettingsLinkRow(PhosphorIcons.Regular.Database, title, value = total, onClick = { details = true })
    if (details) ProductSheet(title, { details = false }) {
        usage?.let { measured ->
            SettingsSection("", showTitle = false) {
                SizeRow(R.string.storage_note_files, measured.notes)
                ProductDivider()
                SettingsLinkRow(title = stringResource(R.string.screen_trash), value = formatSize(measured.trash),
                    onClick = { details = false; onTrash() })
                if (measured.unlisted > 0) {
                    ProductDivider()
                    SettingsInfoRow(title = stringResource(R.string.storage_unlisted),
                        summary = stringResource(R.string.storage_unlisted_detail, formatSize(measured.unlisted)))
                    ProductDivider()
                    SettingsLinkRow(title = stringResource(R.string.transfer_title), onClick = { details = false; onTransfer() })
                }
                ProductDivider()
                SizeRow(R.string.storage_other, measured.other)
                ProductDivider()
                SizeRow(R.string.storage_cache, measured.cache)
            }
            if (!measured.complete) Text(stringResource(R.string.storage_partial), style = MaterialTheme.typography.bodyMedium)
        } ?: Text(total)
        SettingsLinkRow(title = stringResource(R.string.storage_system), summary = stringResource(R.string.storage_system_detail),
            onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) })
    }
}

@Composable
private fun SizeRow(title: Int, size: Long) {
    SettingsInfoRow(title = stringResource(title), trailing = {
        Text(formatSize(size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    })
}
