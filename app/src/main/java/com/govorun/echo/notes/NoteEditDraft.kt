package com.govorun.echo.notes

import java.io.File
import java.util.UUID
import org.json.JSONObject

/** A recoverable editor preview. It is never the committed working text or the immutable transcript. */
internal object NoteEditDraft {
    data class Draft(val token: String, val base: String, val source: String, val text: String, val input: String, val ready: Boolean)
    private fun file(root: File, id: String) = File(root, "$id/editor-draft.json")
    private fun stamp(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun read(root: File, id: String): Draft? = runCatching {
        val o = JSONObject(file(root, id).readText())
        Draft(o.getString("token"), o.getString("base"), o.getString("source"), o.getString("text"), o.getString("input"), o.optBoolean("ready"))
    }.getOrNull()

    fun resumed(root: File, id: String, saved: String, source: String): Draft? = read(root, id)?.takeIf { it.base == stamp(saved) && it.source == stamp(source) }

    @Synchronized
    fun begin(root: File, id: String, saved: String, source: String, text: String, input: String): Draft {
        check(File(root, "$id/meta.json").isFile) { "note_missing" }
        return Draft(UUID.randomUUID().toString(), stamp(saved), stamp(source), text, input, false).also { write(root, id, it) }
    }

    @Synchronized
    fun manual(root: File, id: String, saved: String, source: String, text: String): Draft {
        val held = resumed(root, id, saved, source)
        if (held?.text == text) return held
        return (held?.copy(text = text, ready = true) ?: Draft(UUID.randomUUID().toString(), stamp(saved), stamp(source), text, text, true)).also { write(root, id, it) }
    }

    @Synchronized
    fun finish(root: File, id: String, token: String, source: String, text: String): Boolean {
        val held = read(root, id)?.takeIf { it.token == token && it.source == stamp(source) } ?: return false
        val dir = File(root, id)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return false
        val current = if (meta.optString("mode") == NoteText.MODE) NoteText.read(dir, meta) else runCatching { File(dir, "text.txt").readText() }.getOrNull()
        if (current != source) return false
        write(root, id, held.copy(text = text, ready = true))
        return true
    }

    @Synchronized
    fun discard(root: File, id: String) { file(root, id).let { check(!it.exists() || it.delete()) { "draft_delete_failed" } } }

    private fun write(root: File, id: String, draft: Draft) {
        val o = JSONObject().put("token", draft.token).put("base", draft.base).put("source", draft.source)
            .put("text", draft.text).put("input", draft.input).put("ready", draft.ready)
        val target = file(root, id)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(o.toString())
        check(tmp.renameTo(target)) { "draft_write_failed" }
    }
}
