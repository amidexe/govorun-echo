package com.govorun.echo.smart

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** A technical journal: keep the last valid generation; never interpret damage as an empty queue. */
internal object SmartQueueFile {
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun read(file: File): Map<String, FairQueue.Entry> {
        val backup = File(file.path + ".bak")
        if (!file.exists() && !backup.exists()) return emptyMap()
        for (candidate in listOf(file, backup)) {
            if (!candidate.isFile) continue
            try {
                if (candidate.length() > MAX_BYTES) throw IOException("smart_queue_too_large")
                val rows = FairQueue.decode(candidate.readText())
                // The older generation may predate hand-in. Do not treat its missing ID as permission to pay again.
                return if (candidate == file) rows else rows.mapValues { (_, row) ->
                    if (row.taskId != null) row else row.copy(atMs = FairQueue.HELD, failed = true, wait = "queue_recovered", message = "")
                }
            } catch (_: Exception) {
                // The other generation may still be complete. Leave damaged files intact for recovery.
            }
        }
        throw IOException("smart_queue_damaged")
    }

    fun write(file: File, entries: Map<String, FairQueue.Entry>, replace: (File, File) -> Unit = ::replace) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) { "smart_queue_directory" }
        val text = FairQueue.encode(entries)
        if (text.toByteArray().size > MAX_BYTES) throw IOException("smart_queue_too_large")
        val tmp = File(file.path + ".tmp")
        try {
            sync(tmp, text)
            // Do not overwrite a good backup with an unreadable primary.
            val previous = file.takeIf { it.isFile && it.length() <= MAX_BYTES }?.let { runCatching { it.readText().also(FairQueue::decode) }.getOrNull() }
            if (previous != null) {
                val backupTmp = File(file.path + ".bak.tmp")
                sync(backupTmp, previous)
                replace(backupTmp, File(file.path + ".bak"))
            }
            replace(tmp, file)
        } finally {
            tmp.delete()
            File(file.path + ".bak.tmp").delete()
        }
    }

    /** Copy old technical state only after its new durable copy exists; retain legacy on failure. */
    fun migrate(legacy: File, target: File) {
        if (target.exists() || File(target.path + ".bak").exists() || (!legacy.exists() && !File(legacy.path + ".bak").exists())) return
        write(target, read(legacy))
        check(!legacy.exists() || legacy.delete()) { "smart_queue_migration_cleanup" }
        File(legacy.path + ".bak").delete()
    }

    private fun sync(file: File, text: String) {
        FileOutputStream(file).use { output -> output.write(text.toByteArray()); output.fd.sync() }
    }

    private fun replace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
