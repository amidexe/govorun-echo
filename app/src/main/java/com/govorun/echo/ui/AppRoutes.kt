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
import com.govorun.echo.commands.CommandTemplate
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
    val networkRevision by com.govorun.echo.NetworkPolicy.changes.collectAsState()
    LaunchedEffect(backupRevision, noteCount, networkRevision) {
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
            advanced = advancedMode,
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
    onClose: (() -> Unit)?,
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
    BackHandler(enabled = state.step != OnboardingStep.Welcome || onClose != null) {
        if (state.step == OnboardingStep.Welcome) onClose?.invoke() else actions.onBack()
    }
    OnboardingScreen(state, actions)
}

/** Secondary routes share navigation callbacks and hold no app-wide state. */
@Composable
internal fun SecondaryScreens(
    page: AppPage,
    install: InstallInfo,
    experimentsUnlocked: Boolean,
    commandsVersion: Int,
    settings: SettingsActions,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onLicense: (String) -> Unit,
    onCommand: (Command?) -> Unit,
    onTemplate: (CommandTemplate) -> Unit,
    onCommandsChanged: () -> Unit,
    onNotesChanged: () -> Unit,
) {
    val context = LocalContext.current
    when (page.screen) {
        Screen.CommandHistory -> CommandHistoryScreen(onBack, page.argument)
        Screen.Journal -> JournalScreen(onBack = onBack)
        Screen.Experiments -> ExperimentalSettingsScreen(
            onBack = onBack,
            onPickFolder = settings.onPickBackup,
        )
        Screen.About ->
            AboutScreen(
                install,
                experimentsUnlocked,
                onBack,
                settings.onUnlockExperiments,
                onLicenses,
            ) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            }
        Screen.Licenses -> LicensesScreen(onBack = onBack, onOpen = onLicense)
        Screen.License -> LicenseScreen(page.argument.orEmpty(), onBack = onBack)
        Screen.Trash ->
            TrashScreen(onBack = onBack, onChanged = onNotesChanged)
        Screen.Commands ->
            CommandsScreen(
                commands = remember(commandsVersion) { CommandStore(context).commands() },
                onBack = onBack,
                onOpen = onCommand,
                onTemplate = onTemplate,
                onHistory = { onNavigate(Screen.CommandHistory) },
                onAdvanced = { onNavigate(Screen.AdvancedCommands) },
            )
        Screen.AdvancedCommands ->
            AdvancedCommandsScreen(
                commands = remember(commandsVersion) { CommandStore(context).commands() },
                onBack = onBack,
                onOpen = onCommand,
                onRecipesChanged = onCommandsChanged,
            )
        Screen.CommandCreate ->
            CommandTemplateEditor(CommandTemplate.valueOf(checkNotNull(page.argument)), onBack) {
                onCommandsChanged()
                onBack()
            }
        Screen.CommandEdit -> {
            val command = remember(page.argument, commandsVersion) {
                page.argument?.let { id -> CommandStore(context).commands().firstOrNull { it.id == id } }
            }
            CommandEditorScreen(
                existing = command,
                onBack = onBack,
                onSaved = {
                    onCommandsChanged()
                    onBack()
                },
            )
        }
        Screen.Notes,
        Screen.Note,
        Screen.Settings -> error("Main screen passed as a secondary route")
    }
}
