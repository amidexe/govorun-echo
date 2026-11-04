package com.govorun.echo.commands

import android.content.Context
import android.os.VibrationEffect
import android.util.Log
import android.os.VibratorManager
import com.govorun.echo.Journal
import com.govorun.echo.notes.NoteStore
import java.io.File
import org.json.JSONObject
import com.govorun.echo.notes.NoteTrash

/** When a note has its text: if it starts like a command, send the command. */
object Dispatcher {
    fun onNoteDoneAsync(
        context: Context,
        noteId: String,
        force: Boolean = false,
    ) {
        val app = context.applicationContext
        Thread { runCatching { run(app, noteId, force) }.onFailure { Log.w("Echo", "event=command_failed type=${it.javaClass.simpleName}") } }.start()
    }

    private fun run(
        context: Context,
        noteId: String,
        force: Boolean,
    ) {
        val store = CommandStore(context)
        val commands = store.commands()
        if (commands.isEmpty()) return
        val dir = NoteStore.noteDir(context, noteId)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return
        // A note already handled (or continued with "append") does not fire its command twice.
        if (!force && meta.has("command")) return
        val text = runCatching { File(dir, "text.txt").readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val match = Matcher.find(text, commands) ?: return
        val recipe = Recipes.find(context, match.command.recipeId) ?: return
        val action = recipe.action(match.command.actionId) ?: return
        val vars = Runner.variables(match.command, match, store.connection(recipe), noteId)
        val result = Runner.sendCommand(context, action, vars)
        Journal.add(context, Journal.COMMAND, listOfNotNull("${match.command.name}: ${result.label()}", result.reply).joinToString(" — "))
        // Success has its buzz and the journal; a failure, or an answer from the service, is worth a quiet notice.
        if (!result.ok || !result.reply.isNullOrBlank()) Notice.show(context, match.command.name, listOfNotNull(result.label(), result.reply).joinToString(": "), result.ok)
        NoteStore.updateMeta(dir) {
            put("command", JSONObject().put("name", match.command.name).put("ok", result.ok).put("label", result.label()).put("reply", result.reply.orEmpty()))
        }
        buzz(context, result.ok)
        if (result.ok && match.command.discard) NoteTrash.move(NoteStore.root(context), NoteTrash.root(context), listOf(noteId))
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
