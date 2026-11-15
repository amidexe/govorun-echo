package com.govorun.echo.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.recognition.GovorunProvider
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.CapturePurpose
import com.govorun.echo.record.RecordingService
import com.govorun.echo.record.RecordingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Govorun's volume key on the lock screen: a hold opens this over the lock screen to start a note, the release opens it again to
 * stop. While «−» is held it stays, keeps the screen on and shows Echo's card «Записываю заметку» with the voice's level, so a dark
 * screen does not look like a stopped recording; the release turns it into «Заметка сохранена» for a moment. Only Govorun can open
 * it: the manifest requires Govorun's signature permission, and the permission must belong to the real Govorun Echo trusts.
 */
class VolumeKeyEntry : ComponentActivity() {
    private enum class Phase { Recording, Saving, Saved }

    private var phase by mutableStateOf(Phase.Recording)
    private var watch: Job? = null
    private var closing by mutableStateOf(false)
    private var result by mutableStateOf<CaptureResult?>(null)
    private var openingNote by mutableStateOf(false)
    private var captureEnded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareCaptureTransition()
        setShowWhenLocked(true)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val event = intent?.getStringExtra(EXTRA_EVENT)
        if (!fromGovorun()) {
            Log.w(TAG, "event=volume_key_refused")
            finishAndRemoveTask()
            return
        }
        Log.i(TAG, "event=volume_key_$event")
        val sent =
            runCatching {
                when (event) {
                    START -> startForegroundService(RecordingService.startIntent(this, RecordingService.MODE_NOTE, purpose = CapturePurpose.QUICK))
                    STOP -> startService(RecordingService.stopIntent(this))
                }
            }.onFailure { Log.w(TAG, "event=volume_key_failed", it) }.isSuccess
        when {
            !sent -> finishAndRemoveTask()
            event == START -> hold()
            event == STOP && watch != null -> phase = Phase.Saving
            else -> finishAndRemoveTask()
        }
    }

    /** While «−» is held: the screen stays on with Echo's card; it goes when the recording ends, however it ends. */
    private fun hold() {
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        phase = Phase.Recording
        closing = false
        captureEnded = false
        result = null
        setContent { EchoTheme(AppSettings.themeMode(this), AppSettings.dynamicColors(this), updateSystemBars = false) { HoldCard(phase) } }
        watch?.cancel()
        watch =
            lifecycleScope.launch {
                // Android may refuse the microphone: then there is nothing to hold the screen for.
                if (RecordingBus.state.first { it is RecordingState.Recording || it is RecordingState.Failed } is RecordingState.Recording) {
                    AppSettings.countVolumeKeyHold(this@VolumeKeyEntry)
                    val end = RecordingBus.state.first { it !is RecordingState.Recording && it !is RecordingState.Starting }
                    if (end is RecordingState.Saved) {
                        phase = Phase.Saving
                        val command = waitForCaptureCommand(this@VolumeKeyEntry, end) { result = it }
                        if (command) {
                            phase = Phase.Saved
                            delay(CAPTURE_RESULT_SHOWN_MS)
                        }
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                captureEnded = true
                // Releasing the volume key saves the note but must not cancel a pending unlock.
                if (!openingNote) closing = true
            }
        // A note by the key is short; should no release ever come, the card does not stay for good.
        lifecycleScope.launch {
            delay(HOLD_LIMIT_MS)
            finishAndRemoveTask()
        }
    }

    private fun fromGovorun(): Boolean {
        val govorun = GovorunProvider.resolve(this)?.packageName ?: return false
        val owner = runCatching { packageManager.getPermissionInfo("$govorun.permission.VOLUME_KEY", 0).packageName }.getOrNull()
        return owner == govorun
    }

    @Composable
    private fun HoldCard(phase: Phase) {
        val state by RecordingBus.state.collectAsState()
        val recording = state as? RecordingState.Recording
        val elapsed = captureElapsed(recording?.startedAtMs)
        val commandResult = result?.takeIf { phase == Phase.Saved && it.kind.isNotEmpty() }
        val label = when (phase) {
            Phase.Recording -> stringResource(R.string.capture_quick_speak)
            Phase.Saving -> result?.label ?: stringResource(R.string.volume_key_saving)
            Phase.Saved -> if (commandResult != null) {
                stringResource(if (commandResult.ok) R.string.capture_done else R.string.commands_result_title)
            } else result?.label.orEmpty()
        }
        val open = if (!openingNote && phase == Phase.Recording && recording?.purpose == CapturePurpose.QUICK) {
            {
                openingNote = true
                openCaptureNote(recording.noteId) {
                    openingNote = false
                    if (captureEnded) closing = true
                }
            }
        } else null
        CaptureStage(label, ::finishAndRemoveTask, detail = elapsed, result = commandResult, closeRequested = closing,
            animateEntrance = false, onOpen = open)
    }

    private companion object {
        const val TAG = "VolumeKeyEntry"
        const val EXTRA_EVENT = "com.govorun.api.extra.VOLUME_KEY_EVENT"
        const val START = "start"
        const val STOP = "stop"
        const val HOLD_LIMIT_MS = 10 * 60_000L
    }
}
