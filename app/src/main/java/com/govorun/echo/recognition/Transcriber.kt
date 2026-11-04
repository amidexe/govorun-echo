package com.govorun.echo.recognition

import android.util.Log
import com.govorun.echo.notes.AudioArchive
import com.govorun.echo.notes.ContinuousArchive
import com.govorun.echo.notes.archiveSlice
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Turns a finished recording into the note's text and, when the owner keeps audio, into
 * compressed slices for the player. Speech is never cut here: Govorun gets the whole file
 * (or already streamed the text live) and finds phrase boundaries with its own detector.
 */
internal class Transcriber(
    private val notesRoot: File,
    private val recognizer: Recognizer,
    private val pauseMs: (attempt: Int) -> Long = ::retryPauseMs,
    private val keepAudio: () -> Boolean = { false },
    private val encode: (File, Long, Long, File) -> File? = AudioArchive::encode,
    private val sliceBytes: Long = SLICE_BYTES,
    private val onFailure: (String) -> Unit = {},
    /** Things worth a line in the journal that are not failures: "busy" the first time Govorun is occupied with a note. */
    private val onEvent: (String) -> Unit = {},
    /** A note has its text now: told once it is saved as done. */
    private val onFinished: (String) -> Unit = {},
    private val busyGiveUpMs: Long = BUSY_GIVE_UP_MS,
    /** Before each second of sound goes to Govorun: a hot phone waits here, a warm one goes at the pace of speech. */
    private val pace: suspend () -> Unit = {},
) {
    /** Notes the owner postponed: their recognition stops at the next second of sound, or the next try. */
    private val postponed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The note waits behind others in the queue: the list says «в очереди» instead of «распознаю». */
    fun markQueued(noteId: String) {
        val dir = File(notesRoot, noteId)
        if (dir.isDirectory) NoteStore.updateMeta(dir) { put("queued", true) }
    }

    /** Notes asked to give way to a fresh recording: they keep what was heard and wait in the queue to go on. */
    private val yielding = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun yieldTo(noteId: String) {
        yielding += noteId
        wake.trySend(Unit)
    }

    fun postpone(noteId: String) {
        postponed += noteId
        wake.trySend(Unit)
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The person asked again while a note waits for a busy Govorun: try at once instead of after the pause. */
    fun retryNow() {
        wake.trySend(Unit)
    }

    /** Recognizes `audio.pcm` as one file, retrying while Govorun is busy; false when it gave way to a recording and waits to go on. */
    suspend fun recognizeNote(noteId: String): Boolean {
        val dir = File(notesRoot, noteId)
        val pcm = NoteStore.pcmFile(dir)
        if (!pcm.exists()) {
            // A note that was waiting for its audio when the app was stopped has nothing left to recognize.
            NoteStore.updateMeta(dir) { if (optString("state") == NoteStore.STATE_RECOGNIZING) { put("state", NoteStore.STATE_FAILED); put("error", "import_failed") } }
            return true
        }
        postponed -= noteId
        NoteStore.updateMeta(dir) {
            if (optString("state") != NoteStore.STATE_RECORDING) put("state", NoteStore.STATE_RECOGNIZING)
            put("error", "")
            remove("queued")
        }
        onEvent("file_start:${pcm.length() * 1_000L / NoteStore.BYTES_PER_SECOND}")
        val startedAt = System.nanoTime()
        try {
            val result = recognizeWithRetry(dir, pcm) ?: return !yielding.remove(noteId)
            onEvent("file_done:${(System.nanoTime() - startedAt) / 1_000_000}:${result.text.length}")
            finishWithText(noteId, result.text, result.phrases.ifEmpty { null })
            return true
        } finally {
            RecognitionPreview.clear(noteId)
        }
    }

    /**
     * Stores the text (streamed live or recognized from the file) and archives the audio. A note
     * that was continued ("append") keeps its earlier text, audio and phrases and adds the new ones.
     */
    fun finishWithText(
        noteId: String,
        text: String,
        phrases: List<NoteStore.Phrase>? = null,
        fromLive: Boolean = false,
    ) {
        val dir = File(notesRoot, noteId)
        if (!dir.isDirectory) return
        val appendFromMs = runCatching { JSONObject(File(dir, "meta.json").readText()).optLong("appendFromMs", -1L) }.getOrDefault(-1L)
        val resumeAtMs = runCatching { JSONObject(File(dir, "meta.json").readText()).optLong("resumeAtMs", -1L) }.getOrDefault(-1L)
        val appending = appendFromMs >= 0
        // A note paused part-way goes on like a continuation: its text so far stays, the new phrases are placed after it.
        val continuing = appending || resumeAtMs >= 0
        val offset = if (appending) appendFromMs else maxOf(resumeAtMs, 0)
        val previousText = if (continuing) runCatching { File(dir, "text.txt").readText() }.getOrDefault("") else ""
        if (text.isBlank() && !continuing) {
            NoteStore.updateMeta(dir) {
                put("state", NoteStore.STATE_FAILED)
                put("error", ERROR_NO_SPEECH)
                remove("queued")
            }
        } else {
            val joined = listOf(previousText.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
            File(dir, "text.txt").writeText(joined)
            File(dir, "live.txt").delete()
            File(dir, CHECKPOINT_DIR).deleteRecursively()
            if (phrases != null || continuing) {
                val earlier = if (continuing) NoteStore.readPhrases(dir) else emptyList()
                val shifted = phrases.orEmpty().map { it.shifted(offset) }
                if (earlier.isNotEmpty() || shifted.isNotEmpty()) NoteStore.writePhrases(dir, earlier + shifted)
            }
            NoteStore.updateMeta(dir) {
                put("state", NoteStore.STATE_DONE)
                put("error", "")
                // A continued note counts as changed now, so it can be found by when it was last written.
                if (appending) put("modifiedAtMs", System.currentTimeMillis())
                // Where the continuation's text begins and when it was said, so text leaving the app shows it in its place.
                if (appending && text.isNotBlank()) {
                    val start = previousText.trim().let { if (it.isEmpty()) 0 else it.length + 2 }
                    val at = optLong("appendAtMs").takeIf { it > 0 } ?: System.currentTimeMillis()
                    put("appends", (optJSONArray("appends") ?: org.json.JSONArray()).put(JSONObject().put("atMs", at).put("textAt", start).put("fromMs", maxOf(appendFromMs, 0))))
                }
                remove("recognizedMs")
                remove("totalMs")
                remove("resumeAtMs")
                remove("queued")
                remove("appendFromMs")
                remove("appendAtMs")
            }
        }
        if (fromLive && text.isNotBlank()) onEvent("live_done:${text.length}")
        if (text.isNotBlank()) onFinished(noteId)
        val archiveStarted = System.nanoTime()
        archive(dir, deletePcmWithoutArchive = text.isNotBlank() || appending)
        Log.i(TAG, "event=finished note=$noteId chars=${text.length} archiveMs=${(System.nanoTime() - archiveStarted) / 1_000_000}")
    }

    /**
     * Compresses what is left of the audio into Opus right after the recording stops, before recognition is even tried, and keeps
     * `audio.pcm` for recognition. So the sound can be played and taken out of the phone however long recognition takes.
     */
    fun archiveEarly(noteId: String) {
        val dir = File(notesRoot, noteId)
        if (dir.isDirectory && keepAudio()) archive(dir, deletePcmWithoutArchive = false, keepPcm = true)
    }

    /** Finishes the audio of a note whose app was stopped in the middle of compressing it. */
    fun finishArchive(noteId: String) {
        val dir = File(notesRoot, noteId)
        if (dir.isDirectory) archive(dir, deletePcmWithoutArchive = true)
    }

    /** Compresses `audio.pcm` into one-minute Opus slices, or deletes it when audio is not kept. */
    private fun archive(
        dir: File,
        deletePcmWithoutArchive: Boolean,
        keepPcm: Boolean = false,
    ) {
        val pcm = NoteStore.pcmFile(dir)
        // A recording cut off in the middle left its stream unwrapped: wrap it now.
        ContinuousArchive.seal(dir)
        if (!pcm.exists()) return
        if (!keepAudio()) {
            if (deletePcmWithoutArchive) pcm.delete()
            return
        }
        // What the live stream covers is already in the note; whatever it lacks (a shared file, a failed stream) is compressed from the 16 kHz audio.
        val offset = runCatching { JSONObject(File(dir, "meta.json").readText()).optLong("archivedBytes") }.getOrDefault(0L)
        val total = pcm.length()
        if (total - offset > LEFT_OVER_BYTES && !archiveSlice(dir, pcm, offset, total - offset, encode)) {
            Log.w(TAG, "event=archive_failed note=${dir.name} offset=$offset")
            return
        }
        if (!keepPcm) pcm.delete()
    }

    /** Returns the text, or null after recording a terminal failure in the note. */
    private suspend fun recognizeWithRetry(
        dir: File,
        pcm: File,
    ): Outcome.Text? {
        var attempt = 0
        val waitingSince = System.nanoTime()
        // What a stopped app left as its last checkpoint becomes the note's text, and recognition goes on from there.
        promoteCheckpoint(dir)
        fun resumeAt() = runCatching { JSONObject(File(dir, "meta.json").readText()).optLong("resumeAtMs") }.getOrDefault(0L)
        var feed = NoteFeed(dir, resumeAt())
        while (true) {
            if (dir.name in postponed) return fail(dir, ERROR_POSTPONED)
            if (dir.name in yielding) return giveWay(dir, feed)
            when (val outcome = recognizer.recognize(pcm, feed)) {
                is Outcome.Text -> return outcome
                Outcome.Busy -> {
                    // Govorun took the engine back part-way (the owner's dictation, another app): what was heard stays, and the
                    // next try goes on from the last whole phrase instead of from the start of an hour-long file.
                    if (dir.isDirectory) keepHeard(dir, feed)
                    feed = NoteFeed(dir, resumeAt())
                    // The recording is safe on disk. Govorun is usually busy with the owner's own dictation, which always goes
                    // first, so wait; but not for ever: after a long wait the note says so and offers "recognize" again.
                    if (attempt == 0) onEvent("busy")
                    if ((System.nanoTime() - waitingSince) / 1_000_000 > busyGiveUpMs) return fail(dir, "busy")
                    NoteStore.updateMeta(dir) { put("error", "busy") }
                    withTimeoutOrNull(pauseMs(attempt++)) { wake.receive() }
                }
                Outcome.Unreachable -> {
                    if (dir.isDirectory) keepHeard(dir, feed)
                    feed = NoteFeed(dir, resumeAt())
                    // Govorun died, cannot be started or does not answer (some phones stop it in the background): the sound is
                    // safe on the phone, so wait and try again; after a long wait the note says so and offers "recognize" again.
                    if (attempt == 0) onEvent("unreachable")
                    if ((System.nanoTime() - waitingSince) / 1_000_000 > busyGiveUpMs) return fail(dir, "unreachable")
                    NoteStore.updateMeta(dir) { put("error", "unreachable") }
                    withTimeoutOrNull(pauseMs(attempt++)) { wake.receive() }
                }
                Outcome.NeedsAuthorization -> return fail(dir, "needs_authorization")
                Outcome.NotInstalled -> return fail(dir, "not_installed")
                Outcome.ModelUnavailable -> return fail(dir, "model_unavailable")
                is Outcome.Failed ->
                    return when (outcome.code) {
                        ERROR_RECOGNITION_FAILED -> Outcome.Text("")
                        FAILED_STOPPED ->
                            when {
                                !dir.isDirectory -> null
                                dir.name in yielding -> giveWay(dir, feed)
                                else -> fail(dir, ERROR_POSTPONED).also { keepHeard(dir, feed) }
                            }
                        else -> fail(dir, "failed_${outcome.code}")
                    }
            }
        }
    }

    /**
     * What a long recognition writes into its note as it goes: how far it got (recognizedMs of totalMs, a few times a minute) and
     * the text so far in live.txt, which the note shows until text.txt replaces it. So the list shows progress and words, not
     * only a spinner, and a postponed note keeps what was heard.
     */
    private inner class NoteFeed(
        private val dir: File,
        override val fromMs: Long,
    ) : RecognitionFeed {
        private var progressAt = 0L
        private var textAt = 0L
        private var checkpointAt = System.currentTimeMillis()
        private val earlierText = if (fromMs > 0) runCatching { File(dir, "text.txt").readText().trim() }.getOrDefault("") else ""
        private val earlierPhrases = if (fromMs > 0) NoteStore.readPhrases(dir) else emptyList()

        @Volatile var text = ""

        @Volatile var phrases = emptyList<NoteStore.Phrase>()

        override suspend fun beforeChunk(): Boolean {
            // Postponed, or deleted meanwhile: Govorun gets no more of it.
            if (dir.name in postponed || dir.name in yielding || !dir.isDirectory) return false
            pace()
            return dir.name !in postponed && dir.name !in yielding && dir.isDirectory
        }

        override fun fed(
            ms: Long,
            totalMs: Long,
        ) {
            val now = System.currentTimeMillis()
            if (now - progressAt < WRITE_EVERY_MS && ms < totalMs) return
            progressAt = now
            NoteStore.updateMeta(dir) {
                put("recognizedMs", ms)
                put("totalMs", totalMs)
            }
        }

        override fun heard(
            text: String,
            phrases: List<NoteStore.Phrase>,
        ) {
            this.text = text
            this.phrases = phrases
            RecognitionPreview.current.value = RecognitionPreview.Snapshot(
                dir.name,
                listOf(earlierText, text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n"),
                earlierPhrases + phrases.map { it.shifted(fromMs) },
            )
            val now = System.currentTimeMillis()
            // Once a minute what was heard is put aside with its word times: an app stopped by Android loses a minute, not the file.
            if (now - checkpointAt >= CHECKPOINT_MS && phrases.isNotEmpty()) {
                checkpointAt = now
                runCatching {
                    val aside = File(dir, CHECKPOINT_DIR).apply { mkdirs() }
                    File(aside, "text.txt").writeText(text)
                    NoteStore.writePhrases(aside, phrases.map { it.shifted(fromMs) })
                    File(aside, "end").writeText((fromMs + phrases.maxOf { it.endMs }).toString())
                }
            }
            if (now - textAt < WRITE_EVERY_MS) return
            textAt = now
            runCatching { File(dir, "live.txt").writeText(text) }
        }
    }

    /** A checkpoint left by an app that was stopped mid-file joins the note's text, and its end is where recognition goes on. */
    private fun promoteCheckpoint(dir: File) {
        val aside = File(dir, CHECKPOINT_DIR)
        val end = runCatching { File(aside, "end").readText().trim().toLong() }.getOrNull()
        val text = runCatching { File(aside, "text.txt").readText() }.getOrDefault("")
        if (end != null && text.isNotBlank()) {
            val earlier = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
            File(dir, "text.txt").writeText(listOf(earlier.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n"))
            NoteStore.writePhrases(dir, NoteStore.readPhrases(dir) + NoteStore.readPhrases(aside))
            File(dir, "live.txt").delete()
            NoteStore.updateMeta(dir) { put("resumeAtMs", end) }
        }
        aside.deleteRecursively()
    }

    /** Stepping aside for a fresh recording: what was heard stays, the note shows «в очереди» and goes on from there after it. */
    private fun giveWay(
        dir: File,
        feed: NoteFeed,
    ): Outcome.Text? {
        keepHeard(dir, feed)
        NoteStore.updateMeta(dir) { put("queued", true) }
        return null
    }

    /**
     * A paused note keeps what was heard up to its last whole phrase, with word times, and where that phrase ends: «Продолжить»
     * goes on from there instead of from the start. Without word times there is no safe place to go on from, and it starts over.
     */
    private fun keepHeard(
        dir: File,
        feed: NoteFeed,
    ) {
        File(dir, CHECKPOINT_DIR).deleteRecursively()
        val phrases = feed.phrases
        if (phrases.isEmpty() || feed.text.isBlank()) return
        val earlierText = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
        File(dir, "text.txt").writeText(listOf(earlierText.trim(), feed.text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n"))
        NoteStore.writePhrases(dir, NoteStore.readPhrases(dir) + phrases.map { it.shifted(feed.fromMs) })
        File(dir, "live.txt").delete()
        NoteStore.updateMeta(dir) { put("resumeAtMs", feed.fromMs + phrases.maxOf { it.endMs }) }
    }

    private fun fail(
        dir: File,
        error: String,
    ): Outcome.Text? {
        Log.w(TAG, "event=recognition_failed note=${dir.name} error=$error")
        if (error != ERROR_POSTPONED) File(dir, CHECKPOINT_DIR).deleteRecursively()
        onFailure(error)
        NoteStore.updateMeta(dir) {
            put("state", NoteStore.STATE_FAILED)
            put("error", error)
            remove("queued")
            remove("recognizedMs")
            remove("totalMs")
        }
        return null
    }

    companion object {
        const val ERROR_NO_SPEECH = "no_speech"
        const val ERROR_POSTPONED = "postponed"
        const val WRITE_EVERY_MS = 3_000L
        const val CHECKPOINT_MS = 60_000L
        const val CHECKPOINT_DIR = "checkpoint"
        const val BUSY_GIVE_UP_MS = 30L * 60 * 1_000
        const val SLICE_BYTES = 60L * NoteStore.BYTES_PER_SECOND

        /** A rest this short (half a second) is not worth a file of its own. */
        const val LEFT_OVER_BYTES = NoteStore.BYTES_PER_SECOND / 2L

        /** Govorun answers 5 when the audio holds nothing it can recognize, e.g. silence. */
        private const val ERROR_RECOGNITION_FAILED = 5
        private const val TAG = "Transcriber"
    }
}

/** 2, 4, 8, 15, 15 ... seconds: short, so recognition resumes soon after Govorun frees up. */
internal fun retryPauseMs(attempt: Int): Long = (2_000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L)
