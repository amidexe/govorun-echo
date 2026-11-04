package com.govorun.echo.notes

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToLong

/**
 * «Фрагмент» on disk: cutting a piece out of a note («Вырезать»), keeping only the piece («Оставить») and making a new note of it
 * («Отдельно»). The sound, the text, the words with their times, the bars of the waveform and the places of continuations change
 * together ([CutModel] says how, [CutSound] makes the sound). An edit keeps the note as it was in `undo/` until it is confirmed or
 * undone; once it is confirmed the cut sound is deleted for good.
 */
internal object NoteCut {
    private const val TAG = "NoteCut"
    private const val UNDO = "undo"
    private const val PENDING = "pending"
    private const val WORK = "cut.tmp"

    enum class Edit { CUT, KEEP }

    /** A stretch of a note's sound in milliseconds. */
    data class Span(
        val fromMs: Double,
        val toMs: Double,
    ) {
        val length get() = toMs - fromMs
    }

    /** When the sound at [ms] of a note was said: its start plus the time into it, or by the part or continuation it belongs to. */
    fun saidAt(
        meta: JSONObject,
        ms: Long,
    ): Long {
        val parts = meta.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val offset = part.optLong("offsetMs")
                if (ms >= offset && ms < offset + part.optLong("audioMs")) return part.optLong("startedAtMs") + (ms - offset)
            }
        }
        val appends = meta.optJSONArray("appends")
        val mark = appends?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("fromMs") && it.optLong("fromMs") <= ms }.maxByOrNull { it.optLong("fromMs") } }
        return if (mark != null) mark.optLong("atMs") + (ms - mark.optLong("fromMs")) else meta.optLong("startedAtMs") + ms
    }

    /** The same for a note on disk; the present moment when its meta cannot be read. */
    fun saidAt(
        dir: File,
        ms: Long,
    ): Long = runCatching { saidAt(JSONObject(File(dir, "meta.json").readText()), ms) }.getOrDefault(System.currentTimeMillis())

    /**
     * Whether a note can be cut or trimmed in place: finished, with sound, not a joined note (its parts could no longer be separated)
     * and with a text that is its timed words one after another (or no text at all).
     */
    fun canEdit(dir: File): Boolean =
        runCatching {
            val meta = JSONObject(File(dir, "meta.json").readText())
            if (meta.optString("state") != NoteStore.STATE_DONE || meta.has("parts") || meta.has("appendFromMs") || NoteStore.audioParts(meta).isEmpty()) return false
            if (NoteStore.pcmFile(dir).exists() || File(dir, UNDO).exists()) return false
            val text = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
            text.isBlank() || CutModel.textFollowsWords(text, CutModel.readPhrases(dir).flatMap { p -> p.words.map { it.text } })
        }.getOrDefault(false)

    /** What an edit did: how long the note is now and how much was removed. */
    data class Done(
        val keptMs: Long,
        val removedMs: Long,
    )

    /**
     * Cuts [fromMs]-[toMs] out of the note ([Edit.CUT]) or keeps only it ([Edit.KEEP]). The old state stays in `undo/` until [commit]
     * or [undo]. Null when nothing was changed.
     */
    fun edit(
        dir: File,
        edit: Edit,
        fromMs: Long,
        toMs: Long,
        transcode: (File, File, Double, Double) -> Boolean = CutSound::transcode,
    ): Done? {
        val total = runCatching { NoteStore.audioParts(JSONObject(File(dir, "meta.json").readText())).sumOf { it.ms } }.getOrDefault(0L)
        val from = fromMs.coerceIn(0L, total).toDouble()
        val to = toMs.coerceIn(fromMs.coerceIn(0L, total), total).toDouble()
        val wanted =
            when (edit) {
                Edit.CUT -> listOf(Span(0.0, from), Span(to, total.toDouble()))
                Edit.KEEP -> listOf(Span(from, to))
            }
        return keep(dir, wanted, transcode)
    }

    /**
     * Keeps only the [wanted] stretches of the note (in its own time) and removes the rest: sound, text, words, bars and marks
     * together. The old state stays in `undo/` until [commit] or [undo]. Null when nothing was changed.
     */
    fun keep(
        dir: File,
        wantedSpans: List<Span>,
        transcode: (File, File, Double, Double) -> Boolean = CutSound::transcode,
    ): Done? {
        if (!canEdit(dir)) return null
        val meta = JSONObject(File(dir, "meta.json").readText())
        val parts = NoteStore.audioParts(meta)
        val total = parts.sumOf { it.ms }
        val wanted = CutModel.normalized(wantedSpans, total)
        if (wanted.isEmpty() || wanted.sumOf { it.length } >= total - 1.0) return null
        val work = File(dir, WORK).apply { deleteRecursively(); mkdirs() }
        try {
            val pieces = CutSound.soundOf(dir, parts, wanted, work, exact = false, transcode) ?: return null
            if (pieces.isEmpty()) return null
            val line = CutModel.Timeline(pieces.map { it.span })
            val phrases = CutModel.readPhrases(dir)
            val text = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
            val words = phrases.flatMap { it.words }
            val keptWords = BooleanArray(words.size) { line.keeps((words[it].startMs + words[it].endMs) / 2.0) }
            val newText =
                if (text.isBlank()) {
                    null
                } else {
                    CutModel.cutText(text, words.map { it.text }, keptWords) ?: return null
                }
            val newPhrases = CutModel.cutPhrases(phrases, line)
            val newAppends = newText?.let { CutModel.cutAppends(meta.optJSONArray("appends"), text, it.second, line) } ?: JSONArray()
            val bars = AudioPeaks.savedBars(dir, total)?.let { CutModel.cutPeaks(it, line.kept) }
            // The new sound goes into a folder of its own under its final names; a whole old file is linked there, not copied.
            val fresh = File(work, "chunks").apply { mkdirs() }
            val names = pieces.mapIndexed { i, piece -> "%03d.%s".format(i, (piece.reused ?: piece.file!!).extension) }
            pieces.forEachIndexed { i, piece ->
                val target = File(fresh, names[i])
                if (piece.reused != null) linkOrCopy(piece.reused, target) else check(piece.file!!.renameTo(target)) { "place_failed" }
            }
            // The old state goes aside, then the two folders of sound swap; an edit cut off at any point is put back by [settle].
            val undo = setAside(dir)
            check(File(dir, "chunks").renameTo(File(undo, "chunks"))) { "swap_failed" }
            check(fresh.renameTo(File(dir, "chunks"))) { "swap_failed" }
            if (newText != null) writeAtomically(File(dir, "text.txt"), newText.first)
            CutModel.writePhrases(dir, newPhrases)
            if (bars != null) AudioPeaks.saveBars(dir, bars) else File(dir, "peaks2.bin").delete()
            val keptMs = pieces.sumOf { it.ms }
            NoteStore.writeMeta(
                dir,
                JSONObject(meta.toString()).apply {
                    put("audio", JSONObject().also { audio -> pieces.forEachIndexed { i, piece -> audio.put(names[i], piece.ms) } })
                    put("durationMs", keptMs)
                    if (newAppends.length() > 0) put("appends", newAppends) else remove("appends")
                    // The last time the note got new words is the newest continuation that is still there.
                    if (has("appends") || meta.has("appends")) {
                        val last = (0 until newAppends.length()).maxOfOrNull { newAppends.getJSONObject(it).optLong("atMs") }
                        if (last != null) put("modifiedAtMs", last) else remove("modifiedAtMs")
                    }
                },
            )
            File(undo, PENDING).delete()
            return Done(keptMs, total - keptMs)
        } catch (error: Exception) {
            Log.w(TAG, "event=edit_failed note=${dir.name}", error)
            if (File(File(dir, UNDO), PENDING).exists()) restore(dir) else File(dir, UNDO).deleteRecursively()
            return null
        } finally {
            work.deleteRecursively()
        }
    }

    /** The note's meta, text, words and bars copied to `undo/`, marked as an edit still being written until [PENDING] goes. */
    private fun setAside(dir: File): File {
        val undo = File(dir, UNDO).apply { deleteRecursively(); mkdirs() }
        listOf("meta.json", "text.txt", "segments.json", "peaks2.bin").forEach { name -> File(dir, name).takeIf { it.exists() }?.copyTo(File(undo, name), overwrite = true) }
        File(undo, PENDING).writeText("")
        return undo
    }

    /**
     * Rewrites a note's text, words or meta with [write], its sound untouched ([WordFix]): the old state waits in `undo/` while it is
     * written, so a write cut off by a crash is put back by [settle], and goes once it is written. False when the note is busy with
     * another edit or the write failed; then the note is as it was.
     */
    fun rewrite(
        dir: File,
        write: () -> Unit,
    ): Boolean {
        if (File(dir, UNDO).exists()) return false
        setAside(dir)
        return try {
            write()
            commit(dir)
            true
        } catch (error: Exception) {
            Log.w(TAG, "event=rewrite_failed note=${dir.name}", error)
            restore(dir)
            false
        }
    }

    /** Keeps [wanted] for good: the edit is made and confirmed at once, the cut sound is deleted. */
    fun save(
        dir: File,
        wanted: List<Span>,
        transcode: (File, File, Double, Double) -> Boolean = CutSound::transcode,
    ): Done? = keep(dir, wanted, transcode)?.also { commit(dir) }

    /** Puts the note back as it was before the last edit. */
    fun undo(dir: File): Boolean {
        if (!File(File(dir, UNDO), "meta.json").exists()) return false
        restore(dir)
        return true
    }

    /** Confirms the last edit: the old sound and text are deleted for good. */
    fun commit(dir: File) {
        File(dir, UNDO).deleteRecursively()
        File(dir, WORK).deleteRecursively()
    }

    /** After a restart: an edit cut off half-way is undone, a finished one is confirmed (its "Отменить" is gone with the app). */
    fun settle(root: File) {
        root.listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
            File(dir, WORK).deleteRecursively()
            val undo = File(dir, UNDO)
            if (!undo.exists()) return@forEach
            if (File(undo, PENDING).exists()) restore(dir) else commit(dir)
        }
    }

    /** Puts back the state kept in `undo/`: the old folder of sound (the new one goes), the text, the words, the bars and the meta. */
    private fun restore(dir: File) {
        val undo = File(dir, UNDO)
        val chunks = File(dir, "chunks")
        val old = File(undo, "chunks")
        // Once the old folder is aside, whatever stands in its place is the new sound.
        if (old.exists()) {
            if (chunks.exists()) chunks.deleteRecursively()
            check(old.renameTo(chunks)) { "restore_failed" }
        }
        listOf("text.txt", "segments.json", "peaks2.bin").forEach { name ->
            val saved = File(undo, name)
            if (saved.exists()) saved.copyTo(File(dir, name), overwrite = true) else File(dir, name).delete()
        }
        // As it was, its time of change included: the edit taken back never happened for a sync either.
        File(undo, "meta.json").takeIf { it.exists() }?.let { NoteStore.writeMeta(dir, JSONObject(it.readText()), touched = false) }
        undo.deleteRecursively()
        File(dir, WORK).deleteRecursively()
    }

    /** A whole file shared by the old and the new state: a second name for the same data, or a copy where links are not possible. */
    private fun linkOrCopy(
        source: File,
        target: File,
    ) {
        val linked = runCatching { java.nio.file.Files.createLink(target.toPath(), source.toPath()) }.isSuccess
        if (!linked) source.copyTo(target, overwrite = true)
    }

    /** Makes a new note of [fromMs]-[toMs]: its sound, its text and its words with their times; the note itself stays as it is. */
    fun separate(
        root: File,
        id: String,
        fromMs: Long,
        toMs: Long,
        transcode: (File, File, Double, Double) -> Boolean = CutSound::transcode,
    ): String? = separate(root, id, listOf(Span(fromMs.toDouble(), toMs.toDouble())), transcode)

    /** The same for a piece made of several stretches (a piece chosen across a cut not yet saved). */
    fun separate(
        root: File,
        id: String,
        spans: List<Span>,
        transcode: (File, File, Double, Double) -> Boolean = CutSound::transcode,
    ): String? {
        val dir = File(root, id)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return null
        val parts = NoteStore.audioParts(meta)
        val total = parts.sumOf { it.ms }
        val wanted = CutModel.normalized(spans, total)
        if (wanted.isEmpty()) return null
        val from = wanted.first().fromMs.roundToLong()
        val newId = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val target = File(root, newId)
        val work = File(target, WORK).apply { mkdirs() }
        try {
            val pieces = CutSound.soundOf(dir, parts, wanted, work, exact = true, transcode) ?: error("no_sound")
            val line = CutModel.Timeline(wanted)
            val phrases = CutModel.readPhrases(dir)
            val words = phrases.flatMap { it.words }
            val keptWords = BooleanArray(words.size) { line.keeps((words[it].startMs + words[it].endMs) / 2.0) }
            val text = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
            val newPhrases = CutModel.cutPhrases(phrases, line)
            val cut = if (text.isBlank()) null else CutModel.cutText(text, words.map { it.text }, keptWords)
            val newText = cut?.first ?: newPhrases.joinToString(" ") { it.text }
            val appends = cut?.let { CutModel.cutAppends(meta.optJSONArray("appends"), text, it.second, line) } ?: JSONArray()
            val chunks = File(target, "chunks").apply { mkdirs() }
            val audio = JSONObject()
            pieces.forEachIndexed { i, piece ->
                val name = "%03d.%s".format(i, (piece.reused ?: piece.file!!).extension)
                if (piece.reused != null) linkOrCopy(piece.reused, File(chunks, name)) else check(piece.file!!.renameTo(File(chunks, name))) { "place_failed" }
                audio.put(name, piece.ms)
            }
            File(target, "text.txt").writeText(newText)
            CutModel.writePhrases(target, newPhrases)
            AudioPeaks.savedBars(dir, total)?.let { AudioPeaks.saveBars(target, CutModel.cutPeaks(it, line.kept)) }
            NoteStore.writeMeta(
                target,
                JSONObject()
                    .put("state", NoteStore.STATE_DONE)
                    .put("mode", meta.optString("mode").let { if (it.isEmpty() || it == "merged") "long" else it })
                    .put("startedAtMs", saidAt(meta, from))
                    .put("durationMs", pieces.sumOf { it.ms })
                    .put("bytes", 0)
                    .put("audio", audio)
                    .put("archived", meta.optBoolean("archived"))
                    .apply {
                        meta.optJSONArray("tags")?.takeIf { it.length() > 0 }?.let { put("tags", it) }
                        if (appends.length() > 0) {
                            put("appends", appends)
                            put("modifiedAtMs", (0 until appends.length()).maxOf { appends.getJSONObject(it).optLong("atMs") })
                        }
                    },
            )
            return newId
        } catch (error: Exception) {
            Log.w(TAG, "event=separate_failed note=$id", error)
            target.deleteRecursively()
            return null
        } finally {
            work.deleteRecursively()
        }
    }

    /** The text of [fromMs]-[toMs] of a note: its words with their breaks, as the note shows them. */
    fun textOf(
        dir: File,
        fromMs: Long,
        toMs: Long,
    ): String = textOf(dir, listOf(Span(fromMs.toDouble(), toMs.toDouble())))

    fun textOf(
        dir: File,
        spans: List<Span>,
    ): String {
        val line = CutModel.Timeline(spans)
        val words = CutModel.readPhrases(dir).flatMap { it.words }
        val kept = BooleanArray(words.size) { line.keeps((words[it].startMs + words[it].endMs) / 2.0) }
        val text = runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
        return CutModel.cutText(text, words.map { it.text }, kept)?.first ?: words.filterIndexed { i, _ -> kept[i] }.joinToString(" ") { it.text }
    }
}
