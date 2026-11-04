package com.govorun.echo.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.govorun.echo.R
import com.product.ui.SettingsLinkRow

/**
 * The one place that leads to the volume-button recording, for people who want it. Nothing in the first start mentions it:
 * the option needs the system's "Accessibility" switch and the microphone permission, so it is explained here in plain words
 * and the system settings open only after the person agrees.
 */
@Composable
internal fun HotkeyEntry(onOpenAccessibility: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    SettingsLinkRow(
        PhosphorIcons.Regular.SpeakerHigh,
        stringResource(R.string.hotkey_entry_title),
        stringResource(R.string.hotkey_entry_summary),
        onClick = { asking = true },
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.hotkey_consent_title)) },
            text = { Text(stringResource(R.string.hotkey_consent_text)) },
            confirmButton = { TextButton(onClick = { asking = false; onOpenAccessibility() }) { Text(stringResource(R.string.hotkey_consent_open)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.hotkey_consent_cancel)) } },
        )
    }
}
