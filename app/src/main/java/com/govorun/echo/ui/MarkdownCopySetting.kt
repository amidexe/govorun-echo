package com.govorun.echo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.FolderOpen
import com.govorun.echo.R
import com.product.ui.*

@Composable
internal fun ExperimentalSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ProductScaffold(title = stringResource(R.string.settings_advanced), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(productScreenPadding())) {
            SettingsSection("", showTitle = false) {
                SettingsLinkRow(PhosphorIcons.Regular.FileText, stringResource(R.string.folder_once_markdown),
                    stringResource(R.string.folder_once_markdown_short), onClick = { context.startActivity(FileExportActivity.intent(context, FileExportMode.Markdown)) })
                ProductDivider()
                SettingsLinkRow(PhosphorIcons.Regular.FolderOpen, stringResource(R.string.folder_auto_title),
                    stringResource(R.string.folder_experiment_summary), onClick = { context.startActivity(FileExportActivity.intent(context, FileExportMode.Automatic)) })
            }
        }
    }
}
