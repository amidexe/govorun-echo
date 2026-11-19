package com.govorun.echo.export

import com.govorun.echo.notes.CommandHistory
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteText
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A readable export contains only the requested representation and, optionally, its sound. */
internal object ReadableNotes {
    data class Options(val markdown: Boolean = false, val audio: Boolean = false)
    data class Document(val name: String, val mime: String, val text: ByteArray? = null, val sound: File? = null) {
        fun write(output: OutputStream) {
            if (sound != null) sound.inputStream().use { it.copyTo(output) } else output.write(checkNotNull(text))
        }
    }

    fun eligible(dir: File, meta: JSONObject): Boolean =
        !CommandHistory.isCommand(meta) && meta.optString("state") == NoteStore.STATE_DONE && File(dir, "text.txt").isFile

    fun matches(meta: JSONObject, tags: Set<String>?): Boolean {
        if (tags == null) return true
        val actual = meta.optJSONArray("tags") ?: return false
        return (0 until actual.length()).any { actual.optString(it) in tags }
    }

    fun documents(dir: File, meta: JSONObject, base: String, options: Options, firstAudioStem: String? = null): List<Document> {
        check(eligible(dir, meta)) { "note_unavailable" }
        val text = if (meta.optString("mode") == NoteText.MODE) NoteText.read(dir, meta) else File(dir, "text.txt").readText()
        val parts = if (options.audio) NoteStore.audioParts(meta) else emptyList()
        val sounds = parts.mapIndexed { index, part ->
            val file = File(dir, "chunks/${part.file}")
            check(file.isFile) { "sound_unavailable" }
            val stem = when {
                index == 0 && firstAudioStem != null -> firstAudioStem
                parts.size == 1 -> base
                else -> "${base}_${index + 1}"
            }
            val name = "$stem.${file.extension}"
            Document(name, com.govorun.echo.audio.AudioFormats.mimeOf(file.extension), sound = file)
        }
        val body = if (options.markdown) NoteMarkdown.of(meta, text.trim(), sounds.map { it.name }, dir.name) else text.trim()
        val document = if (options.markdown) Document("$base.md", "text/markdown", body.toByteArray())
                       else Document("$base.txt", "text/plain", body.toByteArray())
        return sounds + document
    }

    /** Explicit IDs keep notes created after the selection out of the saved ZIP. */
    fun zip(root: File, ids: List<String>, options: Options, output: OutputStream) {
        require(ids.isNotEmpty() && ids.distinct().size == ids.size)
        val names = HashMap<String, Int>()
        ZipOutputStream(output.buffered()).use { zip ->
            ids.forEach { id ->
                require(File(id).name == id && id != "." && id != "..")
                val dir = File(root, id)
                val meta = JSONObject(File(dir, "meta.json").readText())
                val date = Exporter.uniqueNames(listOf(ExportItem(meta.getLong("startedAtMs"), "", emptyList()))).single()
                val count = (names[date] ?: 0) + 1
                names[date] = count
                documents(dir, meta, if (count == 1) date else "$date-$count", options).forEach { doc ->
                    zip.putNextEntry(ZipEntry(doc.name))
                    doc.write(zip)
                    zip.closeEntry()
                }
            }
        }
    }
}
