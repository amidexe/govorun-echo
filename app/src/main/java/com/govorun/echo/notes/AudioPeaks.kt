package com.govorun.echo.notes

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.govorun.echo.audio.AudioDecoder
import com.govorun.echo.audio.LivePeaks

/**
 * Loudness of a note over time, one byte per [STEP_MS], for the waveform in the player. It is read
 * back from the compressed audio once and kept next to it in `peaks.bin`, so recording itself is not
 * touched; a note whose audio changed (continued, shortened) is measured again.
 */
object AudioPeaks {
    const val STEP_MS = LivePeaks.STEP_MS

    suspend fun load(
        dir: File,
        audioParts: List<NoteStore.AudioPart>,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            // An imported file is measured while it is decoded for Govorun: wait for that instead of decoding it a second time.
            var parts = audioParts
            if (dir.name in NoteImport.preparing) {
                while (dir.name in NoteImport.preparing) kotlinx.coroutines.delay(IMPORT_POLL_MS)
                parts = runCatching { NoteStore.audioParts(org.json.JSONObject(File(dir, "meta.json").readText())) }.getOrDefault(parts)
            }
            val total = parts.sumOf { it.ms }
            val cache = File(dir, "peaks2.bin")
            runCatching {
                val saved = cache.readBytes()
                val covered = saved.size - HEADER
                if (covered > 0 && abs(coveredMs(saved) - total) <= TOLERANCE_MS) return@withContext saved.copyOfRange(HEADER, saved.size)
            }
            val out = ByteArrayOutputStream()
            // A continued note keeps the bars of what it had: only the sound added after them is measured, not the whole of it again
            // (five hours decode in two minutes, and that was repeated after every continuation that left the cache behind).
            val earlier = earlierBars(dir, parts)
            earlier?.let { out.write(it.first) }
            val startedAt = System.nanoTime()
            val ok = runCatching { parts.drop(earlier?.second ?: 0).forEach { decode(File(dir, "chunks/${it.file}"), out) } }.isSuccess
            android.util.Log.i("AudioPeaks", "event=peaks_decoded audioMs=$total reusedParts=${earlier?.second ?: 0} ms=${(System.nanoTime() - startedAt) / 1_000_000} ok=$ok")
            if (!ok || out.size() == 0) return@withContext null
            // The sound may have changed while it was measured (a piece cut and saved): bars of files that are no longer the note's
            // would hide the right ones, so they are neither kept nor shown.
            val current = runCatching { NoteStore.audioParts(org.json.JSONObject(File(dir, "meta.json").readText())) }.getOrNull()
            if (current != null && current != parts) return@withContext null
            val peaks = out.toByteArray()
            runCatching {
                val header = ByteArray(HEADER) { i -> ((peaks.size.toLong() * STEP_MS) shr (8 * i)).toByte() }
                cache.writeBytes(header + peaks)
            }
            peaks
        }

    /**
     * The saved bars when they end exactly where the first sound files of [parts] end, with how many files that is: what a note
     * had before it was continued. Null when there are none or they end in the middle of a file (they belong to other sound).
     */
    private fun earlierBars(
        dir: File,
        parts: List<NoteStore.AudioPart>,
    ): Pair<ByteArray, Int>? =
        runCatching {
            val saved = File(dir, "peaks2.bin").readBytes()
            if (saved.size <= HEADER) return@runCatching null
            val covered = coveredMs(saved)
            var end = 0L
            parts.forEachIndexed { index, part ->
                end += part.ms
                if (abs(end - covered) <= TOLERANCE_MS) return@runCatching (index + 1).takeIf { it < parts.size }?.let { saved.copyOfRange(HEADER, saved.size) to it }
            }
            null
        }.getOrNull()

    /**
     * Whatever bars are saved, without measuring anything: a recording being made over a continued note draws them as its past,
     * and must not start decoding hours of sound while the microphone is open.
     */
    fun saved(dir: File): ByteArray? =
        runCatching {
            val saved = File(dir, "peaks2.bin").readBytes()
            saved.takeIf { it.size > HEADER }?.copyOfRange(HEADER, saved.size)
        }.getOrNull()

    /**
     * Writes the loudness measured while recording, so the waveform never has to be decoded from the compressed sound. A note that
     * is continued keeps the bars of its earlier recordings and gets the new ones after them; when the earlier ones are missing
     * (a note made before this, or imported), nothing is written and the bars are decoded once, as before.
     */
    fun saveRecorded(
        dir: File,
        recorded: ByteArray,
        previousAudioMs: Long,
    ) {
        if (recorded.isEmpty()) return
        val cache = File(dir, "peaks2.bin")
        val earlier =
            if (previousAudioMs <= TOLERANCE_MS) {
                ByteArray(0)
            } else {
                runCatching {
                    val saved = cache.readBytes()
                    if (abs(coveredMs(saved) - previousAudioMs) <= TOLERANCE_MS) saved.copyOfRange(HEADER, saved.size) else null
                }.getOrNull() ?: return
            }
        val all = earlier + recorded
        runCatching {
            val header = ByteArray(HEADER) { i -> ((all.size.toLong() * STEP_MS) shr (8 * i)).toByte() }
            val tmp = File(dir, "peaks2.bin.tmp")
            tmp.writeBytes(header + all)
            tmp.renameTo(cache)
        }
    }

    /** The bars saved for a note when they cover its [totalMs] of sound; null when there are none or they belong to other sound. */
    internal fun savedBars(
        dir: File,
        totalMs: Long,
    ): ByteArray? =
        runCatching {
            val saved = File(dir, "peaks2.bin").readBytes()
            saved.takeIf { it.size > HEADER && abs(coveredMs(it) - totalMs) <= TOLERANCE_MS }?.copyOfRange(HEADER, saved.size)
        }.getOrNull()

    /** Saves the bars of a note whose sound was cut, so its waveform is not decoded again. */
    internal fun saveBars(
        dir: File,
        bars: ByteArray,
    ) {
        val header = ByteArray(HEADER) { i -> ((bars.size.toLong() * STEP_MS) shr (8 * i)).toByte() }
        val tmp = File(dir, "peaks2.bin.tmp")
        tmp.writeBytes(header + bars)
        check(tmp.renameTo(File(dir, "peaks2.bin"))) { "rename_failed" }
    }

    private fun coveredMs(saved: ByteArray): Long = (0 until HEADER).fold(0L) { acc, i -> acc or ((saved[i].toLong() and 0xFF) shl (8 * i)) }

    /** Bars of one sound file, measured the way a recording measures them, through the same fast decoder as an import. */
    private fun decode(
        file: File,
        out: ByteArrayOutputStream,
    ) {
        val peaks = LivePeaks()
        check(AudioDecoder.decodeFile(file, NOWHERE, peaks)) { "decode_failed" }
        out.write(peaks.bytes())
    }

    /** Only the bars are wanted, not the sound. */
    private val NOWHERE =
        object : java.io.OutputStream() {
            override fun write(b: Int) = Unit

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = Unit
        }


    private const val HEADER = 4
    private const val TOLERANCE_MS = 2_000L
    private const val IMPORT_POLL_MS = 500L
}
