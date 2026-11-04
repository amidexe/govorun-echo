package com.govorun.echo.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A universal summary next to the transcript. The existing wire/storage fields stay readable:
 * the server supplies a Markdown body; older meeting sections remain readable without losing their content.
 * A small text correction keeps it fresh, a continuation makes it stale, as for NoteCard.
 */
data class NoteItogi(
    val nazvanie: String,
    val oChem: String,
    val glavnoe: List<String>,
    val dela: List<Delo>,
    val resheniya: List<String>,
    val voprosy: List<String>,
    val fresh: Boolean = true,
    val madeAtMs: Long = 0,
    val body: String = "",
) {
    /** A thing to do: its short name, a line of what it is, and who and by when where they were named («—» otherwise). */
    data class Delo(
        val chto: String,
        val podrobno: String,
        val kto: String,
        val srok: String,
    ) {
        /** «Андрей Николаевич · на этой неделе», or empty when neither was named. */
        val who: String get() = listOf(kto, srok).filter(::named).joinToString(" · ")

        val line: String
            get() = chto + podrobno.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty() + who.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    }

    /** One complete list for display and sharing, including already saved meeting summaries. */
    val points: List<String>
        get() = (glavnoe + dela.map { it.line } + resheniya + voprosy).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** The server owns the body structure; saved results from older versions keep their complete content. */
    val content: String get() = body.ifBlank { points.joinToString("\n") { "- $it" } }

    /** The visible summary, ready to copy or send. */
    fun markdown(): String =
        listOfNotNull(
            nazvanie.takeIf { it.isNotBlank() }?.let { "# $it" },
            oChem.takeIf { it.isNotBlank() },
            content.takeIf { it.isNotBlank() },
        ).joinToString("\n\n")

    companion object {
        fun named(value: String) = value.isNotBlank() && value.trim() != "—" && value.trim() != "-"

        private fun lines(array: JSONArray?) = array?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()

        /**
         * Govorun's answer `{nazvanie, o_chem, glavnoe, dela, resheniya, voprosy}` as итоги; null when it says nothing. A short note
         * has a name, a line and its tasks, and no main points apart.
         */
        fun parse(answer: JSONObject): NoteItogi? {
            val dela =
                answer.optJSONArray("dela")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                        .map { Delo(it.optString("chto").trim(), it.optString("podrobno").trim(), it.optString("kto").trim(), it.optString("srok").trim()) }
                        .filter { it.chto.isNotEmpty() }
                }.orEmpty()
            return NoteItogi(answer.optString("nazvanie").trim(), answer.optString("o_chem").trim(), lines(answer.optJSONArray("glavnoe")),
                dela, lines(answer.optJSONArray("resheniya")), lines(answer.optJSONArray("voprosy")), body = answer.optString("body").takeUnless { answer.isNull("body") }.orEmpty().trim())
                .takeIf { it.nazvanie.isNotBlank() || it.content.isNotBlank() }
        }

        /** The итоги in [json] for a note whose text is now [noteText]; null when it has none. */
        fun of(
            json: JSONObject?,
            noteText: String,
        ): NoteItogi? {
            if (json == null) return null
            val madeFor = json.optInt("len", -1)
            val now = noteText.trim().length
            val fresh =
                json.optInt("of") == NoteCard.stampOf(noteText) ||
                    (madeFor >= 0 && kotlin.math.abs(now - madeFor) <= maxOf(NoteCard.SMALL_CHANGE, (madeFor * NoteCard.SMALL_SHARE).toInt()))
            return parse(json)?.copy(fresh = fresh, madeAtMs = json.optLong("at"))
        }

        /** Keeps [itogi] in note [id] under [root], made for its [noteText]; nothing when the note is gone or its text changed meanwhile. */
        fun write(
            root: File,
            id: String,
            noteText: String,
            itogi: NoteItogi,
        ): Boolean {
            val dir = File(root, id)
            if (!File(dir, "meta.json").exists()) return false
            if (runCatching { File(dir, "text.txt").readText() }.getOrNull()?.let { NoteCard.stampOf(it) } != NoteCard.stampOf(noteText)) return false
            NoteStore.updateMeta(dir) {
                put(
                    "itogi",
                    JSONObject()
                        .put("nazvanie", itogi.nazvanie)
                        .put("o_chem", itogi.oChem)
                        .put("glavnoe", JSONArray(itogi.glavnoe))
                        .put("dela", JSONArray(itogi.dela.map { JSONObject().put("chto", it.chto).put("podrobno", it.podrobno).put("kto", it.kto).put("srok", it.srok) }))
                        .put("resheniya", JSONArray(itogi.resheniya))
                        .put("voprosy", JSONArray(itogi.voprosy))
                        .put("body", itogi.body)
                        .put("at", itogi.madeAtMs)
                        .put("of", NoteCard.stampOf(noteText))
                        .put("len", noteText.trim().length),
                )
            }
            return true
        }
    }
}
