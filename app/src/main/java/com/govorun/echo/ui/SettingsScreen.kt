package com.govorun.echo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.Certificate
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Database
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.Microphone
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.Flask
import com.adamglin.phosphoricons.regular.FolderOpen
import com.adamglin.phosphoricons.regular.FolderSimple
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Desktop
import com.adamglin.phosphoricons.regular.Lightning
import com.adamglin.phosphoricons.regular.LockSimple
import com.adamglin.phosphoricons.regular.MicrophoneSlash
import com.adamglin.phosphoricons.regular.Palette
import com.adamglin.phosphoricons.regular.Question
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.SquaresFour
import com.adamglin.phosphoricons.regular.ShieldCheck
import com.adamglin.phosphoricons.regular.Swatches
import com.adamglin.phosphoricons.regular.Tag
import com.adamglin.phosphoricons.regular.Warning
import com.adamglin.phosphoricons.regular.Clock
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.ChartBar
import com.adamglin.phosphoricons.regular.Note
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.XCircle
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.product.ui.ProductChoiceSheet
import com.product.ui.ProductDivider
import com.product.ui.ProductSectionItem
import com.product.ui.ProductSettingsShell
import com.product.ui.ProductTokens
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsSection
import com.product.ui.SettingsSwitchRow

internal class SettingsState(
    val serviceEnabled: Boolean,
    val hotkeyEnabled: Boolean,
    val playNext: Boolean,
    val stamps: Boolean,
    val themeMode: Int,
    val dynamicColors: Boolean,
    val noteIds: Set<String>?,
    val advanced: Boolean,
)

internal class SettingsActions(
    val onBack: () -> Unit,
    val onOpenAccessibility: () -> Unit,
    val onHotkey: (Boolean) -> Unit,
    val onPlayNext: (Boolean) -> Unit,
    val onStamps: (Boolean) -> Unit,
    val onTheme: (Int) -> Unit,
    val onDynamicColors: (Boolean) -> Unit,
    val onStorageGuide: () -> Unit,
    val onOpenTrash: () -> Unit,
    val onHelp: () -> Unit,
    val onExportAll: () -> Unit,
    val onTransfer: () -> Unit,
    val onAddTile: () -> Unit,
    val onOpenCommands: () -> Unit,
    val onUnlockExperiments: () -> Unit,
    val onOpenExperiments: () -> Unit,
    val onManageFolder: () -> Unit,
    val onOpenJournal: () -> Unit,
    val onOpenLicenses: () -> Unit,
    val onOpenAbout: () -> Unit,
)

private enum class SettingsTab(
    val title: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    RECORD(R.string.settings_tab_record, PhosphorIcons.Regular.Microphone),
    NOTES(R.string.settings_tab_notes, PhosphorIcons.Regular.Note),
    STORAGE(R.string.settings_tab_storage, PhosphorIcons.Regular.Database),
    // The app's own tab stays the last one, on the right, as in Govorun.
    APP(R.string.settings_tab_app, PhosphorIcons.Regular.GearSix),
}

