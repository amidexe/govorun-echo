package com.govorun.echo.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * The note's sound: Opus at 16 kHz in an Ogg file, written page by page while recording. Every page is complete on its own, so the
 * file plays at once when the recording stops (nothing is wrapped or converted afterwards) and a killed app loses at most a second.
 */
internal object OggOpus {
    const val BIT_RATE = 32_000

    /**
     * The system encoder works at its highest effort (10) unless told otherwise; on a Pixel 10 that cost a fifth of a core for the
     * whole recording. 5 is what WebRTC uses on phones: half the work, and speech at 32 kbit/s sounds the same.
     */
    const val COMPLEXITY = 5

    /** Opus counts time in 48 kHz samples whatever the input rate. */
    const val GRANULE_RATE = 48_000
    const val DEFAULT_PRE_SKIP = 312

    /** Length of an Ogg Opus file from its last complete page, in milliseconds; null when the file holds no sound. */
    fun durationMs(file: File): Long? {
        if (!file.exists() || file.length() < 47) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(raf.length(), 4_096L).toInt())
            raf.readFully(head)
            val preSkip = preSkipOf(head) ?: return null
            val tailStart = maxOf(0L, raf.length() - TAIL_BYTES)
            val tail = ByteArray((raf.length() - tailStart).toInt())
            raf.seek(tailStart)
            raf.readFully(tail)
            val granule = lastGranule(tail) ?: return null
            return ((granule - preSkip).coerceAtLeast(0L)) * 1_000L / GRANULE_RATE
        }
    }

    /** The pre-skip of the OpusHead at the start of a file. */
    internal fun preSkipOf(head: ByteArray): Int? {
        val at = indexOf(head, OPUS_HEAD)
        if (at < 0 || at + 12 > head.size) return null
        return (head[at + 10].toInt() and 0xFF) or ((head[at + 11].toInt() and 0xFF) shl 8)
    }

    /** The granule position of the last whole page in [bytes] that carries audio. */
    internal fun lastGranule(bytes: ByteArray): Long? {
        var best: Long? = null
        var i = 0
        while (i + 27 <= bytes.size) {
            if (bytes[i] == 'O'.code.toByte() && bytes[i + 1] == 'g'.code.toByte() && bytes[i + 2] == 'g'.code.toByte() && bytes[i + 3] == 'S'.code.toByte()) {
                val segments = bytes[i + 26].toInt() and 0xFF
                if (i + 27 + segments <= bytes.size) {
                    var body = 0
                    for (s in 0 until segments) body += bytes[i + 27 + s].toInt() and 0xFF
                    val end = i + 27 + segments + body
                    if (end <= bytes.size) {
                        var granule = 0L
                        for (b in 7 downTo 0) granule = (granule shl 8) or (bytes[i + 6 + b].toLong() and 0xFF)
                        if (granule > 0 && granule != -1L) best = granule
                        i = end
                        continue
                    }
                }
            }
            i++
        }
        return best
    }

    /** How many 48 kHz samples an Opus packet holds, from its first byte (RFC 6716, 3.1). */
    fun samplesOf(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frame48 =
            when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
                config < 16 -> intArrayOf(480, 960)[config and 1]
                else -> intArrayOf(120, 240, 480, 960)[config and 3]
            }
        val frames =
            when (toc and 3) {
                0 -> 1
                3 -> if (packet.size > 1) packet[1].toInt() and 0x3F else 1
                else -> 2
            }
        return frame48 * frames
    }

    private fun indexOf(
        bytes: ByteArray,
        what: ByteArray,
    ): Int {
        outer@ for (i in 0..bytes.size - what.size) {
            for (j in what.indices) if (bytes[i + j] != what[j]) continue@outer
            return i
        }
        return -1
    }

    val OPUS_HEAD = "OpusHead".toByteArray()
    private const val TAIL_BYTES = 64 * 1024L
}

/**
 * Writes Opus packets into Ogg pages (RFC 7845): the OpusHead and OpusTags pages first, then about a second of sound per page.
 * Not thread-safe; [close] writes the last page with the end-of-stream mark.
 */
