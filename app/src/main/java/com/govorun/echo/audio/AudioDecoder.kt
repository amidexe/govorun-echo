package com.govorun.echo.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Turns the sound of any audio or video file the phone can read into raw 16 kHz mono PCM for Govorun. */
internal object AudioDecoder {
    private const val TAG = "Echo"

    /** Decodes [uri] into [target]; the bars of the sound are measured on the way into [peaks]. */
    fun decodeUri(
        context: Context,
        uri: Uri,
        target: File,
        peaks: LivePeaks? = null,
    ): Boolean =
        try {
            val started = System.nanoTime()
            val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            try {
                target.outputStream().buffered(256 * 1024).use { decode(extractor, it, peaks) }
            } finally {
                extractor.release()
            }.also { Log.i(TAG, "event=decoded bytes=${target.length()} ms=${(System.nanoTime() - started) / 1_000_000}") }
        } catch (error: Exception) {
            Log.w(TAG, "event=decode_failed", error)
            false
        }

    /** The same for one of the app's own sound files: an older note whose bars were never measured is decoded only for them. */
    fun decodeFile(
        file: File,
        out: OutputStream,
        peaks: LivePeaks?,
    ): Boolean =
        try {
            val extractor = MediaExtractor().apply { setDataSource(file.path) }
            try {
                decode(extractor, out, peaks)
            } finally {
                extractor.release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "event=decode_failed file=${file.name}", error)
            false
        }

