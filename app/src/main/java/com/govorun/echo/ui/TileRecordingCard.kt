package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.govorun.echo.R
import com.govorun.echo.CapturePurpose
import com.govorun.echo.record.RecordingState

@Composable
internal fun TileRecordingCard(
    state: RecordingState,
    stopping: Boolean,
    failed: Boolean,
    onStop: () -> Unit,
    onClose: () -> Unit,
    result: CaptureResult? = null,
    closeRequested: Boolean = false,
    systemTransition: Boolean = false,
    purpose: CapturePurpose = CapturePurpose.NOTE,
    onOpenNote: ((String) -> Unit)? = null,
    fromLockedTile: Boolean = false,
) {
    val recording = state as? RecordingState.Recording
    val activePurpose = recording?.purpose ?: purpose
    val commandResult = result?.takeIf { it.finished && it.kind.isNotEmpty() }
    val label = when {
        commandResult != null -> stringResource(if (commandResult.ok) R.string.capture_done else R.string.commands_result_title)
        result != null -> result.label
        failed || state is RecordingState.Failed -> stringResource(R.string.tile_start_failed)
        stopping -> stringResource(R.string.volume_key_saving)
        state is RecordingState.Saved -> stringResource(R.string.recording_channel)
        recording != null -> stringResource(when (activePurpose) {
            CapturePurpose.NOTE -> R.string.capture_recording
            CapturePurpose.QUICK -> R.string.capture_quick_speak
            CapturePurpose.COMMAND -> R.string.capture_listening_command
        })
        else -> stringResource(if (purpose == CapturePurpose.COMMAND) R.string.capture_command_starting else R.string.capture_starting)
    }
    val elapsed = captureElapsed(recording?.startedAtMs)
    val active = recording != null && !stopping && result == null
    val open = if (recording != null && active && activePurpose != CapturePurpose.COMMAND && onOpenNote != null) {
        { onOpenNote(recording.noteId) }
    } else null
    CaptureStage(label, onClose, detail = if (active) elapsed else "",
        result = commandResult, onStop = onStop.takeIf { active }, onOpen = open,
        closeRequested = closeRequested,
        animateEntrance = !systemTransition, showBackdrop = !systemTransition,
        fromLockedTile = fromLockedTile)
}
