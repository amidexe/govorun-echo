package com.govorun.echo.notes

import android.content.Context
import com.govorun.echo.AppSettings
import com.govorun.echo.Journal
import com.govorun.echo.R
import com.govorun.echo.CapturePurpose
import org.json.JSONObject
import java.io.File

/**
 * «Убирать пустые записи»: a recording under [MAX_MS] in which Govorun heard no word at all — a tap by mistake, a breath of
 * silence — goes to the trash once its text is known, instead of standing in the list as a note. Only a recognized one: when the
 * text did not come (Govorun away, an error) the words may still be there, and it stays. A note with a name, a text note, an
 * import and a joined note are never touched; the trash keeps it for its usual days, so a mistake is undone.
 * A short command («Алиса, включи свет») has words, so it is not empty.
 */
object EmptyNotes {
    const val MAX_MS = 3_000L

    /** What recognition writes when Govorun heard no words (Transcriber.ERROR_NO_SPEECH; notes sit under recognition). */
    private const val NO_SPEECH = "no_speech"

    fun isEmpty(
        dir: File,
        meta: JSONObject,
    ): Boolean {
        if (CapturePurpose.read(meta) == CapturePurpose.COMMAND) return false
        val mode = meta.optString("mode")
        if (mode == NoteText.MODE || mode == NoteImport.MODE || mode == "merged") return false
        // Govorun's answer «no speech» is the empty one; any other failure (Govorun away, an error) may still hide words.
        val heardNothing = meta.optString("state") == NoteStore.STATE_FAILED && meta.optString("error") == NO_SPEECH
        if ((meta.optString("state") != NoteStore.STATE_DONE && !heardNothing) || meta.optString("title").isNotBlank()) return false
        if (NoteStore.durationOf(dir) >= MAX_MS) return false
        return runCatching { File(dir, "text.txt").readText() }.getOrDefault("").isBlank()
    }

    /** Right after the note's text came: moves it to the trash if it is empty; says whether it did. */
    fun dropIfEmpty(
        context: Context,
        noteId: String,
    ): Boolean {
        if (!AppSettings.dropEmpty(context)) return false
        val root = NoteStore.root(context)
        val dir = File(root, noteId)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return false
        if (!isEmpty(dir, meta)) return false
        val moved = NoteTrash.move(root, NoteTrash.root(context), listOf(noteId)).isNotEmpty()
        if (moved) Journal.add(context, Journal.RECORDING, context.getString(R.string.empty_dropped))
        return moved
    }

    /** The empty records already in the list, all at once: when the switch is turned on, and once for those made before it. */
    fun sweep(context: Context): Int {
        val root = NoteStore.root(context)
        val ids =
            root.listFiles { d -> d.isDirectory }.orEmpty().filter { dir ->
                runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()?.let { isEmpty(dir, it) } == true
            }.map { it.name }
        val moved = NoteTrash.move(root, NoteTrash.root(context), ids).size
        if (moved > 0) Journal.add(context, Journal.RECORDING, context.resources.getQuantityString(R.plurals.empty_swept, moved, moved))
        return moved
    }

    /** Once, on the first start with this feature, if the switch is on: the junk gathered before it goes too. */
    fun sweepOnce(context: Context): Int {
        if (!AppSettings.dropEmpty(context) || AppSettings.emptySwept(context)) return 0
        AppSettings.setEmptySwept(context)
        return sweep(context)
    }
}
