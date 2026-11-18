package com.govorun.echo.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.govorun.echo.AppSettings
import com.govorun.echo.NetworkGate
import com.govorun.echo.NetworkPolicy
import com.govorun.echo.R
import com.govorun.echo.export.Exporter
import com.govorun.echo.export.ExportItem
import com.govorun.echo.export.NoteMarkdown
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteTrash
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Copies finished notes (text and audio) into a folder the owner picked in the system file
 * picker. Any storage app that offers folders there works: the phone's memory, a card, a cloud
 * drive app. Access to a folder that may use the network follows NetworkPolicy. A note is copied again
 * when it changes (for example after "Дописать"); when only its words changed (a fix, its tags for
 * the Markdown copy), only its text files are written again, not its sound. With the experimental
 * Markdown copy each note also gets a `.md` for Obsidian ([NoteMarkdown]) beside its `.txt`.
 */
object BackupFolder {
    private const val PREFS = "backup"
    private const val KEY_TREE = "tree_uri"
    private const val KEY_ERROR = "last_error"
    private const val TAG = "BackupFolder"

    data class Status(
        val folderName: String?,
        val copied: Int,
        val total: Int,
        val lastError: String?,
        val networkReason: String?,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val waitingForNetwork = AtomicBoolean(false)

    internal fun networkAvailable(context: Context) {
        val tree = treeUri(context) ?: return
        // Android's local folders already copy without a connection.
        if (NetworkPolicy.folderRefusal(tree.authority) == null && waitingForNetwork.getAndSet(false)) syncAsync(context)
    }

    fun treeUri(context: Context): Uri? = prefs(context).getString(KEY_TREE, null)?.let(Uri::parse)

    fun setTree(
        context: Context,
        uri: Uri,
    ) {
        prefs(context).edit().putString(KEY_TREE, uri.toString()).remove(KEY_ERROR).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Copies what is missing, in the background; calls are coalesced so a burst makes one more pass. */
    fun syncAsync(
        context: Context,
        onFinished: () -> Unit = {},
    ) {
        val app = context.applicationContext
        if (treeUri(app) == null) return
        again.set(true)
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (again.getAndSet(false)) {
                    val tree = treeUri(app) ?: break
                    try {
                        NetworkPolicy.withFolderAccess(tree.authority) { checkAccess -> sync(app, tree, checkAccess) }
                    } catch (_: NetworkGate.Refused) {
                        waitingForNetwork.set(true)
                        // The route may already have recovered while the previous pass was unwinding.
                        networkAvailable(app)
                    } catch (failure: Exception) {
                        prefs(app).edit().putString(KEY_ERROR, if (failure is SecurityException) "no_access" else failure.message ?: "copy_failed").apply()
                        Log.w(TAG, "event=folder_unavailable", failure)
                    }
                }
            } finally {
                running.set(false)
                onFinished()
                if (again.get()) syncAsync(app)
            }
        }
    }

    fun status(context: Context): Status {
        val tree = treeUri(context)
        val notes = copyableNotes(context)
        val names = planNames(notes.map { it.first.name to it.second.optLong("startedAtMs") })
        val markdown = AppSettings.markdownCopy(context)
        val copied = if (tree == null) 0 else notes.count { (dir, meta) -> isCopied(meta, tree, wanted(dir, meta, meta.optJSONObject("backup")?.optString("base")?.takeIf { it.isNotBlank() } ?: names.getValue(dir.name), markdown)) }
        val name = tree?.let {
            try {
                NetworkPolicy.withFolderAccess(it.authority) { checkAccess ->
                    checkAccess()
                    folderName(context, it)
                }
            } catch (_: NetworkGate.Refused) { null }
                ?: context.getString(R.string.backup_folder)
        }
        return Status(name, copied, notes.size, prefs(context).getString(KEY_ERROR, null), tree?.let { NetworkPolicy.folderRefusal(it.authority) })
    }

    private fun copyableNotes(context: Context): List<Pair<File, JSONObject>> =
        NoteStore
            .root(context)
            .listFiles { f -> f.isDirectory }
            .orEmpty()
            .mapNotNull { dir -> readMeta(dir)?.takeIf { copyable(dir, it) }?.let { dir to it } }
            .sortedWith(compareBy({ it.second.optLong("startedAtMs") }, { it.first.name }))

