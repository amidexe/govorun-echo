package com.govorun.echo.notes

import android.content.Context
import com.govorun.echo.AppSettings
import org.json.JSONObject
import java.io.File

/** Optional inbox housekeeping. Only metadata moves; audio, text and searchability are retained. */
object NoteAutoArchive {
    const val DAYS = 30
    private const val AGE_MS = DAYS * 86_400_000L

    internal fun eligible(meta: JSONObject, hasPcm: Boolean, nowMs: Long): Boolean {
        if (meta.optString("state") != NoteStore.STATE_DONE || meta.optBoolean("archived") || meta.optBoolean("pinned") || meta.optBoolean("draft") || hasPcm || meta.has("appendFromMs")) return false
        if ((meta.optJSONArray("tags")?.length() ?: 0) > 0) return false
        val last = maxOf(meta.optLong("startedAtMs"), meta.optLong("modifiedAtMs"), meta.optLong("changedAtMs"))
        return last > 0 && last <= nowMs - AGE_MS
    }

    /** Re-check under the metadata lock: an intervening edit, pin or tag must prevent a stale decision. */
    fun sweep(root: File, nowMs: Long = System.currentTimeMillis()): Int {
        var count = 0
        root.listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return@forEach
            if (!eligible(meta, NoteStore.pcmFile(dir).exists(), nowMs)) return@forEach
            var moved = false
            runCatching {
                NoteStore.updateMeta(dir) {
                    if (eligible(this, NoteStore.pcmFile(dir).exists(), nowMs)) { put("archived", true); moved = true }
                }
            }.onSuccess { if (moved) count++ }
                .onFailure { android.util.Log.w("NoteAutoArchive", "event=archive_failed", it) }
        }
        return count
    }

    /** Called off the UI thread when the visible library reloads. Default-off installs never sweep. */
    fun beforeRetention(context: Context, days: Int) {
        synchronized(NoteStore) {
            if (AppSettings.autoArchive(context)) sweep(NoteStore.root(context))
            NoteStore.deleteOlderThan(NoteStore.root(context), days)
        }
    }

    fun refresh(context: Context): List<NoteStore.Entry> {
        if (AppSettings.autoArchive(context)) synchronized(NoteStore) { sweep(NoteStore.root(context)) }
        return NoteStore.list(context)
    }
}