    /**
     * Decodes the first audio track. The codec runs in its own process, so every exchange with it costs a round trip: the codec is
     * driven by its callbacks, and where it takes several packets per buffer (Android 15+) a second of sound goes in one exchange.
     * On a Pixel 10 that decodes five minutes in about 1.4 s instead of 30-45 s.
     */
    private fun decode(
        extractor: MediaExtractor,
        out: OutputStream,
        peaks: LivePeaks?,
    ): Boolean {
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return false
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        val perBuffer = if (takesSeveralPackets(codec, mime)) PACKETS_PER_BUFFER else 1
        if (perBuffer > 1) {
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BATCH_INPUT_BYTES)
            format.setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
            format.setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, BATCH_OUTPUT_BYTES / 4)
        }
        val run = Run(extractor, Converter(format.optInt(MediaFormat.KEY_SAMPLE_RATE, Pcm.SAMPLE_RATE), format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1), out, peaks), out, peaks, perBuffer)
        val thread = android.os.HandlerThread("echo-decode").apply { start() }
        try {
            codec.setCallback(run, android.os.Handler(thread.looper))
            codec.configure(format, null, null, 0)
            codec.start()
            // A codec that stops answering does not hold the import for ever: it gives up after a minute without any output.
            var seen = -1L
            while (!run.done.await(STALL_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                if (run.progress == seen) {
                    run.failed = true
                    break
                }
                seen = run.progress
            }
            if (run.failed) return false
            run.converter.flush()
            return true
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            thread.quitSafely()
        }
    }

    /** Feeds the codec from the file and hands what comes out to the converter; every call comes on the decoding thread. */
    private class Run(
        private val extractor: MediaExtractor,
        @Volatile var converter: Converter,
        private val out: OutputStream,
        private val peaks: LivePeaks?,
        private val perBuffer: Int,
    ) : MediaCodec.Callback() {
        val done = java.util.concurrent.CountDownLatch(1)

        @Volatile var failed = false

        @Volatile var progress = 0L
        private var inputDone = false

        override fun onInputBufferAvailable(
            codec: MediaCodec,
            index: Int,
        ) {
            if (inputDone || failed) return
            try {
                val buffer = codec.getInputBuffer(index) ?: return
                val packets = java.util.ArrayDeque<MediaCodec.BufferInfo>()
                var offset = 0
                while (packets.size < perBuffer) {
                    val size = extractor.sampleSize
                    if (size < 0) {
                        inputDone = true
                        break
                    }
                    if (packets.isNotEmpty() && offset + size > buffer.capacity()) break
                    val read = extractor.readSampleData(buffer, offset)
                    packets.add(MediaCodec.BufferInfo().apply { set(offset, read, extractor.sampleTime, 0) })
                    offset += read
                    extractor.advance()
                }
                when {
                    packets.isEmpty() -> codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    // Above one packet only where takesSeveralPackets allowed it, Android 15+; the version is checked again for lint.
                    perBuffer > 1 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
                        if (inputDone) packets.last.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        codec.queueInputBuffers(index, packets)
                    }
                    else -> packets.first.let { codec.queueInputBuffer(index, it.offset, it.size, it.presentationTimeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0) }
                }
            } catch (error: Exception) {
                fail(error)
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) = take(codec, index, listOf(info))

        override fun onOutputBuffersAvailable(
            codec: MediaCodec,
            index: Int,
            infos: java.util.ArrayDeque<MediaCodec.BufferInfo>,
        ) = take(codec, index, infos.toList())

        private fun take(
            codec: MediaCodec,
            index: Int,
            frames: List<MediaCodec.BufferInfo>,
        ) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    for (frame in frames) {
                        if (frame.size <= 0) continue
                        val view = buffer.duplicate()
                        view.limit(frame.offset + frame.size)
                        view.position(frame.offset)
                        converter.add(view.slice().order(ByteOrder.nativeOrder()).asShortBuffer())
                    }
                }
                codec.releaseOutputBuffer(index, false)
                progress++
                if (frames.any { it.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 }) done.countDown()
            } catch (error: Exception) {
                fail(error)
            }
        }

        override fun onOutputFormatChanged(
            codec: MediaCodec,
            format: MediaFormat,
        ) {
            converter.flush()
            converter = Converter(format.optInt(MediaFormat.KEY_SAMPLE_RATE, Pcm.SAMPLE_RATE), format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1), out, peaks)
        }

        override fun onError(
            codec: MediaCodec,
            error: MediaCodec.CodecException,
        ) = fail(error)

        private fun fail(error: Exception) {
            Log.w(TAG, "event=decode_failed", error)
            failed = true
            done.countDown()
        }
    }

    /** Whether the codec takes several packets in one buffer (Android 15+): fewer round trips to the codec's own process. */
    private fun takesSeveralPackets(
        codec: MediaCodec,
        mime: String,
    ): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            runCatching { codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_MultipleFrames) }.getOrDefault(false)

    /** About a second of sound per buffer (Opus and AAC packets are about 20 ms). */
    private const val PACKETS_PER_BUFFER = 50
    private const val BATCH_INPUT_BYTES = 256 * 1024
    private const val BATCH_OUTPUT_BYTES = 2 * 1024 * 1024
    private const val STALL_SECONDS = 60L

    private fun MediaFormat.optInt(
        key: String,
        default: Int,
    ) = if (containsKey(key)) getInteger(key) else default

    /**
     * Mixes the channels down and averages the samples of each output step: plain and good enough for speech. Writes in blocks and
     * hands every block to the bars as well.
     */
    internal class Converter(
        rate: Int,
        private val channels: Int,
        private val out: OutputStream,
        private val peaks: LivePeaks?,
    ) {
        private val direct = rate == Pcm.SAMPLE_RATE
        private val step = rate.toDouble() / Pcm.SAMPLE_RATE
        private var sum = 0.0
        private var count = 0
        private var phase = 0.0
        private var input = ShortArray(8_192)
        private val block = ByteArray(32 * 1024)
        private var used = 0

        fun add(samples: java.nio.ShortBuffer) {
            val n = samples.remaining() - samples.remaining() % channels
            if (input.size < n) input = ShortArray(n)
            samples.get(input, 0, n)
            var i = 0
            while (i < n) {
                var frame = 0
                for (c in 0 until channels) frame += input[i + c]
                i += channels
                if (direct) {
                    emit(frame / channels)
                    continue
                }
                sum += frame.toDouble() / channels
                count++
                phase += 1.0
                if (phase >= step) {
                    phase -= step
                    emit((sum / count).roundToInt())
                    sum = 0.0
                    count = 0
                }
            }
        }

        private fun emit(sample: Int) {
            val value = sample.coerceIn(-32768, 32767)
            block[used++] = value.toByte()
            block[used++] = (value shr 8).toByte()
            if (used == block.size) flush()
        }

        fun flush() {
            if (used == 0) return
            out.write(block, 0, used)
            peaks?.feed(block, used)
            used = 0
        }
    }
}
