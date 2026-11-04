package com.govorun.echo.notes

import org.json.JSONObject
import java.io.File

/** Attention is bookkeeping, never an edit, importance score or archive decision. Missing means unknown/neutral. */
object NoteSeen {
    fun ready(state: String): Boolean = state == NoteStore.STATE_DONE || state == NoteStore.STATE_FAILED

    /** Failure ids remain available for retry; no rollback of successful independent notes. */
    fun setMany(root: File, ids: List<String>, unseen: Boolean): List<String> = ids.distinct().filterNot { set(root, it, unseen) }

    /** Serialized with recording/recognition writes; corrupt or missing notes are never replaced. */
    fun set(root: File, id: String, unseen: Boolean): Boolean = synchronized(NoteStore) {
        runCatching {
            val dir = File(root, id)
            if (dir.canonicalFile.parentFile != root.canonicalFile) return@synchronized false
            val meta = JSONObject(File(dir, "meta.json").readText())
            if (!ready(meta.optString("state"))) return@synchronized false
            if (!meta.has("unseen") || meta.optBoolean("unseen") != unseen) {
                meta.put("unseen", unseen)
                NoteStore.writeMeta(dir, meta, touched = false)
            }
            true
        }.getOrDefault(false)
    }
}
