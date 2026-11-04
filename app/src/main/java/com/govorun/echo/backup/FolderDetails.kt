package com.govorun.echo.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * `echo-notes.json` in the copy folder: what the `.txt` and the sound cannot hold — each note's name, description, tags, pin,
 * archive and «Текст», by the file name its note has in the folder, and the tags' colours and order. Plain JSON, so the folder
 * stays readable without Echo; a restore from the folder reads it and the notes come back as they were, not as bare text.
 */
internal object FolderDetails {
    const val NAME = "echo-notes.json"
    private const val VERSION = 1

    /** Readable-folder restoration reconstructs audio layout; full native transfer preserves exact files. */
    private val LAYOUT = setOf("id", "format", "state", "audio", "bytes", "mode", "backup", "backupPending")
    fun snapshot(id: String, meta: JSONObject): JSONObject = JSONObject(meta.toString()).apply { remove("backup"); remove("backupPending"); put("id", id) }

    /** Tree and checksum of the file last written in this run: unchanged notes write nothing. */
    @Volatile private var written: String? = null

    fun write(
        resolver: ContentResolver,
        present: MutableMap<String, Uri>,
        rootDoc: Uri,
        tree: Uri,
        notes: List<Pair<java.io.File, JSONObject>>,
        names: Map<String, String>,
        tags: JSONObject,
    ) {
        // Added to, never rebuilt: a note gone from the phone (deleted, or a phone just reinstalled) keeps its line, as its files stay
        // in the folder, so a copy made before the restore cannot wipe what the restore needs.
        val list = known(resolver, present, tree)
        notes.forEach { (dir, meta) ->
            val base = names.getValue(dir.name)
            val kept = snapshot(dir.name, meta)
            // A note restored before the folder had this file has none of its details yet: the folder's line stays what it was.
            val before = list.optJSONObject(base)
            if (meta.optString("mode") == "restored" && before != null) before.keys().forEach { key -> if (!kept.has(key)) before.opt(key)?.let { kept.put(key, it) } }
            list.put(base, kept)
        }
        val body = JSONObject().put("version", VERSION).put("tags", tags).put("notes", list).toString()
        val mark = tree.toString() + "|" + java.util.zip.CRC32().apply { update(body.toByteArray()) }.value
        if (mark == written && NAME in present) return
        SafeFolderWrite.write(resolver, present, rootDoc, "application/json", NAME) { it.write(body.toByteArray()) }
        written = mark
    }

    /** The folder's lines as last written or read in this run, for the folder [tree]. */
    @Volatile private var lines: Pair<String, JSONObject>? = null

    private fun known(
        resolver: ContentResolver,
        present: Map<String, Uri>,
        tree: Uri,
    ): JSONObject {
        lines?.takeIf { it.first == tree.toString() }?.let { return it.second }
        // Unreadable now, it is not written over: the pass fails and the next one tries again.
        val read = present[NAME]?.let { doc -> JSONObject(resolver.openInputStream(doc)?.use { it.readBytes().decodeToString() } ?: error("open_failed:$NAME")).optJSONObject("notes") }
        return (read ?: JSONObject()).also { lines = tree.toString() to it }
    }

    fun bases(resolver: ContentResolver, present: Map<String, Uri>, tree: Uri): Map<String, String> {
        val notes = known(resolver, present, tree)
        return notes.keys().asSequence().mapNotNull { base -> notes.optJSONObject(base)?.optString("id")?.takeIf { it.isNotBlank() }?.let { it to base } }.toMap()
    }

    class Details(
        val notes: Map<String, JSONObject>,
        val tags: JSONObject?,
    )

    fun read(
        resolver: ContentResolver,
        doc: Uri,
    ): Details? =
        runCatching {
            val json = JSONObject(resolver.openInputStream(doc)?.use { it.readBytes().decodeToString() } ?: return null)
            val notes = json.optJSONObject("notes") ?: JSONObject()
            Details(notes.keys().asSequence().associateWith { notes.getJSONObject(it) }, json.optJSONObject("tags"))
        }.onFailure { android.util.Log.w("BackupFolder", "event=details_unreadable", it) }.getOrNull()

    /** Lays a note's kept details over [meta]; a restored note keeps saying it was restored. */
    fun apply(
        meta: JSONObject,
        details: JSONObject,
    ): JSONObject {
        details.keys().forEach { key -> if (key !in LAYOUT) details.opt(key)?.let { meta.put(key, if (it is JSONArray) JSONArray(it.toString()) else if (it is JSONObject) JSONObject(it.toString()) else it) } }
        // A typed note comes back as one, not as a recording without its sound.
        if (details.optString("mode") == com.govorun.echo.notes.NoteText.MODE) meta.put("mode", com.govorun.echo.notes.NoteText.MODE)
        return meta
    }
}
