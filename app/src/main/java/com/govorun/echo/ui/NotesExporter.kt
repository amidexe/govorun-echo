package com.govorun.echo.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.TextOptions
import com.govorun.echo.export.ClipExport
import com.govorun.echo.export.ExportCache
import java.io.File

/**
 * Takes notes out of the app: text, or text with audio (and subtitles), through the share sheet or straight into a file; one note
 * also as a video with its text or as subtitles for an editor.
 */
internal class NotesExporter(
    private val activity: ComponentActivity,
) {
    /** What waits for the owner's choice in the export sheet (shown by [Sheet]); null when nothing does. */
    private var request by androidx.compose.runtime.mutableStateOf<ExportRequest?>(null)
    private var pendingZip: List<com.govorun.echo.export.ExportItem> = emptyList()

    /** How far the video being made got; null while none is. */
    private var making by androidx.compose.runtime.mutableStateOf<Float?>(null)

    @Volatile private var stopMaking = false
    private val createZip =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val items = pendingZip
            pendingZip = emptyList()
            if (uri == null || items.isEmpty()) return@registerForActivityResult
            Toast.makeText(activity, R.string.export_preparing, Toast.LENGTH_SHORT).show()
            Thread {
                val saved =
                    runCatching {
                        val scratch = ExportCache.create(activity)
                        try {
                            activity.contentResolver.openOutputStream(uri)!!.use { com.govorun.echo.export.Exporter.zipTo(it.buffered(), items, scratch) }
                        } finally {
                            ExportCache.finish(scratch, handedOff = false)
                        }
                    }.isSuccess
                activity.runOnUiThread { Toast.makeText(activity, if (saved) R.string.export_saved else R.string.export_save_failed, Toast.LENGTH_LONG).show() }
            }.start()
        }

    /** The notes as items for a zip or a bundle: only the [kinds] chosen are filled in. */
    private fun exportItems(
        asked: ExportRequest,
        kinds: Set<ShareKind>,
        options: TextOptions,
    ): List<com.govorun.echo.export.ExportItem> =
        asked.notes.map { note ->
            val chunks = java.io.File(NoteStore.noteDir(activity, note.id), "chunks")
            com.govorun.echo.export.ExportItem(
                note.startedAtMs,
                if (ShareKind.Text in kinds) exportTextOf(note, options) else "",
                if (ShareKind.Audio in kinds) note.audio.map { java.io.File(chunks, it.file) } else emptyList(),
                if (ShareKind.Subtitles in kinds) asked.subtitles[note.id] else null,
            )
        }

    /**
     * The owner ticks what to take in a sheet (text, sound, a video, subtitles, each with its weight) and how the text reads: the
     * time of each piece and a mark on what was written to later. One note goes out as its files together, several notes as one
     * zip; a big sound is written straight to a file the owner picks (a cloud folder, a memory card), so the phone never needs
     * room for a second copy.
     */
    fun exportNotes(targets: List<NoteStore.Entry>) {
        // A note whose text never came can still be taken out with its sound.
        val exportable = targets.filter { it.text.isNotBlank() || it.audio.isNotEmpty() }
        if (exportable.isEmpty()) return
        // Subtitles come from each note's word times, read off the main thread one note at a time: the list holds none.
        Thread {
            val subtitles = exportable.filter(ClipExport::canMake).mapNotNull { note -> ClipExport.subtitles(NoteStore.withPhrases(activity, note))?.let { note.id to it } }.toMap()
            val sizes = LinkedHashMap<ShareKind, Long>()
            exportable.sumOf { it.text.toByteArray().size.toLong() }.takeIf { it > 0 }?.let { sizes[ShareKind.Text] = it }
            exportable.sumOf { note -> note.audio.sumOf { part -> java.io.File(NoteStore.noteDir(activity, note.id), "chunks/${part.file}").length() } }.takeIf { it > 0 }?.let { sizes[ShareKind.Audio] = it }
            exportable.singleOrNull()?.takeIf(ClipExport::canMake)?.let { sizes[ShareKind.Video] = ClipExport.estimatedBytes(it) }
            subtitles.values.sumOf { it.toByteArray().size.toLong() }.takeIf { it > 0 }?.let { sizes[ShareKind.Subtitles] = it }
            val severalPieces = exportable.size > 1 || exportable.any { it.parts.isNotEmpty() || it.continued }
            val asked = ExportRequest(exportable, sizes, severalPieces, subtitles)
            activity.runOnUiThread {
                // One plain note with nothing but text has nothing to choose: it goes out as text at once.
                if (sizes.keys == setOf(ShareKind.Text) && !severalPieces) send(asked, setOf(ShareKind.Text), AppSettings.textOptions(activity)) else request = asked
            }
        }.start()
    }

    private fun send(
        asked: ExportRequest,
        kinds: Set<ShareKind>,
        options: TextOptions,
    ) {
        request = null
        AppSettings.setStampsInText(activity, options.stamps)
        AppSettings.setMarkAppended(activity, options.markAppended)
        AppSettings.setExportKinds(activity, kinds.map { it.name }.toSet())
        val bytes = kinds.sumOf { asked.sizes[it] ?: 0 }
        when {
            // Text alone goes as text, which any messenger shows as a message.
            kinds == setOf(ShareKind.Text) -> activity.shareText(joinedText(asked.notes, options))
            ShareKind.Audio in kinds && asked.audioBytes > SAVE_TO_FILE_BYTES -> {
                pendingZip = exportItems(asked, kinds - ShareKind.Video, options)
                createZip.launch("govorun-echo-notes.zip")
            }
            activity.cacheDir.usableSpace < bytes * 2 -> Toast.makeText(activity, R.string.export_no_space, Toast.LENGTH_LONG).show()
            ShareKind.Video in kinds -> makeVideo(asked.notes.single()) { video -> bundle(asked, kinds, options, video) }
            else -> bundle(asked, kinds, options, null)
        }
    }

    /** Put this in the screen: it shows the export sheet when an export is waiting for a choice. */
    @androidx.compose.runtime.Composable
    fun Sheet() {
        request?.let { asked ->
            val kinds = AppSettings.exportKinds(activity).mapNotNull { name -> ShareKind.entries.firstOrNull { it.name == name } }.toSet()
            ExportSheet(asked, AppSettings.textOptions(activity), kinds, { chosen, options -> send(asked, chosen, options) }) { request = null }
        }
        making?.let { done -> VideoProgress(done) { stopMaking = true } }
    }

    /** The note as a video with its text: made in the background with its progress shown, then handed on. */
    private fun makeVideo(
        note: NoteStore.Entry,
        then: (File) -> Unit,
    ) {
        stopMaking = false
        making = 0f
        Thread {
            var shown = 0f
            val file = runCatching {
                val directory = ExportCache.create(activity)
                val target = File(directory, "${nameOf(note)}.mp4")
                var made = false
                try {
                    made = ClipExport.video(activity, NoteStore.withPhrases(activity, note), target, cancelled = { stopMaking }) { done ->
                        if (done - shown >= 0.01f) {
                            shown = done
                            activity.runOnUiThread { if (making != null) making = done }
                        }
                    }
                    target.takeIf { made }
                } finally {
                    if (!made) ExportCache.finish(directory, handedOff = false)
                }
            }.getOrNull()
            activity.runOnUiThread {
                making = null
                if (file != null) then(file) else if (!stopMaking) Toast.makeText(activity, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** What was ticked, made into files and handed to the share sheet together: one note's files side by side, several notes in a zip. */
    private fun bundle(
        asked: ExportRequest,
        kinds: Set<ShareKind>,
        options: TextOptions,
        video: File?,
    ) {
        if (video == null) Toast.makeText(activity, R.string.export_preparing, Toast.LENGTH_SHORT).show()
        Thread {
            val files = runCatching {
                val dir = video?.parentFile ?: ExportCache.create(activity)
                var ready = false
                try {
                    val items = exportItems(asked, kinds, options)
                    val output = if (items.size == 1) {
                        com.govorun.echo.export.Exporter.bundle(dir, items.single(), video)
                    } else {
                        listOf(com.govorun.echo.export.Exporter.zipFile(File(dir, "govorun-echo-notes.zip"), items))
                    }
                    ready = output.isNotEmpty()
                    output.takeIf { ready }
                } finally {
                    ExportCache.finish(dir, handedOff = ready)
                }
            }.getOrNull()
            activity.runOnUiThread { if (files != null) share(files) else Toast.makeText(activity, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun nameOf(note: NoteStore.Entry): String = com.govorun.echo.export.Exporter.uniqueNames(listOf(com.govorun.echo.export.ExportItem(note.startedAtMs, "", emptyList()))).single()

    /** One file with its own type, or several together for apps that take more than one. */
    private fun share(files: List<java.io.File>) {
        val uris = files.map { androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.files", it) }
        val send =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).setType(typeOf(files.single())).putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        activity.startActivity(Intent.createChooser(send, null))
    }

    private fun typeOf(file: java.io.File): String =
        when (file.extension) {
            "txt" -> "text/plain"
            "srt" -> "application/x-subrip"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            else -> com.govorun.echo.audio.AudioFormats.mimeOf(file.extension)
        }
}
