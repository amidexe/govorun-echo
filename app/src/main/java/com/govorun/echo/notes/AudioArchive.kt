package com.govorun.echo.notes

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import com.govorun.echo.audio.OggOpus
import com.govorun.echo.audio.OpusFileEncoder

/** Compressing raw 16 kHz sound into the note's Ogg Opus files. */
internal object AudioArchive {
    private const val TAG = "AudioArchive"

    /** A range of a raw 16 kHz PCM file compressed into [target]; returns the file on success, null (and no output) on failure. */
    fun encode(
        pcm: File,
        offsetBytes: Long,
        lengthBytes: Long,
        target: File,
    ): File? {
        val partial = File(target.path + ".part")
        try {
            val encoder = OpusFileEncoder(partial)
            try {
                pcm.inputStream().buffered().use { input ->
                    input.skip(offsetBytes)
                    val bytes = ByteArray(8_192)
                    var remaining = lengthBytes
                    while (remaining > 0) {
                        val read = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        encoder.write(bytes, read)
                        remaining -= read
                    }
                }
            } finally {
                encoder.finish()
            }
            return if (partial.renameTo(target)) target else null
        } catch (error: Exception) {
            Log.w(TAG, "event=encode_failed file=${pcm.name}", error)
            return null
        } finally {
            partial.delete()
        }
    }
}

/** Compresses the sound of a running recording as it arrives, in its own thread, so stopping has nothing left to compress. */
internal class ContinuousArchive(
    private val dir: File,
) {
    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_BUFFERS)
    private val end = ByteArray(0)

    @Volatile private var failed = false
    private val thread = Thread({ run() }, "echo-archive").also { it.start() }

    fun feed(
        buffer: ByteArray,
        length: Int,
    ) {
        if (failed) return
        if (!queue.offer(buffer.copyOf(length))) failed = true
    }

    private fun run() {
        try {
            if (NoteStore.ensureChunks(dir) == null) {
                failed = true
                return
            }
            val encoder = OpusFileEncoder(liveFile(dir))
            try {
                while (true) {
                    val data = queue.take()
                    if (data === end) break
                    encoder.write(data, data.size)
                }
            } finally {
                encoder.finish()
            }
        } catch (error: Exception) {
            failed = true
            Log.w("AudioArchive", "event=live_archive_failed", error)
        }
    }

    /** Ends the stream and waits for the last frames; what was written stays usable even if the stream failed on the way. */
    fun finish() {
        queue.offer(end, 5, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { thread.join(JOIN_MS) }
    }

    /** Leaves nothing behind: used when the recording is thrown away. */
    fun discard() {
        queue.clear()
        queue.offer(end)
        runCatching { thread.join(JOIN_MS) }
        liveFile(dir).delete()
    }

    companion object {
        private const val QUEUE_BUFFERS = 1_000
        private const val JOIN_MS = 20_000L

        fun liveFile(dir: File): File = File(dir, "chunks/live.ogg")

        /**
         * Makes the live file of a note (finished, or cut off by a crash) its next sound file: a rename, the file is already whole.
         * [archivedBytes] is set to what the file covers, so whatever it lacks is still archived from the raw 16 kHz file.
         */
        fun seal(dir: File) {
            // A stream left by a version that recorded AAC: the raw file still holds that sound and is archived from it.
            File(File(dir, "chunks"), "live.aac").delete()
            val live = liveFile(dir)
            if (!live.exists()) return
            val ms = OggOpus.durationMs(live)
            if (live.length() < MIN_STREAM_BYTES || ms == null || ms <= 0L) {
                live.delete()
                return
            }
            val index = runCatching { JSONObject(File(dir, "meta.json").readText()).optJSONObject("audio")?.length() }.getOrNull() ?: 0
            val target = File(File(dir, "chunks"), "%03d.ogg".format(index))
            if (!live.renameTo(target)) return
            val covered = minOf(NoteStore.pcmFile(dir).length(), ms * NoteStore.BYTES_PER_SECOND / 1_000)
            NoteStore.updateMeta(dir) {
                val parts = optJSONObject("audio") ?: JSONObject().also { put("audio", it) }
                parts.put(target.name, ms)
                put("archivedBytes", covered)
            }
        }

        private const val MIN_STREAM_BYTES = 200L
    }
}

/** Compresses a range of `audio.pcm` into the next `chunks/NNN.ogg` and records it in the note at once. */
internal fun archiveSlice(
    dir: File,
    pcm: File,
    offset: Long,
    length: Long,
    encode: (File, Long, Long, File) -> File? = AudioArchive::encode,
): Boolean {
    val chunks = NoteStore.ensureChunks(dir) ?: return false
    val index = runCatching { JSONObject(File(dir, "meta.json").readText()).optJSONObject("audio")?.length() }.getOrNull() ?: 0
    val file = File(chunks, "%03d.ogg".format(index))
    if (encode(pcm, offset, length, file) == null) {
        file.delete()
        return false
    }
    NoteStore.updateMeta(dir) {
        val parts = optJSONObject("audio") ?: JSONObject().also { put("audio", it) }
        parts.put(file.name, length * 1_000L / NoteStore.BYTES_PER_SECOND)
        put("archivedBytes", offset + length)
    }
    return true
}
