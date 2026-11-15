package com.govorun.echo.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun MainActivity.createSettingsActions(
    notes: () -> List<NoteStore.Entry>,
    scope: CoroutineScope,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
    onHotkey: (Boolean) -> Unit,
    onRetention: (Int) -> Unit,
    onChanged: () -> Unit,
    onHelp: () -> Unit,
    onUnlockExperiments: () -> Unit,
): SettingsActions =
    SettingsActions(
        onBack = onBack,
        onOpenAccessibility = {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onHotkey = {
            onHotkey(it)
            AppSettings.setHotkeyEnabled(this, it)
        },
        onStamps = {
            AppSettings.setStampsInText(this, it)
            onChanged()
        },
        onPlayNext = {
            AppSettings.setPlayNext(this, it)
            onChanged()
        },
        onTheme = {
            themeMode = it
            AppSettings.setThemeMode(this, it)
        },
        onDynamicColors = {
            dynamicColors = it
            AppSettings.setDynamicColors(this, it)
        },
        onAddTile = { requestRecordTile() },
        onOpenCommands = { onNavigate(Screen.Commands) },
        onOpenJournal = { onNavigate(Screen.Journal) },
        onOpenLicenses = { onNavigate(Screen.Licenses) },
        onOpenAbout = { onNavigate(Screen.About) },
        onUnlockExperiments = onUnlockExperiments,
        onOpenExperiments = { onNavigate(Screen.Experiments) },
        onExportAll = { exporter.exportNotes(notes().sortedBy { it.startedAtMs }) },
        onPickBackup = backupHost::pickFolder,
        onTransfer = { startActivity(Intent(this, TransferActivity::class.java)) },
        onBackupNow = backupHost::run,
        onBackupRestore = backupHost::restore,
        onBackupOff = backupHost::off,
        onHelp = { onHelp() },
        onRetention = {
            onRetention(it)
            AppSettings.setRetentionDays(this, it)
            scope
                .launch(Dispatchers.IO) {
                    com.govorun.echo.notes.NoteAutoArchive.beforeRetention(
                        this@createSettingsActions,
                        it,
                    )
                }
                .invokeOnCompletion { onChanged() }
        },
        onAutoArchive = {
            AppSettings.setAutoArchive(this, it)
            onChanged()
        },
    )
