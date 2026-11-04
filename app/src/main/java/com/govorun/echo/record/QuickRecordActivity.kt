package com.govorun.echo.record

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.govorun.echo.AppEntry

/**
 * Target of the launcher shortcut "Start recording": starts a long recording without showing
 * the app, or opens the app when the microphone is not allowed yet.
 */
class QuickRecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = RecordingBus.state.value
        val busy = state is RecordingState.Recording || state is RecordingState.Starting
        when {
            busy -> startService(RecordingService.stopIntent(this))
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                startForegroundService(RecordingService.startIntent(this, RecordingService.MODE_LONG))
            else -> startActivity(AppEntry.intent(this))
        }
        finish()
    }
}
