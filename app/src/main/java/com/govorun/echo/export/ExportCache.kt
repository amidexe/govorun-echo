package com.govorun.echo.export

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Temporary exports live apart so another export cannot invalidate an existing share URI. */
internal object ExportCache {
    internal val retentionMs = TimeUnit.HOURS.toMillis(24)
    private val cleanup = Executors.newSingleThreadScheduledExecutor()
    private val preparing = mutableSetOf<File>()

    @Synchronized
    fun create(context: Context): File {
        val root = root(context).apply { mkdirs() }
        prune(root)
        return Files.createTempDirectory(root.toPath(), "share-").toFile().also { preparing += it }
    }

    @Synchronized
    fun finish(directory: File, handedOff: Boolean) {
        preparing -= directory
        if (!handedOff) {
            directory.deleteRecursively()
            return
        }
        // A chooser closing does not tell us whether a recipient has finished reading.
        directory.setLastModified(System.currentTimeMillis())
        val root = checkNotNull(directory.parentFile)
        cleanup.schedule({ prune(root) }, retentionMs, TimeUnit.MILLISECONDS)
    }

    fun cleanAsync(context: Context) {
        val root = root(context)
        cleanup.execute { prune(root) }
    }

    @Synchronized
    internal fun prune(root: File, nowMs: Long = System.currentTimeMillis()) {
        root.listFiles().orEmpty().forEach { entry ->
            if (entry !in preparing && entry.lastModified() <= nowMs - retentionMs) {
                if (Files.isSymbolicLink(entry.toPath())) entry.delete() else entry.deleteRecursively()
            }
        }
    }

    private fun root(context: Context) = File(context.cacheDir, "exports")
}