    /** What a note's copy must hold now: its sound files with their names, its text files and a checksum of them. */
    private class Wanted(
        val sound: String,
        val sounds: List<Pair<File, String>>,
        val text: String,
        val markdown: String?,
        val check: String,
    )

    private fun wanted(
        dir: File,
        meta: JSONObject,
        base: String,
        markdown: Boolean,
    ): Wanted {
        val parts = NoteStore.audioParts(meta).map { File(dir, "chunks/${it.file}") }.filter { it.exists() }
        val sounds = parts.mapIndexed { index, file -> file to if (parts.size == 1) "$base.${file.extension}" else "${base}_${index + 1}.${file.extension}" }
        val text = if (meta.optString("mode") == com.govorun.echo.notes.NoteText.MODE) com.govorun.echo.notes.NoteText.read(dir, meta).trim() else runCatching { File(dir, "text.txt").readText() }.getOrDefault("").trim()
        val md = if (markdown) NoteMarkdown.of(meta, text, sounds.map { it.second }, dir.name) else null
        return Wanted(signature(meta, 0).substringBeforeLast('|'), sounds, text, md, checksum(text + "\u0000" + md.orEmpty()))
    }

    private fun checksum(text: String) = java.util.zip.CRC32().apply { update(text.toByteArray()) }.value.toString(16)


    /** A file of the folder that belongs to a note: its base name, the part number (0 for a single file) and the extension. */
    internal data class FolderFile(val base: String, val part: Int, val ext: String, val id: String)

    private val fileName = Regex("^(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}(?:-\\d+)?)(?:_(\\d+))?\\.(txt|" + com.govorun.echo.audio.AudioFormats.PATTERN + ")$")
    private val baseTime = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")

    internal fun parseName(name: String): Pair<String, Pair<Int, String>>? =
        fileName.matchEntire(name)?.let { it.groupValues[1] to ((it.groupValues[2].toIntOrNull() ?: 0) to it.groupValues[3]) }

    /** Brings back the notes of the folder that this phone does not have (matched by the date in the file name). Returns how many. */
    @Synchronized
    fun restore(context: Context): Int {
        val tree = treeUri(context) ?: return 0
        return NetworkPolicy.withFolderAccess(tree.authority) { checkAccess -> restore(context, tree, checkAccess) }
    }

