package com.govorun.echo.notes

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import com.govorun.echo.audio.Pcm
import com.govorun.echo.CapturePurpose

/** One folder per note: `meta.json` plus `chunks/NNN.pcm` (raw 16 kHz mono PCM). */
object NoteStore {
    /** A note written to more than a minute after it began counts as continued. */
    const val CONTINUED_AFTER_MS = 60_000L
    const val SAMPLE_RATE = Pcm.SAMPLE_RATE

    /** Below this much free room nothing new is written: a recording stops, an import is refused. */
    const val MIN_FREE_BYTES = 200L * 1024 * 1024
    const val BYTES_PER_SECOND = Pcm.BYTES_PER_SECOND

    fun root(context: Context): File = com.govorun.echo.UserFiles.notes(context)

    fun newNote(context: Context): Pair<String, File> {
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        return id to File(root(context), id).apply { File(this, "chunks").mkdirs() }
    }

    /** A writer can add audio to its note, but must never recreate a note moved to the trash. */
    internal fun ensureChunks(dir: File): File? {
        if (!Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS)) return null
        return File(dir, "chunks").takeIf { it.mkdir() || Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
    }

    fun durationMs(bytes: Long): Long = bytes * 1_000L / BYTES_PER_SECOND


    const val STATE_RECORDING = "recording"
    const val STATE_RECORDED = "recorded"
    const val STATE_RECOGNIZING = "recognizing"
    const val STATE_DONE = "done"
    const val STATE_FAILED = "failed"

    /** The layout of a note's folder, written into its meta, so a later version knows how to read it; a meta without it is 1. */
    const val FORMAT = 1

    /** The notes that left the list (to the trash, joined, split, cleared away), one JSON line each, for a sync to come. */
    const val DELETED = "deleted.jsonl"

    /**
     * Writes a note's meta with the [FORMAT] in it and, for any change of the note ([touched]), `changedAtMs`: the moment it last
     * changed in any way, which a sync will compare (`modifiedAtMs` stays when it was last continued). Only bookkeeping, like the
     * copy folder's record, is written untouched.
     */
    @Synchronized
    fun writeMeta(
        dir: File,
        meta: JSONObject,
        touched: Boolean = true,
        durable: Boolean = false,
    ) {
        meta.put("format", FORMAT)
        if (touched) meta.put("changedAtMs", System.currentTimeMillis())
        com.govorun.echo.UserFiles.replaceText(File(dir, "meta.json"), meta.toString(), durable)
        if (touched) NoteChanges.signal()
    }

    @Synchronized
    fun updateMeta(
        dir: File,
        touched: Boolean = true,
        change: JSONObject.() -> Unit,
    ) {
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrDefault(JSONObject())
        meta.change()
        writeMeta(dir, meta, touched)
    }

    /** Writes in [root]'s log of deleted notes that note [id] has just left the list. */
    @Synchronized
    fun forget(
        root: File,
        id: String,
    ) = File(root, DELETED).appendText(JSONObject().put("id", id).put("atMs", System.currentTimeMillis()).toString() + "\n").also { NoteChanges.signal() }

    /** Deletes a note's folder for good and notes it in the log of deleted notes. */
    fun remove(dir: File): Boolean = dir.deleteRecursively().also { if (it) forget(dir.parentFile ?: return@also, dir.name) }

    /** Joins finished notes into one new note (oldest first) and removes the originals ([NoteMerge]). */
    fun merge(
        root: File,
        ids: List<String>,
        inOrder: Boolean = false,
    ): String? = NoteMerge.merge(root, ids, inOrder)

    fun noteDir(
        context: Context,
        id: String,
    ): File = CommandHistory.directory(context, id)

    /** Archived notes are kept for good: they leave the main list and the auto-delete never touches them. */
    fun setArchived(
        root: File,
        ids: List<String>,
        archived: Boolean,
    ) = ids.forEach { id -> updateMeta(File(root, id)) { if (archived) put("archived", true) else remove("archived") } }

