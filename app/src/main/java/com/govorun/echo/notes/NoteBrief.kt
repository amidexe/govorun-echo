package com.govorun.echo.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A note's «Кратко», kept in its `meta.json` under `brief`: a name, what the note is about, its main points with their minutes and
 * what is to be done. Made on a tap or by itself ([com.govorun.echo.smart.SmartBriefs]); the search finds the note by its words too,
 * and it stays when the smart functions are turned off. [fresh] follows [NoteCard]'s rule: a fixed word keeps it, a continuation
 * makes it stale («Обновить»). [parts] are a long note's pieces as they were read, so a continuation sends only what is new;
 * [madeAtMs] is when it was made.
 */
data class NoteBrief(
    val name: String,
    val about: String,
    val points: List<Point>,
    val todo: List<String>,
    val fresh: Boolean = true,
    val parts: List<Part> = emptyList(),
    val madeAtMs: Long = 0,
) {
    data class Point(
        val at: String,
        val text: String,
    ) {
        /** [at] in milliseconds from the beginning of the recording; null without a time. */
        val atMs: Long?
            get() {
                val parts = at.split(':').map { it.toLongOrNull() ?: return null }
                return when (parts.size) {
                    2 -> (parts[0] * 60 + parts[1]) * 1000
                    3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                    else -> null
                }
            }
    }

    /** A long note's piece as it was read: its mark (a hash of the piece's text), its points and things to do. */
    data class Part(
        val stamp: Int,
        val points: List<Point>,
        val todo: List<String>,
    )

    /** The words the index keeps for the brief ([NoteIndex.termsOf]). */
    val searchText: String get() = "$name $about ${points.joinToString(" ") { it.text }} ${todo.joinToString(" ")}"

    companion object {
        /** The brief in [json] for a note whose text is now [text]; null when it has none. */
        fun of(
            json: JSONObject?,
            text: String,
        ): NoteBrief? {
            if (json == null) return null
            val points = json.optJSONArray("points")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Point(it.optString("at"), it.optString("text")) } }.orEmpty()
            val todo = json.optJSONArray("todo")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val madeFor = json.optInt("len", -1)
            val now = text.trim().length
            val fresh =
                json.optInt("of") == NoteCard.stampOf(text) ||
                    (madeFor >= 0 && kotlin.math.abs(now - madeFor) <= maxOf(NoteCard.SMALL_CHANGE, (madeFor * NoteCard.SMALL_SHARE).toInt()))
            val parts = json.optJSONArray("parts")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(::partOf) }.orEmpty()
            return NoteBrief(json.optString("name"), json.optString("about"), points, todo, fresh, parts, json.optLong("at"))
                .takeIf { it.points.isNotEmpty() || it.about.isNotEmpty() }
        }

        private fun partOf(json: JSONObject): Part {
            val points = json.optJSONArray("points")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Point(it.optString("at"), it.optString("text")) } }.orEmpty()
            val todo = json.optJSONArray("todo")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return Part(json.optInt("s"), points, todo)
        }

        private fun partJson(part: Part): JSONObject =
            JSONObject()
                .put("s", part.stamp)
                .put("points", JSONArray(part.points.map { JSONObject().put("at", it.at).put("text", it.text) }))
                .put("todo", JSONArray(part.todo))

        /** The pieces of note [id] read so far by a brief that stopped at the day's limit; it goes on from them. */
        fun pending(
            root: File,
            id: String,
        ): List<Part> =
            runCatching {
                val array = JSONArray(File(File(root, id), PENDING).readText())
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map(::partOf)
            }.getOrDefault(emptyList())

        /** Adds [part] to the pieces note [id] keeps for a brief not finished yet. */
        fun keepPending(
            root: File,
            id: String,
            part: Part,
        ) {
            val dir = File(root, id)
            if (!dir.isDirectory) return
            File(dir, PENDING).writeText(JSONArray((pending(root, id) + part).map(::partJson)).toString())
        }

        private const val PENDING = "brief_parts.json"

        /** Keeps [brief] in note [id] under [root], made for its [text]; nothing when the note is gone or its text changed meanwhile. */
        fun write(
            root: File,
            id: String,
            text: String,
            brief: NoteBrief,
        ): Boolean {
            val dir = File(root, id)
            if (!File(dir, "meta.json").exists()) return false
            if (runCatching { File(dir, "text.txt").readText() }.getOrNull()?.let { NoteCard.stampOf(it) } != NoteCard.stampOf(text)) return false
            NoteStore.updateMeta(dir) {
                put(
                    "brief",
                    JSONObject()
                        .put("name", brief.name)
                        .put("about", brief.about)
                        .put("points", JSONArray(brief.points.map { JSONObject().put("at", it.at).put("text", it.text) }))
                        .put("todo", JSONArray(brief.todo))
                        .put("parts", JSONArray(brief.parts.map(::partJson)))
                        .put("at", brief.madeAtMs)
                        .put("of", NoteCard.stampOf(text))
                        .put("len", text.trim().length),
                )
            }
            File(dir, PENDING).delete()
            return true
        }
    }
}
