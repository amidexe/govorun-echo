package com.govorun.echo.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.govorun.echo.notes.NoteStore
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

internal object FolderRestoreAudio {
    /** Finish downloading every part before adding any sound to an existing note. */
    fun restoreMissingSound(
        context: Context,
        tree: Uri,
        sounds: List<BackupFolder.FolderFile>,
        dir: File,
        checkAccess: () -> Unit,
    ): Boolean {
        val stage = Files.createTempDirectory(context.cacheDir.toPath(), "folder-audio-").toFile()
        try {
            val downloaded = JSONObject()
            bringSound(context.contentResolver, tree, sounds, stage, downloaded, checkAccess)
            checkAccess()
            return synchronized(NoteStore) {
                val latest = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
                    ?: return@synchronized false
                if (latest.optString("state") != NoteStore.STATE_DONE ||
                    latest.optString("mode") == com.govorun.echo.notes.NoteText.MODE ||
                    NoteStore.audioParts(latest).isNotEmpty() || NoteStore.pcmFile(dir).exists()
                ) return@synchronized false

                val chunks = File(dir, "chunks")
                check(chunks.isDirectory || chunks.mkdir()) { "chunks_unavailable" }
                val parts = NoteStore.audioParts(downloaded)
                val prefix = if (parts.any { File(chunks, it.file).exists() }) "restored-${UUID.randomUUID()}-" else ""
                val added = mutableListOf<File>()
                try {
                    val audio = JSONObject()
                    for (part in parts) {
                        val target = File(chunks, prefix + part.file)
                        Files.move(File(stage, "chunks/${part.file}").toPath(), target.toPath())
                        added += target
                        audio.put(target.name, part.ms)
                    }
                    latest.put("audio", audio).put("durationMs", maxOf(downloaded.optLong("durationMs"), latest.optLong("durationMs")))
                    NoteStore.writeMeta(dir, latest)
                    true
                } catch (failure: Exception) {
                    added.forEach { it.delete() }
                    throw failure
                }
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    /** The folder's sound files of a note into its `chunks/`, and into its [meta] their names and length. */
    fun bringSound(
        resolver: android.content.ContentResolver,
        tree: Uri,
        sounds: List<BackupFolder.FolderFile>,
        dir: File,
        meta: JSONObject,
        checkAccess: () -> Unit,
    ) {
        if (sounds.isEmpty()) return
        val audio = JSONObject()
        var durationMs = 0L
        sounds.sortedBy { it.part }.forEachIndexed { index, f ->
            checkAccess()
            val name = "%03d.%s".format(index, f.ext)
            val target = File(dir, "chunks/$name").apply { parentFile?.mkdirs() }
            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, f.id))?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("open_failed")
            val ms = audioLength(target)
            audio.put(name, ms)
            durationMs += ms
        }
        meta.put("audio", audio).put("durationMs", maxOf(durationMs, meta.optLong("durationMs")))
    }

    private fun audioLength(file: File): Long =
        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
}