@Composable
internal fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
) {
    var themeSheet by remember { mutableStateOf(false) }
    val themeLabels = mapOf(AppSettings.THEME_SYSTEM to R.string.theme_system, AppSettings.THEME_LIGHT to R.string.theme_light, AppSettings.THEME_DARK to R.string.theme_dark)
    val context = androidx.compose.ui.platform.LocalContext.current
    var tabName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsTab.RECORD.name) }
    val tab = SettingsTab.valueOf(tabName)
    val tabs = SettingsTab.entries.map { ProductSectionItem(it, stringResource(it.title), it.icon) }
    ProductSettingsShell( title = stringResource(R.string.screen_settings), items = tabs, selected = tab, onSelected = { tabName = it.name }, onBack = actions.onBack) {
        run {
            if (tab == SettingsTab.RECORD) {
                item {
                    SettingsSection("", showTitle = false) {
                        MicrophoneSetting()
                        GovorunKeyEntry(actions.onOpenAccessibility)
                        if (com.govorun.echo.BuildConfig.HAS_HOTKEY) {
                            if (state.serviceEnabled) {
                                SwitchRow(PhosphorIcons.Regular.SpeakerHigh, R.string.settings_hotkey_switch, R.string.settings_hotkey_switch_text, state.hotkeyEnabled, actions.onHotkey)
                            } else {
                                HotkeyEntry(actions.onOpenAccessibility)
                            }
                        }
                        SettingsLinkRow(PhosphorIcons.Regular.SquaresFour, stringResource(R.string.settings_tile_title), onClick = actions.onAddTile)
                        SilenceStopSetting()
                        EmptyNotesSetting()
                    }
                }
                // What Echo lets you feel and hear, and what it puts in the shade: apart from how a recording is made.
                item {
                    SettingsSection(stringResource(R.string.settings_group_signals)) {
                        RecordingTapsSetting()
                        SavedNoticeSetting()
                        ActionSoundsSetting()
                    }
                }
                item { RecordingHelpRow() }
            }
            if (tab == SettingsTab.NOTES) {
                item {
                    SettingsSection(stringResource(R.string.settings_play_title)) {
                        SwitchRow(PhosphorIcons.Regular.Play, R.string.settings_next_title, R.string.settings_next_text, state.playNext, actions.onPlayNext)
                        PlayScreenSetting()
                        RaiseToListenSetting()
                    }
                }
                item {
                    SettingsSection("", showTitle = false) {
                        SearchOrderSetting()
                        SwitchRow(PhosphorIcons.Regular.Clock, R.string.settings_stamps_title, R.string.settings_stamps_text, state.stamps, actions.onStamps)
                    }
                }
            }
            if (tab == SettingsTab.STORAGE) {
                item {
                    SettingsSection(stringResource(R.string.settings_storage_title)) {
                        StorageUsageSetting(state.noteIds, actions.onOpenTrash, actions.onTransfer)
                        ProductDivider()
                        InactiveNotesSetting()
                        ProductDivider()
                        SettingsLinkRow(PhosphorIcons.Regular.Info, stringResource(R.string.storage_guide_title), onClick = actions.onStorageGuide)
                    }
                }
            }
            if (tab == SettingsTab.STORAGE) {
                item {
                    SettingsSection(stringResource(R.string.backup_section)) {
                        SettingsLinkRow(PhosphorIcons.Regular.Desktop, stringResource(R.string.transfer_title), stringResource(R.string.transfer_short), onClick = actions.onTransfer)
                        ProductDivider()
                        SettingsLinkRow(PhosphorIcons.Regular.DownloadSimple, stringResource(R.string.settings_export_all), stringResource(R.string.settings_export_all_text), onClick = actions.onExportAll)
                        val revision by com.govorun.echo.backup.FolderExports.changes.collectAsState()
                        if (remember(revision) { com.govorun.echo.backup.FolderExports.lastTree(context) } != null) {
                            ProductDivider()
                            SettingsLinkRow(PhosphorIcons.Regular.FolderOpen, stringResource(R.string.folder_manage),
                                stringResource(if (com.govorun.echo.backup.FolderExports.active(context)) R.string.folder_enabled else R.string.folder_paused), onClick = actions.onManageFolder)
                        }
                    }
                }
                item { SettingsSection(stringResource(R.string.android_backup_section)) { GoogleBackupRow() } }
            }
            if (tab == SettingsTab.APP) {
                item {
                    SettingsSection(stringResource(R.string.settings_appearance_title)) {
                        SettingsLinkRow(
                            PhosphorIcons.Regular.Palette,
                            stringResource(R.string.settings_theme_title),
                            value = stringResource(themeLabels.getValue(state.themeMode)),
                            onClick = { themeSheet = true },
                        )
                        SwitchRow(PhosphorIcons.Regular.Swatches, R.string.settings_dynamic_title, R.string.settings_dynamic_text, state.dynamicColors, actions.onDynamicColors)
                    }
                }
            }
            if (tab == SettingsTab.APP && com.govorun.echo.smart.Smart.available) {
                item {
                    // What needs the network, Govorun's итоги and fair copy; a build without them only says what is coming.
                    SettingsSection(stringResource(R.string.fair_settings_title)) {
                        FairSettings()
                    }
                }
            }
            if (tab == SettingsTab.APP) item { NetworkSettings() }
            if (tab == SettingsTab.APP) {
                item {
                    SettingsSection("", showTitle = false) {
                        AppLockSetting()
                        SettingsLinkRow(PhosphorIcons.Regular.Lightning, stringResource(R.string.commands_title),
                            stringResource(R.string.commands_summary), onClick = actions.onOpenCommands)
                    }
                }
            }
            if (tab == SettingsTab.APP) {
                item {
                    SettingsSection("", showTitle = false) {
                        SettingsLinkRow(PhosphorIcons.Regular.Question, stringResource(R.string.settings_help),
                            stringResource(R.string.settings_help_text), onClick = actions.onHelp)
                        SettingsLinkRow(PhosphorIcons.Regular.FileText, stringResource(R.string.journal_title),
                            stringResource(R.string.journal_summary), onClick = actions.onOpenJournal)
                        if (state.advanced) {
                            SettingsLinkRow(PhosphorIcons.Regular.Flask, stringResource(R.string.settings_advanced),
                                onClick = actions.onOpenExperiments)
                        }
                        SettingsLinkRow(PhosphorIcons.Regular.Info, stringResource(R.string.about_title),
                            value = appVersionLabel,
                            onClick = actions.onOpenAbout)
                    }
                }
            }
        }
    }
    if (themeSheet) {
        ProductChoiceSheet(
            title = stringResource(R.string.settings_theme_title),
            selected = state.themeMode,
            options = themeLabels.keys.toList(),
            optionLabel = { context.getString(themeLabels.getValue(it)) },
            onSelected = actions.onTheme,
            onDismissRequest = { themeSheet = false },
        )
    }

}

@Composable
internal fun SwitchRow(
    icon: ImageVector,
    title: Int,
    text: Int?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) = SettingsSwitchRow(
    title = stringResource(title),
    summary = text?.let { stringResource(it) },
    checked = checked,
    onCheckedChange = onChange,
    icon = icon,
)
