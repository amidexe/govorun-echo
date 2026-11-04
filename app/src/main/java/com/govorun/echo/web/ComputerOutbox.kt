package com.govorun.echo.web

import com.govorun.echo.notes.NoteStore
import org.json.JSONObject

/** Only an accepted frame advances delivery. Failed snapshot parts remain at the head for retry. */
internal class ComputerOutbox(notes: List<NoteStore.Entry>, private val device: String, private val names: Map<String, String>, private val budget: Int = WebExport.PART_BYTES, checkpoint: JSONObject? = null) {
    class Frame(val body: JSONObject, val versions: Map<String, Int>, val gone: Set<String>)
    private val sent = mutableMapOf<String, Int>()
    private val pending = java.util.ArrayDeque<Frame>()

    private var initial = checkpoint?.optBoolean("initial", true) ?: true
    init {
        if (initial) append(WebExport.snapshot(notes, device, names, budget), notes)
        else {
            checkpoint?.optJSONObject("sent")?.let { saved -> saved.keys().forEach { sent[it] = saved.getInt(it) } }
            changes(notes)
        }
    }

    fun checkpoint() = JSONObject().put("initial", initial).put("sent", JSONObject(sent.toMap()))

    /** Rebuild unsent content from authoritative files. A revoked tag can never survive in a retry payload. */
    fun reconcile(notes: List<NoteStore.Entry>) {
        val allowed = notes.map { it.id }.toSet()
        if (pending.any { frame -> frame.versions.keys.any { it !in allowed } }) {
            pending.clear()
            if (initial) append(WebExport.snapshot(notes, device, names, budget), notes)
        }
        changes(notes)
    }

    fun peek(): Frame? = pending.peekFirst()

    fun changes(notes: List<NoteStore.Entry>) {
        if (pending.isNotEmpty()) return
        val (changed, gone) = WebExport.diff(sent, notes)
        if (changed.isNotEmpty() || gone.isNotEmpty()) append(WebExport.changes(changed, gone, names, budget), changed)
    }

    fun accepted(frame: Frame): Boolean {
        if (pending.peekFirst() !== frame) return false
        pending.removeFirst()
        frame.gone.forEach(sent::remove)
        sent.putAll(frame.versions)
        if (pending.isEmpty()) initial = false
        return true
    }

    private fun append(messages: List<JSONObject>, notes: List<NoteStore.Entry>) {
        val versions = notes.associate { it.id to WebExport.signature(it) }
        messages.forEach { message ->
            val ids = message.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }.orEmpty()
            val gone = message.optJSONArray("gone")?.let { a -> (0 until a.length()).map(a::getString).toSet() }.orEmpty()
            pending.add(Frame(message, ids.associateWith(versions::getValue), gone))
        }
    }
}
