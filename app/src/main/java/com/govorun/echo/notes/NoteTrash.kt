package com.govorun.echo.notes

import android.content.Context
import com.govorun.echo.PortablePath
import com.govorun.echo.UserFiles
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.UserPrincipal

/** Deleted notes wait here for [DAYS] days, so a delete can be undone, and then go for good. */
internal object NoteTrash {
    private const val DETACHED_PREFIX = "detached-"
    private val generatedId = Regex("[0-9]{13}-[0-9a-f]{8}")

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
            if (meta.optString("state") == NoteStore.STATE_RECORDING) return@filter false
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
            if (stagedId(from) != null && readMeta(from)?.has("startedAtMs") != true) return@filter false
            if (generatedId.matches(id) && (to.exists() || File(trash, DETACHED_PREFIX + id).exists())) {
                finishInterruptedDeletions(root, trash)
            }
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

    // Older import workers could recreate chunks after the note had left the library.
    // Only the deletion journal and the known writer layout together identify that case.
    fun finishInterruptedDeletions(root: File, trash: File): PurgeResult = synchronized(NoteStore) {
        attemptDeletion {
            if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) || !Files.isDirectory(trash.toPath(), NOFOLLOW_LINKS)) {
                return@attemptDeletion PurgeResult.INCOMPLETE
            }
            val owner = Files.getOwner(trash.parentFile!!.toPath(), NOFOLLOW_LINKS)
            if (!ensureWritable(trash, owner)) return@attemptDeletion PurgeResult.ACCESS_DENIED
            var result = PurgeResult.COMPLETE
            for (dir in children(trash)) {
                val id = stagedId(dir) ?: continue
                result = result.combine(attemptDeletion { finishImportFiles(dir, id, root, trash, owner, staged = true) })
            }
            val journal = File(root, NoteStore.DELETED)
            if (!Files.isRegularFile(journal.toPath(), NOFOLLOW_LINKS)) return@attemptDeletion result
            val names = children(root).mapTo(HashSet()) { it.name }
            journal.useLines { lines ->
                for (line in lines) {
                    val id = runCatching { JSONObject(line).getString("id") }.getOrNull() ?: continue
                    if (!names.remove(id) || !generatedId.matches(id)) continue
                    result = result.combine(attemptDeletion {
                        finishImportFiles(File(root, id), id, root, trash, owner, staged = false)
                    })
                }
            }
            result
        }
    }

    private fun stagedId(dir: File): String? =
        dir.name.takeIf { it.startsWith(DETACHED_PREFIX) }?.removePrefix(DETACHED_PREFIX)?.takeIf(generatedId::matches)

    private val importChunk = Regex("(?:[0-9]{3,}|live)\\.(?:ogg|aac)(?:\\.part)?")
    private val importFiles = setOf("meta.json", "audio.pcm", "audio.pcm.part", "peaks2.bin", "peaks2.bin.tmp")
    private val importNumbers = setOf("format", "changedAtMs", "durationMs", "preparedMs", "totalMs", "bytes", "archivedBytes")

    private fun readableChildren(dir: File, owner: UserPrincipal?): List<File> {
        if (owner != null && (!Files.isReadable(dir.toPath()) || !Files.isExecutable(dir.toPath()))) {
            if (!ensureWritable(dir, owner)) throw AccessDeniedException(dir.path)
        }
        return children(dir)
    }

    private fun containsOnlyImportFiles(dir: File, staged: Boolean, owner: UserPrincipal? = null): Boolean {
        if (!Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS)) return false
        val files = readableChildren(dir, owner)
        if (files.any { it.name != "chunks" && (it.name !in importFiles || !Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) }) return false
        val metadata = files.find { it.name == "meta.json" }
        if (metadata != null) {
            if (!Files.isRegularFile(metadata.toPath(), NOFOLLOW_LINKS) || metadata.length() > 64 * 1024) return false
            val meta = try { JSONObject(metadata.readText()) } catch (_: org.json.JSONException) { return false }
            if (meta.has("format") && meta.optInt("format", -1) != NoteStore.FORMAT) return false
            for (key in meta.keys()) {
                val known = when (key) {
                    "audio" -> true
                    "state" -> meta.opt(key) in setOf(NoteStore.STATE_RECORDED, NoteStore.STATE_RECOGNIZING, NoteStore.STATE_FAILED, NoteStore.STATE_DONE)
                    "error" -> meta.opt(key) in setOf("", "not_installed", "import_failed", "storage_low", "preparing")
                    else -> key in importNumbers && meta.opt(key) is Number
                }
                if (!known) return false
            }
            if (meta.has("audio")) {
                val audio = meta.optJSONObject("audio") ?: return false
                if (audio.keys().asSequence().any { !importChunk.matches(it) || audio.opt(it) !is Number }) return false
            }
        }
        val chunks = files.find { it.name == "chunks" } ?: return staged
        return Files.isDirectory(chunks.toPath(), NOFOLLOW_LINKS) &&
            readableChildren(chunks, owner).all { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && importChunk.matches(it.name) }
    }

    private fun finishImportFiles(dir: File, id: String, root: File, trash: File, owner: UserPrincipal, staged: Boolean): PurgeResult {
        if (!containsOnlyImportFiles(dir, staged, owner)) return PurgeResult.COMPLETE
        val original = File(trash, id)
        val active = File(root, id)
        // A recoverable or already restored note keeps all its bytes. Never overwrite a
        // chunk or change playback metadata. Full transfer and restore move this folder too.
        val parent = when {
            Files.isDirectory(original.toPath(), NOFOLLOW_LINKS) -> original
            !Files.notExists(original.toPath(), NOFOLLOW_LINKS) -> return PurgeResult.INCOMPLETE
            staged && Files.isDirectory(active.toPath(), NOFOLLOW_LINKS) && !containsOnlyImportFiles(active, false) -> active
            staged && !Files.isDirectory(active.toPath(), NOFOLLOW_LINKS) && !Files.notExists(active.toPath(), NOFOLLOW_LINKS) -> return PurgeResult.INCOMPLETE
            else -> null
        }
        if (!ensureWritable(dir.parentFile!!, owner)) return PurgeResult.ACCESS_DENIED
        if (parent != null) {
            if (!ensureWritable(parent, owner)) return PurgeResult.ACCESS_DENIED
            var extra = File(parent, "interrupted-import")
            var sequence = 1
            while (Files.exists(extra.toPath(), NOFOLLOW_LINKS)) extra = File(parent, "interrupted-import-${sequence++}")
            if (!moveDirectory(dir, extra)) return PurgeResult.INCOMPLETE
            return PurgeResult.COMPLETE
        }
        val pending = File(trash, DETACHED_PREFIX + id)
        if (!staged && (!canMove(dir, pending) || !moveDirectory(dir, pending))) return PurgeResult.INCOMPLETE
        val result = purgeEntry(pending, owner)
        if (result == PurgeResult.COMPLETE) UserFiles.syncDirectory(trash)
        return result
    }

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
        if (!Files.isDirectory(trash.toPath(), NOFOLLOW_LINKS) || !Files.isWritable(trash.toPath())) return@synchronized
        val limit = nowMs - DAYS * 24L * 3_600_000L
        trash.listFiles { dir -> Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) }.orEmpty().forEach { dir ->
            if (stagedId(dir) != null) return@forEach
            val deleted = deletedAt(readMeta(dir)) ?: return@forEach
            if (deleted < limit) purgeEntry(dir, owner = null)
        }
    }

    /** A null listing means the trash could not be read. Damaged entries still show up for deletion. */
    fun list(trash: File): List<TrashedNote>? {
        if (!Files.isDirectory(trash.toPath(), NOFOLLOW_LINKS)) return null
        return trash.listFiles()?.mapNotNull { dir ->
            if (stagedId(dir) != null && runCatching { containsOnlyImportFiles(dir, true) }.getOrDefault(true)) return@mapNotNull null
            val directory = Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS)
            val meta = if (directory) readMeta(dir)?.takeIf { stagedId(dir) == null || it.has("startedAtMs") } else null
            TrashedNote(
                id = dir.name,
                startedAtMs = meta?.optLong("startedAtMs") ?: 0,
                deletedAtMs = deletedAt(meta) ?: dir.lastModified(),
                durationMs = meta?.optLong("durationMs") ?: 0,
                // A cancelled recording has no text yet: what was heard while it went on.
                text = if (directory)
                    runCatching { File(dir, "text.txt").readText() }.getOrNull() ?: runCatching { File(dir, "live.txt").readText() }.getOrDefault("")
                else "",
                unrecognized = meta?.optString("state").let { it == NoteStore.STATE_RECORDED || it == NoteStore.STATE_RECOGNIZING },
                readable = meta != null,
            )
        }?.sortedByDescending { it.deletedAtMs }
    }

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

    enum class PurgeResult {
        COMPLETE, INCOMPLETE, ACCESS_DENIED;

        fun combine(other: PurgeResult): PurgeResult = maxOf(this, other)
    }

    /** Attempts each entry even if another entry fails. */
    fun purge(
        trash: File,
        ids: List<String>? = null,
    ): PurgeResult = synchronized(NoteStore) {
        attemptDeletion {
            if (!Files.isDirectory(trash.toPath(), NOFOLLOW_LINKS)) return@attemptDeletion PurgeResult.INCOMPLETE
            val owner = trash.parentFile?.let { parent ->
                runCatching { Files.getOwner(parent.toPath(), NOFOLLOW_LINKS) }.getOrNull()
            }
            if (!ensureWritable(trash, owner)) return@attemptDeletion PurgeResult.ACCESS_DENIED
            val targets = ids?.filter(::validId)?.map { File(trash, it) } ?: children(trash)
            var result = if (ids == null || targets.size == ids.size) PurgeResult.COMPLETE else PurgeResult.INCOMPLETE
            for (target in targets) {
                val entry = purgeEntry(target, owner)
                result = result.combine(entry)
            }
            if (result == PurgeResult.COMPLETE && targets.isNotEmpty()) UserFiles.syncDirectory(trash)
            result
        }
    }

    private fun purgeEntry(entry: File, owner: UserPrincipal?, keepTranscript: Boolean = true): PurgeResult = attemptDeletion {
        if (Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS)) {
            if (!ensureWritable(entry, owner)) return@attemptDeletion PurgeResult.ACCESS_DENIED
            val files = children(entry)
            // Keep the transcript and metadata readable if removing one of the other files fails.
            val ordered = if (keepTranscript) {
                val last = listOf("live.txt", "text.txt", "meta.json")
                val byName = files.associateBy { it.name }
                files.filterNot { it.name in last } + last.mapNotNull(byName::get)
            } else files
            for (file in ordered) {
                val result = purgeEntry(file, owner, keepTranscript = false)
                if (result != PurgeResult.COMPLETE) return@attemptDeletion result
            }
        }
        Files.deleteIfExists(entry.toPath())
        PurgeResult.COMPLETE
    }

    private fun children(dir: File): List<File> =
        Files.newDirectoryStream(dir.toPath()).use { entries -> entries.map { it.toFile() } }

    private fun ensureWritable(dir: File, owner: UserPrincipal?): Boolean {
        val path = dir.toPath()
        if (owner != null && (!Files.isWritable(path) || !Files.isReadable(path) || !Files.isExecutable(path))) {
            // Manual removal can repair owner bits on real directories inside the app's trash.
            val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            val attributes = view?.readAttributes()
            if (view != null && attributes?.isDirectory == true && attributes.owner() == owner) {
                val current = attributes.permissions()
                val required = current + setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)
                if (required != current) view.setPermissions(required)
            }
        }
        return Files.isWritable(path) && Files.isReadable(path) && Files.isExecutable(path)
    }

    private inline fun attemptDeletion(action: () -> PurgeResult): PurgeResult = try {
        action()
    } catch (error: DirectoryIteratorException) {
        if (error.cause is AccessDeniedException) PurgeResult.ACCESS_DENIED else PurgeResult.INCOMPLETE
    } catch (_: AccessDeniedException) {
        PurgeResult.ACCESS_DENIED
    } catch (_: SecurityException) {
        PurgeResult.ACCESS_DENIED
    } catch (_: IOException) {
        PurgeResult.INCOMPLETE
    }
}
