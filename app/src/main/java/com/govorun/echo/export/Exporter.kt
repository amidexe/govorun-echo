package com.govorun.echo.export

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.govorun.echo.audio.AudioJoiner

/** One note ready to leave the app: its text (blank when not chosen), its compressed audio files in playback order and its subtitles (SRT). */
internal class ExportItem(
    val startedAtMs: Long,
    val text: String,
    val audio: List<File>,
    val subtitles: String? = null,
)

/** Builds files for the share sheet: a plain text file, or a zip with text and audio. */
internal object Exporter {
    private val nameFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm").withZone(ZoneId.systemDefault())
    private val titleFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

    /** `2026-10-17_14-05`; the second note of the same minute gets `-2`. */
    fun uniqueNames(items: List<ExportItem>): List<String> {
        val seen = HashMap<String, Int>()
        return items.map { item ->
            val base = nameFormat.format(Instant.ofEpochMilli(item.startedAtMs))
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            if (count == 1) base else "$base-$count"
        }
    }

    /** Text only: one note is its text, several notes are joined under date headings in the order given (the owner's selection order). */
    fun textFile(
        target: File,
        items: List<ExportItem>,
    ): File {
        val body =
            if (items.size == 1) {
                items.single().text.trim()
            } else {
                items.joinToString("\n\n") { "== ${titleFormat.format(Instant.ofEpochMilli(it.startedAtMs))} ==\n${it.text.trim()}" }
            }
        target.writeText(body)
        return target
    }

    /** A note's sound as files named after [base]: one file when it can be joined, otherwise its parts numbered. */
    private fun audioEntries(
        item: ExportItem,
        base: String,
        scratch: File,
    ): List<Pair<String, File>> {
        val parts = item.audio.filter { it.exists() }
        if (parts.isEmpty()) return emptyList()
        val extension = parts.first().extension
        if (parts.size == 1) return listOf("$base.$extension" to parts.single())
        val joined = AudioJoiner.join(parts, File(scratch, "$base.$extension").apply { delete() })
        return if (joined != null) listOf(joined.name to joined) else parts.mapIndexed { i, file -> "${base}_${i + 1}.${file.extension}" to file }
    }

    /**
     * One note's chosen files side by side in [dir] for the share sheet, under one name: `<date>.txt`, its sound, `<date>.srt`
     * (each only when the item carries it); [extra] (a video already made there) goes with them.
     */
    fun bundle(
        dir: File,
        item: ExportItem,
        extra: File? = null,
    ): List<File> {
        val base = uniqueNames(listOf(item)).single()
        val files = mutableListOf<File>()
        if (item.text.isNotBlank()) files += File(dir, "$base.txt").apply { writeText(item.text.trim()) }
        audioEntries(item, base, dir).forEach { (name, file) -> files += if (file.parentFile?.canonicalFile == dir.canonicalFile) file else file.copyTo(File(dir, name), overwrite = true) }
        item.subtitles?.let { files += File(dir, "$base.srt").apply { writeText(it) } }
        extra?.let { files += it }
        return files
    }

    /** Text and sound: `<date>.txt` and the sound under the same name. */
    fun zipFile(
        target: File,
        items: List<ExportItem>,
    ): File {
        target.outputStream().buffered().use { zipTo(it, items, target.parentFile ?: File(".")) }
        return target
    }

    /** Streams the zip straight to [out] (a file the owner picked), so no second copy is made on the phone. */
    fun zipTo(
        out: java.io.OutputStream,
        items: List<ExportItem>,
        scratch: File,
    ) {
        val names = uniqueNames(items)
        ZipOutputStream(out).use { zip ->
            items.forEachIndexed { index, item ->
                val base = names[index]
                if (item.text.isNotBlank()) {
                    zip.putNextEntry(ZipEntry("$base.txt"))
                    zip.write(item.text.trim().toByteArray())
                    zip.closeEntry()
                }
                // Subtitles next to the sound: an editor takes both and lays them on any picture.
                item.subtitles?.let { srt ->
                    zip.putNextEntry(ZipEntry("$base.srt"))
                    zip.write(srt.toByteArray())
                    zip.closeEntry()
                }
                audioEntries(item, base, scratch).forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}
