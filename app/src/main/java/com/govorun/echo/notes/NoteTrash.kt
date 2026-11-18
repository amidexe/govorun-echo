package com.govorun.echo.notes

import android.content.Context
import com.govorun.echo.PortablePath
import com.govorun.echo.UserFiles
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Deleted notes wait here for [DAYS] days, so a delete can be undone, and then go for good. */
internal object NoteTrash {
    fun root(context: Context): File = File(context.filesDir, "trash").apply { mkdirs() }

    /** The folder's location is the commit. An interruption before rename leaves the note active. */
    fun move(
        root: File,
        trash: File,
        ids: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> = synchronized(NoteStore) {
        ids.filter { id ->
            if (!validId(id)) return@filter false
            val from = File(root, id)
            val to = File(trash, id)
            if (!canMove(from, to)) return@filter false
            val meta = readMeta(from) ?: return@filter false
            val priorDate = meta.opt("deletedAtMs")
            if (runCatching {
                    // No expiry can be based on the filesystem age of a moved folder.
                    NoteStore.writeMeta(from, meta.put("deletedAtMs", nowMs), touched = false, durable = true)
                }.isFailure
            ) return@filter false
            if (!moveDirectory(from, to)) {
                // The note is still active even if storage also refuses this cleanup.
                meta.put("deletedAtMs", priorDate)
                runCatching { NoteStore.writeMeta(from, meta, touched = false) }
                return@filter false
            }
            runCatching { NoteStore.forget(root, id) }.onFailure { NoteChanges.signal() }
            true
        }
    }

    fun restore(
        root: File,
        trash: File,
        ids: List<String>,
    ): List<String> = synchronized(NoteStore) {
        ids.filter { id ->
            if (!validId(id)) return@filter false
            val from = File(trash, id)
            val to = File(root, id)
            if (!canMove(from, to)) return@filter false
            val meta = readMeta(from) ?: return@filter false
            if (!moveDirectory(from, to)) return@filter false
            // An interrupted cleanup is harmless: expiry only ever traverses the trash.
            meta.remove("deletedAtMs")
            runCatching { NoteStore.writeMeta(to, meta, durable = true) }.onFailure { NoteChanges.signal() }
            true
        }
    }

    private fun validId(id: String) = PortablePath.safe(id) && '/' !in id

    private fun canMove(from: File, to: File): Boolean =
        Files.isDirectory(from.toPath(), NOFOLLOW_LINKS) &&
            Files.isDirectory(to.parentFile!!.toPath(), NOFOLLOW_LINKS) &&
            !Files.exists(to.toPath(), NOFOLLOW_LINKS)

    private fun moveDirectory(from: File, to: File): Boolean {
        try {
            Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE)
        } catch (_: IOException) {
            // A non-atomic copy is never an acceptable fallback.
            return false
        }
        // The rename already committed. Even if a flush fails, both possible locations
        // after a restart are safe because the deletion date was written first.
        runCatching {
            UserFiles.syncDirectory(to.parentFile!!)
            UserFiles.syncDirectory(from.parentFile!!)
        }
        return true
    }

    private fun readMeta(dir: File): JSONObject? =
        runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()

    private fun deletedAt(meta: JSONObject?): Long? =
        (meta?.opt("deletedAtMs") as? Number)?.toLong()?.takeIf { it > 0 }

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
    ) = synchronized(NoteStore) {
        if (!validId(id)) return@synchronized
        val dir = File(root, id)
        val meta = readMeta(dir) ?: return@synchronized
        NoteStore.writeMeta(dir, meta.apply {
            put("durationMs", NoteStore.durationMs(bytes))
            put("bytes", bytes)
            put("silencedMs", silencedMs)
            put("state", NoteStore.STATE_RECORDED)
        })
        move(root, trash, listOf(id))
        Unit
    }

    const val DAYS = 30

    /** Missing or damaged timestamps on existing installs require an explicit user decision. */
    fun purgeExpired(trash: File, nowMs: Long = System.currentTimeMillis()) = synchronized(NoteStore) {
        val limit = nowMs - DAYS * 24L * 3_600_000L
        trash.listFiles { dir -> Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) }.orEmpty().forEach { dir ->
            val deleted = deletedAt(readMeta(dir)) ?: return@forEach
            if (deleted < limit) purgeEntry(dir)
        }
    }

    /** A null listing means the trash could not be read. Damaged entries still show up for deletion. */
    fun list(trash: File): List<TrashedNote>? =
        trash.listFiles()?.map { dir ->
            val meta = dir.takeIf { Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }?.let(::readMeta)
            TrashedNote(
                id = dir.name,
                startedAtMs = meta?.optLong("startedAtMs") ?: 0,
                deletedAtMs = deletedAt(meta) ?: dir.lastModified(),
                durationMs = meta?.optLong("durationMs") ?: 0,
                // A cancelled recording has no text yet: what was heard while it went on.
                text = if (Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS))
                    runCatching { File(dir, "text.txt").readText() }.getOrNull() ?: runCatching { File(dir, "live.txt").readText() }.getOrDefault("")
                else "",
                unrecognized = meta?.optString("state").let { it == NoteStore.STATE_RECORDED || it == NoteStore.STATE_RECOGNIZING },
                readable = meta != null,
            )
        }?.sortedByDescending { it.deletedAtMs }

    @androidx.compose.runtime.Immutable
    data class TrashedNote(
        val id: String,
        val startedAtMs: Long,
        val deletedAtMs: Long,
        val durationMs: Long,
        val text: String,
        /** Brought back, it still has to be recognized. */
        val unrecognized: Boolean = false,
        val readable: Boolean = true,
    )

    /** Attempts each entry even if another entry fails. Returns whether deletion completed. */
    fun purge(
        trash: File,
        ids: List<String>? = null,
    ): Boolean = synchronized(NoteStore) {
        val targets = ids?.filter(::validId)?.map { File(trash, it) } ?: trash.listFiles()?.toList() ?: return@synchronized false
        var complete = ids == null || targets.size == ids.size
        for (target in targets) if (!purgeEntry(target)) complete = false
        complete
    }

    private fun purgeEntry(entry: File): Boolean {
        if (!entry.isDirectory || java.nio.file.Files.isSymbolicLink(entry.toPath())) {
            return deleteFile(entry)
        }
        val children = entry.listFiles() ?: return false
        // Keep the transcript and metadata readable if removing one of the other files fails.
        val last = listOf("live.txt", "text.txt", "meta.json")
        val byName = children.associateBy { it.name }
        for (child in children.filterNot { it.name in last } + last.mapNotNull(byName::get)) {
            if (!deleteTree(child)) return false
        }
        return deleteFile(entry)
    }

    private fun deleteTree(file: File): Boolean {
        if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            val children = file.listFiles() ?: return false
            for (child in children) if (!deleteTree(child)) return false
        }
        return deleteFile(file)
    }

    private fun deleteFile(file: File): Boolean =
        file.delete() || file.parentFile?.list()?.contains(file.name) == false
}
