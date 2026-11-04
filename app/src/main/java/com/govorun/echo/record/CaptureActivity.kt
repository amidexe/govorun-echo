package com.govorun.echo.record

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A user-invoked tile starts the microphone from a resumed activity, as Android requires.
 * Keep it alive until capture really starts (or fails), then return to the previous screen.
 */
class CaptureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        if (!canStartTileCapture(RecordingBus.state.value, RecordingBus.stopping.value)) {
            Log.i("RecordTile", "event=tile_capture_ignored")
            finishAndRemoveTask(); return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(com.govorun.echo.AppEntry.intent(this)); finishAndRemoveTask(); return
        }
        run {
            Log.i("RecordTile", "event=tile_capture_focused")
            RecordingBus.state.value = RecordingState.Starting
            runCatching { startForegroundService(RecordingService.startIntent(this, RecordingService.MODE_LONG)) }
                .onFailure { RecordingBus.failed("activity_start_failed") }
            scope.launch {
                val state = RecordingBus.state.first { it !is RecordingState.Starting }
                Log.i("RecordTile", "event=tile_capture_ready phase=${recordTilePhase(state, RecordingBus.stopping.value)}")
                if (state is RecordingState.Failed) {
                    android.widget.Toast.makeText(this@CaptureActivity, com.govorun.echo.R.string.tile_start_failed, android.widget.Toast.LENGTH_LONG).show()
                    startActivity(com.govorun.echo.AppEntry.intent(this@CaptureActivity))
                }
                finishAndRemoveTask()
            }
        }
    }

    override fun finishAndRemoveTask() {
        super.finishAndRemoveTask()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
