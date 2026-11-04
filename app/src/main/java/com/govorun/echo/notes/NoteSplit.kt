package com.govorun.echo.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Takes a joined note apart into the notes it was made of. The joined note keeps a passport of its parts (their text,
 * name, start, tags, which pieces of sound are theirs and where their words begin); the sound is not recompressed, the pieces
 * just go back to their notes, so a split gives the same folders as before the join: meta.json, text.txt, segments.json, chunks/.
 */
internal object NoteSplit {
    /** Only while nothing was added to the joined note: its sound is exactly the parts' sound. */
    fun canSplit(meta: JSONObject): Boolean {
        val parts = meta.optJSONArray("parts") ?: return false
        if (parts.length() < 2 || meta.has("appendFromMs") || meta.optString("state") != NoteStore.STATE_DONE) return false
        val owned = (0 until parts.length()).flatMap { i -> parts.getJSONObject(i).optJSONArray("files")?.let { f -> (0 until f.length()).map { f.getString(it) } }.orEmpty() }.toSet()
        val audio = meta.optJSONObject("audio")?.keys()?.asSequence()?.toSet().orEmpty()
        return owned == audio
    }

    /** The ids of the notes that came back, or null if this note cannot be split. */
    fun split(
        root: File,
        id: String,
    ): List<String>? {
        val dir = File(root, id)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return null
        if (!canSplit(meta)) return null
        val parts = meta.getJSONArray("parts")
        val phrases = NoteStore.readPhrases(dir)
        val tagsOfJoined = meta.optJSONArray("tags")
        val archived = meta.optBoolean("archived")
        val made = mutableListOf<String>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val wanted = part.optString("id")
            val noteId = if (wanted.isNotEmpty() && !File(root, wanted).exists()) wanted else "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            val target = File(root, noteId).apply { mkdirs() }
            File(target, "chunks").mkdirs()
            val audio = JSONObject()
            val files = part.optJSONArray("files") ?: JSONArray()
            for (k in 0 until files.length()) {
                val name = files.getString(k)
                val ms = meta.getJSONObject("audio").optLong(name)
                val newName = "%03d.%s".format(k, name.substringAfterLast('.'))
                if (File(dir, "chunks/$name").renameTo(File(target, "chunks/$newName"))) audio.put(newName, ms)
            }
            File(target, "text.txt").writeText(part.optString("text"))
            val offset = part.optLong("offsetMs")
            val end = offset + part.optLong("audioMs")
            val own = phrases.filter { it.startMs >= offset && it.startMs < end }.map { it.shifted(-offset) }
            if (own.isNotEmpty()) NoteStore.writePhrases(target, own)
            val tags = part.optJSONArray("tags")?.takeIf { it.length() > 0 } ?: tagsOfJoined
            NoteStore.writeMeta(
                target,
                JSONObject()
                    .put("state", NoteStore.STATE_DONE)
                    .put("mode", part.optString("mode").ifEmpty { "note" })
                    .put("startedAtMs", part.optLong("startedAtMs"))
                    .put("durationMs", part.optLong("durationMs"))
                    .put("bytes", 0)
                    .put("audio", audio)
                    .put("archived", archived)
                    .apply { part.optLong("modifiedAtMs").takeIf { it > part.optLong("startedAtMs") }?.let { put("modifiedAtMs", it) } }
                    .apply { part.optJSONArray("appends")?.takeIf { it.length() > 0 }?.let { put("appends", it) } }
                    .apply { if (tags != null && tags.length() > 0) put("tags", tags) }
                    .apply { part.optString("title").takeIf { it.isNotBlank() }?.let { put("title", it).also { if (part.optBoolean("titleAuto")) put("titleAuto", true) } } },
            )
            made += noteId
        }
        NoteStore.remove(dir)
        return made
    }
}
