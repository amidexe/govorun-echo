package com.govorun.echo.backup

import android.content.Context
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.NoteStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The small file that Android's own backup carries to the owner's Google account: every finished note's text and tags, and the
 * tag colours and order, without the audio (too big for it; the audio has "copy to folder") and without the word times (of no use
 * without the sound, and a semester of lectures holds tens of megabytes of them). Android carries at most 25 MB for an app, so the
 * snapshot stays under [MAX_BYTES], compressed (speech as text shrinks about four times, so some 700 hours of it fit): when the
 * texts are more, the newest notes go in and the oldest are left to the copy folder, and Settings says so ([GoogleBackup]).
 * After a restore on a new phone the notes are rebuilt from it, as text notes.
 */
internal object Snapshot {
    private const val VERSION = 1

    /** Under the 25 MB Android's backup carries for an app, with room for its settings: counted as stored, compressed. */
    const val MAX_BYTES = 20_000_000

    /** What the notes looked like (file times and sizes) when the snapshot was last made in this run of the app. */
    @Volatile private var made: String? = null

    fun file(context: Context): File = File(File(context.filesDir, "snapshot").apply { mkdirs() }, "notes.json.gz")

    /** The uncompressed snapshot of 0.1.122 and earlier: still read after a restore, removed once a compressed one is made. */
    private fun legacy(context: Context): File = File(File(context.filesDir, "snapshot"), "notes.json")

    /**
     * Writes the snapshot when the notes changed since it was last made; when they did not, only file times and sizes are read.
     * One at a time (the list and the recording service both ask for it), and a failed one is only logged: it is tried again.
     */
    @Synchronized
    fun write(context: Context) {
        try {
            val root = NoteStore.root(context)
            val tags = AppSettings.tagBackup(context)
            val settings = PortablePreferences.capture(context)
            val mark = fingerprint(root, JSONObject().put("tags",tags).put("settings",settings))
            if (mark == made) return
            val target = file(context)
            val tmp = File(target.parentFile, "notes.json.gz.tmp")
            val stored = Counting(tmp.outputStream())
            var notesIn = 0
            var notesAll = 0
            // Note by note into the file: a semester of lectures is too much text to hold as one string.
            java.util.zip.GZIPOutputStream(stored).bufferedWriter().use { out ->
                out.write("{\"version\":$VERSION,\"tags\":$tags,\"settings\":$settings,\"notes\":[")
                notesAll =
                    notes(root, tags, Int.MAX_VALUE) { json ->
                        // Newest first; the oldest stay out once the stored file nears what Google keeps.
                        if (stored.count > MAX_BYTES) return@notes false
                        if (notesIn > 0) out.write(",")
                        out.write(json)
                        notesIn++
                        true
                    }
                out.write("]}")
            }
            if (tmp.renameTo(target)) {
                made = mark
                legacy(context).delete()
                GoogleBackup.snapshot(context, notesIn, notesAll)
            } else {
                tmp.delete()
            }
        } catch (failure: Exception) {
            android.util.Log.w("Snapshot", "event=snapshot_failed", failure)
        }
    }

    /** The names, times and sizes of the files the snapshot is made from, and the tags: equal while nothing in them changed. */
    private fun fingerprint(
        root: File,
        tags: JSONObject,
    ): String {
        val crc = java.util.zip.CRC32()
        root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.forEach { dir ->
            val meta = File(dir, "meta.json")
            val text = File(dir, "text.txt")
            crc.update("${dir.name}|${meta.lastModified()}|${meta.length()}|${text.lastModified()}|${text.length()};".toByteArray())
        }
        crc.update(tags.toString().toByteArray())
        return crc.value.toString(16)
    }

    /** After an install with restored data and no notes yet: bring the notes back from the snapshot. */
    fun restoreIfEmpty(context: Context) {
        val root = NoteStore.root(context)
        if (root.listFiles { f -> f.isDirectory }.orEmpty().isNotEmpty()) return
        val json =
            runCatching {
                if (file(context).exists()) JSONObject(java.util.zip.GZIPInputStream(file(context).inputStream()).bufferedReader().use { it.readText() })
                else JSONObject(legacy(context).readText())
            }.getOrNull() ?: return
        restore(root, json)
        val settings = json.optJSONObject("settings")
        if(settings != null) PortablePreferences.restore(context,settings,false) else AppSettings.restoreTags(context, json.optJSONObject("tags") ?: JSONObject())
    }

    fun build(
        root: File,
        tags: JSONObject,
        maxBytes: Int = MAX_BYTES,
    ): JSONObject {
        val notes = JSONArray()
        notes(root, tags, maxBytes) { notes.put(JSONObject(it)); true }
        return JSONObject().put("version", VERSION).put("notes", notes).put("tags", tags)
    }

    /**
     * Each finished note as JSON, one at a time, newest first, while the text stays under [maxBytes] and [take] wants more: the
     * oldest are left out. Returns how many finished notes there are in all.
     */
    private fun notes(
        root: File,
        tags: JSONObject,
        maxBytes: Int = MAX_BYTES,
        take: (String) -> Boolean,
    ): Int {
        var bytes = tags.toString().toByteArray().size
        val done =
            root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
                runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()?.takeIf { it.optString("state") == NoteStore.STATE_DONE && !com.govorun.echo.notes.CommandHistory.isCommand(it) }?.let { dir to it }
            }
        for ((dir, meta) in done.sortedByDescending { it.second.optLong("startedAtMs") }) {
            val text = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
            val json = JSONObject().put("id", dir.name).put("meta", meta).put("text", text).toString()
            bytes += json.toByteArray().size
            if (bytes > maxBytes || !take(json)) break
        }
        return done.size
    }

    /** Counts the bytes that reach the file, after compression. */
    private class Counting(
        out: java.io.OutputStream,
    ) : java.io.FilterOutputStream(out) {
        var count = 0L

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            out.write(b, off, len)
            count += len
        }
    }

    fun restore(
        root: File,
        json: JSONObject,
    ) {
        val notes = json.optJSONArray("notes") ?: return
        for (i in 0 until notes.length()) {
            val note = notes.optJSONObject(i) ?: continue
            val dir = File(root, note.optString("id")).apply { mkdirs() }
            val meta = note.optJSONObject("meta") ?: JSONObject()
            // The audio did not come along: no player, no leftover work.
            listOf("audio", "archivedBytes", "appendFromMs", "appendAtMs", "error", "redo").forEach { meta.remove(it) }
            NoteStore.writeMeta(dir, meta.put("state", NoteStore.STATE_DONE).put("restored", true))
            File(dir, "text.txt").writeText(note.optString("text"))
            note.optJSONArray("segments")?.let { File(dir, "segments.json").writeText(it.toString()) }
        }
    }
}