    private fun restore(context: Context, tree: Uri, checkAccess: () -> Unit): Int {
        val resolver = context.contentResolver
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(rootDoc))
        val files = mutableMapOf<String, MutableList<FolderFile>>()
        var detailsDoc: String? = null
        checkAccess()
        resolver
            .query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == FolderDetails.NAME) detailsDoc = cursor.getString(0)
                    val (base, rest) = parseName(cursor.getString(1).orEmpty()) ?: continue
                    files.getOrPut(base) { mutableListOf() } += FolderFile(base, rest.first, rest.second, cursor.getString(0))
                }
            }
        checkAccess()
        val details = detailsDoc?.let { FolderDetails.read(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it)) }
        Log.i(TAG, "event=restore files=${files.size} details=${details?.notes?.size ?: -1}")
        // The tags' colours and order come back to a phone that has none of its own yet.
        details?.tags?.let { if (AppSettings.tags(context).isEmpty()) AppSettings.restoreTags(context, it) }
        val localRoot = NoteStore.root(context)
        val folderIds = details?.notes.orEmpty().mapValues { (_, meta) -> meta.optString("id") }
        val trash = NoteTrash.root(context)
        val (haveIds, inTrash) = synchronized(NoteStore) {
            val finished = localRoot.listFiles { it.isDirectory }.orEmpty()
                .mapNotNull { dir -> readMeta(dir)?.takeIf { it.optString("state") == NoteStore.STATE_DONE }?.let { dir.name to it } }
                .sortedWith(compareBy({ it.second.optLong("startedAtMs") }, { it.first }))
            val trashed = trash.listFiles { it.isDirectory }.orEmpty().associate { dir -> dir.name to (readMeta(dir) ?: JSONObject()) }
            val planned = planNames(finished.map { (id, meta) -> id to meta.optLong("startedAtMs") })
            // A matching record in the trash still exists. Only the trash's Restore action brings it back.
            matchRestoredCopies(finished.toMap() + trashed, planned, tree.toString(), folderIds, files.keys) to trashed.keys
        }
        val have = haveIds.values.toSet()
        // The notes the phone already has take from the folder what they lack: the sound of a note that came back from Google's
        // backup as text only, and the tags and names of a note restored before the folder carried them.
        var restored = 0
        haveIds.forEach { (id, base) ->
            if (id in inTrash) return@forEach
            val dir = File(localRoot, id)
            val meta = readMeta(dir) ?: return@forEach
            val sounds = files[base].orEmpty().filter { it.ext != "txt" }
            if (NoteStore.audioParts(meta).isEmpty() && meta.optString("mode") != com.govorun.echo.notes.NoteText.MODE && sounds.isNotEmpty()) {
                checkAccess()
                runCatching { FolderRestoreAudio.restoreMissingSound(context, tree, sounds, dir, checkAccess) }
                    .onSuccess { if (it) restored++ }
                    .onFailure { if (it is NetworkGate.Refused) throw it; Log.w(TAG, "event=sound_restore_failed note=$base", it) }
            }
            val kept = details?.notes?.get(base)
            if (kept != null) synchronized(NoteStore) {
                val latest = readMeta(dir) ?: return@synchronized
                if (latest.optString("mode") == "restored" && !latest.has("tags") && !latest.has("title")) {
                    NoteStore.writeMeta(dir, FolderDetails.apply(latest, kept))
                }
            }
        }
        for ((base, list) in files.toSortedMap()) {
            if (base in have) continue
            val text = list.firstOrNull { it.ext == "txt" } ?: continue
            val startedAt = restoredStart(base) ?: continue
            checkAccess()
            val (id, dir) = NoteStore.newNote(context)
            try {
                File(dir, "text.txt").writeText(resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, text.id))?.use { it.readBytes().decodeToString() }.orEmpty())
                val meta =
                    JSONObject()
                        .put("mode", "restored")
                        .put("startedAtMs", startedAt)
                        .put("durationMs", 0L)
                        .put("bytes", 0)
                        .put("state", NoteStore.STATE_DONE)
                FolderRestoreAudio.bringSound(resolver, tree, list.filter { it.ext != "txt" }, dir, meta, checkAccess)
                details?.notes?.get(base)?.let { FolderDetails.apply(meta, it) }
                meta.put("backup", JSONObject().put("tree", tree.toString()).put("base", base).put("sig", signature(meta, textLength(dir))))
                NoteStore.writeMeta(dir, meta)
                restored++
            } catch (failure: Exception) {
                dir.deleteRecursively()
                if (failure is NetworkGate.Refused) throw failure
                Log.w(TAG, "event=restore_failed note=$base", failure)
            }
        }
        return restored
    }

    /** Resolve explicit identities first, then match older folders without identifiers by name. */
    internal fun matchRestoredCopies(
        finished: Map<String, JSONObject>,
        planned: Map<String, String>,
        tree: String,
        folderIds: Map<String, String>,
        available: Set<String>,
    ): Map<String, String> {
        val manifestNames = folderIds.entries.asSequence()
            .filter { (base, id) -> base in available && id.isNotBlank() }
            .associate { (base, id) -> id to base }
        val matches = mutableMapOf<String, String>()
        val claimed = mutableSetOf<String>()
        for ((id, meta) in finished) {
            val receiptName = meta.optJSONObject("backup")
                ?.takeIf { it.optString("tree") == tree }?.optString("base")
            val base = receiptName?.takeIf { it in available && it !in claimed } ?: manifestNames[id]
            if (base != null && claimed.add(base)) matches[id] = base
        }
        for ((id, base) in planned) {
            if (id !in matches && base in available && folderIds[base].isNullOrBlank() && claimed.add(base)) {
                matches[id] = base
            }
        }
        return matches
    }

    private fun restoredStart(base: String): Long? =
        runCatching {
            java.time.LocalDateTime.parse(base.take(16), baseTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + base.drop(16).removePrefix("-").toIntOrNull().let { (it ?: 1) - 1 } * 1_000L
        }.getOrNull()

    /** One pass over all notes; returns when everything copyable is copied or the first hard error hit. */
    private fun sync(context: Context, tree: Uri, checkAccess: () -> Unit) {
        val resolver = context.contentResolver
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val notes = copyableNotes(context)
        val markdown = AppSettings.markdownCopy(context)
        checkAccess()
        hideFromMedia(resolver, tree, rootDoc, checkAccess)
        // The folder is listed once a pass: listing it again for every note made a thousand notes' first copy take minutes.
        checkAccess()
        val present = listed(resolver, tree, rootDoc)
        checkAccess()
        val knownNames = FolderDetails.bases(resolver, present, tree)
        val planned = planNames(notes.map { it.first.name to it.second.optLong("startedAtMs") })
        val names = notes.associate { (dir, meta) -> dir.name to (meta.optJSONObject("backup")?.takeIf { it.optString("tree") == tree.toString() }?.optString("base")?.takeIf { it.isNotBlank() }
            ?: knownNames[dir.name] ?: (planned.getValue(dir.name).take(16) + "-" + (dir.name.hashCode().toLong() and 0xffffffffL))) }

        var error: String? = null
        val completed = mutableListOf<Pair<File, JSONObject>>()
        for ((dir, meta) in notes) {
            val base = names.getValue(dir.name)
            val want = wanted(dir, meta, base, markdown)
            val expectedNames = listOfNotNull("$base.txt", "$base.md".takeIf { want.markdown != null }) + want.sounds.map { it.second }
            if (isCopied(meta, tree, want) && expectedNames.all { it in present }) { completed += dir to meta; continue }
            checkAccess()
            try {
                // A note without its sound on the phone (back from Google's backup as text, or its sound let go) never takes the
                // folder's sound with it: only a new sound replaces an old one.
                FolderOwnership.check(resolver, present, meta, tree, base, markdown, checkAccess)
                val previous = JSONObject().apply { present.filterKeys { it == "$base.txt" || it == "$base.md" || it.startsWith("${base}_") || it.substringBeforeLast('.') == base }.forEach { (name, uri) -> put(name, FolderOwnership.hash(resolver, uri, checkAccess)) } }
                val desired = JSONObject().put("$base.txt", CopyReceipt.hash(want.text.toByteArray())).apply {
                    want.markdown?.let { put("$base.md", CopyReceipt.hash(it.toByteArray())) }
                    want.sounds.forEach { (file, name) -> put(name, com.govorun.echo.backup.PortableArchive.sha(file)) }
                }
                NoteStore.updateMeta(dir, touched = false) { put("backupPending", JSONObject().put("tree", tree.toString()).put("base", base).put("previous", previous).put("documents", desired)) }
                if ((soundCopied(meta, tree, want) && want.sounds.all { it.second in present }) || want.sounds.isEmpty()) writeText(resolver, present, rootDoc, base, want, checkAccess) else copyNote(resolver, present, rootDoc, base, want, checkAccess)
                checkAccess()
                val record = record(tree, meta, dir, want).put("base", base).put("documents", JSONObject(meta.optJSONObject("backup")?.optJSONObject("documents")?.toString() ?: "{}").apply { expectedNames.forEach { name -> present[name]?.let { put(name, FolderOwnership.hash(resolver, it, checkAccess)) } } })
                NoteStore.updateMeta(dir, touched = false) { put("backup", record); remove("backupPending") }
                completed += dir to meta
            } catch (refused: NetworkGate.Refused) {
                throw refused
            } catch (failure: SecurityException) {
                error = "no_access"
                Log.w(TAG, "event=no_access", failure)
                break
            } catch (failure: Exception) {
                error = failure.message ?: failure.javaClass.simpleName
                Log.w(TAG, "event=copy_failed note=${dir.name}", failure)
            }
        }
        if (error != "no_access") {
            checkAccess()
            runCatching { FolderDetails.write(resolver, present, rootDoc, tree, completed, names, AppSettings.tagBackup(context), checkAccess) }
                .onFailure { if (it is NetworkGate.Refused) throw it; error = error ?: (it.message ?: it.javaClass.simpleName); Log.w(TAG, "event=details_failed", it) }
        }
        prefs(context).edit().apply { if (error == null) remove(KEY_ERROR) else putString(KEY_ERROR, error) }.apply()
        if (error != null) com.govorun.echo.Journal.add(context, com.govorun.echo.Journal.BACKUP, context.getString(com.govorun.echo.R.string.journal_backup_failed))
    }

    /** What a note's meta keeps of its copy: the folder, the sound as copied (with the text length, as before) and the text checksum. */
    private fun record(
        tree: Uri,
        meta: JSONObject,
        dir: File,
        want: Wanted,
    ) = JSONObject().put("tree", tree.toString()).put("sig", signature(meta, textLength(dir))).put("text", want.check)

    private fun copyNote(
        resolver: android.content.ContentResolver,
        present: MutableMap<String, Uri>,
        rootDoc: Uri,
        base: String,
        want: Wanted,
        checkAccess: () -> Unit,
    ) {
        want.sounds.forEach { (file, name) -> SafeFolderWrite.write(resolver, present, rootDoc, mimeOf(file.extension), name, checkAccess) { out -> file.inputStream().use { it.copyTo(out) } } }
        writeText(resolver, present, rootDoc, base, want, checkAccess)
        val keep = want.sounds.map { it.second }.toSet()
        present.keys.filter { it !in keep && Regex("^" + Regex.escape(base) + "(_\\d+)?\\.(" + com.govorun.echo.audio.AudioFormats.PATTERN + ")$").matches(it) }.toList().forEach { name ->
            checkAccess()
            check(DocumentsContract.deleteDocument(resolver, present.getValue(name))) { "delete_failed" }; present.remove(name)
        }
    }

    private fun writeText(
        resolver: android.content.ContentResolver,
        present: MutableMap<String, Uri>,
        rootDoc: Uri,
        base: String,
        want: Wanted,
        checkAccess: () -> Unit,
    ) {
        SafeFolderWrite.write(resolver, present, rootDoc, "text/plain", "$base.txt", checkAccess) { it.write(want.text.toByteArray()) }
        want.markdown?.let { md -> SafeFolderWrite.write(resolver, present, rootDoc, "text/markdown", "$base.md", checkAccess) { it.write(md.toByteArray()) } }
    }

    private fun mimeOf(extension: String) = com.govorun.echo.audio.AudioFormats.mimeOf(extension)

    /** An empty `.nomedia` keeps the copied sound out of the gallery and music players; the files themselves stay as they are. */
    private fun hideFromMedia(
        resolver: android.content.ContentResolver,
        tree: Uri,
        rootDoc: Uri,
        checkAccess: () -> Unit,
    ) {
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(rootDoc))
            val present =
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) if (cursor.getString(0) == NO_MEDIA) found = true
                    found
                } ?: false
            if (!present) {
                checkAccess()
                DocumentsContract.createDocument(resolver, rootDoc, "application/octet-stream", NO_MEDIA)
            }
        }
    }

    private const val NO_MEDIA = ".nomedia"

    /** The folder's files by name, read once a pass and kept up to date as the pass writes and deletes. */
    private fun listed(
        resolver: android.content.ContentResolver,
        tree: Uri,
        rootDoc: Uri,
    ): MutableMap<String, Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(rootDoc))
        val found = HashMap<String, Uri>()
        resolver
            .query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> while (cursor.moveToNext()) found[cursor.getString(1).orEmpty()] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)) }
        return found
    }

    private fun folderName(
        context: Context,
        tree: Uri,
    ): String? =
        runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver
                .query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

    private fun copyable(
        dir: File,
        meta: JSONObject,
    ) = !com.govorun.echo.notes.CommandHistory.isCommand(meta) && meta.optString("state") == NoteStore.STATE_DONE && File(dir, "text.txt").exists()

    private fun isCopied(
        meta: JSONObject,
        tree: Uri,
        want: Wanted,
    ): Boolean = soundCopied(meta, tree, want) && meta.optJSONObject("backup")?.optString("text") == want.check

    /** The note's sound in the folder is the sound it has now (its length and parts); its text may still be old. */
    private fun soundCopied(
        meta: JSONObject,
        tree: Uri,
        want: Wanted,
    ): Boolean {
        val saved = meta.optJSONObject("backup") ?: return false
        return saved.optString("tree") == tree.toString() && saved.optString("sig").substringBeforeLast('|') == want.sound
    }

    private fun textLength(dir: File) = File(dir, "text.txt").length()

    private fun readMeta(dir: File): JSONObject? = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What makes a copy out of date: the length of the recording, its audio parts and the text size. */
    internal fun signature(
        meta: JSONObject,
        textLength: Long,
    ): String {
        val audio = meta.optJSONObject("audio")
        val parts = audio?.keys()?.asSequence()?.sorted()?.joinToString(",") { "$it=${audio.optLong(it)}" }.orEmpty()
        return "${meta.optLong("durationMs")}|$parts|$textLength"
    }

    /** One stable file name per note: the date and time, with `-2`, `-3` for notes in the same minute. */
    internal fun planNames(notes: List<Pair<String, Long>>): Map<String, String> {
        val items = notes.map { ExportItem(it.second, "", emptyList()) }
        val names = Exporter.uniqueNames(items)
        return notes.mapIndexed { index, (id, _) -> id to names[index] }.toMap()
    }
}
