package com.govorun.echo.audio

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Cuts an Ogg Opus file without decoding it (RFC 7845): the Opus packets are copied as they are and only the Ogg pages around
 * them are written anew. Opus counts time in 48 kHz samples; the head's pre-skip is how many decoded samples come before the sound,
 * and the last page's position may end the sound before the end of its last packet.
 */
internal object OggOpusCut {
    /** An Opus decoder needs 80 ms of earlier packets to settle (RFC 7845, 4.6 and 5.1). */
    const val PRE_ROLL = 3_840
    private const val PER_MS = OggOpus.GRANULE_RATE / 1_000.0

    /** What part of the source an output holds, in milliseconds of the source's sound. */
    data class Kept(
        val fromMs: Double,
        val toMs: Double,
    )

    /** Whether [file] is an Ogg stream that carries Opus. */
    fun isOpus(file: File): Boolean =
        runCatching { file.inputStream().buffered().use { input -> PacketReader(input).next()?.bytes?.let { it.size >= 19 && it.copyOf(8).contentEquals(OggOpus.OPUS_HEAD) } } }.getOrNull() == true

    /**
     * Writes the sound of [fromMs] to [toMs] of [source] into [target]; a [toMs] past the end means "to the end".
     *
     * [exact] is for a copy that leaves the source as it is ("Отдельно", "Поделиться"): decoding starts 80 ms early so the decoder
     * settles, and the pre-skip and the last page cut the sound to the sample. Without [exact] what lies outside the range is being
     * deleted and must not stay in any file: only whole packets inside the range are written, so the kept sound begins and ends on a
     * packet boundary (20 ms in the app's recordings) a little inside the range, where a cut point always sits in a pause.
     *
     * Returns what the output holds, or null (and no output) when the range holds no whole packet or the file is not Ogg Opus.
     */
    fun copy(
        source: File,
        target: File,
        fromMs: Double,
        toMs: Double,
        exact: Boolean,
    ): Kept? {
        val partial = File(target.path + ".part")
        val kept =
            try {
                source.inputStream().buffered(64 * 1024).use { input -> partial.outputStream().buffered(64 * 1024).use { out -> cut(PacketReader(input), out, fromMs, toMs, exact) } }
            } catch (error: Exception) {
                partial.delete()
                throw error
            }
        if (kept == null || !partial.renameTo(target)) {
            partial.delete()
            return null
        }
        return kept
    }

    private fun cut(
        reader: PacketReader,
        out: OutputStream,
        fromMs: Double,
        toMs: Double,
        exact: Boolean,
    ): Kept? {
        val head = reader.next()?.bytes ?: return null
        if (head.size < 19 || !head.copyOf(8).contentEquals(OggOpus.OPUS_HEAD)) return null
        val preSkip = OggOpus.preSkipOf(head) ?: return null
        reader.next() ?: return null // OpusTags: written anew below
        // Where the stream starts: a stream that was itself cut may not start at sample 0 (RFC 7845, 4.5).
        val firstPage = reader.wholePage() ?: return null
        val origin = firstPage.last().granule - firstPage.sumOf { OggOpus.samplesOf(it.bytes).toLong() }
        val from48 = Math.round(fromMs * PER_MS)
        // "To the end" may come as a huge number: kept far from overflowing when the pre-skip is added.
        val to48 = minOf(Math.round(toMs * PER_MS), Long.MAX_VALUE / 4)
        // The first packet to keep: with [exact], the one 80 ms before the start; otherwise the first one wholly inside the range.
        val startAt = if (exact) from48 + preSkip - PRE_ROLL else from48 + preSkip
        val pending = ArrayDeque<OggPacket>().apply { addAll(firstPage) }
        var position = origin
        var writer: PageWriter? = null
        // The new stream counts its positions from [shift] in the old one: a copy from the very start keeps the old count (and so
        // the same length as the note says), a copy from the middle starts again from zero.
        var shift = 0L
        var newPreSkip = 0
        var last: OggPacket? = null
        var reachedEnd = true
        while (true) {
            val packet = pending.removeFirstOrNull() ?: reader.next() ?: break
            val samples = OggOpus.samplesOf(packet.bytes)
            val end = position + samples
            if (writer == null) {
                val begins =
                    when {
                        from48 <= 0 -> true
                        exact -> end > startAt
                        else -> position >= startAt
                    }
                if (!begins) {
                    position = end
                    continue
                }
                val fromStart = position == origin
                shift = if (fromStart) 0L else position
                newPreSkip =
                    when {
                        fromStart -> preSkip + (if (exact) from48.toInt() else 0)
                        exact -> (from48 + preSkip - position).toInt()
                        else -> 0
                    }
                writer = PageWriter(out, head, newPreSkip, if (fromStart) origin else 0L)
            }
            // The last packet to keep: with [exact], the one that holds the end; otherwise the last one that ends inside the range.
            val inside = if (exact) position < to48 + preSkip else end <= to48 + preSkip
            if (!inside) {
                reachedEnd = false
                break
            }
            last = packet
            writer.add(packet.bytes, samples)
            position = end
        }
        val w = writer ?: return null
        val lastPacket = last ?: return null
        val granule =
            when {
                // To the end of the source: the source's own end trim is kept.
                reachedEnd -> lastPacket.granule - shift
                exact -> newPreSkip + (to48 - from48)
                else -> position - shift
            }.coerceIn(newPreSkip.toLong(), position - shift)
        w.close(granule)
        // A moment of the new stream is the old one moved by [shift]; its sound starts where the new pre-skip ends.
        val keptFrom = (shift + newPreSkip - preSkip) / PER_MS
        val keptTo = (granule + shift - preSkip) / PER_MS
        return Kept(maxOf(0.0, keptFrom), keptTo)
    }

