package com.govorun.echo.web

import com.govorun.echo.notes.NoteStore
import org.json.JSONArray
import org.json.JSONObject

/** null means all; an empty selection matches nothing. Names are literal, never magic privacy labels. */
internal data class ComputerSelection(val tags: Set<String>? = null) {
    fun includes(n: NoteStore.Entry): Boolean =
        n.state != NoteStore.STATE_RECORDING && n.state != NoteStore.STATE_RECOGNIZING && !n.archiving &&
            (tags == null || n.tags.any(tags::contains))
    fun json() = JSONObject().put("kind", if (tags == null) "all" else "tags").apply {
        tags?.let { put("tags", JSONArray(it.sorted())) }
    }
    companion object {
        fun read(json: JSONObject): ComputerSelection = when (json.getString("kind")) {
            "all" -> ComputerSelection()
            "tags" -> ComputerSelection(json.getJSONArray("tags").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            else -> error("unknown computer selection")
        }
    }
}

internal object ComputerRetry {
    private val delays = longArrayOf(15_000, 30_000, 60_000, 120_000, 300_000, 900_000)
    fun delay(failures: Int): Long? = delays.getOrNull(failures - 1)
}
