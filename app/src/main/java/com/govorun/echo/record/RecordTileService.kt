package com.govorun.echo.record

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.govorun.echo.R
import com.govorun.echo.AppEntry
import com.govorun.echo.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quick Settings tile: tap to start a long recording, tap again to stop. The tile is reachable
 * from the lock screen by pulling the shade down, like the flashlight.
 */
class RecordTileService : TileService() {
    private var scope: CoroutineScope? = null
    private val entry = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingStart: Job? = null

    override fun onStartListening() {
        scope?.cancel()
        render(RecordingBus.state.value, RecordingBus.stopping.value, RecordingBus.pausedAt.value != null)
        val listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = listening
        listening.launch {
            combine(RecordingBus.state, RecordingBus.stopping, RecordingBus.pausedAt) { state, stopping, paused -> Triple(state, stopping, paused != null) }.collectLatest { (state, stopping, paused) ->
                render(state, stopping, paused)
                while (state is RecordingState.Recording && !stopping) {
                    render(state, stopping, paused)
                    delay(1_000)
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val state = RecordingBus.state.value
        Log.i(TAG, "event=tile_click phase=${recordTilePhase(state, RecordingBus.stopping.value)}")
        if (RecordingBus.stopping.value) return
        if (state is RecordingState.Recording || state is RecordingState.Starting) {
            pendingStart?.cancel()
            RecordingBus.stopping.value = true
            runCatching { startService(RecordingService.stopIntent(this)) }.onFailure { RecordingBus.stopping.value = false; Log.w(TAG, "event=tile_stop_rejected", it) }
            return
        }
        RecordingBus.state.value = RecordingState.Starting
        val request = runCatching { startForegroundService(RecordingService.startIntent(this, RecordingService.MODE_LONG)) }
        if (request.isFailure) {
            val error = request.exceptionOrNull()!!
            Log.w(TAG, "event=tile_direct_rejected", error)
            val visible = error is android.app.ForegroundServiceStartNotAllowedException || error is SecurityException
            RecordingBus.state.value = RecordingState.Failed("tile_direct_start_failed", visible)
        }
        pendingStart = entry.launch {
            val result = withTimeoutOrNull(15_000) { RecordingBus.state.first { it !is RecordingState.Starting } }
            when {
                result is RecordingState.Failed && result.needsVisibleStart -> openRecordingCard()
                result is RecordingState.Failed -> showStartError()
                result == null && RecordingBus.state.value is RecordingState.Starting -> {
                    RecordingBus.failed("tile_start_timeout"); showStartError()
                }
            }
        }
    }

    private fun openRecordingCard() {
        Log.i(TAG, "event=tile_visible_fallback")
        runCatching { openActivity(AppEntry.recording(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { Log.w(TAG, "event=tile_card_rejected", it); showStartError() }
    }

    private fun showStartError() = android.widget.Toast.makeText(this, R.string.tile_start_failed, android.widget.Toast.LENGTH_LONG).show()

    override fun onDestroy() { entry.cancel(); scope?.cancel(); super.onDestroy() }

    private fun openActivity(open: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = if (open.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0) android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle() else null
            // A distinct token also replaces the animated launch options retained from an older installation.
            startActivityAndCollapse(PendingIntent.getActivity(this, if (options != null) 1 else 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, options))
        } else {
            // Lint flags every Intent call here whatever the version check; before Android 14 it is the only one there is.
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(open)
        }
    }

    private var lastPhase: RecordTilePhase? = null
    private fun render(state: RecordingState, stopping: Boolean, paused: Boolean) {
        val tile = qsTile ?: return
        val phase = recordTilePhase(state, stopping)
        if (phase != lastPhase) { lastPhase = phase; Log.i(TAG, "event=tile_state phase=$phase") }
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, if (phase == RecordTilePhase.Recording) R.drawable.ic_action_stop else R.drawable.ic_action_record)
        tile.state = when (phase) { RecordTilePhase.Recording -> Tile.STATE_ACTIVE; RecordTilePhase.Stopping -> Tile.STATE_UNAVAILABLE; else -> Tile.STATE_INACTIVE }
        tile.subtitle = when (phase) {
            RecordTilePhase.Starting -> getString(R.string.tile_starting)
            RecordTilePhase.Stopping -> getString(R.string.tile_saving)
            RecordTilePhase.Recording -> getString(if (paused) R.string.recording_bar_paused else R.string.recording_bar_on, formatDuration(RecordingBus.recordedMs((state as RecordingState.Recording).startedAtMs)))
            RecordTilePhase.Idle -> getString(R.string.tile_idle)
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "RecordTile"
    }
}
