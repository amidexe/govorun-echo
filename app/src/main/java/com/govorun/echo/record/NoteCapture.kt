package com.govorun.echo.record

import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import com.govorun.echo.audio.LivePeaks
import com.govorun.echo.audio.RumbleFilter
import com.govorun.echo.notes.AudioPeaks
import com.govorun.echo.notes.ContinuousArchive
import com.govorun.echo.notes.NoteStore

/** Reads the microphone into one raw PCM file and hands every buffer to [feed]. Blocks until stopped. */
internal class NoteCapture(
    private val initialRecord: AudioRecord,
    private val dir: File,
    private val mode: () -> String,
    private val capMs: () -> Long,
    private val stopRequested: () -> String?,
    private val environment: () -> Environment,
    private val idleMs: () -> Long,
    private val feed: (ByteArray, Int) -> Unit,
    /** Called with the type of the microphone in use whenever Android moves the recording to another one (a headset, say). */
    private val onRoute: (Int) -> Unit = {},
    /** Opens the microphone again after it was lost (the audio server restarted, a headset came and went); null when it cannot be opened. */
    private val reopen: () -> AudioRecord? = { null },
    /** A device preference is only a request: never save a buffer from an unexpected input. */
    private val acceptsInput: (AudioRecord) -> Boolean = { true },
    private val onMicRecovered: () -> Unit = {},
    /** Compresses every buffer as it arrives. */
    private val archive: ContinuousArchive? = null,
    /** Called when Android starts or stops giving this recording silence because another app took the microphone. */
    private val onSilenced: (Boolean) -> Unit = {},
    /** While true the sound is read and dropped: the note pauses (the person dictates to Govorun meanwhile). */
    private val paused: () -> Boolean = { false },
) {
    data class Environment(
        val batteryPercent: Int,
        val charging: Boolean,
        val freeBytes: Long,
        /** Stop after this long without speech; 0 never. */
        val silenceLimitMs: Long = 0L,
    )

    data class Result(
        val bytes: Long,
        val stopReason: String,
        val silencedMs: Long,
        val pausedMs: Long = 0L,
    )

    private var record: AudioRecord = initialRecord

    @Volatile private var silenced = false

    fun run(callbackExecutor: Executor): Result {
        var bytes = 0L
        var silencedMs = 0L
        var pausedMs = 0L
        var deadMs = 0L
        var reason = RecordingService.STOP_USER
        val callback = silenceCallback()
        val meterFilter = RumbleFilter(NoteStore.SAMPLE_RATE)
        val recordedPeaks = LivePeaks()
        val startedElapsed = SystemClock.elapsedRealtime()
        var lastCheck = startedElapsed
        var lastRoute = -1
        val routing =
            AudioRecord.OnRoutingChangedListener { router ->
                val type = router.routedDevice?.type ?: -1
                if (type != lastRoute) {
                    lastRoute = type
                    onRoute(type)
                }
            }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun attach() {
            record.registerAudioRecordingCallback(callbackExecutor, callback)
            record.addOnRoutingChangedListener(routing, handler)
        }

        fun detach() {
            runCatching { record.removeOnRoutingChangedListener(routing) }
            runCatching { record.unregisterAudioRecordingCallback(callback) }
        }
        attach()
        try {
            NoteStore.pcmFile(dir).outputStream().buffered().use { out ->
                val buffer = ByteArray(BUFFER_BYTES)

                /** One piece of sound from the microphone goes, as it is, into the note: sound file, raw file, meter, bars and live text. */
                fun take(
                    pcm: ByteArray,
                    read: Int,
                ) {
                    if (read == 0) return
                    archive?.feed(pcm, read)
                    out.write(pcm, 0, read)
                    bytes += read
                    val level = levelOf(pcm, read, meterFilter, recordedPeaks)
                    RecordingBus.level.value = level
                    feed(pcm, read)
                    val chunkMs = read / 2 * 1_000L / NoteStore.SAMPLE_RATE
                    // Android gives the app digital silence (all zeros) while another app holds the microphone; some do not announce it.
                    deadMs = if (level == 0f) deadMs + chunkMs else 0L
                    if (silenced || deadMs >= DEAD_AFTER_MS) silencedMs += chunkMs
                }

                // The note is written a third of a second late. When a dictation into Govorun starts, that last third of a second (the tap
                // on the bird) is dropped and the sound fades out; after the dictation it fades back in, so the join makes no click.
                val held = ArrayDeque<ByteArray>()
                var heldBytes = 0
                val holdBytes = NoteStore.BYTES_PER_SECOND * HOLD_MS / 1_000
                val fadeBytes = NoteStore.BYTES_PER_SECOND * FADE_MS / 1_000
                var fadeInNext = false
                fun flushHeld() {
                    while (held.isNotEmpty()) held.removeFirst().let { take(it, it.size) }
                    heldBytes = 0
                }
                while (true) {
                    stopRequested()?.let {
                        reason = it
                        break
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - startedElapsed >= capMs()) {
                        reason = RecordingService.STOP_CAP
                        break
                    }
                    if (now - lastCheck >= CHECK_INTERVAL_MS) {
                        lastCheck = now
                        val env = environment()
                        StopPolicy.reason(mode(), idleMs(), env.batteryPercent, env.charging, env.freeBytes, env.silenceLimitMs)?.let {
                            reason = it
                            break
                        }
                    }
                    val read = record.read(buffer, 0, buffer.size)
                    val raw = if (read > 0 && !acceptsInput(record)) AudioRecord.ERROR_DEAD_OBJECT else read
                    if (raw > 0 && paused()) {
                        // Dictation into Govorun: the microphone keeps running, nothing of it goes into the note.
                        if (held.isNotEmpty()) {
                            val first = held.first()
                            val tail = minOf(fadeBytes, first.size) and 1.inv()
                            fade(first, tail, out = true)
                            take(first, tail)
                            pausedMs += (heldBytes - tail) * 1_000L / NoteStore.BYTES_PER_SECOND
                            held.clear()
                            heldBytes = 0
                        }
                        pausedMs += raw * 1_000L / NoteStore.BYTES_PER_SECOND
                        RecordingBus.level.value = 0f
                        fadeInNext = true
                        continue
                    }
                    if (raw > 0) {
                        val chunk = buffer.copyOf(raw)
                        if (fadeInNext) {
                            fade(chunk, minOf(fadeBytes, raw) and 1.inv(), out = false)
                            fadeInNext = false
                        }
                        held.addLast(chunk)
                        heldBytes += raw
                        while (held.isNotEmpty() && heldBytes - held.first().size >= holdBytes) {
                            val next = held.removeFirst()
                            heldBytes -= next.size
                            take(next, next.size)
                        }
                    } else if (raw < 0) {
                        // The microphone was taken away (audio server restart, a headset): reopen it and go on in the same recording.
                        detach()
                        runCatching { record.stop() }
                        runCatching { record.release() }
                        var again: AudioRecord? = null
                        repeat(REOPEN_TRIES) {
                            if (again == null && stopRequested() == null) {
                                Thread.sleep(REOPEN_PAUSE_MS)
                                again = reopen()
                            }
                        }
                        val fresh = again
                        if (fresh == null) {
                            reason = if (stopRequested() != null) stopRequested()!! else RecordingService.STOP_READ_ERROR
                            record = initialRecord
                            break
                        }
                        record = fresh
                        attach()
                        deadMs = 0L
                        onMicRecovered()
                    }
                }
                flushHeld()
            }
        } catch (error: Exception) {
            Log.e(TAG, "event=capture_failed", error)
            reason = RecordingService.STOP_IO_ERROR
        } finally {
            detach()
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        // A cancelled recording leaves no bars: a continued note keeps its own as they were.
        if (reason != RecordingService.STOP_CANCEL) savePeaks(recordedPeaks.bytes())
        return Result(bytes, reason, silencedMs, pausedMs)
    }

    /** A linear fade over the first [length] bytes of 16-bit sound: down to silence ([out]) or up from it. */
    private fun fade(
        chunk: ByteArray,
        length: Int,
        out: Boolean,
    ) {
        val samples = length / 2
        if (samples <= 0) return
        for (i in 0 until samples) {
            val gain = if (out) 1f - i.toFloat() / samples else i.toFloat() / samples
            val sample = ((chunk[2 * i].toInt() and 0xFF) or (chunk[2 * i + 1].toInt() shl 8)).toShort() * gain
            val value = sample.toInt()
            chunk[2 * i] = value.toByte()
            chunk[2 * i + 1] = (value shr 8).toByte()
        }
    }

    /** The bars of this recording, kept next to the note so the player shows its waveform at once. */
    private fun savePeaks(recorded: ByteArray) {
        runCatching {
            val meta = org.json.JSONObject(File(dir, "meta.json").readText())
            AudioPeaks.saveRecorded(dir, recorded, NoteStore.audioParts(meta).sumOf { it.ms })
        }.onFailure {
            // A new note has no meta yet or none with sound: its bars begin here.
            if (!File(dir, "meta.json").exists()) AudioPeaks.saveRecorded(dir, recorded, 0L)
        }
    }

    /** Android hands the app silence while a call or another privileged client holds the mic. */
    private fun silenceCallback() =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                val now = mine?.isClientSilenced == true
                if (now != silenced) {
                    silenced = now
                    onSilenced(now)
                }
            }
        }

    private companion object {
        const val TAG = "Echo"
        const val BUFFER_BYTES = 4_096

        /** How late the note is written, so the tap on Govorun's bird can be left out of it. */
        const val HOLD_MS = 300

        /** How long the sound fades at a join. */
        const val FADE_MS = 20
        const val CHECK_INTERVAL_MS = 30_000L
        const val DEAD_AFTER_MS = 1_500L
        const val REOPEN_TRIES = 20
        const val REOPEN_PAUSE_MS = 500L
    }
}

/** Loudness of a 16-bit PCM buffer on the same 0..1 scale as the player's bars, so the live tape and the played one match. */
internal fun levelOf(
    buffer: ByteArray,
    length: Int,
    filter: RumbleFilter,
    /** Gets every filtered sample too, so the note's waveform is measured in the same pass. */
    peaks: LivePeaks? = null,
): Float {
    var peak = 0
    var i = 0
    while (i + 1 < length) {
        val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
        val amplitude = kotlin.math.abs(filter.process(sample.toShort().toFloat())).toInt()
        peak = maxOf(peak, amplitude)
        peaks?.add(amplitude)
        i += 2
    }
    return LivePeaks.scale(peak) / 255f
}
