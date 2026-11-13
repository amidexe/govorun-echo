package com.govorun.echo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.govorun.echo.R
import com.govorun.echo.commands.CommandStore
import com.govorun.echo.CapturePurpose
import com.govorun.echo.record.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** One capture shared by the app, the tile dialog and the shortcut/keyguard activity. */
internal class CaptureSession(private val context: Context, private val scope: CoroutineScope,
    private val existingOnly: Boolean, private val mode: String, alreadyAttempted: Boolean = false,
    private val onSettled: (failed: Boolean) -> Unit, private val onFinished: () -> Unit,
    private val onActivityRequired: (() -> Unit)? = null, focusWaitMs: Long = 5_000,
    val purpose: CapturePurpose = CapturePurpose.NOTE) {
    var attempted by mutableStateOf(alreadyAttempted)
        private set
    var failed by mutableStateOf(false)
        private set
    var result by mutableStateOf<CaptureResult?>(null)
        private set
    private var watching: Job? = null
    private val focusWait = scope.launch { delay(focusWaitMs); if (!attempted) { attempted = true; fail() } }

    /** Called only once the host's real window has focus. */
    fun begin() {
        if (watching != null || failed) return
        focusWait.cancel()
        if (!attempted) {
            attempted = true
            if (purpose == CapturePurpose.COMMAND) {
                if (RecordingBus.state.value is RecordingState.Recording || RecordingBus.state.value is RecordingState.Starting || RecordingBus.stopping.value) {
                    finishCommand(R.string.capture_command_busy)
                    return
                }
                if (CommandStore(context).commands().isEmpty()) {
                    finishCommand(R.string.capture_command_no_templates)
                    return
                }
            }
            if (!existingOnly && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                fail(); return
            }
            if (canStartTileCapture(RecordingBus.state.value, RecordingBus.stopping.value, existingOnly)) {
                Log.i("RecordTile", "event=tile_card_start")
                RecordingBus.state.value = RecordingState.Starting
                runCatching { ContextCompat.startForegroundService(context, RecordingService.startIntent(context, mode, purpose = purpose)) }
                    .onFailure { RecordingBus.failed("visible_window_start_failed") }
            }
        }
        if (RecordingBus.state.value is RecordingState.Idle) { fail(); return }
        watching = scope.launch {
            var state = withTimeoutOrNull(5_000) { RecordingBus.state.first { it !is RecordingState.Starting } }
            if (state == null) { stop(); fail(); return@launch }
            if (state is RecordingState.Recording) {
                Log.i("RecordTile", "event=tile_card_recording")
                state = RecordingBus.state.first { it !is RecordingState.Recording && it !is RecordingState.Starting }
            }
            if (state is RecordingState.Saved) {
                val command = waitForCaptureCommand(context, state) { result = it }
                if (command) delay(COMMAND_RESULT_SHOWN_MS)
                onSettled(false)
                onFinished()
            } else if (state is RecordingState.Discarded) onFinished()
            else if (purpose == CapturePurpose.COMMAND && state is RecordingState.Failed && state.reason == "too_short") {
                finishCommand(R.string.capture_command_no_speech)
            } else {
                fail()
                if (state is RecordingState.Failed && state.needsVisibleStart) onActivityRequired?.invoke()
            }
        }
    }

    private fun finishCommand(message: Int) {
        result = CaptureResult(context.getString(message), finished = true)
        watching = scope.launch {
            delay(COMMAND_RESULT_SHOWN_MS)
            onSettled(false)
            onFinished()
        }
    }

    private fun fail() {
        failed = true
        onSettled(true)
    }

    fun stop() {
        if (RecordingBus.stopping.value || (RecordingBus.state.value !is RecordingState.Recording && RecordingBus.state.value !is RecordingState.Starting)) return
        RecordingBus.stopping.value = true
        runCatching { context.startService(RecordingService.stopIntent(context)) }
            .onFailure {
                RecordingBus.stopping.value = false
                android.widget.Toast.makeText(context, com.govorun.echo.R.string.recording_entry_stop_failed, android.widget.Toast.LENGTH_LONG).show()
            }
    }

    fun close() { focusWait.cancel(); watching?.cancel(); watching = null }
}

private const val COMMAND_RESULT_SHOWN_MS = 4_000L
