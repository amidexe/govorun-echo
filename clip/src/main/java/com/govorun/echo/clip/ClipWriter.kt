@file:OptIn(UnstableApi::class)

package com.govorun.echo.clip

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** The note's sound as 16 kHz mono 16-bit PCM, handed over piece by piece in order; false when it could not be read to the end. */
fun interface PcmSource {
    fun pump(sink: (ByteArray, Int) -> Unit): Boolean
}

/**
 * The video of a note to send to a messenger: a square of [FrameRenderer.SIZE] pixels with the text, H.264 pictures only where the
 * text changes (a still picture costs almost nothing) and AAC sound, in an MP4. Both encoders are Android's own; only the MP4 box
 * writing comes from Media3.
 */
object ClipWriter {
    const val SAMPLE_RATE = 16_000
    private const val AUDIO_BITRATE = 48_000
    // Still text needs little: the encoder spends what it is given over time, not per picture.
    private const val VIDEO_BITRATE = 64_000
    private const val NOMINAL_FPS = 30
    private const val KEY_FRAME_SECONDS = 10
    private const val TIMEOUT_US = 10_000L
    private const val QP_FIRST = 24
    private const val QP_CHANGES = 30

    /** Writes the video to [target]; false (and no file) when it could not be made or [cancelled] said stop. */
    fun write(
        words: List<ClipWord>,
        durationMs: Long,
        pcm: PcmSource,
        style: ClipStyle,
        target: File,
        cancelled: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ): Boolean {
        val part = File(target.path + ".part")
        val ok =
            runCatching {
                FileOutputStream(part).use { out -> Session(words, durationMs, style, out, cancelled, progress).run(pcm) }
            }.onFailure { android.util.Log.w("ClipWriter", "event=clip_failed", it) }.getOrDefault(false)
        if (ok && part.renameTo(target)) return true
        part.delete()
        return false
    }

    private class Stopped : RuntimeException("clip_cancelled")

    /** One video being made: both encoders fed in time order, their output written as soon as both tracks are known. */
    private class Session(
        words: List<ClipWord>,
        private val durationMs: Long,
        style: ClipStyle,
        out: FileOutputStream,
        private val cancelled: () -> Boolean,
        private val progress: (Float) -> Unit,
    ) {
        private val frames = Captions.frames(words, durationMs)
        private val renderer = FrameRenderer(words, style)
        private val size = renderer.size
        private val yuv = Yuv(size, size)
        private val muxer =
            // The index goes at the end: Media3 reserves a fixed 400 KB at the start for it, twice the size of a short note's video,
            // and messengers download a sent file whole or encode it again anyway.
            Mp4Muxer
                .Builder(out)
                .setAttemptStreamableOutputEnabled(false)
                .setLastSampleDurationBehavior(Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS)
                .build()
        private val video = encoder(videoFormat(size))
        private val audio = encoder(audioFormat())
        private val info = MediaCodec.BufferInfo()
        private val tracks = IntArray(2) { -1 }
        private val formats = arrayOfNulls<MediaFormat>(2)
        private val waiting = ArrayList<Triple<Int, ByteArray, BufferInfo>>()
        private val done = BooleanArray(2)
        private var nextFrame = 0
        private var samples = 0L

        fun run(pcm: PcmSource): Boolean {
            try {
                video.start()
                audio.start()
                if (!pcm.pump { bytes, length -> feedAudio(bytes, length) }) return false
                picturesUntil(durationMs - 1)
                endOfStream(video, VIDEO, durationMs * 1_000)
                endOfStream(audio, AUDIO, samples * 1_000_000 / SAMPLE_RATE)
                while (!done[VIDEO] || !done[AUDIO]) {
                    drain(video, VIDEO)
                    drain(audio, AUDIO)
                }
                muxer.close()
                return true
            } finally {
                runCatching { video.stop() }
                runCatching { audio.stop() }
                video.release()
                audio.release()
            }
        }

        private fun feedAudio(
            bytes: ByteArray,
            length: Int,
        ) {
            var offset = 0
            while (offset < length) {
                if (cancelled()) throw Stopped()
                val index = audio.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) {
                    drain(audio, AUDIO)
                    continue
                }
                val buffer = audio.getInputBuffer(index)!!
                buffer.clear()
                val n = minOf(buffer.remaining(), length - offset) / 2 * 2
                buffer.put(bytes, offset, n)
                audio.queueInputBuffer(index, 0, n, samples * 1_000_000 / SAMPLE_RATE, 0)
                samples += n / 2
                offset += n
                drain(audio, AUDIO)
            }
            val nowMs = samples * 1_000 / SAMPLE_RATE
            picturesUntil(nowMs)
            if (durationMs > 0) progress((nowMs.toFloat() / durationMs).coerceIn(0f, 1f))
        }

