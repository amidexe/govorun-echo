package com.govorun.echo.notes

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Logical sizes of regular files in the Android app's private data directory. */
internal data class LocalStorageUsage(
    val notes: Long,
    val trash: Long,
    val unlisted: Long,
    val other: Long,
    val cache: Long,
    val complete: Boolean,
) {
    val total: Long get() = notes + trash + unlisted + other + cache

    companion object {
        fun measure(dataDir: File, filesDir: File, cacheDir: File, codeCacheDir: File, noteIds: Set<String>): LocalStorageUsage {
            val notesRoot = File(filesDir, "notes").toPath()
            val trashRoot = File(filesDir, "trash").toPath()
            val cacheRoot = cacheDir.toPath()
            val codeCache = codeCacheDir.toPath()
            var notes = 0L
            var trash = 0L
            var unlisted = 0L
            var other = 0L
            var cache = 0L
            var complete = true
            // The default visitor never follows links out of app storage or into a cycle.
            val visitor = object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        val size = attrs.size()
                        when {
                            path.startsWith(trashRoot) -> trash += size
                            path.startsWith(notesRoot) && path.parent != notesRoot -> {
                                val id = notesRoot.relativize(path).getName(0).toString()
                                if (id in noteIds) notes += size else unlisted += size
                            }
                            path.startsWith(cacheRoot) || path.startsWith(codeCache) -> cache += size
                            else -> other += size
                        }
                    }
                    return CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    complete = false
                    return CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) complete = false
                    return CONTINUE
                }
            }
            try {
                Files.walkFileTree(dataDir.toPath(), visitor)
            } catch (_: IOException) {
                complete = false
            } catch (_: SecurityException) {
                complete = false
            }
            return LocalStorageUsage(notes, trash, unlisted, other, cache, complete)
        }
    }
}
