package com.govorun.echo.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A note's card from the smart functions, kept in its `meta.json` under `card`: a name, one line of what the note is about, and the
 * words a person would look for it by. It is the note's own, like its tags: the list shows it, the search finds the note by its words
 * without the network, and it stays when the smart functions are turned off. It never writes the note's own name: a name the person
 * typed always wins and hides the card's ([com.govorun.echo.ui] shows the card's name only for a note nobody named).
 *
 * [fresh] says whether it still fits the text: made for this very text, or for one that changed little since (a fixed word or two:
 * the length moved by no more than [SMALL_CHANGE] characters or [SMALL_SHARE] of it), so a fix costs no new request. A note that
 * changed more (a continuation) gets its line and search words made again, and keeps the name it had: a name, once shown, is never
 * changed by itself. A note the model gave nothing usable for keeps an empty card ([none]) for its text, so it is not asked about
 * again and again; a new text asks once more.
 */
data class NoteCard(
    val name: String,
    val about: String,
    val words: List<String>,
    val fresh: Boolean = true,
    val none: Boolean = false,
) {
    /** The words the index keeps for the card ([NoteIndex.termsOf]). */
    val searchText: String get() = if (none) "" else "$name $about ${words.joinToString(" ")}"

    companion object {
        /** The card in [json] for a note whose text is now [text]; null when it has none. */
        fun of(
            json: JSONObject?,
            text: String,
        ): NoteCard? {
            if (json == null) return null
            val words = json.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val none = json.optBoolean("none")
            val madeFor = json.optInt("len", -1)
            val now = text.trim().length
            val fresh = json.optInt("of") == stampOf(text) || (madeFor >= 0 && kotlin.math.abs(now - madeFor) <= maxOf(SMALL_CHANGE, (madeFor * SMALL_SHARE).toInt()))
            return NoteCard(json.optString("name"), json.optString("about"), words, fresh, none).takeIf { none || it.name.isNotEmpty() }
        }

        /** A change of the text this small keeps the card: a fixed word costs no new request. */
        const val SMALL_CHANGE = 100
        const val SMALL_SHARE = 0.15

        /** Which text a card was made for: the text's own hash, so a fixed word or a continuation makes the card stale. */
        fun stampOf(text: String): Int = text.trim().hashCode()

        /** Keeps [card] in note [id] under [root], made for its [text]; nothing when the note is gone or its text changed meanwhile. */
        fun write(
            root: File,
            id: String,
            text: String,
            card: NoteCard,
        ): Boolean {
            val dir = File(root, id)
            if (!File(dir, "meta.json").exists()) return false
            if (runCatching { File(dir, "text.txt").readText() }.getOrNull()?.let { stampOf(it) } != stampOf(text)) return false
            NoteStore.updateMeta(dir) {
                put(
                    "card",
                    if (card.none) {
                        JSONObject().put("none", true).put("of", stampOf(text)).put("len", text.trim().length)
                    } else {
                        JSONObject()
                            .put("name", card.name)
                            .put("about", card.about)
                            .put("words", JSONArray(card.words))
                            .put("of", stampOf(text))
                            .put("len", text.trim().length)
                    },
                )
            }
            return true
        }
    }
}
