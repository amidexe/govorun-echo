package com.govorun.echo.ui

import android.content.Intent
import android.provider.Settings
import com.govorun.echo.AppSettings

internal fun MainActivity.createSettingsActions(
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
    onHotkey: (Boolean) -> Unit,
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
        onManageFolder = { startActivity(FileExportActivity.intent(this, FileExportMode.Manage)) },
        onExportAll = { startActivity(FileExportActivity.intent(this, FileExportMode.Notes)) },
        onTransfer = { startActivity(Intent(this, TransferActivity::class.java)) },
        onHelp = { onHelp() },
        onStorageGuide = { onNavigate(Screen.StorageGuide) },
        onOpenTrash = { onNavigate(Screen.Trash) },
    )
