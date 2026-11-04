package com.govorun.echo.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream
import java.util.UUID

/** New bytes are closed before the old copy is moved; failed publication restores the old document. */
internal interface FolderDocuments<T> {
    fun create(name: String): T
    fun output(doc: T): OutputStream
    fun rename(doc: T, name: String): T
    fun name(doc: T): String
    fun delete(doc: T)
}

internal fun <T> safeFolderWrite(files: FolderDocuments<T>, previous: T?, name: String, body: (OutputStream) -> Unit): T {
    val suffix = UUID.randomUUID().toString()
    var staged: T? = files.create(".echo-part-$suffix")
    var backup: T? = null
    var published: T? = null
    try {
        files.output(staged!!).use(body)
        if (previous != null) backup = files.rename(previous, ".echo-previous-$suffix")
        published = files.rename(checkNotNull(staged), name)
        staged = null
        check(files.name(published) == name) { "copy_conflict" }
    } catch (error: Exception) {
        published?.let { runCatching { files.delete(it) }.onFailure(error::addSuppressed) }
        backup?.let { runCatching { files.rename(it, name) }.onFailure(error::addSuppressed) }
        staged?.let { runCatching { files.delete(it) }.onFailure(error::addSuppressed) }
        throw error
    }
    backup?.let { files.delete(it) }
    return checkNotNull(published)
}

internal object SafeFolderWrite {
    fun write(resolver: ContentResolver, present: MutableMap<String, Uri>, parent: Uri, mime: String, name: String, body: (OutputStream) -> Unit) {
        val files = object : FolderDocuments<Uri> {
            override fun create(name: String) = DocumentsContract.createDocument(resolver, parent, mime, name) ?: error("create_failed")
            override fun output(doc: Uri) = resolver.openOutputStream(doc, "wt") ?: error("open_failed")
            override fun rename(doc: Uri, name: String) = DocumentsContract.renameDocument(resolver, doc, name) ?: error("rename_failed")
            override fun name(doc: Uri): String = resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: error("name_failed")
            override fun delete(doc: Uri) { check(DocumentsContract.deleteDocument(resolver, doc)) { "delete_failed" } }
        }
        present[name] = safeFolderWrite(files, present[name], name, body)
    }
}
