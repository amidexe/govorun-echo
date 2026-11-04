package com.govorun.echo.notes

import android.util.Log
import com.govorun.echo.audio.AudioDecoder
import com.govorun.echo.audio.AudioJoiner
import com.govorun.echo.audio.OggOpusCut
import com.govorun.echo.audio.OpusFileEncoder
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToLong

/**
 * The sound of a piece of a note: Ogg Opus is cut packet by packet without decoding, a whole file is reused as it is, and other
 * formats are encoded into the note's own Opus.
 */
internal object CutSound {
    private const val TAG = "NoteCut"

    /** The sound of [fromMs]-[toMs] as one file for the share sheet; null when it could not be made. */
    fun soundFile(
        dir: File,
        fromMs: Long,
        toMs: Long,
        target: File,
        transcode: (File, File, Double, Double) -> Boolean = ::transcode,
    ): File? = soundFile(dir, listOf(NoteCut.Span(fromMs.toDouble(), toMs.toDouble())), target, transcode)

    fun soundFile(
        dir: File,
        spans: List<NoteCut.Span>,
        target: File,
        transcode: (File, File, Double, Double) -> Boolean = ::transcode,
    ): File? {
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return null
        val wanted = CutModel.normalized(spans, NoteStore.audioParts(meta).sumOf { it.ms })
        val fromMs = wanted.firstOrNull()?.fromMs?.roundToLong() ?: return null
        val toMs = wanted.last().toMs.roundToLong()
        val work = File(target.parentFile, "${target.nameWithoutExtension}.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val pieces = soundOf(dir, NoteStore.audioParts(meta), wanted, work, exact = true, transcode) ?: return null
            val files = pieces.map { it.reused ?: it.file!! }
            return when {
                files.isEmpty() -> null
                files.size == 1 -> files.single().copyTo(target, overwrite = true)
                // The pieces of a continued note: copied into one file packet by packet, as an export does.
                else -> AudioJoiner.join(files, target) ?: run {
                    // Different formats cannot share a file: everything is encoded once more into the note's own format.
                    val joined = File(work, "joined.ogg")
                    if (transcodeAll(dir, NoteStore.audioParts(meta), wanted, joined)) joined.copyTo(target, overwrite = true) else null
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "event=sound_failed note=${dir.name}", error)
            return null
        } finally {
            work.deleteRecursively()
        }
    }

    /** One piece of the new sound: a new file, or a whole old file reused as it is; [span] is what it holds in the old note's time. */
    class Piece(
        val file: File?,
        val reused: File?,
        val span: NoteCut.Span,
    ) {
        val ms: Long get() = span.length.roundToLong()
    }

    /**
     * The sound of the [wanted] stretches (in the note's time) as pieces, one per old file each stretch touches. A whole file is
     * reused; Ogg Opus is cut packet by packet ([exact] for a copy, see [OggOpusCut.copy]); other formats are encoded into Opus.
     */
    fun soundOf(
        dir: File,
        parts: List<NoteStore.AudioPart>,
        wanted: List<NoteCut.Span>,
        work: File,
        exact: Boolean,
        transcode: (File, File, Double, Double) -> Boolean,
    ): List<Piece>? {
        val pieces = mutableListOf<Piece>()
        var offset = 0.0
        parts.forEach { part ->
            val source = File(dir, "chunks/${part.file}")
            val end = offset + part.ms
            wanted.forEach { span ->
                val from = maxOf(span.fromMs, offset)
                val to = minOf(span.toMs, end)
                if (to - from < 1.0) return@forEach
                val localFrom = from - offset
                val localTo = to - offset
                if (localFrom <= 0.0 && localTo >= part.ms) {
                    pieces += Piece(null, source, NoteCut.Span(from, to))
                    return@forEach
                }
                val out = File(work, "${pieces.size}.ogg")
                // What reaches the end of a file is cut "to the end", so the file's own end trim is kept.
                val until = if (localTo >= part.ms) Double.MAX_VALUE else localTo
                if (OggOpusCut.isOpus(source)) {
                    val kept = OggOpusCut.copy(source, out, localFrom, until, exact) ?: return@forEach
                    pieces += Piece(out, null, NoteCut.Span(offset + kept.fromMs, minOf(end, offset + kept.toMs)))
                } else {
                    if (!transcode(source, out, localFrom, minOf(localTo, part.ms.toDouble()))) return null
                    pieces += Piece(out, null, NoteCut.Span(from, to))
                }
            }
            offset = end
        }
        return pieces
    }

    /** The [spans] across a note's files encoded one after another into one Opus file. */
    private fun transcodeAll(
        dir: File,
        parts: List<NoteStore.AudioPart>,
        spans: List<NoteCut.Span>,
        target: File,
    ): Boolean {
        val encoder = OpusFileEncoder(target)
        var ok = true
        try {
            for (span in spans) {
                var offset = 0.0
                for (part in parts) {
                    val from = maxOf(span.fromMs, offset) - offset
                    val to = minOf(span.toMs, offset + part.ms) - offset
                    if (to > from) ok = ok && decodeRange(File(dir, "chunks/${part.file}"), from, to) { bytes, length -> encoder.write(bytes, length) }
                    offset += part.ms
                }
            }
        } finally {
            encoder.finish()
        }
        return ok
    }

    /** Decodes [source] and encodes its [fromMs]-[toMs] into an Ogg Opus [target], the note's own format (16 kHz mono). */
    fun transcode(
        source: File,
        target: File,
        fromMs: Double,
        toMs: Double,
    ): Boolean {
        val encoder = OpusFileEncoder(target)
        val ok =
            try {
                decodeRange(source, fromMs, toMs) { bytes, length -> encoder.write(bytes, length) }
            } finally {
                encoder.finish()
            }
        if (!ok) target.delete()
        return ok
    }

    /** Hands the 16 kHz PCM of [fromMs]-[toMs] of [source] to [sink]; decoding stops once the range is passed. */
    private fun decodeRange(
        source: File,
        fromMs: Double,
        toMs: Double,
        sink: (ByteArray, Int) -> Unit,
    ): Boolean {
        val bytesPerMs = NoteStore.BYTES_PER_SECOND / 1_000.0
        val first = ((fromMs * bytesPerMs).roundToLong() / 2) * 2
        val last = ((toMs * bytesPerMs).roundToLong() / 2) * 2
        val range = RangeStream(first, last, sink)
        val decoded = AudioDecoder.decodeFile(source, range, null)
        return decoded || range.done
    }

    /** Passes on only the bytes between [first] and [last]; past the end it stops the decoder by throwing. */
    private class RangeStream(
        private val first: Long,
        private val last: Long,
        private val sink: (ByteArray, Int) -> Unit,
    ) : OutputStream() {
        private var position = 0L
        var done = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (done) throw Finished()
            val from = maxOf(position, first)
            val to = minOf(position + len, last)
            if (to > from) {
                val start = (from - position).toInt()
                sink(b.copyOfRange(off + start, off + start + (to - from).toInt()), (to - from).toInt())
            }
            position += len
            if (position >= last) {
                done = true
                throw Finished()
            }
        }
    }

    private class Finished : RuntimeException("range_done")
}
