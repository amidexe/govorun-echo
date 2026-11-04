package com.govorun.echo.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CloudCheck
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.CloudWarning
import com.govorun.echo.R
import com.govorun.echo.backup.GoogleBackup
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsLinkRow

/**
 * «Копия в Google» in Settings → Хранение: when Android last took the copy, how much of Google's 25 MB it fills, and which notes
 * it holds — so the person sees it works, or sees that it has not happened, instead of trusting an invisible net.
 */
@Composable
internal fun GoogleBackupRow() {
    val context = LocalContext.current
    val state = remember { GoogleBackup.state(context) }
    var on by remember { mutableStateOf(GoogleBackup.enabled(context)) }
    var asking by remember { mutableStateOf(false) }
    SwitchRow(PhosphorIcons.Regular.Cloud, R.string.google_backup_title, R.string.google_backup_safety, on) {
        if (it) {
            GoogleBackup.setEnabled(context, true)
            on = true
        } else {
            asking = true
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.google_backup_off_title)) },
            text = { Text(stringResource(R.string.google_backup_off_text)) },
            confirmButton = { TextButton(onClick = { GoogleBackup.setEnabled(context, false); on = false; asking = false }) { Text(stringResource(R.string.google_backup_off_confirm)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.google_backup_off_cancel)) } },
        )
    }
    if (!on) return
    val now = System.currentTimeMillis()
    val notes =
        when {
            state.notesAll == 0 -> null
            state.notesIn >= state.notesAll -> pluralStringResource(R.plurals.google_backup_all, state.notesAll, state.notesAll)
            else -> stringResource(R.string.google_backup_newest, state.notesIn, state.notesAll)
        }
    // Three honest states: done (a tick), waiting for the first copy (a plain cloud: nothing is wrong yet), trouble (a warning).
    val (icon, line) =
        when {
            state.overQuota -> PhosphorIcons.Regular.CloudWarning to stringResource(R.string.google_backup_over)
            state.lastMs == 0L -> PhosphorIcons.Regular.Cloud to stringResource(R.string.google_backup_never)
            now - state.lastMs > GoogleBackup.STALE_MS ->
                PhosphorIcons.Regular.CloudWarning to stringResource(R.string.google_backup_stale, DateUtils.getRelativeTimeSpanString(state.lastMs, now, DateUtils.DAY_IN_MILLIS).toString())
            else ->
                PhosphorIcons.Regular.CloudCheck to stringResource(R.string.google_backup_last, DateUtils.getRelativeTimeSpanString(state.lastMs, now, DateUtils.MINUTE_IN_MILLIS).toString())
        }
    val summary = listOfNotNull(stringResource(R.string.google_backup_used, formatSize(state.bytes)), notes).joinToString("\n")
    // Waiting or silent too long: the row opens the phone's backup screen, where the copy can be taken now.
    if (state.lastMs == 0L || now - state.lastMs > GoogleBackup.STALE_MS) {
        SettingsLinkRow(icon, line, summary) { GoogleBackup.openPhoneBackup(context) }
    } else {
        SettingsInfoRow(icon, line, summary)
    }
}
