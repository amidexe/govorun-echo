package com.govorun.echo.record

import com.govorun.echo.CapturePurpose
import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.ContinuousArchive
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.recognition.LiveSession
import org.json.JSONObject
import java.io.File

internal data class PreparedCapture(val noteId: String, val dir: File, val startedAtMs: Long, val appendBaseMs: Long?)

/** Same file transaction for phone and headset: input is ready before any existing note is changed. */
internal fun prepareCapture(context: Context, mode: String, appendTo: String?, tag: String?, purpose: CapturePurpose): PreparedCapture {
    val existing = appendTo?.let { File(NoteStore.root(context), it) }?.takeIf { NoteStore.isAppendable(it) }
    val (noteId, dir) = if (existing != null) existing.name to existing else NoteStore.newNote(context)
    val startedAtMs = System.currentTimeMillis()
    val base = existing?.let { NoteStore.durationOf(it) }
    if (existing != null) {
        NoteStore.pcmFile(existing).delete()
        ContinuousArchive.liveFile(existing).delete()
        File(existing, "live.txt").delete()
        NoteStore.updateMeta(existing) {
            remove(CapturePurpose.FIELD)
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
            .apply { if (purpose != CapturePurpose.NOTE) put(CapturePurpose.FIELD, purpose.id) }
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

/** Receives Govorun's speech callbacks and supplies the microphone loop with the current pause. */
internal class CaptureSpeech {
    @Volatile private var active = false
    @Volatile private var heardSpeech = false
    @Volatile private var readyAt = 0L
    @Volatile private var lastSpeechEndedAt = 0L

    fun reset() {
        active = false
        heardSpeech = false
        readyAt = 0L
        RecordingBus.speaking.value = false
        resume()
    }

    fun resume() { lastSpeechEndedAt = SystemClock.elapsedRealtime() }

    fun ready() { readyAt = SystemClock.elapsedRealtime() }

    fun interrupted() {
        readyAt = 0L
        changed(false)
    }

    fun changed(speaking: Boolean) {
        if (!speaking) resume()
        active = speaking
        if (speaking) heardSpeech = true
        RecordingBus.speaking.value = speaking
    }

    fun idleMs(session: LiveSession?, since: Long = lastSpeechEndedAt): Long {
        if (session == null || session.failed || active || RecordingBus.pausedAt.value != null) return 0L
        return SystemClock.elapsedRealtime() - since
    }

    fun commandStop(purpose: CapturePurpose, session: LiveSession?, dictating: Boolean): String? {
        if (purpose != CapturePurpose.COMMAND || readyAt == 0L || dictating) return null
        return StopPolicy.commandReason(heardSpeech, idleMs(session, maxOf(lastSpeechEndedAt, readyAt)))
    }
}
