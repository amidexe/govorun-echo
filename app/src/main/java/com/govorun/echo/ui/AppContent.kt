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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.setValue
import com.govorun.echo.AppEntry
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteTrash
import com.govorun.echo.recognition.RecognitionClient
import com.govorun.echo.recognition.RecognitionStatus
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingService
import com.govorun.echo.record.RecordingState
import com.govorun.echo.record.SavedNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivity.App() {
    val ui = rememberSaveable(saver = NotesUiState.Saver) { NotesUiState(AppSettings.lastTag(this)) }
    val navigation = rememberSaveable(saver = AppNavigation.Saver) { AppNavigation() }
    val current = navigation.current
    DisposableEffect(current.key) { onDispose { noteConfirmation?.dismiss() } }
    val pageStates = rememberSaveableStateHolder()
    val previousKeys = remember { navigation.pages.mapTo(mutableSetOf()) { it.key } }
    val activeKeys = navigation.pages.mapTo(mutableSetOf()) { it.key }
    SideEffect {
        (previousKeys - activeKeys).forEach(pageStates::removeState)
        previousKeys.clear()
        previousKeys.addAll(activeKeys)
    }
    LaunchedEffect(ui.filter.tag) { AppSettings.setLastTag(this@App, ui.filter.tag) }
    with(ui) {
        // Keep the list itself alive while the animated navigation shows a note.
        val (tagList, tagHeader) = key(tab, filter.tag) {
            rememberLazyListState() to rememberTopAppBarState()
        }
        val tagOnly = query.isNullOrBlank() && filter == NotesFilter(tag = filter.tag)
        val (notesList, notesHeader) = if (tagOnly) tagList to tagHeader else key(tab, filter.tag) {
            rememberLazyListState() to rememberTopAppBarState()
        }
        var notesLoaded by remember { mutableStateOf(false) }
        val recordingState by RecordingBus.state.collectAsState()
        val scope = rememberCoroutineScope()
        val client = remember { RecognitionClient(this@App) }
        LaunchedEffect(pendingOpen) {
            val intent = pendingOpen ?: return@LaunchedEffect
            intent.getStringExtra(AppEntry.COMMAND_EXTRA)?.let { id ->
                navigation.openFromIntent(Screen.CommandHistory, id)
            } ?: SavedNotice.noteOf(intent)?.let { request ->
                val command = withContext(Dispatchers.IO) {
                    val dir = NoteStore.noteDir(this@App, request.id)
                    runCatching { org.json.JSONObject(java.io.File(dir, "meta.json").readText()).has("command") }.getOrDefault(false)
                }
                if (command) {
                    navigation.openFromIntent(Screen.CommandHistory, request.id)
                } else {
                    if (request.byUser) com.govorun.echo.notes.NoteSeen.set(NoteStore.root(this@App), request.id, false)
                    navigation.openFromIntent(Screen.Note, request.id)
                    changes++
                }
            }
            filtersOpen = false
            selected = emptySet()
            selectMode = false
            pendingOpen = null
        }
        var commandsVersion by remember { mutableIntStateOf(0) }
        var advancedMode by remember { mutableStateOf(AppSettings.advanced(this@App)) }
        var govorun by remember { mutableStateOf(GovorunState.Checking) }
        var govorunVersion by remember { mutableStateOf<String?>(null) }
        var install by remember { mutableStateOf(InstallInfo.cached(this@App)) }
        var onboarding by rememberSaveable { mutableStateOf(!AppSettings.onboardingDone(this@App)) }
        var onboardingStep by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
        var selfTest by remember { mutableStateOf<String?>(null) }
        var selfTestRunning by remember { mutableStateOf(false) }
        var hotkeyEnabled by remember { mutableStateOf(AppSettings.hotkeyEnabled(this@App)) }
        var retention by remember { mutableIntStateOf(AppSettings.retentionDays(this@App)) }
        val snackbar = remember { SnackbarHostState() }
        val deleteBatch = remember { DeleteBatch() }
        LaunchedEffect(current.key) {
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
                if (ImportActivity.picked(this@App, it)) changes++
            }

        LaunchedEffect(Unit) {
            com.govorun.echo.notes.NoteImport.pendingReveal.collect { id ->
                if (id != null) {
                    tab = NotesTab.All
                    filter = NotesFilter()
                    query = null
                    selected = emptySet()
                    selectMode = false
                    navigation.showNotes()
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
                    notesLoaded = true
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
            remember(changes, current.screen) { NoteTrash.root(this@App).listFiles()?.size ?: 0 }
        val tags = remember(changes, notes) { TagHost.read(this@App, notes) }
        val notesActions =
            createNotesActions(
                ui,
                navigation,
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
                onNavigate = { screen -> if (navigation.current == current) navigation.open(screen) },
                onBack = { navigation.back(current) },
                onHotkey = { hotkeyEnabled = it },
                onRetention = { retention = it },
                onChanged = { changes++ },
                onHelp = {
                    onboardingStep = OnboardingStep.Welcome
                    onboarding = true
                },
                onUnlockExperiments = {
                    if (!advancedMode) advancedMode = unlockAdvanced()
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
                onClose = if (AppSettings.onboardingDone(this@App)) ({ onboarding = false }) else null,
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
                    current.screen != Screen.Notes ||
                    query != null ||
                    tab != NotesTab.All
        ) {
            when {
                current.screen != Screen.Notes -> navigation.back(current)
                selected.isNotEmpty() || selectMode -> {
                    selected = emptySet()
                    selectMode = false
                }
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
        EchoNavigation(notes, tab, notesActions, current.key) { drawer, onMenu ->
            AnimatedContent(
                current,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "screen",
            ) { shown ->
                pageStates.SaveableStateProvider(shown.key) {
                    when (shown.screen) {
                        Screen.Notes -> {
                            NotesScreen(
                                notes,
                                notesLoaded,
                                notesList,
                                notesHeader,
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
                            if (shown == current && filtersOpen)
                                FilterSheet(filter, { filter = it }, { filtersOpen = false })
                        }
                        Screen.Note -> {
                            val note = notes.firstOrNull { it.id == shown.argument }
                            if (note == null) {
                                // A note just made (typed, shared in) is on disk before the list is
                                // read again: it waits; a gone one leaves.
                                if (
                                    shown.argument?.let {
                                        java.io.File(NoteStore.root(this@App), it).isDirectory
                                    } != true
                                )
                                    LaunchedEffect(Unit) { navigation.back(shown) }
                            } else {
                                NoteScreen(
                                    note,
                                    playing.takeIf { it.noteId == note.id },
                                    recordingState is RecordingState.Recording ||
                                        recordingState is RecordingState.Starting,
                                    notesActions,
                                    snackbar,
                                    shown == current,
                                    shown.query,
                                ) {
                                    navigation.back(shown)
                                }
                            }
                        }
                        Screen.Settings -> {
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
                        }
                        else ->
                            SecondaryScreens(
                                shown,
                                install,
                                advancedMode,
                                commandsVersion,
                                settingsActions,
                                onNavigate = { screen -> if (navigation.current == shown) navigation.open(screen) },
                                onBack = { navigation.back(shown) },
                                onLicenses = {
                                    if (navigation.current == shown) navigation.open(Screen.Licenses)
                                },
                                onLicense = {
                                    if (navigation.current == shown) navigation.open(Screen.License, it)
                                },
                                onCommand = {
                                    if (navigation.current == shown) navigation.open(Screen.CommandEdit, it?.id)
                                },
                                onTemplate = {
                                    if (navigation.current == shown) navigation.open(Screen.CommandCreate, it.name)
                                },
                                onCommandsChanged = { commandsVersion++ },
                                onNotesChanged = { changes++ },
                            )
                    }
                }
            }
        }
    }
}