internal class OggOpusWriter(
    private val out: OutputStream,
    private val inputRate: Int,
    private val preSkip: Int,
    private val serial: Int = (System.nanoTime() xor 0x5EC0L).toInt(),
) {
    private var sequence = 0
    private var granule = preSkip.toLong()
    private val packets = ArrayList<ByteArray>()
    private var pageSamples = 0

    init {
        page(head(), granule = 0, flags = BOS)
        page(tags(), granule = 0, flags = 0)
        out.flush()
    }

    /** Adds one Opus packet; a page goes to the disk once it holds about a second of sound. */
    fun write(packet: ByteArray) {
        packets += packet
        pageSamples += OggOpus.samplesOf(packet)
        if (pageSamples >= PAGE_SAMPLES || segmentsOf(packets) > MAX_SEGMENTS - 20) flushPage(flags = 0)
    }

    /**
     * Ends the stream. [inputSamples] is how much sound went into the encoder: the last page's position is cut to it, so the
     * encoder's padding of the final frame is not played.
     */
    fun close(inputSamples: Long? = null) {
        val exact = inputSamples?.let { preSkip + it * (OggOpus.GRANULE_RATE / inputRate) }
        flushPage(flags = EOS, cap = exact)
        out.flush()
        out.close()
    }

    private fun flushPage(
        flags: Int,
        cap: Long? = null,
    ) {
        if (packets.isEmpty() && flags != EOS) return
        granule += pageSamples
        val position = cap?.let { minOf(it, granule).coerceAtLeast(preSkip.toLong()) } ?: granule
        val body = java.io.ByteArrayOutputStream()
        packets.forEach { body.write(it) }
        page(body.toByteArray(), position, flags, packets.map { it.size })
        packets.clear()
        pageSamples = 0
        out.flush()
    }

    private fun page(
        body: ByteArray,
        granule: Long,
        flags: Int,
        sizes: List<Int> = listOf(body.size),
    ) {
        val lacing = java.io.ByteArrayOutputStream()
        sizes.forEach { size ->
            var left = size
            while (left >= 255) {
                lacing.write(255)
                left -= 255
            }
            lacing.write(left)
        }
        val table = lacing.toByteArray()
        val header = ByteArray(27 + table.size)
        "OggS".toByteArray().copyInto(header, 0)
        header[4] = 0
        header[5] = flags.toByte()
        for (b in 0 until 8) header[6 + b] = (granule ushr (8 * b)).toByte()
        for (b in 0 until 4) header[14 + b] = (serial ushr (8 * b)).toByte()
        for (b in 0 until 4) header[18 + b] = (sequence ushr (8 * b)).toByte()
        header[26] = table.size.toByte()
        table.copyInto(header, 27)
        val crc = OggCrc.update(OggCrc.update(0, header), body)
        for (b in 0 until 4) header[22 + b] = (crc ushr (8 * b)).toByte()
        out.write(header)
        out.write(body)
        sequence++
    }

    private fun head(): ByteArray {
        val bytes = ByteArray(19)
        OggOpus.OPUS_HEAD.copyInto(bytes, 0)
        bytes[8] = 1
        bytes[9] = 1
        bytes[10] = preSkip.toByte()
        bytes[11] = (preSkip shr 8).toByte()
        for (b in 0 until 4) bytes[12 + b] = (inputRate ushr (8 * b)).toByte()
        return bytes
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

    private fun segmentsOf(list: List<ByteArray>) = list.sumOf { it.size / 255 + 1 }

    private companion object {
        const val BOS = 0x02
        const val EOS = 0x04
        const val PAGE_SAMPLES = OggOpus.GRANULE_RATE
        const val MAX_SEGMENTS = 255
    }
}

/** The Ogg page checksum: CRC-32 with polynomial 0x04C11DB7, no reflection, starting from zero. */
internal object OggCrc {
    private val table =
        IntArray(256) { n ->
            var r = n shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

    fun update(
        crc: Int,
        bytes: ByteArray,
    ): Int {
        var c = crc
        for (b in bytes) c = (c shl 8) xor table[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }
}

/** Opus encoder (Android's own) that writes a 16 kHz mono stream into an Ogg file; calls are blocking and not thread-safe. */
internal class OpusFileEncoder(
    target: File,
    private val rate: Int = Pcm.SAMPLE_RATE,
    bitRate: Int = OggOpus.BIT_RATE,
) {
    private val out = target.outputStream().buffered(32 * 1024)
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var writer: OggOpusWriter? = null
    private var presentationUs = 0L
    private var inputSamples = 0L

    init {
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                setInteger(MediaFormat.KEY_COMPLEXITY, OggOpus.COMPLEXITY)
            }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(
        data: ByteArray,
        length: Int,
    ) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain()
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: error("no input buffer")
            buffer.clear()
            val count = minOf(buffer.capacity(), length - offset) and 1.inv()
            buffer.put(data, offset, count)
            codec.queueInputBuffer(index, 0, count, presentationUs, 0)
            inputSamples += count / 2
            presentationUs = inputSamples * 1_000_000L / rate
            offset += count
            drain()
        }
    }

    /** Flushes the encoder, writes the last page and releases everything. */
    fun finish() {
        try {
            var queued = false
            var tries = 0
            while (!queued && tries++ < MAX_WAIT_TRIES) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else {
                    drain()
                }
            }
            var done = false
            tries = 0
            while (!done && tries++ < MAX_WAIT_TRIES) done = drain(TIMEOUT_US)
            writerOrDefault().close(inputSamples)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { out.close() }
        }
    }

    private fun writerOrDefault(preSkip: Int = OggOpus.DEFAULT_PRE_SKIP) = writer ?: OggOpusWriter(out, rate, preSkip).also { writer = it }

    /** Writes every ready packet; returns true once the end of the stream came out. */
    private fun drain(waitUs: Long = 0L): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, waitUs)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The encoder tells its delay in its own OpusHead; the file's header carries it so players skip it.
                val csd = codec.outputFormat.takeIf { it.containsKey("csd-0") }?.getByteBuffer("csd-0")
                csd?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }?.let(OggOpus::preSkipOf)?.let { if (writer == null) writerOrDefault(it) }
                continue
            }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    if (writer == null) writerOrDefault(OggOpus.preSkipOf(bytes) ?: OggOpus.DEFAULT_PRE_SKIP)
                } else {
                    writerOrDefault().write(bytes)
                }
            }
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(index, false)
            if (end) return true
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_WAIT_TRIES = 500
    }
}
