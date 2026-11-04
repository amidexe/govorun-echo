package com.govorun.echo.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Puts a note's sound files (an appended recording, or old minute-long pieces) into one file without decoding: the packets are copied one after another. */
internal object AudioJoiner {
    fun join(
        parts: List<File>,
        target: File,
    ): File? {
        val files = parts.filter { it.exists() }
        if (files.isEmpty()) return null
        if (files.size == 1) return files.single().copyTo(target, overwrite = true)
        // Different codecs cannot share a file.
        if (files.map { it.extension }.distinct().size != 1) return null
        val container = if (files.first().extension == "m4a") MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 else MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
        var muxer: MediaMuxer? = null
        var started = false
        var offsetUs = 0L
        val buffer = ByteBuffer.allocate(64 * 1024)
        val info = MediaCodec.BufferInfo()
        return try {
            for (file in files) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    val format = extractor.getTrackFormat(track)
                    extractor.selectTrack(track)
                    if (muxer == null) {
                        muxer = MediaMuxer(target.path, container)
                        muxer.addTrack(format)
                        muxer.start()
                        started = true
                    }
                    var last = 0L
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val time = extractor.sampleTime
                        info.set(0, size, offsetUs + time, 0)
                        muxer.writeSampleData(0, buffer, info)
                        last = time
                        extractor.advance()
                    }
                    val length = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else last + FRAME_US
                    offsetUs += maxOf(length, last + FRAME_US)
                } finally {
                    extractor.release()
                }
            }
            if (started) muxer?.stop()
            muxer?.release()
            target
        } catch (error: Exception) {
            runCatching { muxer?.release() }
            target.delete()
            null
        }
    }

    private const val FRAME_US = 20_000L
}
