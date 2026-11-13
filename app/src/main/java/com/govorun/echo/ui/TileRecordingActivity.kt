package com.govorun.echo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.govorun.echo.AppEntry
import com.govorun.echo.AppSettings
import com.govorun.echo.CapturePurpose
import com.govorun.echo.record.*

/** A real recording card for shortcuts and keyguard; never an invisible microphone bootstrap. */
class TileRecordingActivity : ComponentActivity() {
    private var fromTile by mutableStateOf(false)
    private var closing by mutableStateOf(false)
    private var session by mutableStateOf<CaptureSession?>(null)
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        openSession(false)
        if (window.decorView.hasWindowFocus()) session?.begin()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareCaptureTransition()
        setShowWhenLocked(intent.getBooleanExtra(AppEntry.TILE_RECORDING_EXTRA, false))
        val canAskForMicrophone = intent.getBooleanExtra(AppEntry.WIDGET_RECORDING_EXTRA, false) ||
            (!intent.getBooleanExtra(AppEntry.TILE_RECORDING_EXTRA, false) &&
                CapturePurpose.of(intent.getStringExtra(CapturePurpose.FIELD)) == CapturePurpose.COMMAND)
        val needsMicrophone = canAskForMicrophone &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (savedInstanceState == null && needsMicrophone) {
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        } else if (savedInstanceState?.getBoolean("awaitingMicrophone") != true) {
            openSession(savedInstanceState?.getBoolean("attempted") ?: false)
        }
        setContent {
            EchoTheme(AppSettings.themeMode(this), AppSettings.dynamicColors(this), updateSystemBars = false) {
                val state by RecordingBus.state.collectAsState()
                val stopping by RecordingBus.stopping.collectAsState()
                session?.let { capture ->
                    TileRecordingCard(if (capture.attempted) state else RecordingState.Starting, stopping, capture.failed,
                        capture::stop, ::finishAndRemoveTask, capture.result, closeRequested = closing, systemTransition = fromTile,
                        purpose = capture.purpose, onOpenNote = { openCaptureNote(it) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (session == null) return
        val requested = CapturePurpose.of(intent.getStringExtra(CapturePurpose.FIELD))
        if (session?.purpose != requested || session?.failed == true ||
            canStartTileCapture(RecordingBus.state.value, RecordingBus.stopping.value)) {
            session?.close(); openSession(false)
        }
        if (window.decorView.hasWindowFocus()) session?.begin()
    }

    private fun openSession(attempted: Boolean) {
        closing = false
        fromTile = intent.getBooleanExtra(AppEntry.TILE_RECORDING_EXTRA, false)
        setShowWhenLocked(fromTile)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session = CaptureSession(this, lifecycleScope, intent.getBooleanExtra("recording_existing_only", false),
            intent.getStringExtra("recording_mode")?.takeIf { it == RecordingService.MODE_NOTE } ?: RecordingService.MODE_LONG, attempted,
            onSettled = { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }, onFinished = { closing = true },
            purpose = CapturePurpose.of(intent.getStringExtra(CapturePurpose.FIELD)))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) session?.begin()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("attempted", session?.attempted ?: false)
        outState.putBoolean("awaitingMicrophone", session == null)
        super.onSaveInstanceState(outState)
    }
}
