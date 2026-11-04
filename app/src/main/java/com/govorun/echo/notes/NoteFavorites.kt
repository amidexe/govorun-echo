package com.govorun.echo.notes

import org.json.JSONObject
import java.io.File

/** Explicit importance, independent of tags, viewing and archive. Reads/writes the old pin flag without a migration. */
object NoteFavorites {
    /** Apply one explicit state to the whole selection; keep independent failures for retry. */
    fun setMany(root: File, ids: List<String>, on: Boolean): List<String> = ids.distinct().filterNot { set(root, it, on) }

    fun set(root: File, id: String, on: Boolean): Boolean = synchronized(NoteStore) {
        runCatching {
            val dir = File(root, id)
            if (dir.canonicalFile.parentFile != root.canonicalFile) return@synchronized false
            val meta = JSONObject(File(dir, "meta.json").readText())
            if (!NoteSeen.ready(meta.optString("state")) && !meta.optBoolean("pinned")) return@synchronized false
            if (meta.optBoolean("pinned") != on) {
                if (on) meta.put("pinned", true) else meta.remove("pinned")
                NoteStore.writeMeta(dir, meta)
            }
            true
        }.getOrDefault(false)
    }
}
