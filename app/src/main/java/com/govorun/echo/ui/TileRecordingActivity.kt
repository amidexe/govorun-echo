package com.govorun.echo.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.govorun.echo.AppEntry
import com.govorun.echo.AppSettings
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingService
import com.govorun.echo.record.RecordingState
import com.govorun.echo.record.canStartTileCapture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A real recording card, like the volume-key entry: no library or transcript above the keyguard. */
class TileRecordingActivity : ComponentActivity() {
    private var attempted by mutableStateOf(false)
    private var watching: Job? = null
    private var failed by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        attempted = savedInstanceState?.getBoolean("attempted") ?: false
        setContent {
            EchoTheme(AppSettings.themeMode(this), AppSettings.dynamicColors(this)) {
                val state by RecordingBus.state.collectAsState()
                val stopping by RecordingBus.stopping.collectAsState()
                TileRecordingCard(if (attempted) state else RecordingState.Starting, stopping, failed, unlocked, ::stopRecording, ::finishAndRemoveTask, ::openApp)
            }
        }
    }

    override fun onResume() { super.onResume(); unlocked = !locked() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Reopening a still-visible card adopts its recording. It never starts a second one.
        if (failed || canStartTileCapture(RecordingBus.state.value, RecordingBus.stopping.value)) {
            attempted = false; failed = false; watching?.cancel(); watching = null
            if (window.decorView.hasWindowFocus()) begin()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { unlocked = !locked(); begin() }
    }

    private fun begin() {
        if (watching != null) return
        if (!attempted) {
            attempted = true
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                failed = true; return
            }
            if (canStartTileCapture(RecordingBus.state.value, RecordingBus.stopping.value)) {
                Log.i("RecordTile", "event=tile_card_start")
                RecordingBus.state.value = RecordingState.Starting
                runCatching { startForegroundService(RecordingService.startIntent(this, RecordingService.MODE_LONG)) }
                    .onFailure { RecordingBus.failed("tile_card_start_failed") }
            }
        }
        if (RecordingBus.state.value is RecordingState.Idle) { failed = true; return }
        watching = lifecycleScope.launch {
            var state = RecordingBus.state.first { it !is RecordingState.Starting }
            if (state is RecordingState.Recording) {
                Log.i("RecordTile", "event=tile_card_recording")
                state = RecordingBus.state.first { it !is RecordingState.Recording && it !is RecordingState.Starting }
            }
            if (state is RecordingState.Saved || state is RecordingState.Discarded) {
                delay(1_200); finishAndRemoveTask()
            } else failed = true
        }
    }

    private fun stopRecording() {
        if (RecordingBus.stopping.value) return
        RecordingBus.stopping.value = true
        runCatching { startService(RecordingService.stopIntent(this)) }
            .onFailure {
                RecordingBus.stopping.value = false
                android.widget.Toast.makeText(this, com.govorun.echo.R.string.recording_entry_stop_failed, android.widget.Toast.LENGTH_LONG).show()
            }
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun openApp() {
        // Recheck at the action, including when the phone locked while this card was visible.
        if (locked()) { unlocked = false; return }
        startActivity(AppEntry.intent(this))
        finishAndRemoveTask()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("attempted", attempted)
        super.onSaveInstanceState(outState)
    }
}