    /** The note recorded next on the same day that has audio, for playing a day's notes one after another. */
    fun nextToPlay(
        context: Context,
        id: String,
    ): Entry? {
        val all = list(context)
        val current = all.firstOrNull { it.id == id } ?: return null
        fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return all
            .filter { it.audio.isNotEmpty() && it.archived == current.archived && it.startedAtMs > current.startedAtMs && day(it.startedAtMs) == day(current.startedAtMs) }
            .minByOrNull { it.startedAtMs }
    }

    /**
     * The text a note shows: the saved text, and while it is being continued also what is being said now (live.txt),
     * after what was already there. Before the first save, only the live text.
     */
    private fun textOf(
        dir: File,
        state: String,
        textFile: File,
    ): String {
        val saved = runCatching { textFile.readText() }.getOrNull()
        val live = runCatching { File(dir, "live.txt").readText() }.getOrDefault("")
        return when {
            saved == null -> live
            (state == STATE_RECORDING || state == STATE_RECOGNIZING) && live.isNotBlank() -> listOf(saved.trim(), live.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
            else -> saved
        }
    }

    /** Adds or removes one tag on every listed note; the list of tags is simply what the notes carry. */
    fun setTag(
        root: File,
        ids: List<String>,
        tag: String,
        add: Boolean,
    ) = ids.forEach { id ->
        updateMeta(File(root, id)) {
            val current = optJSONArray("tags")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
            val next = if (add) (current + tag).distinct() else current - tag
            if (next.isEmpty()) remove("tags") else put("tags", org.json.JSONArray(next))
        }
    }

    /** A tag's new name on every listed note, in its place among the others, and in a joined note's passport; a name already there stays once. */
    fun renameTag(root: File, ids: List<String>, from: String, to: String) = ids.forEach { id ->
        updateMeta(File(root, id)) {
            val rename = { holder: JSONObject ->
                holder.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.takeIf { from in it }
                    ?.let { tags -> holder.put("tags", org.json.JSONArray(tags.map { if (it == from) to else it }.distinct())) }
            }
            rename(this)
            optJSONArray("parts")?.let { parts -> (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.forEach { rename(it) } }
        }
    }

    /**
     * A note's own name, as the owner typed it; a blank one leaves the note without a name. Any state: a lecture can be named while
     * it runs. A note gone meanwhile (deleted while its name was being typed) is left alone.
     */
    fun setTitle(
        root: File,
        id: String,
        title: String,
    ) {
        val dir = File(root, id)
        if (!File(dir, "meta.json").exists()) return
        // A name typed by hand is the owner's own, even where an automatic one stood ([AutoTitles]).
        updateMeta(dir) {
            remove("titleAuto")
            cleanTitle(title).let { if (it.isEmpty()) remove("title") else put("title", it) }
        }
    }

    /** Keeps what note [id] is about, as its owner wrote it; empty removes it. */
    fun setAbout(
        root: File,
        id: String,
        about: String,
    ) {
        val dir = File(root, id)
        if (!File(dir, "meta.json").exists()) return
        about.trim().take(MAX_ABOUT).let { text -> updateMeta(dir) { if (text.isEmpty()) remove("about") else put("about", text) } }
    }

    const val MAX_ABOUT = 300

    /** One line of reasonable length: a pasted paragraph or a line break becomes spaces. */
    fun cleanTitle(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)

    const val MAX_TITLE = 120

    /** A finished note can be continued; a failed or still-working one cannot. */
    /**
     * A note can be continued once its text is settled: done, or failed with nothing left to recognize (no speech was found). A note
     * still waiting to be recognized again keeps its raw audio, which a continuation would replace, so it is not continued. Nor is a
     * note whose sound was deleted: its text is all there is, and a continuation would rebuild it from sound alone and lose it.
     */
    fun isAppendable(dir: File): Boolean =
        runCatching {
            val meta = JSONObject(File(dir, "meta.json").readText())
            when (meta.optString("state")) {
                STATE_DONE -> audioParts(meta).isNotEmpty() || pcmFile(dir).exists()
                STATE_FAILED -> !pcmFile(dir).exists()
                else -> false
            }
        }.getOrDefault(false)

    /**
     * Length of what is already recorded. A recording that was cut short (the app killed mid-way) leaves audio
     * without a saved duration, so the audio parts count too: continuing such a note starts after them.
     */
    fun durationOf(dir: File): Long =
        runCatching {
            val meta = JSONObject(File(dir, "meta.json").readText())
            val parts = audioParts(meta).sumOf { it.ms }
            maxOf(meta.optLong("durationMs"), parts)
        }.getOrDefault(0L)

    /** The note with its words and their times, read from its folder: the list does not hold them, a lecture's take megabytes. */
    fun withPhrases(
        context: Context,
        note: Entry,
    ): Entry = if (!note.timed || note.phrases.isNotEmpty()) note else note.copy(phrases = readPhrases(noteDir(context, note.id)))

    fun readPhrases(dir: File): List<Phrase> =
        runCatching {
            val array = org.json.JSONArray(File(dir, "segments.json").readText())
            (0 until array.length()).map {
                val item = array.getJSONObject(it)
                val words = item.optJSONArray("w")
                val known = words?.let { w -> (0 until w.length()).map { i -> w.getJSONArray(i).let { Word(it.getLong(0), it.getLong(1), it.getString(2)) } } }.orEmpty()
                val start = item.getLong("s")
                val end = item.getLong("e")
                val text = item.getString("t")
                // Notes made when Govorun gave no word times still highlight word by word, with estimated times.
                Phrase(start, end, text, known.ifEmpty { com.govorun.echo.notes.estimatedWords(text, start, end) })
            }
        }.getOrDefault(emptyList())

    fun writePhrases(
        dir: File,
        phrases: List<Phrase>,
    ) {
        val array = org.json.JSONArray()
        phrases.forEach { phrase ->
            val item = JSONObject().put("s", phrase.startMs).put("e", phrase.endMs).put("t", phrase.text)
            if (phrase.words.isNotEmpty()) {
                item.put("w", org.json.JSONArray().also { w -> phrase.words.forEach { w.put(org.json.JSONArray().put(it.startMs).put(it.endMs).put(it.text)) } })
            }
            array.put(item)
        }
        File(dir, "segments.json").writeText(array.toString())
    }

    /** Removes the compressed audio but keeps the text; phrases lose their meaning and go too. */
    fun deleteAudio(dir: File) {
        File(dir, "chunks").listFiles().orEmpty().forEach { it.delete() }
        File(dir, "segments.json").delete()
        File(dir, "peaks.bin").delete()
        File(dir, "peaks2.bin").delete()
        updateMeta(dir) { remove("audio") }
    }

    /** The recording itself: raw PCM, valid even if cut short by a crash. */
    fun pcmFile(dir: File): File = File(dir, "audio.pcm")

    fun writeLiveText(
        dir: File,
        text: String,
    ) {
        runCatching { File(dir, "live.txt").writeText(text) }
    }

    /** Notes that still need work and have no live recording behind them. */
    fun pendingNoteIds(
        context: Context,
        recordingNoteId: String?,
    ): List<String> =
        root(context)
            .listFiles { file -> file.isDirectory && file.name != recordingNoteId }
            .orEmpty()
            .filter { dir ->
                val state = runCatching { JSONObject(File(dir, "meta.json").readText()).optString("state") }.getOrDefault("")
                // A finished note with raw audio left next to it was cut off while its audio was being compressed.
                state == STATE_RECORDING || state == STATE_RECORDED || state == STATE_RECOGNIZING || (state == STATE_DONE && pcmFile(dir).exists())
            }.map { it.name }

    /** A continuation that recorded nothing or was cancelled: the note goes back as it was, and nothing heard in it stays. */
    fun putBack(dir: File) {
        pcmFile(dir).delete()
        File(dir, "live.txt").delete()
        updateMeta(dir) {
            put("state", STATE_DONE)
            remove("appendFromMs")
            remove("appendAtMs")
        }
    }

    fun delete(
        context: Context,
        id: String,
    ) {
        remove(noteDir(context, id))
    }

    /** One phrase of a streamed recording with its approximate place in the audio. */
    @androidx.compose.runtime.Immutable
    data class Phrase(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val words: List<Word> = emptyList(),
    ) {
        fun shifted(byMs: Long) = copy(startMs = startMs + byMs, endMs = endMs + byMs, words = words.map { it.copy(startMs = it.startMs + byMs, endMs = it.endMs + byMs) })
    }

    /** One word as shown in the text (with its punctuation) and where it is spoken. */
    @androidx.compose.runtime.Immutable
    data class Word(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    @androidx.compose.runtime.Immutable
    data class AudioPart(
        val file: String,
        val ms: Long,
    )

    @androidx.compose.runtime.Immutable
    data class Entry(
        val id: String,
        val mode: String,
        val startedAtMs: Long,
        val durationMs: Long,
        val bytes: Long,
        val stopReason: String,
        val state: String,
        val error: String,
        val text: String,
        val audio: List<AudioPart> = emptyList(),
        val silencedMs: Long = 0L,
        val phrases: List<Phrase> = emptyList(),
        val archived: Boolean = false,
        /** Favorite marker. The legacy metadata key remains pinned; favorites stay in chronology. */
        val pinned: Boolean = false,
        /** Text is ready, the compressed audio is still being written, so the player will appear. */
        val archiving: Boolean = false,
        val tags: List<String> = emptyList(),
        /** True while this note is being continued ("append"): its length so far is what it already holds. */
        val appending: Boolean = false,
        /** A joined note whose parts can still be separated again (nothing was added to it since). */
        val canSplit: Boolean = false,
        /** The parts of a joined note: when each began and its text, in the order joined; empty for a plain note. */
        val parts: List<Part> = emptyList(),
        /** When the note last got new text or sound (continued); the start time if it never did. */
        val modifiedAtMs: Long = 0L,
        /** While the note is being recorded or continued: what is being said now, before it becomes part of the saved text. */
        val live: String = "",
        /** Where each later continuation begins in the text, and when it was said. */
        val appends: List<Append> = emptyList(),
        /** The name the owner gave the note; empty when it has none, which is most notes. */
        val title: String = "",
        /** The name was taken from the note's words, not typed ([AutoTitles]): it does not leave the app with the text. */
        val titleAuto: Boolean = false,
        /** What the note is about, in the owner's own words: a line or two to find one's way by; never written by a model. */
        val about: String = "",
        /** Govorun gave the text with word times. The list leaves them on disk ([withPhrases] reads them for the open note). */
        val timed: Boolean = phrases.isNotEmpty(),
        val recognizedMs: Long = 0L, val totalMs: Long = 0L, val queued: Boolean = false, val preparedMs: Long = 0L, // a long file's progress
        /** The card the smart functions made for the note ([NoteCard]); null until one is made. */
        val card: NoteCard? = null,
        /** The note's «Кратко» ([NoteBrief]); null until the person asks for one. */
        val brief: NoteBrief? = null,
        /** Govorun's fair copy («Улучшить через Говорун») and «Итоги»; null until asked for, the transcript stays as it is. */
        val fair: NoteFair? = null,
        val itogi: NoteItogi? = null,
        val unseen: Boolean? = null,
    ) {
        /** Written to after it was made: by continuations kept with their places, or earlier ones known only by the last time. */
        val continued: Boolean get() = appends.isNotEmpty() || modifiedAtMs - startedAtMs > CONTINUED_AFTER_MS
    }

    /** One note a joined note was made of: when it began, when it was last written to, and its text. */
    @androidx.compose.runtime.Immutable
    data class Part(
        val startedAtMs: Long,
        val modifiedAtMs: Long,
        val text: String,
        val appends: List<Append> = emptyList(),
    )

    /** A continuation of a note: when it was said and where its text begins in the note's text. */
    @androidx.compose.runtime.Immutable
    data class Append(
        val atMs: Long,
        val textAt: Int,
    )

    fun appendsOf(array: org.json.JSONArray?): List<Append> =
        array?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Append(it.optLong("atMs"), it.optInt("textAt")) } }.orEmpty()

    /** Compressed chunks of a note in playback order, with their lengths. */
    fun audioParts(meta: JSONObject): List<AudioPart> {
        val audio = meta.optJSONObject("audio") ?: return emptyList()
        return audio.keys().asSequence().sorted().map { AudioPart(it, audio.optLong(it)) }.toList()
    }

    private class Cached(
        val stamp: Long,
        val entry: Entry,
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    /**
     * Newest first. Finished notes are cached by file timestamps, so reopening a list of thousands of notes rereads only what
     * changed. Word times stay on disk: a semester of lectures holds tens of megabytes of them, and the list needs none.
     */
    fun list(context: Context): List<Entry> {
        val dirs = root(context).listFiles { file -> file.isDirectory }.orEmpty()
        cache.keys.retainAll(dirs.map { it.name }.toSet())
        return dirs
            .mapNotNull { dir ->
                runCatching {
                    val metaFile = File(dir, "meta.json")
                    val textFile = File(dir, "text.txt")
                    val stamp = metaFile.lastModified() * 31 + metaFile.length() + textFile.lastModified() + textFile.length()
                    cache[dir.name]?.takeIf { it.stamp == stamp }?.let { return@runCatching it.entry }
                    val meta = JSONObject(metaFile.readText())
                    if (CommandHistory.isCommand(meta)) {
                        if (meta.optString("state") == STATE_DONE && !pcmFile(dir).exists() && meta.getJSONObject("command").optString("phase") !in setOf("processing", "dispatching")) CommandHistory.separate(context, dir)
                        return@runCatching null
                    }
                    if (CapturePurpose.read(meta) == CapturePurpose.COMMAND) return@runCatching null
                    val state = meta.optString("state", STATE_RECORDED)
                    val entry =
                        Entry(
                            id = dir.name,
                            mode = meta.optString("mode"),
                            startedAtMs = meta.optLong("startedAtMs"),
                            durationMs = meta.optLong("durationMs"),
                            bytes = meta.optLong("bytes"),
                            stopReason = meta.optString("stopReason"),
                            state = state,
                            error = meta.optString("error"),
                            audio = audioParts(meta),
                            silencedMs = meta.optLong("silencedMs"),
                            archived = meta.optBoolean("archived"),
                            pinned = meta.optBoolean("pinned"), unseen = if (meta.has("unseen")) meta.optBoolean("unseen") else null,
                            tags = meta.optJSONArray("tags")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty(),
                            archiving = state == STATE_DONE && pcmFile(dir).exists(),
                            appending = meta.has("appendFromMs"),
                            canSplit = NoteSplit.canSplit(meta),
                            parts = meta.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { p -> Part(p.optLong("startedAtMs"), p.optLong("modifiedAtMs"), p.optString("text"), appendsOf(p.optJSONArray("appends"))) } } }.orEmpty(),
                            modifiedAtMs = meta.optLong("modifiedAtMs").takeIf { it > 0 } ?: meta.optLong("startedAtMs"),
                            text = if (meta.optString("mode") == NoteText.MODE) NoteText.read(dir, meta).trim() else textOf(dir, state, textFile),
                            live = if (state == STATE_RECORDING) runCatching { File(dir, "live.txt").readText().trim() }.getOrDefault("") else "",
                            appends = appendsOf(meta.optJSONArray("appends")),
                            title = meta.optString("title"),
                            titleAuto = meta.optBoolean("titleAuto"),
                            about = meta.optString("about"),
                            timed = File(dir, "segments.json").length() > 2,
                            recognizedMs = meta.optLong("recognizedMs"), totalMs = meta.optLong("totalMs"), queued = meta.optBoolean("queued"), preparedMs = meta.optLong("preparedMs"),
                        ).let { it.copy(card = NoteCard.of(meta.optJSONObject("card"), it.text), brief = NoteBrief.of(meta.optJSONObject("brief"), it.text), fair = NoteFair.of(meta.optJSONObject("fair"), it.text), itogi = NoteItogi.of(meta.optJSONObject("itogi"), it.text)) }
                    if (((state == STATE_DONE && !entry.archiving) || state == STATE_FAILED)) cache[dir.name] = Cached(stamp, entry)
                    entry
                }.getOrNull()
            }.sortedByDescending { it.startedAtMs }
    }
}
