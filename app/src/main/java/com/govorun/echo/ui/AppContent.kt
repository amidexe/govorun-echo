package com.govorun.echo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteTrash
import com.govorun.echo.recognition.RecognitionClient
import com.govorun.echo.recognition.RecognitionStatus
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingService
import com.govorun.echo.record.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MainActivity.App() {
    val ui = remember { NotesUiState(AppSettings.lastTag(this)) }
    LaunchedEffect(ui.filter.tag) { AppSettings.setLastTag(this@App, ui.filter.tag) }
    with(ui) {
        val recordingState by RecordingBus.state.collectAsState()
        val scope = rememberCoroutineScope()
        val client = remember { RecognitionClient(this@App) }
        LaunchedEffect(asked) {
            asked?.let {
                if (it.byUser)
                    com.govorun.echo.notes.NoteSeen.set(
                        NoteStore.root(this@App),
                        it.id,
                        false,
                    )
                openId = it.id
                screen = Screen.Note
                changes++
                asked = null
            }
        }
        var editingCommand by remember { mutableStateOf<com.govorun.echo.commands.Command?>(null) }
        var licenseName by remember { mutableStateOf("") }
        var commandsVersion by remember { mutableIntStateOf(0) }
        var advancedMode by remember { mutableStateOf(AppSettings.advanced(this@App)) }
        var versionTaps by remember { mutableIntStateOf(0) }
        var govorun by remember { mutableStateOf(GovorunState.Checking) }
        var govorunVersion by remember { mutableStateOf<String?>(null) }
        var install by remember { mutableStateOf(InstallInfo.cached(this@App)) }
        var onboarding by remember { mutableStateOf(!AppSettings.onboardingDone(this@App)) }
        var onboardingStep by remember { mutableStateOf(OnboardingStep.Welcome) }
        var selfTest by remember { mutableStateOf<String?>(null) }
        var selfTestRunning by remember { mutableStateOf(false) }
        var hotkeyEnabled by remember { mutableStateOf(AppSettings.hotkeyEnabled(this@App)) }
        var retention by remember { mutableIntStateOf(AppSettings.retentionDays(this@App)) }
        val snackbar = remember { SnackbarHostState() }
        val deleteBatch = remember { DeleteBatch() }
        LaunchedEffect(screen) {
            if (!deleteBatch.hasPendingUndo) snackbar.currentSnackbarData?.dismiss()
        }
        val paused by RecognitionStatus.pausedReason.collectAsState()
        val playing by player.state.collectAsState()
        val microphone =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val permissions =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                changes++
            }
        val pickFiles =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
                if (com.govorun.echo.record.FileImport.picked(this@App, it)) changes++
            }

        LaunchedEffect(Unit) {
            com.govorun.echo.notes.NoteImport.pendingReveal.collect { id ->
                if (id != null) {
                    tab = NotesTab.All
                    filter = NotesFilter()
                    query = null
                    selected = emptySet()
                    selectMode = false
                    screen = Screen.Notes
                    changes++
                }
            }
        }
        val lifecycle = visibleLifecycle()
        // Import creates its note on the service's IO thread, often after the picker's first reload.
        // Observe the store as well as local actions so an idle list cannot miss a new file.
        LaunchedEffect(Unit) {
            lifecycle.whileVisible {
                com.govorun.echo.notes.NoteChanges.events.collect { changes++ }
            }
        }
        LaunchedEffect(resumes, changes, recordingState, backupHost.changed) {
            // With the screen off nobody looks at the list: it is read again the moment the app
            // comes back.
            lifecycle.whileVisible {
                while (true) {
                    notes =
                        withContext(Dispatchers.IO) {
                            com.govorun.echo.notes.NoteAutoArchive.refresh(this@App)
                        }
                    hotkeyServiceOn = hotkeyServiceEnabled(this@App)
                    selected = selected.filter { id -> notes.any { it.id == id } }.toSet()
                    filter = filter.withoutStaleTag(notes, AppSettings.tags(this@App))
                    if (notes.any { it.isInProgress() }) {
                        RecordingBus.awaitLiveText(1_500)
                        continue
                    }
                    launch(Dispatchers.IO) {
                        val read = notes
                        runCatching {
                            com.govorun.echo.notes.NoteIndex.get(this@App).update(read)
                        }
                            .onFailure { android.util.Log.w("NoteIndex", "event=index_failed", it) }
                        com.govorun.echo.backup.Snapshot.write(this@App)
                        com.govorun.echo.notes.ActivityDiary.update(this@App, read)
                        com.govorun.echo.web.ComputerSession.offer()
                    }
                    break
                }
            }
        }
        LaunchedEffect(resumes, changes) {
            // Package lookups, signature hashing and Binder calls must stay off the main thread.
            val (version, state) =
                withContext(Dispatchers.IO) {
                    client.installedVersion().let { it to client.status() }
                }
            govorunVersion = version
            govorun = govorunStateOf(state, version)
        }
        // Notes that failed only because Govorun was missing or not yet allowed are retried once it
        // is ready.
        val waitingRecognition = notes.filter { it.waitsForGovorun() }.map { it.id }
        val recognitionRecovery = remember(resumes, govorunVersion) { com.govorun.echo.recognition.RecognitionRecovery() }
        LaunchedEffect(govorun, waitingRecognition, recognitionRecovery) {
            recognitionRecovery.take(govorun == GovorunState.Ready, waitingRecognition).forEach { id ->
                startForegroundService(RecordingService.transcribeIntent(this@App, id))
            }
        }
        LaunchedEffect(Unit) {
            install = withContext(Dispatchers.IO) { InstallInfo.refresh(this@App) }
        }

        val fairQueue by com.govorun.echo.smart.FairQueue.changes.collectAsState()
        LaunchedEffect(fairQueue) { if (fairQueue > 0) changes++ }
        val reload = { changes++ }
        val trashCount =
            remember(changes, screen) { NoteTrash.root(this@App).listFiles()?.size ?: 0 }
        val tags = remember(changes, notes) { TagHost.read(this@App, notes) }
        val notesActions =
            createNotesActions(
                ui,
                scope,
                client,
                { playing },
                {
                    onboardingStep = OnboardingStep.Govorun
                    onboarding = true
                },
                snackbar,
                deleteBatch,
                trashCount,
                tags,
                microphone,
                requestPermissions = {
                    permissions.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                    )
                },
                onAddFiles = { pickFiles.launch(arrayOf("audio/*", "video/*", "application/ogg")) },
                reload = { reload() },
                onHelp = {
                    onboardingStep = OnboardingStep.Welcome
                    onboarding = true
                },
            )
        notesActionsRef = notesActions
        val settingsActions =
            createSettingsActions(
                { notes },
                scope,
                onNavigate = { screen = it },
                onHotkey = { hotkeyEnabled = it },
                onRetention = { retention = it },
                onChanged = { changes++ },
                onHelp = {
                    onboardingStep = OnboardingStep.Welcome
                    onboarding = true
                },
                onVersionTap = {
                    if (++versionTaps >= UNLOCK_TAPS && !advancedMode)
                        advancedMode = unlockAdvanced()
                },
            )
        if (onboarding) {
            OnboardingRoute(
                OnboardingState(
                    onboardingStep,
                    govorun,
                    govorunVersion,
                    install,
                    microphone,
                    selfTest,
                    selfTestRunning,
                    hotkeyServiceOn,
                ),
                notesActions,
                settingsActions,
                onStep = { onboardingStep = it },
                onFinish = {
                    AppSettings.setOnboardingDone(this@App, true)
                    onboarding = false
                },
                onSelfTest = {
                    scope.launch {
                        selfTestRunning = true
                        selfTest = null
                        selfTest = runSelfTest(this@App, client)
                        selfTestRunning = false
                    }
                },
            )
            return
        }
        BackHandler(
            enabled =
                selected.isNotEmpty() ||
                    selectMode ||
                    screen != Screen.Notes ||
                    query != null ||
                    tab != NotesTab.All
        ) {
            when {
                selected.isNotEmpty() || selectMode -> {
                    selected = emptySet()
                    selectMode = false
                }
                screen == Screen.CommandEdit -> screen = Screen.Commands
                screen == Screen.License -> screen = Screen.Licenses
                screen == Screen.Commands || screen == Screen.Journal || screen == Screen.About ->
                    screen = Screen.Settings
                screen == Screen.Licenses -> screen = Screen.About
                screen != Screen.Notes -> screen = Screen.Notes
                tab != NotesTab.All && query == null -> {
                    tab = NotesTab.All
                    filter = NotesFilter()
                }
                else -> query = null
            }
        }
        // The sheet for taking notes out belongs to no screen: it opens over the list and over a
        // note alike.
        exporter.Sheet()
        EchoNavigation(notes, tab, notesActions) { drawer, onMenu ->
            AnimatedContent(
                screen,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "screen",
            ) { shown ->
                when (shown) {
                    Screen.Notes -> {
                        NotesScreen(
                            notes,
                            tab,
                            filter,
                            recordingState,
                            SetupState(microphone, govorun),
                            selected,
                            selectMode,
                            query,
                            paused,
                            playing,
                            snackbar,
                            notesActions,
                            drawer,
                            onMenu,
                        )
                        if (filtersOpen)
                            FilterSheet(filter, { filter = it }, { filtersOpen = false })
                    }
                    Screen.Note -> {
                        val note = notes.firstOrNull { it.id == openId }
                        if (note == null) {
                            // A note just made (typed, shared in) is on disk before the list is
                            // read again: it waits; a gone one leaves.
                            if (
                                openId?.let {
                                    java.io.File(NoteStore.root(this@App), it).isDirectory
                                } != true
                            )
                                LaunchedEffect(Unit) { screen = Screen.Notes }
                        } else {
                            NoteScreen(
                                note,
                                playing.takeIf { it.noteId == note.id },
                                recordingState is RecordingState.Recording ||
                                    recordingState is RecordingState.Starting,
                                notesActions,
                                snackbar,
                                query,
                            ) {
                                screen = Screen.Notes
                            }
                        }
                    }
                    Screen.Settings ->
                        SettingsRoute(
                            notes.size,
                            backupHost.changed,
                            hotkeyEnabled,
                            themeMode,
                            dynamicColors,
                            retention,
                            advancedMode,
                            settingsActions,
                        )
                    else ->
                        SecondaryScreens(
                            shown,
                            install,
                            licenseName,
                            editingCommand,
                            commandsVersion,
                            settingsActions,
                            onNavigate = { screen = it },
                            onLicense = {
                                licenseName = it
                                screen = Screen.License
                            },
                            onCommand = {
                                editingCommand = it
                                screen = Screen.CommandEdit
                            },
                            onCommandsChanged = { commandsVersion++ },
                            onNotesChanged = { changes++ },
                        )
                }
            }
        }
    }
}
