package com.govorun.echo.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.govorun.echo.R
import com.govorun.echo.audio.AudioDecoder
import com.govorun.echo.clip.Captions
import com.govorun.echo.clip.ClipStyle
import com.govorun.echo.clip.ClipWord
import com.govorun.echo.clip.ClipWriter
import com.govorun.echo.clip.Palette
import com.govorun.echo.clip.PcmSource
import com.govorun.echo.notes.AudioPeaks
import com.govorun.echo.notes.NoteStore
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A note as the clip module takes it: its words with their times, its sound decoded to PCM, and how the video names it. */
internal object ClipExport {
    private val labelFormat = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneId.systemDefault())

    fun words(note: NoteStore.Entry): List<ClipWord> = note.phrases.flatMap { phrase -> phrase.words.map { ClipWord(it.startMs, it.endMs, it.text) } }

    fun durationMs(note: NoteStore.Entry): Long = note.audio.sumOf { it.ms }

    /** Whether the note can become a video or subtitles: it has sound and words with times. */
    fun canMake(note: NoteStore.Entry): Boolean = note.audio.isNotEmpty() && note.timed

    /** The note's subtitles as an SRT file; null for a note without words. */
    fun subtitles(note: NoteStore.Entry): String? = words(note).takeIf { it.isNotEmpty() }?.let { Captions.srt(Captions.cues(it, durationMs(note))) }

    /** What the video will roughly weigh: the sound at 48 kbit/s and the pictures, which for speech cost about as much again as 30 kbit/s. */
    fun estimatedBytes(note: NoteStore.Entry): Long = durationMs(note) * (48_000 + 30_000) / 8 / 1_000

    fun video(
        context: Context,
        note: NoteStore.Entry,
        target: File,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ): Boolean {
        val dir = NoteStore.noteDir(context, note.id)
        // The note's files one after another, decoded to the 16 kHz PCM the clip module expects.
        val pcm = PcmSource { sink -> note.audio.all { part -> AudioDecoder.decodeFile(File(dir, "chunks/${part.file}"), SinkStream(sink), null) } }
        val style =
            ClipStyle(
                typeface = context.resources.getFont(R.font.geist_variable),
                brand = context.getString(R.string.clip_brand),
                label = labelFormat.format(Instant.ofEpochMilli(note.startedAtMs)),
                logo = appIcon(context),
                palette = Palette.of(note.id),
                loudness = AudioPeaks.savedBars(dir, durationMs(note)),
            )
        return ClipWriter.write(words(note), durationMs(note), pcm, style, target, cancelled, progress)
    }

    /** The app's own icon, as the launcher shows it, for the mark on the video. */
    private fun appIcon(context: Context): Bitmap? =
        runCatching {
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { bitmap ->
                icon.setBounds(0, 0, ICON_PX, ICON_PX)
                icon.draw(Canvas(bitmap))
            }
        }.getOrNull()

    private class SinkStream(
        private val sink: (ByteArray, Int) -> Unit,
    ) : OutputStream() {
        override fun write(b: Int) = sink(byteArrayOf(b.toByte()), 1)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = sink(if (off == 0) b else b.copyOfRange(off, off + len), len)
    }

    private const val ICON_PX = 128
}
