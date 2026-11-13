package com.govorun.echo.notes

import android.content.Context
import com.govorun.echo.CapturePurpose
import com.govorun.echo.R
import java.io.File
import org.json.JSONObject

/** Command captures are journal records, never notes. Whole directories retain unknown fields and files. */
internal object CommandHistory {
    data class Entry(val id: String, val atMs: Long, val result: CommandResult)

    fun root(context: Context): File = File(context.filesDir, "command-history").apply { mkdirs() }
    fun isCommand(meta: JSONObject): Boolean = meta.optJSONObject("command") != null

    fun directory(context: Context, id: String): File {
        val history = File(root(context), id)
        return history.takeIf(File::isDirectory) ?: File(NoteStore.root(context), id)
    }

    /** Called after recognition/archive finishes, or for a settled record from the previous build. */
    fun separate(context: Context, dir: File): Boolean = separate(root(context), dir)

    fun separate(history: File, dir: File): Boolean = synchronized(NoteStore) {
        if (dir.parentFile == history) return@synchronized true
        history.mkdirs()
        val target = File(history, dir.name)
        if (target.exists()) return@synchronized false // A conflict never overwrites either record.
        val moved = dir.renameTo(target)
        if (moved) NoteChanges.signal()
        moved
    }

    fun entries(context: Context): List<Entry> {
        // Also finish separating records left by an interrupted older process; this performs no action.
        val pending = NoteStore.root(context).listFiles().orEmpty().filter(File::isDirectory)
        val directories = root(context).listFiles().orEmpty().filter(File::isDirectory) + pending
        return directories.mapNotNull { dir ->
            runCatching {
                val meta = JSONObject(File(dir, "meta.json").readText())
                val receipt = meta.optJSONObject("command")
                if (receipt == null) {
                    // Recovery can leave a completed command capture before the dispatcher has written its receipt.
                    if (CapturePurpose.read(meta) != CapturePurpose.COMMAND ||
                        meta.optString("state") !in setOf(NoteStore.STATE_DONE, NoteStore.STATE_FAILED)) return@runCatching null
                    return@runCatching Entry(dir.name, meta.optLong("startedAtMs"), CommandResult(
                        context.getString(R.string.widget_command_label), false, context.getString(R.string.commands_result_background),
                        "", kind = "command", phase = "failed",
                    ))
                }
                if (dir in pending && meta.optString("state") == NoteStore.STATE_DONE && !NoteStore.pcmFile(dir).exists()
                    && receipt.optString("phase") !in setOf("processing", "dispatching")) separate(context, dir)
                Entry(dir.name, meta.optLong("startedAtMs"), CommandResult.read(receipt,
                    context.getString(com.govorun.echo.R.string.commands_smart_expired), context.getString(com.govorun.echo.R.string.commands_smart_unknown)))
            }.getOrNull()
        }.sortedByDescending { it.atMs }
    }
}
