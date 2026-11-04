package com.govorun.echo.notes

import org.json.JSONObject
import java.io.File

/**
 * A note's fair copy, made by Govorun's text improvement on «Улучшить через Говорун» and kept in its `meta.json` under `fair`: a
 * readable text without the «э-э» and repeats, with a name and a line of what it is about when Govorun gave them. Only beside the
 * transcript, never instead of it: the transcript keeps the sound, the minutes and the subtitles. [fresh] follows [NoteCard]'s rule:
 * a fixed word keeps it, a continuation makes it stale («Обновить»).
 */
data class NoteFair(
    val name: String,
    val about: String,
    val text: String,
    val fresh: Boolean = true,
    val madeAtMs: Long = 0,
    /** Made by the person (the pencil), not by Govorun: then it says nothing of Govorun. */
    val byHand: Boolean = false,
    /** The version this one replaced, one step back: the person can return to it. */
    val before: NoteFair? = null,
) {
    /** The prepared text alone. Legacy metadata stays readable in storage, but is not repeated in text exports. */
    fun markdown(): String = text.trim()

    companion object {
        /** New improvements have a body only; title and description belong to the summary. */
        fun prepared(reply: String, madeAtMs: Long, textOnly: Boolean = false): NoteFair? =
            if (textOnly) NoteFair("", "", reply.trim(), madeAtMs = madeAtMs).takeIf { it.text.isNotBlank() }
            else parse(reply)?.copy(name = "", about = "", madeAtMs = madeAtMs)

        /** The fair copy in [json] for a note whose text is now [noteText]; null when it has none. */
        fun of(
            json: JSONObject?,
            noteText: String,
        ): NoteFair? {
            if (json == null) return null
            val madeFor = json.optInt("len", -1)
            val now = noteText.trim().length
            val fresh =
                json.optInt("of") == NoteCard.stampOf(noteText) ||
                    (madeFor >= 0 && kotlin.math.abs(now - madeFor) <= maxOf(NoteCard.SMALL_CHANGE, (madeFor * NoteCard.SMALL_SHARE).toInt()))
            val before = json.optJSONObject("prev")?.let { p -> NoteFair(p.optString("name"), p.optString("about"), p.optString("text"), true, p.optLong("at"), p.optString("by") == "hand").takeIf { it.text.isNotBlank() || p.optBoolean("empty") } }
            val own = NoteFair(json.optString("name"), json.optString("about"), json.optString("text"), fresh, json.optLong("at"), json.optString("by") == "hand", before)
            // A copy by hand that says what the transcript says, with nothing to go back to, is no second text: there is no layer.
            if (own.byHand && before == null && own.text.trim() == noteText.trim()) return null
            return own.takeIf { it.text.isNotBlank() || own.byHand && json.optBoolean("empty") }
        }

        /**
         * Govorun's answer read as a fair copy. A style made for Echo's notes puts the name first («# Название») and what it is about
         * next («> о чём»); any other style gives the text alone, which is a fair copy all the same.
         */
        fun parse(reply: String): NoteFair? {
            val lines = reply.trim().lines()
            var at = 0
            var name = ""
            var about = ""
            if (lines.getOrNull(at)?.startsWith("# ") == true) name = lines[at++].removePrefix("# ").trim()
            while (at < lines.size && lines[at].isBlank()) at++
            // A model now and then writes the quote mark as «›»; it is the same description.
            if (lines.getOrNull(at)?.let { it.startsWith("> ") || it.startsWith("› ") } == true) about = lines[at++].drop(2).trim()
            val text = lines.drop(at).joinToString("\n").trim()
            return NoteFair(name.take(NAME_CHARS), about.take(ABOUT_CHARS), text).takeIf { text.isNotBlank() }
        }

        /** Keeps [fair] in note [id] under [root], made for its [noteText]; nothing when the note is gone or its text changed meanwhile. */
        fun write(
            root: File,
            id: String,
            noteText: String,
            fair: NoteFair,
        ): Boolean {
            val dir = File(root, id)
            if (!File(dir, "meta.json").exists()) return false
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return false
            if (meta.optString("mode") == NoteText.MODE) return NoteText.replace(root, id, fair.text, expected = noteText)
            if (runCatching { File(dir, "text.txt").readText() }.getOrNull()?.let { NoteCard.stampOf(it) } != NoteCard.stampOf(noteText)) return false
            // Saving an unchanged first manual copy is a successful no-op, not a new stored version.
            if (fair.byHand && fair.text.trim() == noteText.trim() && of(meta.optJSONObject("fair"), noteText) == null) return true
            NoteStore.updateMeta(dir) {
                // One step back: what stood here before, when the text is different now.
                val old = optJSONObject("fair")?.takeIf { of(it, noteText) != null }
                val previous = if (old != null && old.optString("text") != fair.text) JSONObject(old.toString()).apply { remove("prev") } else old?.optJSONObject("prev")
                put(
                    "fair",
                    JSONObject()
                        .put("name", fair.name)
                        .put("about", fair.about)
                        .put("text", fair.text)
                        .put("empty", fair.byHand && fair.text.isEmpty())
                        .put("at", fair.madeAtMs)
                        .put("of", NoteCard.stampOf(noteText))
                        .put("len", noteText.trim().length)
                        .apply {
                            if (fair.byHand) put("by", "hand")
                            previous?.let { put("prev", it) }
                        },
                )
            }
            return true
        }

        /** The fair copy of note [id] as it is on disk now: the card shows what was kept, with the step back it has. */
        fun read(
            root: File,
            id: String,
            noteText: String,
        ): NoteFair? = of(runCatching { JSONObject(File(File(root, id), "meta.json").readText()).optJSONObject("fair") }.getOrNull(), noteText)

        /** «Вернуть прошлую»: the version one step back becomes the text, and the one just left is the step back from it. */
        fun swapBefore(
            root: File,
            id: String,
            noteText: String,
        ): NoteFair? {
            val dir = File(root, id)
            NoteStore.updateMeta(dir) {
                val now = optJSONObject("fair") ?: return@updateMeta
                val back = now.optJSONObject("prev") ?: return@updateMeta
                val left = JSONObject(now.toString()).apply { remove("prev") }
                put("fair", JSONObject(back.toString()).put("of", NoteCard.stampOf(noteText)).put("len", noteText.trim().length).put("prev", left))
            }
            return read(root, id, noteText)
        }

        private const val NAME_CHARS = 80
        private const val ABOUT_CHARS = 300
    }
}
