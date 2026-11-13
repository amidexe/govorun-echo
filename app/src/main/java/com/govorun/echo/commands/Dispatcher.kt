package com.govorun.echo.commands

import android.content.Context
import android.os.VibrationEffect
import android.util.Log
import android.os.VibratorManager
import com.govorun.echo.Journal
import com.govorun.echo.R
import com.govorun.echo.notes.CommandHistory
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.CAPTURE_STOP_CAP
import com.govorun.echo.CapturePurpose
import java.io.File
import org.json.JSONObject

/** Only a capture that opted into commands may interpret its recognized text as an action. */
object Dispatcher {
    fun onNoteDoneAsync(
        context: Context,
        noteId: String,
        force: Boolean = false,
        onOrdinaryNote: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext
        // Claim and separate the command before callers snapshot or export ordinary notes.
        val claimed = runCatching { resolve(app, noteId, force) }
        if (claimed.isFailure) {
            Log.w("Echo", "event=command_claim_failed")
            return
        }
        val pending = claimed.getOrNull()
        Thread {
            runCatching {
                if (pending == null) {
                    val dir = NoteStore.noteDir(app, noteId)
                    val handled = runCatching { JSONObject(File(dir, "meta.json").readText()).has("command") }.getOrDefault(false)
                    if (!handled) onOrdinaryNote?.invoke()
                }
                else run(app, noteId, pending)
            }.onFailure { Log.w("Echo", "event=command_failed type=${it.javaClass.simpleName}") }
        }.start()
    }

    private data class Pending(val command: Command, val action: Action, val vars: Map<String, String>, val capturedAtMs: Long, val expiresAtMs: Long)

    @Synchronized
    private fun resolve(
        context: Context,
        noteId: String,
        force: Boolean,
    ): Pending? {
        val dir = NoteStore.noteDir(context, noteId)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return null
        val purpose = CapturePurpose.read(meta)
        val previous = meta.optJSONObject("command")
        if (!mayMatch(purpose, previous != null, force)) return null
        if (force && previous != null) {
            val active = previous.optString("phase") == "processing" && System.currentTimeMillis() - previous.optLong("requestedAtMs") < CommandPolicy.current().timeoutSeconds * 1000L
            if (previous.optBoolean("ok") || previous.optString("phase") in setOf("dispatching", "unknown") || active) return null
        }
        val store = CommandStore(context)
        val text = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
        val match = Matcher.find(text, store.commands())
        val recipe = match?.let { Recipes.find(context, it.command.recipeId) }
        val action = match?.let { recipe?.action(it.command.actionId) }
        if (match == null || recipe == null || action == null) {
            if (purpose == CapturePurpose.COMMAND && !force) {
                val message = if (text.isBlank()) R.string.capture_command_no_speech else R.string.capture_command_no_match
                rejectCapture(context, dir, message)
            } else if (purpose == CapturePurpose.QUICK && !force) {
                NoteStore.updateMeta(dir) { remove(CapturePurpose.FIELD) }
            }
            return null
        }
        if (purpose == CapturePurpose.COMMAND && !force && meta.optString("stopReason") == CAPTURE_STOP_CAP) {
            rejectCapture(context, dir, R.string.capture_command_too_long)
            return null
        }
        val vars = Runner.variables(match.command, match, store.connection(recipe), noteId)
        val requestedAt = if (force) System.currentTimeMillis() else meta.optLong("recordedAtMs", meta.optLong("startedAtMs") + meta.optLong("durationMs"))
        val expiresAt = requestedAt + CommandPolicy.current().timeoutSeconds * 1_000L
        NoteStore.updateMeta(dir) {
            put("command", (previous?.let { JSONObject(it.toString()) } ?: JSONObject()).put("name", match.command.name)
                .put("kind", if (action.yandexScenario) "yandex" else action.android?.let { SmartCommand.kind(it.kind) }.orEmpty())
                .put("notifyResult", match.command.notifyResult).put("parameters", previous?.optJSONObject("parameters")).put("detail", previous?.optString("detail").orEmpty())
                .put("phase", "processing").put("ok", false).put("label", context.getString(R.string.commands_smart_processing)).put("requestedAtMs", requestedAt)
                .put("expiresAtMs", expiresAt))
        }
        check(CommandHistory.separate(context, dir)) { "command_history_conflict" }
        return Pending(match.command, action, vars, requestedAt, expiresAt)
    }

    internal fun mayMatch(purpose: CapturePurpose, hasReceipt: Boolean, force: Boolean): Boolean =
        if (force) hasReceipt else purpose != CapturePurpose.NOTE && !hasReceipt

    private fun rejectCapture(context: Context, dir: File, message: Int) {
        NoteStore.updateMeta(dir) {
            put("command", JSONObject().put("name", context.getString(R.string.widget_command_label))
                .put("kind", "command").put("phase", "failed").put("ok", false)
                .put("label", context.getString(message)))
        }
        check(CommandHistory.separate(context, dir)) { "command_history_conflict" }
    }

    private fun run(context: Context, noteId: String, pending: Pending) {
        val (command, action, vars, capturedAtMs, expiresAtMs) = pending
        val dir = NoteStore.noteDir(context, noteId)
        val attempt = runCatching {
            if (System.currentTimeMillis() >= expiresAtMs) Runner.Result(false, 0, null, context.getString(R.string.commands_smart_expired))
            else Runner.sendCommand(context, action, vars, blockOnLockScreen = command.blockOnLockScreen,
                capturedAtMs = capturedAtMs, expiresAtMs = expiresAtMs) {
                NoteStore.updateMeta(dir) { getJSONObject("command").put("phase", "dispatching") }
            }
        }
        val result = attempt.getOrElse { Runner.Result(false, 0, null, context.getString(R.string.commands_smart_failed)) }
        Journal.add(context, Journal.COMMAND, listOfNotNull("${command.name}: ${result.label()}", result.reply).joinToString(" — "))
        if (!dir.isDirectory) return
        NoteStore.updateMeta(dir) {
            val receipt = getJSONObject("command")
            if (receipt.optLong("requestedAtMs") != capturedAtMs) return@updateMeta
            val unknown = result.uncertain || (attempt.isFailure && receipt.optString("phase") == "dispatching")
            receipt.put("ok", result.ok).put("phase", if (unknown) "unknown" else if (result.ok) "completed" else "failed")
                .put("label", when {
                    unknown -> context.getString(R.string.commands_smart_unknown)
                    !result.ok -> result.label()
                    action.yandexScenario -> context.getString(R.string.commands_yandex_started)
                    action.android?.let { SmartCommand.kind(it.kind) } in setOf("calendar", "reminder") -> context.getString(R.string.commands_smart_created)
                    action.android != null -> context.getString(R.string.commands_clock_sent)
                    else -> result.label()
                })
                .put("reply", result.reply.orEmpty())
        }
        if (command.notifyResult) Notice.result(context, noteId)
        if (!result.deferred) buzz(context, result.ok)

    }

    /** One short buzz for success, one long for failure: no sound. */
    private fun buzz(
        context: Context,
        ok: Boolean,
    ) {
        runCatching {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(if (ok) 40 else 400, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
