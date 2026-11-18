package com.govorun.echo.notes

import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Notes of text only, and the text of a note whose sound is gone: what Echo keeps besides recordings. */
object NoteText {
    /** A note of text only — typed, or shared into Echo: done at once, no sound, its text in `text.txt` as any note's. */
    fun create(
        root: File,
        text: String = "",
        title: String = "",
    ): String {
        val now = System.currentTimeMillis()
        val id = "$now-${UUID.randomUUID().toString().take(8)}"
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "text.txt").writeText(text.trim())
        NoteStore.writeMeta(dir, JSONObject().put("draft", true).put("unseen", text.isNotBlank()).put("state", NoteStore.STATE_DONE).put("mode", MODE).put("startedAtMs", now).put("modifiedAtMs", now).apply { NoteStore.cleanTitle(title).takeIf { it.isNotEmpty() }?.let { put("title", it) } })
        return id
    }

    /** One current text for a typed/shared note, including old versions that kept edits in `fair`. */
    fun read(dir: File, meta: JSONObject): String = when {
        meta.has("typedTextPending") -> meta.getString("typedTextPending")
        meta.optJSONObject("fair")?.let { it.optString("text").isNotBlank() || it.optBoolean("empty") } == true -> meta.getJSONObject("fair").getString("text")
        else -> runCatching { File(dir, "text.txt").readText() }.getOrDefault("")
    }

    /** A voice transcript is protected even without audio. Only originally typed/shared notes are written here. */
    fun setText(root: File, id: String, text: String) { replace(root, id, text) }

    /**
     * A small write-ahead value makes a crash between text and metadata writes recoverable. This is not a version history:
     * the pending value is the current text until the plain file is committed. Old fair data is retained only for compatibility.
     */
    @Synchronized
    fun replace(root: File, id: String, text: String, expected: String? = null): Boolean {
        val dir = File(root, id)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return false
        if (meta.optString("mode") != MODE || NoteStore.audioParts(meta).isNotEmpty() || NoteStore.pcmFile(dir).exists()) return false
        val current = read(dir, meta)
        if (expected != null && current.trim() != expected.trim()) return false
        if (current == text && !meta.has("fair") && !meta.has("typedTextPending")) return true
        NoteStore.updateMeta(dir) {
            if (has("fair") && !has("legacyTypedFair")) {
                put("legacyTypedFair", getJSONObject("fair"))
                put("legacyTypedSource", runCatching { File(dir, "text.txt").readText() }.getOrDefault(""))
            }
            put("typedTextPending", text)
            put("modifiedAtMs", System.currentTimeMillis())
        }
        // Once the journal is saved, the new text is durable and readable even if completing the plain file fails.
        runCatching {
            val tmp = File(dir, "text.txt.tmp")
            tmp.writeText(text)
            check(tmp.renameTo(File(dir, "text.txt"))) { "text rename failed" }
            NoteStore.updateMeta(dir) { remove("fair"); remove("typedTextPending") }
        }.onFailure { android.util.Log.w("NoteText", "event=typed_commit_deferred", it) }
        return true
    }

    /** A new text note not yet kept or let go: shared in or typed a moment ago, it asks «Сохранить» or «Не сохранять». */
    fun isDraft(
        root: File,
        id: String,
    ): Boolean = runCatching { JSONObject(File(root, "$id/meta.json").readText()).optBoolean("draft") }.getOrDefault(false)

    /** Keeps a new text note for good; leaving it without a choice keeps it too, so no typed or shared text is lost. */
    @Synchronized
    fun keep(
        root: File,
        id: String,
    ) {
        if (isDraft(root, id)) NoteStore.updateMeta(File(root, id)) { remove("draft") }
    }

    /** «Не сохранять»: a text shared in by mistake goes without a trace, not to the trash. */
    @Synchronized
    fun discard(
        root: File,
        id: String,
    ) {
        if (isDraft(root, id)) File(root, id).deleteRecursively()
    }

    /** A new text note left empty: it goes without a trace, not to the trash. */
    fun dropIfEmpty(
        root: File,
        id: String,
    ): Boolean {
        val dir = File(root, id)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return false
        if (!meta.optBoolean("draft") || meta.optString("mode") != MODE || runCatching { File(dir, "text.txt").readText() }.getOrDefault("").isNotBlank() || meta.has("fair")) return false
        return dir.deleteRecursively()
    }

    const val MODE = "text"
}
