package com.govorun.echo.notes

import android.content.Context
import com.govorun.echo.CapturePurpose
import com.govorun.echo.PortablePath
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** One optional destination for finished, unfiled notes. Trash keeps its own recovery period. */
internal object InactiveNotes {
    const val DAYS = 30
    private const val DAY_MS = 86_400_000L
    private const val LAST_USED = "lastUsedAtMs"

    fun eligible(meta: JSONObject, hasPcm: Boolean, nowMs: Long, days: Int = DAYS): Boolean {
        if (meta.optLong("startedAtMs") <= 0 || meta.optString("state") != NoteStore.STATE_DONE || meta.optBoolean("archived") ||
            meta.optBoolean("pinned") || meta.optBoolean("draft") || hasPcm || meta.has("appendFromMs") ||
            CommandHistory.isCommand(meta)) return false
        if (meta.has(CapturePurpose.FIELD) && meta.optString(CapturePurpose.FIELD) !in
            listOf(CapturePurpose.NOTE.id, CapturePurpose.QUICK.id)) return false
        if (meta.has("tags") && meta.optJSONArray("tags")?.length() != 0) return false
        val last = maxOf(meta.optLong("startedAtMs"), meta.optLong("modifiedAtMs"),
            meta.optLong("changedAtMs"), meta.optLong(LAST_USED))
        return last > 0 && last <= nowMs - days * DAY_MS
    }

    /** Opening and playing are not edits and must not replace missing or damaged metadata. */
    fun used(context: Context, id: String) {
        if (InactiveNoteRule.read(context).action != InactiveNoteAction.KEEP)
            markUsed(NoteStore.root(context), id, System.currentTimeMillis())
    }

    fun markUsed(root: File, id: String, nowMs: Long) = synchronized(NoteStore) {
        val dir = File(root, id)
        val file = File(dir, "meta.json")
        if (!PortablePath.safe(id) || '/' in id || !Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) ||
            !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return@synchronized
        runCatching {
            val meta = JSONObject(file.readText())
            if (meta.optLong(LAST_USED) < nowMs)
                NoteStore.writeMeta(dir, meta.put(LAST_USED, nowMs), touched = false)
        }.onFailure { android.util.Log.w("InactiveNotes", "event=use_time_unavailable") }
    }

    fun sweep(
        root: File,
        nowMs: Long = System.currentTimeMillis(),
        rule: InactiveNoteRule,
        trash: File? = null,
        inUse: Set<String> = emptySet(),
        stillCurrent: () -> Boolean = { true },
    ): Int {
        if (rule.action == InactiveNoteAction.KEEP || rule.days !in InactiveNoteRule.periods ||
            rule.sinceMs > nowMs - rule.days * DAY_MS) return 0
        var count = 0
        for (dir in root.listFiles().orEmpty()) {
            if (dir.name in inUse || !Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS)) continue
            synchronized(NoteStore) {
                if (!stillCurrent()) return count
                val metaFile = File(dir, "meta.json")
                val textFile = File(dir, "text.txt")
                if (!Files.isRegularFile(metaFile.toPath(), NOFOLLOW_LINKS) ||
                    !Files.isRegularFile(textFile.toPath(), NOFOLLOW_LINKS) || !textFile.canRead()) return@synchronized
                val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                if (meta != null && eligible(meta, NoteStore.pcmFile(dir).exists(), nowMs, rule.days)) {
                    if (rule.action == InactiveNoteAction.TRASH) {
                        count += NoteTrash.move(root, requireNotNull(trash), listOf(dir.name), nowMs).size
                    } else {
                        runCatching { NoteStore.writeMeta(dir, meta.put("archived", true)) }
                            .onSuccess { count++ }
                            .onFailure { android.util.Log.w("InactiveNotes", "event=archive_failed", it) }
                    }
                }
            }
        }
        return count
    }

    fun applyRule(context: Context, inUse: Set<String>): Int {
        val rule = InactiveNoteRule.read(context)
        if (rule.action == InactiveNoteAction.KEEP) return 0
        return sweep(NoteStore.root(context), rule = rule,
            trash = if (rule.action == InactiveNoteAction.TRASH) NoteTrash.root(context) else null, inUse = inUse,
            stillCurrent = { InactiveNoteRule.read(context) == rule })
    }
}
