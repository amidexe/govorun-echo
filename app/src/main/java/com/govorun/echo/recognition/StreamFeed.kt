package com.govorun.echo.recognition

import android.os.ParcelFileDescriptor
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The note's side of a long recognition. Echo hands Govorun the sound itself, a second at a time, instead of the whole file at
 * once: so it knows how far Govorun got, can keep a hot phone from heating further and can stop when asked.
 */
interface RecognitionFeed {
    /** Before each second of sound: may wait (a hot phone), false stops the recognition. */
    suspend fun beforeChunk(): Boolean

    fun fed(
        ms: Long,
        totalMs: Long,
    )

    /** Everything recognized so far and its phrases with word times (counted from [fromMs]). */
    fun heard(
        text: String,
        phrases: List<NoteStore.Phrase>,
    )

    /** Where in the sound to start: a note paused part-way goes on from there. */
    val fromMs: Long get() = 0L
}

/** Neither a second of sound taken nor a phrase back for this long: Govorun is stuck, and the note says so. */
internal const val STALL_MS = 3L * 60_000L

/** Stopped from [RecognitionFeed.beforeChunk]: the owner postponed it. */
internal const val FAILED_STOPPED = -4

/** Govorun's answer, or null when the feed stopped it or nothing happened on either side for [STALL_MS]. */
internal suspend fun awaitFed(
    result: CompletableDeferred<Outcome>,
    lost: CompletableDeferred<Unit>,
    activeAt: java.util.concurrent.atomic.AtomicLong,
    stopped: java.util.concurrent.atomic.AtomicBoolean,
    holding: java.util.concurrent.atomic.AtomicBoolean,
): Outcome? {
    while (true) {
        val outcome =
            withTimeoutOrNull(1_000L) {
                kotlinx.coroutines.selects.select<Outcome> {
                    result.onAwait { it }
                    lost.onAwait { Outcome.Unreachable }
                }
            }
        if (outcome != null) return outcome
        if (stopped.get()) return null
        // Silence from both sides this long is a stall; a phone cooling down is not.
        if (holding.get()) activeAt.set(System.currentTimeMillis())
        if (System.currentTimeMillis() - activeAt.get() > STALL_MS) return null
    }
}

/**
 * Fills the pipe a second of sound at a time, asking [feed] first each time: a hot phone waits there, a postponed note stops.
 * Govorun reading slower than this blocks the write, so it never holds more than it is working on.
 */
internal suspend fun pump(
    file: File,
    sink: ParcelFileDescriptor,
    feed: RecognitionFeed,
    activeAt: java.util.concurrent.atomic.AtomicLong,
    stopped: java.util.concurrent.atomic.AtomicBoolean,
    holding: java.util.concurrent.atomic.AtomicBoolean,
) {
    val total = file.length()
    val totalMs = total * 1_000L / NoteStore.BYTES_PER_SECOND
    // 16-bit samples: the start is kept on a whole sample.
    val skip = (feed.fromMs * NoteStore.BYTES_PER_SECOND / 1_000L).coerceIn(0L, total) and 1L.inv()
    try {
        ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
            file.inputStream().buffered(NoteStore.BYTES_PER_SECOND).use { input ->
                var skipped = 0L
                while (skipped < skip) skipped += input.skip(skip - skipped).takeIf { it > 0 } ?: break
                val chunk = ByteArray(NoteStore.BYTES_PER_SECOND)
                var sent = skip
                while (true) {
                    holding.set(true)
                    val go = feed.beforeChunk()
                    holding.set(false)
                    if (!go) {
                        stopped.set(true)
                        return
                    }
                    activeAt.set(System.currentTimeMillis())
                    val read = input.read(chunk)
                    if (read <= 0) break
                    out.write(chunk, 0, read)
                    sent += read
                    activeAt.set(System.currentTimeMillis())
                    feed.fed(sent * 1_000L / NoteStore.BYTES_PER_SECOND, totalMs)
                }
            }
        }
    } catch (_: java.io.IOException) {
        // Govorun closed its end (an error, a cancel): the outcome comes from Govorun.
    }
}
