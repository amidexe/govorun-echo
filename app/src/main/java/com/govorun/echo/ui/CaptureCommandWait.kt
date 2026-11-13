package com.govorun.echo.ui

import android.content.Context
import com.govorun.echo.commands.Command
import com.govorun.echo.commands.CommandPolicy
import com.govorun.echo.commands.CommandStore
import com.govorun.echo.commands.Matcher
import com.govorun.echo.notes.NoteChanges
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.record.RecordingState
import com.govorun.echo.CapturePurpose
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal const val CAPTURE_RESULT_SHOWN_MS = 8_000L

/** Only a short recording with an actual command phrase may keep the result window open. */
internal suspend fun waitForCaptureCommand(
    context: Context,
    saved: RecordingState.Saved,
    onResult: (CaptureResult) -> Unit,
): Boolean {
    val policy = CommandPolicy.current()
    if (saved.purpose == CapturePurpose.NOTE) return false
    val started = System.currentTimeMillis()
    val id = saved.noteId
    if (saved.purpose == CapturePurpose.QUICK) {
        if (saved.durationMs > policy.maximumRecordingSeconds * 1_000L) return false
        if (!recognizesQuickCommand(context, id)) return false
    }

    val expiresAt = withContext(Dispatchers.IO) {
        val dir = NoteStore.noteDir(context, id)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
        meta?.optJSONObject("command")?.optLong("expiresAtMs")?.takeIf { it > 0 }
            ?: ((meta?.optLong("recordedAtMs")?.takeIf { it > 0 } ?: started) + policy.timeoutSeconds * 1_000L)
    }
    val result = withTimeoutOrNull((expiresAt - System.currentTimeMillis()).coerceAtLeast(1L)) {
        NoteChanges.events.onSubscription { emit(Unit) }
            .map { withContext(Dispatchers.IO) { readCaptureResult(context, id) } }
            .first {
                if (!it.finished || it.commandId.isNotEmpty()) onResult(it)
                it.finished
            }
    }
    if (result != null) return result.commandId.isNotEmpty()
    onResult(withContext(Dispatchers.IO) { captureTimeout(context, id) })
    return true
}

private suspend fun recognizesQuickCommand(context: Context, id: String): Boolean {
    val commands = withContext(Dispatchers.IO) { CommandStore(context).commands() }
    if (commands.isEmpty()) return false
    val recognized = withTimeoutOrNull(600L) {
        repeat(8) { attempt ->
            val match = withContext(Dispatchers.IO) {
                val dir = NoteStore.noteDir(context, id)
                val final = File(dir, "text.txt").capturePrefix()?.takeIf(String::isNotBlank)
                val live = File(dir, "live.txt").capturePrefix()
                captureCommandMatch(final, live, commands)
            }
            if (match != null) return@withTimeoutOrNull match
            if (attempt < 7) delay(75L)
        }
        false
    } == true
    return recognized
}

/** A partial transcript may prove a prefix. Only the final transcript can settle an exact match or a miss. */
internal fun captureCommandMatch(final: String?, live: String?, commands: List<Command>): Boolean? = when {
    final != null -> Matcher.find(final, commands) != null
    live != null && Matcher.find(live, commands.filter { it.prefix }) != null -> true
    else -> null
}

private fun File.capturePrefix(): String? =
    runCatching { inputStream().use { it.readNBytes(4_096).toString(Charsets.UTF_8) } }.getOrNull()
