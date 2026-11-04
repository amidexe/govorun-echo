package com.govorun.echo

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * What happened, in a few words: stops, recognition problems, copy errors, command results and the smart functions' failures. The last 100 lines;
 * nothing that was said is kept here.
 */
object Journal {
    class Entry(
        val atMs: Long,
        val kind: String,
        val label: String,
    )

    const val RECORDING = "recording"
    const val RECOGNITION = "recognition"
    const val BACKUP = "backup"
    const val COMMAND = "command"
    const val SMART = "smart"
    private const val LIMIT = 100

    private fun file(context: Context) = File(context.filesDir, "journal.json")

    @Synchronized
    fun entries(context: Context): List<Entry> {
        val array = runCatching { JSONArray(file(context).readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            Entry(item.optLong("t"), item.optString("k"), item.optString("l"))
        }
    }

    @Synchronized
    fun add(
        context: Context,
        kind: String,
        label: String,
    ) {
        val kept = (listOf(Entry(System.currentTimeMillis(), kind, label)) + entries(context)).take(LIMIT)
        runCatching { file(context).writeText(JSONArray(kept.map { JSONObject().put("t", it.atMs).put("k", it.kind).put("l", it.label) }).toString()) }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}
