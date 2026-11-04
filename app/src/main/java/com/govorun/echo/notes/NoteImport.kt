package com.govorun.echo.notes

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.govorun.echo.AppSettings
import org.json.JSONObject
import java.io.File
import com.govorun.echo.audio.AudioDecoder
import com.govorun.echo.audio.AudioFormats
import com.govorun.echo.audio.LivePeaks

/**
 * A shared audio or video file becomes a note. A compressed sound the phone plays well (a voice message, a recorder's file, MP3)
 * is kept as it came and can be played at once; anything else (video, WAV) is compressed to Opus after recognition, as a recording
 * is. Either way the sound is decoded once to raw audio for Govorun, and the bars are measured in the same pass.
 */
internal object NoteImport {
    private const val MIN_BYTES = 3_200L

    /** The new note's id once its audio is ready for recognition; null if the file could not be read (the note then says so). */
    fun prepare(
        context: Context,
        uri: Uri,
    ): String? {
        val (id, dir) = NoteStore.newNote(context)
        preparing.add(id)
        try {
            return prepareNote(context, uri, id, dir)
        } finally {
            preparing.remove(id)
        }
    }

    private fun prepareNote(
        context: Context,
        uri: Uri,
        id: String,
        dir: File,
    ): String? {
        NoteStore.writeMeta(
            dir,
            JSONObject()
                .put("mode", MODE).put("unseen", true)
                .put("startedAtMs", System.currentTimeMillis())
                .put("state", NoteStore.STATE_RECOGNIZING)
                .put("error", PREPARING),
        )
        // The only real limit on what can be imported: room for its decoded sound (16 kHz, about 115 MB an hour) while it is
        // recognized. Several files queue up; length and number are not limited, the phone's temperature is minded instead.
        val length = durationMs(context, uri)
        val needed = length * NoteStore.BYTES_PER_SECOND / 1_000L + NoteStore.MIN_FREE_BYTES
        if (context.filesDir.usableSpace < needed) {
            NoteStore.updateMeta(dir) {
                put("state", NoteStore.STATE_FAILED)
                put("error", "storage_low")
            }
            return null
        }
        val kept = keepOriginal(context, uri, dir)
        val part = File(dir, "audio.pcm.part")
        val peaks = LivePeaks()
        // The card shows its progress line from the start: how much of the file is decoded, while it is.
        if (length > 0) NoteStore.updateMeta(dir) { put("totalMs", length) }
        val watch =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor().apply {
                scheduleWithFixedDelay({ NoteStore.updateMeta(dir) { put("preparedMs", part.length() * 1_000L / NoteStore.BYTES_PER_SECOND) } }, 1, 2, java.util.concurrent.TimeUnit.SECONDS)
            }
        val ok = try {
            context.filesDir.usableSpace > NoteStore.MIN_FREE_BYTES && AudioDecoder.decodeUri(context, uri, part, peaks) && part.length() >= MIN_BYTES
        } finally {
            watch.shutdownNow()
            NoteStore.updateMeta(dir) { remove("preparedMs") }
        }
        val pcm = NoteStore.pcmFile(dir)
        if (!ok || !part.renameTo(pcm)) {
            part.delete()
            NoteStore.updateMeta(dir) {
                put("state", NoteStore.STATE_FAILED)
                put("error", "import_failed")
            }
            return null
        }
        val ms = pcm.length() * 1_000L / NoteStore.BYTES_PER_SECOND
        AudioPeaks.saveRecorded(dir, peaks.bytes(), 0L)
        NoteStore.updateMeta(dir) {
            put("durationMs", ms)
            put("bytes", pcm.length())
            put("error", "")
            // The kept file holds all of the sound: nothing is compressed from the raw audio, which goes once the text is there.
            // Its length is the decoded one, so the bars and the times of the words match it.
            if (kept != null) {
                put("audio", JSONObject().put(kept, ms))
                put("archivedBytes", pcm.length())
            }
        }
        return id
    }

    /** The file's length as its container says, 0 when it does not say. */
    private fun durationMs(
        context: Context,
        uri: Uri,
    ): Long =
        runCatching {
            android.media.MediaMetadataRetriever().use {
                it.setDataSource(context, uri)
                it.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)

    /** Copies a compressed audio file into the note as its sound; its name, or null when it is to be compressed instead. */
    private fun keepOriginal(
        context: Context,
        uri: Uri,
        dir: File,
    ): String? =
        runCatching {
            val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val formats = try { (0 until extractor.trackCount).map { extractor.getTrackFormat(it) } } finally { extractor.release() }
            if (formats.any { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }) return@runCatching null
            val audio = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") } ?: return@runCatching null
            if (!AudioFormats.keepsAsIs(audio.getString(MediaFormat.KEY_MIME).orEmpty())) return@runCatching null
            val extension = AudioFormats.extensionFor(displayName(context, uri), context.contentResolver.getType(uri)) ?: return@runCatching null
            val chunks = File(dir, "chunks").apply { mkdirs() }
            val name = "000.$extension"
            val partial = File(chunks, "$name.part")
            val copied = context.contentResolver.openInputStream(uri)?.use { input -> partial.outputStream().use { input.copyTo(it, 256 * 1024) } }
            val target = File(chunks, name)
            if (copied == null || !partial.renameTo(target)) {
                partial.delete()
                return@runCatching null
            }
            // Playable now, while the sound is still being decoded for Govorun.
            val durationUs = if (audio.containsKey(MediaFormat.KEY_DURATION)) audio.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > 0) NoteStore.updateMeta(dir) { put("audio", JSONObject().put(name, durationUs / 1_000)) }
            name
        }.getOrNull()

    private fun displayName(
        context: Context,
        uri: Uri,
    ): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment

    /** Notes whose sound is being prepared right now; a resume of unfinished work must leave them alone. */
    val preparing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    const val MODE = "import"

    /** A recording, or a file under five minutes: it goes in the queue's quick lane and never waits behind an hour-long import. */
    fun isQuick(dir: java.io.File): Boolean {
        val meta = runCatching { JSONObject(java.io.File(dir, "meta.json").readText()) }.getOrNull() ?: return true
        return meta.optString("mode") != MODE || meta.optLong("durationMs") < 5 * 60_000L
    }
    const val PREPARING = "preparing"
}
