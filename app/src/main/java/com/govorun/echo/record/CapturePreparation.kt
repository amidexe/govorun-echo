package com.govorun.echo.record

import android.content.Context
import android.os.BatteryManager
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.ContinuousArchive
import com.govorun.echo.notes.NoteStore
import org.json.JSONObject
import java.io.File

internal data class PreparedCapture(val noteId: String, val dir: File, val startedAtMs: Long, val appendBaseMs: Long?)

/** Same file transaction for phone and headset: input is ready before any existing note is changed. */
internal fun prepareCapture(context: Context, mode: String, appendTo: String?, tag: String?): PreparedCapture {
    val existing = appendTo?.let { File(NoteStore.root(context), it) }?.takeIf { NoteStore.isAppendable(it) }
    val (noteId, dir) = if (existing != null) existing.name to existing else NoteStore.newNote(context)
    val startedAtMs = System.currentTimeMillis()
    val base = existing?.let { NoteStore.durationOf(it) }
    if (existing != null) {
        NoteStore.pcmFile(existing).delete()
        ContinuousArchive.liveFile(existing).delete()
        File(existing, "live.txt").delete()
        NoteStore.updateMeta(existing) {
            put("state", NoteStore.STATE_RECORDING)
            put("appendFromMs", base)
            put("appendAtMs", startedAtMs)
            put("archivedBytes", 0L)
        }
    } else {
        NoteStore.writeMeta(dir, JSONObject()
            .put("mode", mode).put("unseen", true)
            .put("startedAtMs", startedAtMs)
            .put("state", NoteStore.STATE_RECORDING)
            .apply { tag?.let { put("tags", org.json.JSONArray().put(it)) } })
    }
    return PreparedCapture(noteId, dir, startedAtMs, base)
}

internal fun recordingEnvironment(context: Context): NoteCapture.Environment {
    val battery = context.getSystemService(BatteryManager::class.java)
    return NoteCapture.Environment(
        batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1,
        charging = battery?.isCharging == true,
        freeBytes = context.filesDir.usableSpace,
        silenceLimitMs = AppSettings.silenceStopMs(context),
    )
}

/** How long Govorun's speech detector has heard nothing; 0 while it is not listening. */
internal fun recordingIdleMs(session: com.govorun.echo.recognition.LiveSession?, speechActive: Boolean, lastSpeechEndedAt: Long): Long {
    if (session == null || session.failed || speechActive || RecordingBus.pausedAt.value != null) return 0L
    return android.os.SystemClock.elapsedRealtime() - lastSpeechEndedAt
}
