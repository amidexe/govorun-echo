package com.govorun.echo.ui

import android.app.KeyguardManager
import android.content.Context
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.commands.CommandStore
import com.govorun.echo.commands.Matcher
import com.govorun.echo.notes.CommandResult
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.CapturePurpose
import java.io.File
import org.json.JSONObject

internal data class CaptureResult(
    val label: String,
    val title: String = "",
    val detail: String = "",
    val finished: Boolean = false,
    val kind: String = "",
    val commandId: String = "",
    val ok: Boolean = true,
)

internal fun readCaptureResult(context: Context, noteId: String): CaptureResult {
    val dir = NoteStore.noteDir(context, noteId)
    val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }
        .recoverCatching { JSONObject(File(NoteStore.noteDir(context, noteId), "meta.json").readText()) }.getOrNull()
        ?: return CaptureResult(context.getString(R.string.commands_result_background), finished = true)
    meta.optJSONObject("command")?.let { command ->
        val receipt = CommandResult.read(command, context.getString(R.string.commands_smart_expired),
            context.getString(R.string.commands_smart_unknown))
        val yandex = receipt.kind == "yandex"
        val statusLabel = if (yandex && receipt.phase == "unknown")
            context.getString(R.string.commands_yandex_unconfirmed) else receipt.label
        // Built-in receipts preview the current utterance. Other recipes may return private data.
        val builtIn = receipt.kind in setOf("calendar", "reminder", "timer", "alarm")
        val hideContent = !builtIn && AppSettings.appLock(context) &&
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        val finished = receipt.phase in setOf("completed", "failed", "unknown")
        if (hideContent) {
            val status = when (receipt.phase) {
                "completed" -> context.getString(R.string.capture_done)
                "failed" -> context.getString(R.string.commands_smart_unsupported)
                "unknown" -> statusLabel
                else -> context.getString(R.string.commands_smart_processing)
            }
            return CaptureResult(status, finished = finished, commandId = noteId, ok = receipt.ok)
        }
        val reminder = when {
            receipt.reminderMinutes > 0 -> context.getString(R.string.commands_reminder_before, receipt.reminderMinutes)
            receipt.reminderMinutes == 0 -> context.getString(R.string.commands_reminder_at)
            else -> ""
        }
        val kind = when (receipt.kind) {
            "timer" -> R.string.commands_preset_timer
            "alarm" -> R.string.commands_preset_alarm
            "reminder" -> R.string.commands_preset_reminder
            "yandex" -> R.string.commands_yandex_title
            "command" -> R.string.widget_command_label
            else -> R.string.commands_preset_calendar
        }
        val label = when {
            receipt.ok && receipt.kind in setOf("calendar", "reminder") -> context.getString(R.string.capture_event_created)
            yandex && finished -> statusLabel
            else -> "${context.getString(kind)} · ${receipt.label}"
        }
        val title = if (yandex && finished) receipt.name else receipt.title
        return CaptureResult(
            label,
            title,
            listOf(receipt.detail, reminder).filter(String::isNotBlank).joinToString("\n"),
            finished,
            receipt.kind,
            noteId,
            receipt.ok,
        )
    }
    val state = meta.optString("state")
    if (CapturePurpose.read(meta) == CapturePurpose.COMMAND) {
        return CaptureResult(context.getString(R.string.commands_smart_processing))
    }
    if (state == NoteStore.STATE_FAILED) return CaptureResult(context.getString(R.string.commands_speech_failed), finished = true)
    if (state != NoteStore.STATE_DONE) return CaptureResult(context.getString(R.string.commands_smart_processing))
    val text = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
    if (Matcher.find(text, CommandStore(context).commands()) != null) {
        return CaptureResult(context.getString(R.string.commands_smart_processing))
    }
    return CaptureResult(context.getString(R.string.volume_key_saved), finished = true)
}

/** A timeout rereads the receipt: dispatch uncertainty must never be shown as a definite failure. */
internal fun captureTimeout(context: Context, noteId: String): CaptureResult = readCaptureResult(context, noteId)
    .takeIf { it.finished } ?: CaptureResult(context.getString(R.string.commands_smart_expired), finished = true)
