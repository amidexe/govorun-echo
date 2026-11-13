package com.govorun.echo.record

import com.govorun.echo.CapturePurpose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Single in-process view of the recording, shared by the service, hotkey and UI. */
sealed interface RecordingState {
    data object Idle : RecordingState

    data object Starting : RecordingState

    data class Recording(
        val noteId: String,
        val mode: String,
        val startedAtMs: Long,
        val purpose: CapturePurpose = CapturePurpose.NOTE,
    ) : RecordingState

    data class Saved(
        val noteId: String,
        val durationMs: Long,
        val stopReason: String,
        val purpose: CapturePurpose = CapturePurpose.NOTE,
    ) : RecordingState

    /** Cancelled with «Отмена»: a new recording went to the trash unrecognized, a continued note stayed as it was. */
    data object Discarded : RecordingState

    /** [needsVisibleStart] means Android refused a background microphone start. */
    data class Failed(
        val reason: String,
        val needsVisibleStart: Boolean = false,
    ) : RecordingState
}

object RecordingBus {
    val state = MutableStateFlow<RecordingState>(RecordingState.Idle)

    fun failed(reason: String) { state.value = RecordingState.Failed(reason); stopping.value = false }

    /** Why the text does not appear while recording ("govorun_9", "not_ready"...), or null while it flows; shown on the note. */
    val liveProblem = MutableStateFlow<String?>(null)

    /** Loudness of the latest microphone buffer, 0..1, for the live waveform. */
    val level = MutableStateFlow(0f)

    /** True from the tap on stop until the recording is saved: the stop buttons show a spinner at once instead of waiting for the work behind them. */
    val stopping = MutableStateFlow(false)

    /** Whether Govorun's detector hears speech right now; the screen stays awake while it does. */
    val speaking = MutableStateFlow(false)

    /** When the person paused the recording (wall clock); null while it runs. Nothing is written meanwhile. */
    val pausedAt = MutableStateFlow<Long?>(null)

    /** How long this recording has stood paused so far, so its clock shows only what was recorded. */
    val pausedMs = MutableStateFlow(0L)

    /** What the running recording holds at [now]: the time since [startedAtMs] without its pauses. */
    fun recordedMs(
        startedAtMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Long = ((pausedAt.value ?: now) - startedAtMs - pausedMs.value).coerceAtLeast(0L)

    private val liveTicks = MutableStateFlow(0L)

    /** Called when new live text has been written, so the list can show it at once. */
    fun liveTextArrived() {
        liveTicks.value++
    }

    /** Waits for the next live text, or [timeoutMs] at most. */
    suspend fun awaitLiveText(timeoutMs: Long) {
        val seen = liveTicks.value
        withTimeoutOrNull(timeoutMs) { liveTicks.first { it != seen } }
    }
}
