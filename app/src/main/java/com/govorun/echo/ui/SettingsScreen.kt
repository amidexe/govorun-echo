package com.govorun.echo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.adamglin.phosphoricons.regular.ClockCountdown
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
import com.product.ui.SettingsInfoRow
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
    val retentionDays: Int,
    val noteCount: Int,
    val usageBytes: Long,
    val advanced: Boolean,
    val backup: com.govorun.echo.backup.BackupFolder.Status?,
)

internal class SettingsActions(
    val onBack: () -> Unit,
    val onOpenAccessibility: () -> Unit,
    val onHotkey: (Boolean) -> Unit,
    val onPlayNext: (Boolean) -> Unit,
    val onStamps: (Boolean) -> Unit,
    val onTheme: (Int) -> Unit,
    val onDynamicColors: (Boolean) -> Unit,
    val onRetention: (Int) -> Unit,
    val onAutoArchive: (Boolean) -> Unit,
    val onHelp: () -> Unit,
    val onExportAll: () -> Unit,
    val onPickBackup: () -> Unit,
    val onBackupNow: () -> Unit,
    val onBackupOff: () -> Unit,
    val onBackupRestore: () -> Unit,
    val onTransfer: () -> Unit,
    val onAddTile: () -> Unit,
    val onOpenCommands: () -> Unit,
    val onUnlockExperiments: () -> Unit,
    val onOpenExperiments: () -> Unit,
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
    var retentionSheet by remember { mutableStateOf(false) }
    var backupMore by remember { mutableStateOf(false) }
    val themeLabels = mapOf(AppSettings.THEME_SYSTEM to R.string.theme_system, AppSettings.THEME_LIGHT to R.string.theme_light, AppSettings.THEME_DARK to R.string.theme_dark)
    val context = androidx.compose.ui.platform.LocalContext.current
    val retentionLabel = { days: Int -> if (days == 0) context.getString(R.string.retention_never) else context.getString(R.string.retention_days, days) }
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
                    HelpSection(stringResource(R.string.settings_storage_title), R.string.help_storage_title, R.string.help_storage) {
                        SettingsInfoRow(
                            PhosphorIcons.Regular.Database,
                            stringResource(R.string.settings_usage_title),
                            trailing = {
                                Text(
                                    stringResource(R.string.settings_usage_value, state.noteCount, formatSize(state.usageBytes)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        ProductDivider()
                        AutoArchiveSetting(actions.onAutoArchive)
                        ProductDivider()
                        SettingsLinkRow(
                            PhosphorIcons.Regular.ClockCountdown,
                            stringResource(R.string.retention_title),
                            stringResource(R.string.retention_summary),
                            value = retentionLabel(state.retentionDays),
                            onClick = { retentionSheet = true },
                        )
                        ProductDivider()
                        SettingsLinkRow(PhosphorIcons.Regular.DownloadSimple, stringResource(R.string.settings_export_all), stringResource(R.string.settings_export_all_text), onClick = actions.onExportAll)
                    }
                }
            }
            if (tab == SettingsTab.STORAGE) {
                item {
                    HelpSection(stringResource(R.string.backup_section), R.string.help_backup_title, R.string.help_backup) {
                        SettingsLinkRow(PhosphorIcons.Regular.Desktop, stringResource(R.string.transfer_title), stringResource(R.string.transfer_short), onClick = actions.onTransfer)
                        ProductDivider()
                        GoogleBackupRow()
                        ProductDivider()
                        val backup = state.backup
                        if (backup?.folderName != null) {
                            SettingsInfoRow(
                                PhosphorIcons.Regular.FolderSimple,
                                backup.folderName,
                                stringResource(R.string.backup_copied, backup.copied, backup.total),
                            )
                            backup.lastError?.let {
                                ProductDivider()
                                SettingsInfoRow(
                                    PhosphorIcons.Regular.Warning,
                                    stringResource(R.string.backup_error, when (it) { "no_access" -> stringResource(R.string.backup_no_access); "copy_conflict" -> stringResource(R.string.backup_conflict); else -> stringResource(R.string.backup_write_failed) }),
                                )
                            }
                            ProductDivider()
                            if (backup.networkReason != null) NetworkNotice(backup.networkReason, Modifier.padding(horizontal = ProductTokens.Spacing.Section))
                            else SettingsLinkRow(PhosphorIcons.Regular.ArrowsClockwise, stringResource(R.string.backup_now), onClick = actions.onBackupNow)
                            ProductDivider()
                            SettingsLinkRow(PhosphorIcons.Regular.DotsThree, stringResource(R.string.backup_more), onClick = { backupMore = !backupMore })
                            if (backupMore) {
                                ProductDivider()
                                SettingsLinkRow(PhosphorIcons.Regular.DownloadSimple, stringResource(R.string.backup_restore), enabled = backup.networkReason == null, onClick = actions.onBackupRestore)
                                ProductDivider()
                                SettingsLinkRow(PhosphorIcons.Regular.FolderOpen, stringResource(R.string.backup_change), onClick = actions.onPickBackup)
                                ProductDivider()
                                SettingsLinkRow(PhosphorIcons.Regular.XCircle, stringResource(R.string.backup_off), onClick = actions.onBackupOff)
                            }
                        } else {
                            SettingsLinkRow(PhosphorIcons.Regular.FolderSimple, stringResource(R.string.backup_pick), stringResource(R.string.backup_text), onClick = actions.onPickBackup)
                        }
                    }
                }
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
    if (retentionSheet) {
        ProductChoiceSheet(
            title = stringResource(R.string.retention_title),
            selected = state.retentionDays,
            options = listOf(0, 30, 90, 365),
            optionLabel = { retentionLabel(it) },
            onSelected = actions.onRetention,
            onDismissRequest = { retentionSheet = false },
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
