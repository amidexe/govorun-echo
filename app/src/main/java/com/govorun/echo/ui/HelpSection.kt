package com.govorun.echo.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Info
import com.govorun.echo.R
import com.product.ui.ProductDivider
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsSection

/** Related settings with a help row after the controls. */
@Composable
internal fun HelpSection(
    title: String,
    helpTitle: Int,
    help: Int,
    content: @Composable () -> Unit,
) {
    val label = stringResource(helpTitle)
    SettingsHelp(label, stringResource(help)) { open ->
        SettingsSection(title) {
            content()
            ProductDivider()
            SettingsLinkRow(PhosphorIcons.Regular.Info, label, onClick = open)
        }
    }
}

@Composable
internal fun RecordingHelpRow() {
    val title = stringResource(R.string.settings_record_help)
    val help = listOf(R.string.help_record, R.string.settings_tile_text, R.string.settings_silence_text)
        .map { stringResource(it) }.joinToString("\n\n")
    SettingsHelp(title, help) { open ->
        SettingsSection(title, showTitle = false) {
            SettingsLinkRow(PhosphorIcons.Regular.Info, title, onClick = open)
        }
    }
}

@Composable
private fun SettingsHelp(title: String, help: String, entry: @Composable (() -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    entry { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(help, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.help_close)) } },
        )
    }
}
