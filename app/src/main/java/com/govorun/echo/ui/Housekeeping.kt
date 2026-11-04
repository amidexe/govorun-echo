package com.govorun.echo.ui

import android.content.Context
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingService
import com.govorun.echo.record.RecordingState

/** On every return to the app: notes past the kept days go, and notes left unfinished by a killed app are taken up again. */
internal fun Context.housekeepOnResume() {
    AppSettings.retentionDays(this).takeIf { it > 0 }?.let { days ->
        Thread { com.govorun.echo.notes.NoteAutoArchive.beforeRetention(this, days) }.start()
    }
    // Notes waiting for Govorun: its words brought up to date, its answers collected, what it no longer has handed in again.
    if (com.govorun.echo.smart.Smart.available) Thread { com.govorun.echo.smart.FairQueue.opened(this) }.start()
    val active = (RecordingBus.state.value as? RecordingState.Recording)?.noteId
    if (NoteStore.pendingNoteIds(this, active).isNotEmpty()) {
        runCatching { startForegroundService(RecordingService.resumeIntent(this)) }
    }
}