        /** Every picture due by [ms], each drawn and encoded at its own moment. */
        private fun picturesUntil(ms: Long) {
            while (nextFrame < frames.size && frames[nextFrame].atMs <= ms) {
                val frame = frames[nextFrame++]
                var index = video.dequeueInputBuffer(TIMEOUT_US)
                while (index < 0) {
                    drain(video, VIDEO)
                    index = video.dequeueInputBuffer(TIMEOUT_US)
                }
                yuv.write(renderer.draw(frame), video.getInputImage(index)!!)
                video.queueInputBuffer(index, 0, size * size * 3 / 2, frame.atMs * 1_000, 0)
                drain(video, VIDEO)
            }
        }

        private fun endOfStream(
            codec: MediaCodec,
            kind: Int,
            atUs: Long,
        ) {
            var index = codec.dequeueInputBuffer(TIMEOUT_US)
            while (index < 0) {
                drain(codec, kind)
                index = codec.dequeueInputBuffer(TIMEOUT_US)
            }
            codec.queueInputBuffer(index, 0, 0, atUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            ends[kind] = atUs
        }

        private val ends = LongArray(2)

        private fun drain(
            codec: MediaCodec,
            kind: Int,
        ) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        formats[kind] = codec.outputFormat
                        if (formats.all { it != null }) startTracks()
                    }
                    index < 0 -> return
                    else -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            buffer.position(info.offset).limit(info.offset + info.size)
                            write(kind, buffer, BufferInfo(info.presentationTimeUs, info.size, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME))
                        }
                        val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (last) {
                            // The end of the stream sets how long the last picture (or sound) lasts.
                            write(kind, ByteBuffer.allocate(0), BufferInfo(ends[kind], 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM))
                            done[kind] = true
                            return
                        }
                    }
                }
            }
        }

        private fun write(
            kind: Int,
            data: ByteBuffer,
            sample: BufferInfo,
        ) {
            if (tracks[kind] >= 0) {
                muxer.writeSampleData(tracks[kind], data, sample)
                return
            }
            // A sample that comes before both tracks are known waits, copied, for them.
            val copy = ByteArray(data.remaining()).also { data.get(it) }
            waiting += Triple(kind, copy, sample)
        }

        private fun startTracks() {
            tracks[VIDEO] = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(formats[VIDEO]!!))
            tracks[AUDIO] = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(formats[AUDIO]!!))
            waiting.forEach { (kind, bytes, sample) -> muxer.writeSampleData(tracks[kind], ByteBuffer.wrap(bytes), sample) }
            waiting.clear()
        }
    }

    private const val VIDEO = 0
    private const val AUDIO = 1

    private fun videoFormat(size: Int): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size, size).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, NOMINAL_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_SECONDS)
            // The first picture sharp, the changes after it plain: the encoder may not spend bits polishing what did not change.
            setInteger(MediaFormat.KEY_VIDEO_QP_I_MAX, QP_FIRST)
            setInteger(MediaFormat.KEY_VIDEO_QP_P_MIN, QP_CHANGES)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }

    private fun audioFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

    /**
     * Android's own encoder for [format]. For pictures the software one comes first: a still page with one word changing is
     * what it skips cheaply, where a phone's hardware encoder keeps spending bits polishing the unchanged background.
     */
    private fun encoder(format: MediaFormat): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        val software = codecs.firstOrNull { it.isSoftwareOnly && it.getCapabilitiesForType(mime).isFormatSupported(format) }?.name
        val name = (if (mime.startsWith("video/")) software else null) ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) ?: error("no_encoder:$mime")
        android.util.Log.i("ClipWriter", "event=clip_encoder mime=$mime name=$name")
        return MediaCodec.createByCodecName(name).apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
    }
}
