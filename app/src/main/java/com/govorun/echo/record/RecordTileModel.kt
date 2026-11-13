package com.govorun.echo.record

/** Startup and finalization are not an active microphone recording. */
internal enum class RecordTilePhase { Idle, Starting, Recording, Stopping }
internal fun canStartTileCapture(state: RecordingState, stopping: Boolean, existingOnly: Boolean = false) = !existingOnly && !stopping && state !is RecordingState.Starting && state !is RecordingState.Recording
internal fun recordTilePhase(state: RecordingState, stopping: Boolean): RecordTilePhase = when {
    stopping -> RecordTilePhase.Stopping
    state is RecordingState.Starting -> RecordTilePhase.Starting
    state is RecordingState.Recording -> RecordTilePhase.Recording
    else -> RecordTilePhase.Idle
}
