package com.govorun.echo.notes

import org.json.JSONObject
import java.io.File

/**
 * Joining finished notes into one: their sound files move into the new note one after another, their word times shift by where
 * each one's sound begins, and a passport of the parts keeps what each note was, so [NoteSplit] can take the joined note apart
 * again. The originals leave the list, and the log of deleted notes records them.
 */
internal object NoteMerge {
    /** Joins finished notes into one new note (oldest first) and removes the originals. */
    fun merge(
        root: File,
        ids: List<String>,
        inOrder: Boolean = false,
    ): String? {
        val sources =
            ids.mapNotNull { id ->
                val dir = File(root, id)
                val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return@mapNotNull null
                if (meta.optString("state") != NoteStore.STATE_DONE) return@mapNotNull null
                Triple(dir, meta, runCatching { File(dir, "text.txt").readText() }.getOrDefault(""))
            }
        if (sources.size < 2 || sources.size != ids.size) return null
        // The order the owner selected them in, or by time when no order was chosen.
        val ordered = if (inOrder) sources else sources.sortedBy { it.second.optLong("startedAtMs") }
        val id = "${System.currentTimeMillis()}-merged"
        val target = File(root, id).apply { mkdirs() }
        File(target, "text.txt").writeText(ordered.joinToString("\n\n") { it.third.trim() })
        val audio = JSONObject()
        File(target, "chunks").mkdirs()
        var index = 0
        // Where each note's sound begins in the joined one, so its words keep their places.
        var offsetMs = 0L
        val joinedPhrases = mutableListOf<NoteStore.Phrase>()
        // The passport of the parts: what each note was, so the joined note can be split back into them.
        val passport = org.json.JSONArray()
        ordered.forEach { (dir, meta, text) ->
            val parts = NoteStore.audioParts(meta)
            val files = org.json.JSONArray()
            NoteStore.readPhrases(dir).takeIf { parts.isNotEmpty() }?.let { phrases -> joinedPhrases += phrases.map { it.shifted(offsetMs) } }
            parts.forEach { part ->
                val name = "%03d.%s".format(index++, part.file.substringAfterLast('.'))
                if (File(dir, "chunks/${part.file}").renameTo(File(target, "chunks/$name"))) {
                    audio.put(name, part.ms)
                    files.put(name)
                }
            }
            passport.put(
                JSONObject()
                    .put("id", dir.name)
                    .put("startedAtMs", meta.optLong("startedAtMs"))
                    .put("modifiedAtMs", meta.optLong("modifiedAtMs"))
                    .put("durationMs", meta.optLong("durationMs"))
                    .put("mode", meta.optString("mode"))
                    .put("text", text)
                    .put("appends", meta.optJSONArray("appends") ?: org.json.JSONArray())
                    .put("tags", meta.optJSONArray("tags") ?: org.json.JSONArray())
                    .put("title", meta.optString("title"))
                    .put("titleAuto", meta.optBoolean("titleAuto"))
                    .put("files", files)
                    .put("offsetMs", offsetMs)
                    .put("audioMs", parts.sumOf { it.ms }),
            )
            offsetMs += parts.sumOf { it.ms }
        }
        if (joinedPhrases.isNotEmpty()) NoteStore.writePhrases(target, joinedPhrases)
        val tags = ordered.flatMap { (_, meta, _) -> meta.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() }.distinct()
        NoteStore.writeMeta(
            target,
            JSONObject()
                .put("audio", audio)
                .put("mode", "merged")
                .put("startedAtMs", ordered.minOf { it.second.optLong("startedAtMs") })
                .put("durationMs", ordered.sumOf { it.second.optLong("durationMs") })
                .put("bytes", 0)
                .put("stopReason", "merge")
                .put("parts", passport)
                .put("archived", ordered.any { it.second.optBoolean("archived") })
                .apply { if (tags.isNotEmpty()) put("tags", org.json.JSONArray(tags)) }
                // The first name typed for a part names the whole (an automatic one does not); each part keeps its own in the passport.
                .apply { ordered.map { it.second }.firstOrNull { it.optString("title").isNotBlank() && !it.optBoolean("titleAuto") }?.let { put("title", it.optString("title")) } }
                .put("state", NoteStore.STATE_DONE),
        )
        ordered.forEach { NoteStore.remove(it.first) }
        return id
    }
}
