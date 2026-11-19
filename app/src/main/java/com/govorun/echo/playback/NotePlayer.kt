package com.govorun.echo.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Plays a note's sound files back to back as one recording with a single position. ExoPlayer lands on the exact moment in a file
 * of any length; the system player keeps only 512 marks per Ogg file, so in an hour-long recording it started up to 7 s past the
 * tapped word or the phrase "skip pauses" jumped to.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NotePlayer private constructor(
    private val context: Context,
) {
    data class State(
        val noteId: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0L,
        val totalMs: Long = 0L,
        /** Set while only a piece of the note plays: where it stops. */
        val rangeEndMs: Long? = null,
    )

    val state = MutableStateFlow(State())

    /** How fast notes play, kept between sessions; speech stays at its natural pitch. */
    val speed = MutableStateFlow(AppSettings.playSpeed(context))

    /** Whether the pauses in a note are jumped over; kept between sessions. */
    val skipSilence = MutableStateFlow(AppSettings.skipSilence(context))
    private var silences: List<LongRange> = emptyList()
    private var loadedDir: File? = null
    private var loadedParts: List<NoteStore.AudioPart> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private val raiseToListen =
        RaiseToListen(context, route = { device ->
            player?.let { current -> runCatching { current.setPreferredAudioDevice(device) }.isSuccess } ?: false
        }, rewind = ::rewindAtEar)
    private var starts: List<Long> = emptyList()
    private val sources = ProgressiveMediaSource.Factory(FileDataSource.Factory(), CONTAINERS)
    private val tick =
        object : Runnable {
            override fun run() {
                publish()
                if (state.value.playing) handler.postDelayed(this, if (skipSilence.value) SKIP_TICK_MS else TICK_MS)
            }
        }
    private val events =
        object : Player.Listener {
            // Another app taking the sound or unplugged headphones pause the note just as the pause button does.
            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                if (!playWhenReady && state.value.playing) pauseNow()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) handler.post { if (player?.playbackState == Player.STATE_ENDED) finished() }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "event=playback_failed code=${error.errorCodeName}", error)
                handler.post { if (player?.playerError != null) stop() }
            }
        }

    /**
     * Plays stretches of the note's own sound one after another, as a draft of «Фрагмент» will sound once saved: each stretch of each
     * file is clipped to it (Media3 ClippingMediaSource), so what is cut is not heard and the sound ends exactly at the piece's edge.
     * [startMs] is where the first stretch begins in the draft: positions are the draft's, for its scale.
     */
    fun playPieces(
        noteId: String,
        dir: File,
        parts: List<NoteStore.AudioPart>,
        spans: List<Pair<Long, Long>>,
        startMs: Long,
        draftTotalMs: Long,
    ) {
        clearPlayer(keepEar = state.value.playing)
        remember(dir, parts)
        val pieces = mutableListOf<Pair<Long, androidx.media3.exoplayer.source.MediaSource>>()
        var at = startMs
        spans.forEach { (fromMs, toMs) ->
            var offset = 0L
            parts.forEach { part ->
                val end = offset + part.ms
                val from = maxOf(fromMs, offset)
                val to = minOf(toMs, end)
                if (to > from) {
                    val file = sources.createMediaSource(MediaItem.fromUri(Uri.fromFile(File(dir, "chunks/${part.file}"))))
                    val clipped =
                        androidx.media3.exoplayer.source.ClippingMediaSource
                            .Builder(file)
                            .setStartPositionMs(from - offset)
                            .apply { if (to < end) setEndPositionMs(to - offset) }
                            .build()
                    pieces += at to clipped
                    at += to - from
                }
                offset = end
            }
        }
        if (pieces.isEmpty()) {
            raiseToListen.playing(false)
            return
        }
        starts = pieces.map { it.first }
        state.value = State(noteId, playing = false, positionMs = startMs, totalMs = draftTotalMs, rangeEndMs = at)
        player =
            build().apply {
                setMediaSources(pieces.map { it.second })
                prepare()
            }
        Log.i(TAG, "event=pieces_play from=$startMs to=$at pieces=${pieces.size}")
        resume()
    }

    /** The pieces have played to their end. */
    private fun endRange() {
        Log.i(TAG, "event=pieces_end at=${state.value.positionMs}")
        stop()
    }

    /** Leaves the pieces of a draft for the whole note at [ms], playing or not as it was. */
    private fun leaveRange(ms: Long) {
        val noteId = state.value.noteId ?: return
        val dir = loadedDir ?: return
        open(noteId, dir, loadedParts, ms.coerceIn(0L, loadedParts.sumOf { it.ms }), play = state.value.playing)
    }

    /** Starts the note from the beginning, or pauses and resumes it if it is the current one. */
    fun toggle(
        noteId: String,
        dir: File,
        parts: List<NoteStore.AudioPart>,
    ) {
        // A draft was playing (its positions are the draft's): the note itself starts from its beginning.
        if (state.value.rangeEndMs != null) return open(noteId, dir, parts, 0L)
        if (state.value.noteId == noteId && player != null) {
            if (state.value.playing) pauseNow() else resume()
            return
        }
        open(noteId, dir, parts, 0L)
    }

    /** Starts the note at [positionMs], e.g. from a tapped phrase. */
    fun playFrom(
        noteId: String,
        dir: File,
        parts: List<NoteStore.AudioPart>,
        positionMs: Long,
    ) {
        if (state.value.rangeEndMs != null) return open(noteId, dir, parts, positionMs)
        if (state.value.noteId == noteId && player != null && parts == loadedParts) {
            seekTo(positionMs)
            resume()
            return
        }
        open(noteId, dir, parts, positionMs)
    }

    fun playPause() {
        if (state.value.playing) pause() else play()
    }

    /** Resumes a paused note; does nothing if it already plays. */
    fun play() {
        if (player != null && !state.value.playing) resume()
    }

    fun pause() {
        if (state.value.playing) pauseNow()
    }

    fun skip(deltaMs: Long) = seekTo(state.value.positionMs + deltaMs)

    fun setSpeed(value: Float) {
        speed.value = value
        AppSettings.setPlaySpeed(context, value)
        player?.setPlaybackSpeed(value)
    }

    fun setSkipSilence(on: Boolean) {
        skipSilence.value = on
        AppSettings.setSkipSilence(context, on)
        if (on && silences.isEmpty()) measureSilences()
        if (state.value.playing) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun remember(
        dir: File,
        parts: List<NoteStore.AudioPart>,
    ) {
        loadedDir = dir
        loadedParts = parts
        silences = emptyList()
        if (skipSilence.value) measureSilences()
    }

    /** The note in [dir] got new words (fixed): if it is the one loaded, its pauses are read again for «Без пауз». */
    fun phrasesChanged(dir: File) {
        if (loadedDir == dir && skipSilence.value) measureSilences()
    }

    /**
     * Puts the note at [positionMs], as a place found by the search opens: the note already loaded keeps playing or stays paused
     * there, any other note is loaded paused at it (stopping the one that played).
     */
    fun placeAt(
        noteId: String,
        dir: File,
        parts: List<NoteStore.AudioPart>,
        positionMs: Long,
    ) {
        if (state.value.noteId == noteId && player != null && parts == loadedParts && state.value.rangeEndMs == null) seekTo(positionMs) else cue(noteId, dir, parts, positionMs)
    }

    /** Loads the note at [positionMs] without playing it: where it stood before a piece of it was played. */
    fun cue(
        noteId: String,
        dir: File,
        parts: List<NoteStore.AudioPart>,
        positionMs: Long,
    ) = open(noteId, dir, parts, positionMs, play = false)

    /** The gaps between the recognised phrases of the loaded note; read in the background, until then nothing is skipped. */
    private fun measureSilences() {
        val dir = loadedDir ?: return
        val total = loadedParts.sumOf { it.ms }
        CoroutineScope(Dispatchers.IO).launch {
            val found = pausesBetween(NoteStore.readPhrases(dir).map { it.startMs..it.endMs }, total)
            handler.post { if (loadedDir == dir) silences = found }
        }
    }

    /** While a pause is being played, jumps to just before the next word. */
    private fun skipPauseAt(position: Long) {
        if (!skipSilence.value || silences.isEmpty()) return
        val range = silences.firstOrNull { position in it } ?: return
        val target = range.last + 1
        Log.i(TAG, "event=skip_pause from=$position to=$target")
        seekTo(target)
    }

    fun seekTo(ms: Long) {
        val current = player ?: return
        if (state.value.noteId == null) return
        if (state.value.rangeEndMs != null) return leaveRange(ms)
        val target = ms.coerceIn(0L, state.value.totalMs)
        val part = partAt(target)
        current.seekTo(part, target - starts[part])
        publish()
    }

    fun refreshRaiseToListen() = raiseToListen.refresh()

    /** Rewind inside the loaded playlist, including fragment previews, without leaving their clipped sources. */
    private fun rewindAtEar() {
        val current = player ?: return
        val first = starts.firstOrNull() ?: return
        val position = starts.getOrElse(current.currentMediaItemIndex) { first } + current.currentPosition
        val target = earRewindPosition(position, first)
        val part = partAt(target)
        current.seekTo(part, target - starts[part])
        state.value = state.value.copy(positionMs = target)
        Log.i(TAG, "event=ear_rewind from=$position to=$target")
    }

    fun stop() = clearPlayer(keepEar = false)

    private fun clearPlayer(keepEar: Boolean) {
        handler.removeCallbacks(tick)
        // Remove callbacks before releasing: the old player must not pause a new session.
        player?.let { current ->
            current.removeListener(events)
            runCatching { current.pause() }
            if (!keepEar) raiseToListen.playing(false)
            runCatching { current.release() }
        }
        if (!keepEar) raiseToListen.playing(false)
        player = null
        state.value = State()
    }

    private fun open(
        noteId: String,
        dir: File,
        parts: List<NoteStore.AudioPart>,
        positionMs: Long,
        play: Boolean = true,
    ) {
        clearPlayer(keepEar = play && state.value.playing)
        remember(dir, parts)
        starts = parts.runningFold(0L) { sum, part -> sum + part.ms }.dropLast(1)
        val total = parts.sumOf { it.ms }
        val at = positionMs.coerceIn(0L, total)
        state.value = State(noteId, playing = false, positionMs = at, totalMs = total)
        if (parts.isEmpty()) return finished()
        val part = partAt(at)
        player =
            build().apply {
                setMediaItems(parts.map { MediaItem.fromUri(Uri.fromFile(File(dir, "chunks/${it.file}"))) }, part, at - starts[part])
                prepare()
            }
        if (play) resume() else publish()
    }

    private fun partAt(ms: Long): Int = starts.indexOfLast { it <= ms }.coerceAtLeast(0)

    /** Only what a note needs: the audio renderer and the containers a note's sound can be in, so the rest of ExoPlayer is left out. */
    private fun build(): ExoPlayer =
        ExoPlayer
            .Builder(
                context,
                RenderersFactory { handler, _, audio, _, _ -> arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audio)) },
                ProgressiveMediaSource.Factory(FileDataSource.Factory(), CONTAINERS),
            ).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                raiseToListen.applyTo(this)
                setPlaybackSpeed(speed.value)
                addListener(events)
            }

    private fun pauseNow() {
        state.value = state.value.copy(playing = false)
        player?.pause()
        raiseToListen.playing(false)
        handler.removeCallbacks(tick)
        publish()
        Log.i(TAG, "event=paused at=${state.value.positionMs}")
    }

    private fun resume() {
        val current = player ?: return
        PlaybackService.start(context)
        raiseToListen.playing(true)
        current.play()
        state.value.noteId?.let { id ->
            CoroutineScope(Dispatchers.IO).launch { com.govorun.echo.notes.InactiveNotes.used(context, id) }
        }
        state.value = state.value.copy(playing = true)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun finished() {
        if (state.value.rangeEndMs != null) return endRange()
        val noteId = state.value.noteId
        // With "play one after another" on, the next note of the day starts by itself; otherwise playback just ends.
        val next = noteId?.takeIf { AppSettings.playNext(context) }?.let { NoteStore.nextToPlay(context, it) }
        if (next != null) open(next.id, NoteStore.noteDir(context, next.id), next.audio, 0L) else stop()
    }

    private fun publish() {
        val current = player ?: return
        val position = (starts.getOrElse(current.currentMediaItemIndex) { 0L } + current.currentPosition).coerceIn(0L, state.value.totalMs)
        state.value = state.value.copy(positionMs = position)
        // A piece plays as it is, pauses included: it is being listened to before it is cut.
        if (state.value.playing && state.value.rangeEndMs == null) skipPauseAt(position)
    }

    companion object {
        private const val TAG = "NotePlayer"
        private const val TICK_MS = 250L
        private const val SKIP_TICK_MS = 100L

        /** Our own recordings are Ogg Opus; an imported file is kept in its own container. MP3 gets an exact index, as its own is coarse. */
        private val CONTAINERS =
            ExtractorsFactory {
                arrayOf(
                    OggExtractor(),
                    Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                    MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED),
                    AmrExtractor(AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
                    AdtsExtractor(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
                    Mp3Extractor(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
                )
            }

        @Volatile private var instance: NotePlayer? = null

        /** One player for the whole process, so playback outlives the screen. */
        fun get(context: Context): NotePlayer = instance ?: synchronized(this) { instance ?: NotePlayer(context.applicationContext).also { instance = it } }
    }
}
