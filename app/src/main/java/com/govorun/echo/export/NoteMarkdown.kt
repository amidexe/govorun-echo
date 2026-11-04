package com.govorun.echo.export

import com.govorun.echo.formatDuration
import com.govorun.echo.notes.NoteStore
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A note as a Markdown file for Obsidian and apps like it, next to its sound in the copy folder — open formats, nothing of Echo's
 * own: its date, length, name, description, tags and pin as YAML properties, its id and `source: echo` so a later copy or import
 * knows it again, its sound embedded with a plain Markdown link (Obsidian plays it inside the note, other apps open it), then its
 * «Текст» if it has one and the transcript under «Расшифровка», with the time of every piece said into it later (and of every
 * note a joined one was made of), as text leaving the app carries them. The plain `.txt` beside it stays what is restored from.
 */
internal object NoteMarkdown {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneId.systemDefault())
    private val stamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

    private class Piece(
        val atMs: Long,
        val later: Boolean,
        val text: String,
    )

    /** The file for a note with this [meta] and [text], its sound in the files named [sounds] beside it. */
    fun of(
        meta: JSONObject,
        text: String,
        sounds: List<String>,
        id: String = "",
    ): String {
        val startedAt = meta.optLong("startedAtMs")
        val out = StringBuilder("---\n")
        out.append("created: ").append(dateTime.format(Instant.ofEpochMilli(startedAt))).append('\n')
        val modifiedAt = meta.optLong("modifiedAtMs")
        if (modifiedAt - startedAt > NoteStore.CONTINUED_AFTER_MS) out.append("updated: ").append(dateTime.format(Instant.ofEpochMilli(modifiedAt))).append('\n')
        meta.optLong("durationMs").takeIf { it > 0 }?.let { out.append("duration: ").append(quoted(formatDuration(it))).append('\n') }
        // A typed name or description is the person's own; else what Govorun's «Текст» suggested, as the note's screen shows.
        val typed = meta.optString("mode") == com.govorun.echo.notes.NoteText.MODE
        val fair = if (typed) null else com.govorun.echo.notes.NoteFair.of(meta.optJSONObject("fair"), text)
        val summary = com.govorun.echo.notes.NoteItogi.of(meta.optJSONObject("itogi"), text)
        val title = meta.optString("title").takeIf { it.isNotBlank() && !meta.optBoolean("titleAuto") } ?: summary?.nazvanie?.takeIf { it.isNotBlank() } ?: fair?.name.orEmpty()
        val description = meta.optString("about").ifBlank { summary?.oChem?.takeIf { it.isNotBlank() } ?: fair?.about.orEmpty() }
        if (title.isNotBlank()) out.append("title: ").append(quoted(title.trim())).append('\n')
        if (description.isNotBlank()) out.append("description: ").append(quoted(description.trim())).append('\n')
        val tags = meta.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
        if (tags.isNotEmpty()) {
            out.append("tags:\n")
            // A tag in Obsidian is one word: spaces inside become underscores.
            tags.forEach { out.append("  - ").append(quoted(it.replace(Regex("\\s+"), "_"))).append('\n') }
        }
        if (meta.optBoolean("pinned")) out.append("pinned: true\n")
        if (id.isNotBlank()) out.append("id: ").append(quoted(id)).append('\n')
        out.append("source: echo\n")
        out.append("---\n")
        // A name taken from the note's own words repeats its first line: only a typed one, or the «Текст»'s, becomes the heading.
        if (title.isNotBlank()) out.append("\n# ").append(title.trim()).append('\n')
        // A plain Markdown link: Obsidian plays the sound inside the note, any other app opens the file.
        if (sounds.isNotEmpty()) out.append('\n').append(sounds.joinToString("\n") { "![]($it)" }).append('\n')
        summary?.let { out.append("\n## Кратко\n\n").append(listOf(it.oChem, it.content).filter { part -> part.isNotBlank() }.joinToString("\n\n")).append('\n') }
        // The person's working text first; the transcript, tied to the sound, under its own heading.
        val worked = fair?.text?.trim().orEmpty()
        if (fair != null) out.append("\n## Текст\n\n").append(worked).append("\n\n## Расшифровка\n")
        else if (summary != null) out.append(if (typed) "\n## Текст\n" else "\n## Расшифровка\n")
        val pieces = piecesOf(meta, text.trim())
        val stamped = pieces.size > 1 || pieces.any { it.later }
        pieces.forEach { piece ->
            out.append('\n')
            if (stamped) out.append("**").append(stamp.format(Instant.ofEpochMilli(piece.atMs))).append(if (piece.later) ", дописано" else "").append("**\n")
            out.append(piece.text).append('\n')
        }
        return out.toString()
    }

    private fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The pieces of the text, each with the time it was said: a joined note's parts, then the pieces said into a note later. */
    private fun piecesOf(
        meta: JSONObject,
        text: String,
    ): List<Piece> {
        val marks = NoteStore.appendsOf(meta.optJSONArray("appends"))
        val parts = meta.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        if (parts.isEmpty()) return split(text, 0, meta.optLong("startedAtMs"), false, marks)
        val joined = parts.joinToString("\n\n") { it.optString("text").trim() }
        val own = parts.flatMap { part -> split(part.optString("text").trim(), 0, part.optLong("startedAtMs"), false, NoteStore.appendsOf(part.optJSONArray("appends"))) }
        // What follows the parts' texts was said into the joined note after the join.
        val added = if (text.length > joined.length && text.startsWith(joined)) split(text, joined.length, meta.optLong("modifiedAtMs"), true, marks) else emptyList()
        return own + added
    }

    /** [text] from [from] on: up to the first mark of a continuation it was said at [atMs], each continuation at its own time. */
    private fun split(
        text: String,
        from: Int,
        atMs: Long,
        later: Boolean,
        marks: List<NoteStore.Append>,
    ): List<Piece> {
        // A mark is trusted only at the start of a paragraph, as in the text that leaves the app.
        val cuts = marks.filter { it.textAt in (from + 1)..text.length && text[it.textAt - 1] == '\n' }.sortedBy { it.textAt }
        val bounds = listOf(from) + cuts.map { it.textAt } + text.length
        return bounds.zipWithNext().mapIndexedNotNull { i, (a, b) ->
            text.substring(a, b).trim().takeIf { it.isNotEmpty() }?.let { if (i == 0) Piece(atMs, later, it) else Piece(cuts[i - 1].atMs, true, it) }
        }
    }
}
