package com.govorun.echo.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.govorun.echo.NetworkPolicy
import com.govorun.echo.export.ExportItem
import com.govorun.echo.export.Exporter
import com.govorun.echo.export.ReadableNotes
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteTrash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object FolderExports {
    private const val TREE = "tree_uri" // The previous release stored only this key. It grants no ongoing consent.
    private const val ACTIVE = "export_enabled_v2"
    private val ioLock = Any()
    val changes = MutableStateFlow(0L)
    private fun prefs(context: Context) = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    fun lastTree(context: Context): Uri? = prefs(context).getString(TREE, null)?.let(Uri::parse)
    fun active(context: Context) = prefs(context).getBoolean(ACTIVE, false)
    fun lastError(context: Context): String? = prefs(context).getString("last_error", null)
    data class Config(val tree: Uri, val tags: Set<String>?, val options: ReadableNotes.Options, val revision: String)

    fun config(context: Context): Config? {
        val values = prefs(context).all
        val tree = values[TREE] as? String ?: return null
        if (values[ACTIVE] != true) return null
        val tags = (values["scope_tags"] as? Set<*>)?.filterIsInstance<String>()?.toSet()
        return Config(Uri.parse(tree), tags, ReadableNotes.Options(values["as_markdown"] == true, values["with_audio"] == true),
            values["revision"] as? String ?: return null)
    }

    fun enable(context: Context, tree: Uri, tags: Set<String>?, options: ReadableNotes.Options) {
        require(tags == null || tags.isNotEmpty())
        check(prefs(context).edit().putString(TREE, tree.toString()).putBoolean(ACTIVE, true)
            .putStringSet("scope_tags", tags).putBoolean("as_markdown", options.markdown).putBoolean("with_audio", options.audio)
            .putString("revision", UUID.randomUUID().toString()).remove("last_error").commit())
        synchronized(ioLock) { /* let an earlier destination roll back before its grant is released */ }
        changes.update { it + 1 }
        BackupFolder.syncAsync(context)
    }

    /** Invalidate before draining. The caller runs on IO and returns after the provider has finished or rolled back. */
    fun stop(context: Context) {
        check(prefs(context).edit().putBoolean(ACTIVE, false).remove("revision").remove("last_error").commit())
        synchronized(ioLock) { /* drain the previous writer */ }
        changes.update { it + 1 }
    }

    fun forgetLocation(context: Context) {
        stop(context)
        val old = lastTree(context)
        check(prefs(context).edit().remove(TREE).commit())
        old?.let { release(context, it) }
        changes.update { it + 1 }
    }

    fun release(context: Context, tree: Uri) {
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree } ?: return
        val flags = (if (grant.isReadPermission) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) context.contentResolver.releasePersistableUriPermission(tree, flags)
    }

    class Stopped : Exception()

    fun sync(context: Context, config: Config, network: () -> Unit) = synchronized(ioLock) {
        val checkAccess = {
            if (prefs(context).getString("revision", null) != config.revision) throw Stopped()
            network()
        }
        checkAccess()
        val tree = config.tree
        val resolver = context.contentResolver
        val root = root(tree)
        val present = list(context, tree)
        var conflict = false
        FolderLedger(context, tree.toString()).use { ledger ->
            val entries = ledger.entries()
            val notes = NoteStore.root(context).listFiles { dir -> dir.isDirectory } ?: error("notes_unavailable")
            for (dir in notes) {
                checkAccess()
                val meta = readMeta(dir) ?: continue
                if (!ReadableNotes.eligible(dir, meta) || !ReadableNotes.matches(meta, config.tags)) continue
                val legacy = legacyRecord(meta, tree.toString())
                val base = legacy?.optString("base")?.takeIf { BackupFolder.parseName("$it.txt") != null }
                    ?: stableBase(dir.name, meta.optLong("startedAtMs"))
                // A deletion can race with enumeration. Never write metadata back into the note directory.
                if (!dir.isDirectory) continue
                // Keep the first recording's path after an append, including existing numbered exports.
                val firstPart = if (config.options.audio) NoteStore.audioParts(meta).firstOrNull()?.file else null
                val numbered = "${base}_1"
                val oldName = firstPart?.let { "$numbered.${File(it).extension}" }
                val firstStem = if (oldName != null && (oldName in entries ||
                    legacy?.optJSONObject("documents")?.has(oldName) == true)) numbered else base
                val docs = ReadableNotes.documents(dir, meta, base, config.options, firstStem)
                for (doc in docs) {
                    checkAccess()
                    val entry = entries[doc.name]
                    val uri = present[doc.name]
                    val stamp = sourceStamp(doc)
                    if (entry?.stamp == stamp && entry.pending == null && uri != null) continue
                    val actual = uri?.let { FolderOwnership.hash(resolver, it, checkAccess) }
                    if (actual != null && entry?.owns(actual) != true &&
                        legacy?.optJSONObject("documents")?.let { CopyReceipt.matches(it, doc.name, actual) } != true) {
                        conflict = true
                        continue
                    }
                    val digest = doc.sound?.let(PortableArchive::sha) ?: stamp
                    if (actual != digest) {
                        ledger.put(doc.name, FolderLedger.Entry(actual, digest, null))
                        SafeFolderWrite.write(resolver, present, root, doc.mime, doc.name, checkAccess, beforePublish = {
                            if (uri != null) check(FolderOwnership.hash(resolver, uri, checkAccess) == actual) { "copy_conflict" }
                        }, body = doc::write)
                        check(FolderOwnership.hash(resolver, present.getValue(doc.name), checkAccess) == digest) { "copy_readback_failed" }
                    }
                    val saved = FolderLedger.Entry(digest, null, stamp)
                    ledger.put(doc.name, saved)
                    entries[doc.name] = saved
                }
            }
        }
        checkAccess()
        if (conflict) error("copy_conflict")
    }

    fun result(context: Context, revision: String, error: String?) {
        if (prefs(context).getString("revision", null) == revision) {
            prefs(context).edit().putString("last_error", error).apply()
            changes.update { it + 1 }
        }
    }

    internal fun sourceStamp(doc: ReadableNotes.Document): String =
        doc.sound?.let { "${it.name}:${it.length()}:${it.lastModified()}" } ?: CopyReceipt.hash(checkNotNull(doc.text))

    private fun readMeta(dir: File) = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
    internal fun stableBase(id: String, started: Long): String =
        Exporter.uniqueNames(listOf(ExportItem(started, "", emptyList()))).single() + "-" + CopyReceipt.hash(id.toByteArray()).take(12)

    internal fun legacyRecord(meta: JSONObject, tree: String): JSONObject? = meta.optJSONObject("backup")
        ?.takeIf { it.optString("tree") == tree && meta.optString("mode") != "restored" && it.has("text") &&
            BackupFolder.parseName(it.optString("base") + ".txt") != null }

    fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    fun list(context: Context, tree: Uri): MutableMap<String, Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = mutableMapOf<String, Uri>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                check(found.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))) == null) { "duplicate_name" }
            }
        } ?: error("folder_unavailable")
        return found
    }

    data class Cleanup(val tree: Uri, val files: Map<String, String>, val legacyIndex: String?, val preserved: Int)
    data class Removed(val removed: Int, val kept: Int)

    /** A filename or an imported file alone never proves ownership. A legacy aggregate is separately opt-in. */
    fun preview(context: Context, tree: Uri): Cleanup = synchronized(ioLock) {
        NetworkPolicy.withFolderAccess(tree.authority) { checkAccess ->
            checkAccess()
            val present = list(context, tree)
            val entries = FolderLedger(context, tree.toString()).use { it.entries() }
            for (root in listOf(NoteStore.root(context), NoteTrash.root(context))) {
                root.listFiles { file -> file.isDirectory }.orEmpty().forEach { dir ->
                    val meta = readMeta(dir) ?: return@forEach
                    val record = legacyRecord(meta, tree.toString()) ?: return@forEach
                    val base = record.optString("base")
                    if (BackupFolder.parseName("$base.txt") == null) return@forEach
                    val proof = record.optJSONObject("documents") ?: return@forEach
                    proof.keys().forEach { name ->
                        if (name == "$base.md" || BackupFolder.parseName(name)?.first == base)
                            entries.putIfAbsent(name, FolderLedger.Entry(proof.getString(name), null, null))
                    }
                }
            }
            val verified = mutableMapOf<String, String>()
            val retained = mutableSetOf<String>()
            entries.forEach { (name, proof) ->
                val uri = present[name] ?: return@forEach
                val current = FolderOwnership.hash(context.contentResolver, uri, checkAccess)
                if (proof.owns(current)) verified[name] = current else retained += name
            }
            val index = present[FolderDetails.NAME]?.let { uri ->
                val before = FolderOwnership.hash(context.contentResolver, uri, checkAccess)
                val bases = FolderDetails.bases(context.contentResolver, uri) ?: return@let null
                present.keys.filterTo(retained) { name ->
                    name !in verified && (BackupFolder.parseName(name)?.first ?: name.removeSuffix(".md")) in bases
                }
                before
            }
            Cleanup(tree, verified, index, retained.size)
        }
    }

    fun remove(context: Context, plan: Cleanup, includeIndex: Boolean): Removed = synchronized(ioLock) {
        check(!active(context) || lastTree(context) != plan.tree) { "stop_export_first" }
        NetworkPolicy.withFolderAccess(plan.tree.authority) { checkAccess ->
            checkAccess()
            val present = list(context, plan.tree)
            var removed = 0
            var kept = plan.preserved
            val targets = plan.files.toMutableMap()
            if (includeIndex && plan.legacyIndex != null) targets[FolderDetails.NAME] = plan.legacyIndex
            FolderLedger(context, plan.tree.toString()).use { ledger ->
                targets.forEach { (name, digest) ->
                    val uri = present[name] ?: return@forEach
                    if (FolderOwnership.hash(context.contentResolver, uri, checkAccess) != digest) { kept++; return@forEach }
                    checkAccess()
                    check(DocumentsContract.deleteDocument(context.contentResolver, uri)) { "delete_failed" }
                    ledger.forget(name)
                    removed++
                }
            }
            Removed(removed, kept)
        }
    }
}
