package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Notebook
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.backup.BackupFolder

/**
 * «Копия для Obsidian», among the experimental settings: the copy folder also gets every note as a Markdown file with its date, tags
 * and sound inside, so the folder can be an Obsidian vault. Turned on or off, the folder catches up at once.
 */
@Composable
internal fun MarkdownCopySetting(onPickFolder: () -> Unit) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AppSettings.markdownCopy(context)) }
    SwitchRow(PhosphorIcons.Regular.Notebook, R.string.settings_markdown_title, R.string.settings_markdown_text, on) {
        on = it
        AppSettings.setMarkdownCopy(context, it)
        BackupFolder.syncAsync(context)
    }
    if (on && BackupFolder.treeUri(context) == null) androidx.compose.material3.TextButton(onClick = onPickFolder) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.backup_pick)) }
}
