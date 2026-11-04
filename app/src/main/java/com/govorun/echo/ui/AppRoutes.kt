package com.govorun.echo.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.govorun.echo.AppSettings
import com.govorun.echo.backup.BackupFolder
import com.govorun.echo.commands.Command
import com.govorun.echo.commands.CommandStore
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads derived settings only while Settings is visible; choices still belong to MainActivity. */
@Composable
internal fun SettingsRoute(
    noteCount: Int,
    backupRevision: Int,
    hotkeyEnabled: Boolean,
    themeMode: Int,
    dynamicColors: Boolean,
    retentionDays: Int,
    advancedMode: Boolean,
    actions: SettingsActions,
) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf(0L) }
    var backup by remember { mutableStateOf<com.govorun.echo.backup.BackupFolder.Status?>(null) }
    LaunchedEffect(backupRevision, noteCount) {
        backup =
            withContext(Dispatchers.IO) { com.govorun.echo.backup.BackupFolder.status(context) }
    }
    LaunchedEffect(noteCount) {
        usage = withContext(Dispatchers.IO) { NoteStore.usageBytes(NoteStore.root(context)) }
    }
    SettingsScreen(
        SettingsState(
            serviceEnabled = hotkeyServiceEnabled(context),
            hotkeyEnabled = hotkeyEnabled,
            playNext = AppSettings.playNext(context),
            stamps = AppSettings.stampsInText(context),
            themeMode = themeMode,
            dynamicColors = dynamicColors,
            retentionDays = retentionDays,
            noteCount = noteCount,
            usageBytes = usage,
            backup = backup,
            versionName =
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
            advanced =
                advancedMode ||
                    com.govorun.echo.commands.CommandStore(context).commands().isNotEmpty(),
        ),
        actions,
    )
}

/** First-run navigation; the owner of step and test state remains the app. */
@Composable
internal fun OnboardingRoute(
    state: OnboardingState,
    notes: NotesActions,
    settings: SettingsActions,
    onStep: (OnboardingStep) -> Unit,
    onFinish: () -> Unit,
    onSelfTest: () -> Unit,
) {
    val context = LocalContext.current
    val order = OnboardingStep.entries
    val actions =
        OnboardingActions(
            onBack = { onStep(order[(order.indexOf(state.step) - 1).coerceAtLeast(0)]) },
            onNext = {
                if (state.step == order.last()) onFinish()
                else onStep(order[order.indexOf(state.step) + 1])
            },
            onOpenUrl = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            },
            onAuthorizeGovorun = notes.onAuthorizeGovorun,
            onSelfTest = onSelfTest,
            onRequestPermissions = notes.onRequestMicrophone,
            onAddTile = settings.onAddTile,
            onOpenAccessibility = settings.onOpenAccessibility,
        )
    BackHandler(enabled = state.step != OnboardingStep.Welcome) { actions.onBack() }
    OnboardingScreen(state, actions)
}

/** Secondary routes share navigation callbacks and hold no app-wide state. */
@Composable
internal fun SecondaryScreens(
    screen: Screen,
    install: InstallInfo,
    licenseName: String,
    editingCommand: Command?,
    commandsVersion: Int,
    settings: SettingsActions,
    onNavigate: (Screen) -> Unit,
    onLicense: (String) -> Unit,
    onCommand: (Command?) -> Unit,
    onCommandsChanged: () -> Unit,
    onNotesChanged: () -> Unit,
) {
    val context = LocalContext.current
    when (screen) {
        Screen.Journal -> JournalScreen(onBack = { onNavigate(Screen.Settings) })
        Screen.About ->
            AboutScreen(
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
                install,
                { onNavigate(Screen.Settings) },
                settings.onVersionTap,
                { onNavigate(Screen.Licenses) },
            ) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            }
        Screen.Licenses -> LicensesScreen(onBack = { onNavigate(Screen.About) }, onOpen = onLicense)
        Screen.License -> LicenseScreen(licenseName, onBack = { onNavigate(Screen.Licenses) })
        Screen.Trash ->
            TrashScreen(onBack = { onNavigate(Screen.Notes) }, onChanged = onNotesChanged)
        Screen.Commands ->
            CommandsScreen(
                commands = remember(commandsVersion) { CommandStore(context).commands() },
                onBack = { onNavigate(Screen.Settings) },
                onOpen = onCommand,
                onRecipesChanged = onCommandsChanged,
            )
        Screen.CommandEdit ->
            CommandEditorScreen(
                existing = editingCommand,
                onBack = { onNavigate(Screen.Commands) },
                onSaved = {
                    onCommandsChanged()
                    onNavigate(Screen.Commands)
                },
            )
        Screen.Notes,
        Screen.Note,
        Screen.Settings -> error("Main screen passed as a secondary route")
    }
}
