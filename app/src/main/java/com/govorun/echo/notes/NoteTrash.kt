package com.govorun.echo.notes

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Deleted notes wait here for [DAYS] days, so a delete can be undone, and then go for good. */
internal object NoteTrash {
    fun root(context: Context): File = File(context.filesDir, "trash").apply { mkdirs() }

    /** Moves notes out of the list without deleting them, so a delete can be undone. */
    fun move(
        root: File,
        trash: File,
        ids: List<String>,
    ): List<String> =
        ids.filter { File(root, it).renameTo(File(trash, it)) }.onEach {
            NoteStore.updateMeta(File(trash, it)) { put("deletedAtMs", System.currentTimeMillis()) }
            // For a sync it has left the list; brought back, its meta is written anew and says it is there again.
            NoteStore.forget(root, it)
        }

    fun restore(
        root: File,
        trash: File,
        ids: List<String>,
    ) = ids.forEach { if (File(trash, it).renameTo(File(root, it))) NoteStore.updateMeta(File(root, it)) { remove("deletedAtMs") } }

    /**
     * A new recording cancelled with «Отмена» leaves the list unrecognized and waits here like any deleted note, so a wrong tap can
     * be undone. Its raw sound stays with it: brought back, it is recognized from the file then.
     */
    fun moveCancelled(
        root: File,
        trash: File,
        id: String,
        bytes: Long,
        silencedMs: Long,
    ) {
        NoteStore.updateMeta(File(root, id)) {
            put("durationMs", NoteStore.durationMs(bytes))
            put("bytes", bytes)
            put("silencedMs", silencedMs)
            put("state", NoteStore.STATE_RECORDED)
        }
        move(root, trash, listOf(id))
    }

    const val DAYS = 30

    /** Removes what has been in the trash longer than [DAYS] days; a note with no date counts from its folder's time. */
    fun purgeExpired(trash: File) {
        val limit = System.currentTimeMillis() - DAYS * 24L * 3_600_000L
        trash.listFiles().orEmpty().forEach { dir ->
            val deleted = runCatching { JSONObject(File(dir, "meta.json").readText()).optLong("deletedAtMs") }.getOrDefault(0L).takeIf { it > 0 } ?: dir.lastModified()
            if (deleted < limit) dir.deleteRecursively()
        }
    }

    /** What the trash holds, newest deletion first: only what the trash screen shows. */
    fun list(trash: File): List<TrashedNote> =
        trash.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            runCatching {
                val meta = JSONObject(File(dir, "meta.json").readText())
                TrashedNote(
                    id = dir.name,
                    startedAtMs = meta.optLong("startedAtMs"),
                    deletedAtMs = meta.optLong("deletedAtMs").takeIf { it > 0 } ?: dir.lastModified(),
                    durationMs = meta.optLong("durationMs"),
                    // A cancelled recording has no text yet: what was heard while it went on.
                    text = runCatching { File(dir, "text.txt").readText() }.getOrNull() ?: runCatching { File(dir, "live.txt").readText() }.getOrDefault(""),
                    unrecognized = meta.optString("state").let { it == NoteStore.STATE_RECORDED || it == NoteStore.STATE_RECOGNIZING },
                )
            }.getOrNull()
        }.sortedByDescending { it.deletedAtMs }

    @androidx.compose.runtime.Immutable
    data class TrashedNote(
        val id: String,
        val startedAtMs: Long,
        val deletedAtMs: Long,
        val durationMs: Long,
        val text: String,
        /** Brought back, it still has to be recognized. */
        val unrecognized: Boolean = false,
    )

    /** Deletes the given notes for good, or everything in the trash when [ids] is null. */
    fun purge(
        trash: File,
        ids: List<String>? = null,
    ) {
        val targets = ids?.map { File(trash, it) } ?: trash.listFiles().orEmpty().toList()
        targets.forEach { it.deleteRecursively() }
    }
}
