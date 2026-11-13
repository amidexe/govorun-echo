package com.govorun.echo.record

import com.govorun.echo.CapturePurpose
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
        val purpose = if (intent.action == AppEntry.SHORTCUT_COMMAND_ACTION) CapturePurpose.COMMAND else CapturePurpose.NOTE
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(AppEntry.recording(this, purpose = purpose).putExtra(AppEntry.WIDGET_RECORDING_EXTRA, true))
        } else {
            if (busy && purpose == CapturePurpose.NOTE && (state as? RecordingState.Recording)?.purpose != CapturePurpose.COMMAND) {
                RecordingBus.stopping.value = true
                startService(RecordingService.stopIntent(this))
            }
            startActivity(AppEntry.recording(this, existingOnly = busy, purpose = purpose))
        }
        finish()
    }
}