    /** One Opus packet and the position of the page it ended on. */
    private class OggPacket(
        val bytes: ByteArray,
        val granule: Long,
    )

    /** Reads the packets of the first logical stream of an Ogg file, page by page. */
    private class PacketReader(
        input: InputStream,
    ) {
        private val data = DataInputStream(input)
        private val ready = ArrayDeque<OggPacket>()
        private val partial = java.io.ByteArrayOutputStream()
        private var serial: Int? = null

        fun next(): OggPacket? {
            while (ready.isEmpty()) if (!readPage()) return null
            return ready.removeFirst()
        }

        /** All the packets that end on the next page, once the page in hand is used up. */
        fun wholePage(): List<OggPacket>? {
            if (ready.isEmpty() && !readPage()) return null
            return ready.toList().also { ready.clear() }
        }

        private fun readPage(): Boolean {
            while (true) {
                val header = ByteArray(27)
                try {
                    data.readFully(header)
                } catch (end: EOFException) {
                    return false
                }
                check(header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() && header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()) { "not an Ogg page" }
                val granule = (0 until 8).fold(0L) { acc, b -> acc or ((header[6 + b].toLong() and 0xFF) shl (8 * b)) }
                val pageSerial = (0 until 4).fold(0) { acc, b -> acc or ((header[14 + b].toInt() and 0xFF) shl (8 * b)) }
                val lacing = ByteArray(header[26].toInt() and 0xFF).also { data.readFully(it) }
                val body = ByteArray(lacing.sumOf { it.toInt() and 0xFF }).also { data.readFully(it) }
                if (serial == null) serial = pageSerial
                if (pageSerial != serial) continue
                var at = 0
                for (value in lacing) {
                    val size = value.toInt() and 0xFF
                    partial.write(body, at, size)
                    at += size
                    if (size < 255) {
                        ready.addLast(OggPacket(partial.toByteArray(), granule))
                        partial.reset()
                    }
                }
                if (ready.isNotEmpty()) return true
            }
        }
    }

    /** Writes an Ogg Opus stream: the head with its new pre-skip, short tags, then about a second of packets per page. */
    private class PageWriter(
        private val out: OutputStream,
        head: ByteArray,
        preSkip: Int,
        base: Long,
    ) {
        private val serial = (System.nanoTime() xor 0x0C07L).toInt()
        private var sequence = 0
        private var granule = base
        private val packets = ArrayList<ByteArray>()
        private var pageSamples = 0

        init {
            val newHead = head.copyOf()
            newHead[10] = preSkip.toByte()
            newHead[11] = (preSkip shr 8).toByte()
            page(listOf(newHead), 0L, BOS)
            page(listOf(tags()), 0L, 0)
        }

        fun add(
            packet: ByteArray,
            samples: Int,
        ) {
            if (packets.isNotEmpty() && (pageSamples + samples > OggOpus.GRANULE_RATE || segments(packets) + packet.size / 255 + 1 > MAX_SEGMENTS)) flush(0, null)
            packets += packet
            pageSamples += samples
        }

        /** Writes the last page; [end] is the position the sound ends at, which may be inside the last packet. */
        fun close(end: Long) {
            flush(EOS, end)
            out.flush()
        }

        private fun flush(
            flags: Int,
            end: Long?,
        ) {
            granule += pageSamples
            page(packets.toList(), end ?: granule, flags)
            packets.clear()
            pageSamples = 0
        }

        private fun segments(list: List<ByteArray>) = list.sumOf { it.size / 255 + 1 }

        private fun page(
            list: List<ByteArray>,
            position: Long,
            flags: Int,
        ) {
            val lacing = java.io.ByteArrayOutputStream()
            list.forEach { packet ->
                repeat(packet.size / 255) { lacing.write(255) }
                lacing.write(packet.size % 255)
            }
            val table = lacing.toByteArray()
            val header = ByteArray(27 + table.size)
            "OggS".toByteArray().copyInto(header, 0)
            header[5] = flags.toByte()
            for (b in 0 until 8) header[6 + b] = (position ushr (8 * b)).toByte()
            for (b in 0 until 4) header[14 + b] = (serial ushr (8 * b)).toByte()
            for (b in 0 until 4) header[18 + b] = (sequence ushr (8 * b)).toByte()
            header[26] = table.size.toByte()
            table.copyInto(header, 27)
            var crc = OggCrc.update(0, header)
            list.forEach { crc = OggCrc.update(crc, it) }
            for (b in 0 until 4) header[22 + b] = (crc ushr (8 * b)).toByte()
            out.write(header)
            list.forEach { out.write(it) }
            sequence++
        }

        private fun tags(): ByteArray {
            val vendor = "Govorun Echo".toByteArray()
            val bytes = java.io.ByteArrayOutputStream()
            bytes.write("OpusTags".toByteArray())
            bytes.write(le32(vendor.size))
            bytes.write(vendor)
            bytes.write(le32(0))
            return bytes.toByteArray()
        }

        private fun le32(value: Int) = ByteArray(4) { (value ushr (8 * it)).toByte() }

        private companion object {
            const val BOS = 0x02
            const val EOS = 0x04
            const val MAX_SEGMENTS = 255
        }
    }
}
