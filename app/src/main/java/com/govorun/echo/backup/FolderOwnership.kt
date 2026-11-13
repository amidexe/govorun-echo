package com.govorun.echo.backup

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONObject

/** An unrelated file or an externally edited Markdown document is a conflict, never an overwrite. */
internal object FolderOwnership {
    fun hash(resolver: ContentResolver, uri: Uri, checkAccess: () -> Unit): String {
        checkAccess()
        return resolver.openInputStream(uri)?.use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkAccess()
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } ?: error("open_failed")
    }

    fun check(resolver: ContentResolver, present: Map<String, Uri>, meta: JSONObject, tree: Uri, base: String, markdown: Boolean, checkAccess: () -> Unit) {
        val own = present.filterKeys { it == "$base.txt" || it == "$base.md" || it.startsWith("${base}_") || it.substringBeforeLast('.') == base }
        if (own.isEmpty()) return
        val saved = meta.optJSONObject("backup")?.takeIf { it.optString("tree") == tree.toString() }
        val pending = meta.optJSONObject("backupPending")?.takeIf { it.optString("tree") == tree.toString() && it.optString("base") == base }
        val digests = saved?.optJSONObject("documents")
        if (pending != null) {
            own.forEach { (name, uri) -> check(CopyReceipt.accepts(saved, pending, name, hash(resolver, uri, checkAccess))) { "copy_conflict" } }
            return
        }
        check(saved != null) { "copy_conflict" }
        if (digests != null) {
            own.keys.filter { it != "$base.md" || markdown }.forEach { name ->
                present[name]?.let { check(digests.has(name) && hash(resolver, it, checkAccess) == digests.getString(name)) { "copy_conflict" } }
            }
        } else {
            // Legacy copies kept one CRC of text + separator + optional Markdown.
            val crc = java.util.zip.CRC32()
            fun feed(uri: Uri) {
                checkAccess()
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        checkAccess()
                        val size = input.read(buffer)
                        if (size < 0) break
                        crc.update(buffer, 0, size)
                    }
                } ?: error("open_failed")
            }
            present["$base.txt"]?.let(::feed)
            crc.update(0)
            val textOnly = crc.value.toString(16)
            present["$base.md"]?.let(::feed)
            check(crc.value.toString(16) == saved.optString("text") || (!markdown && textOnly == saved.optString("text"))) { "copy_conflict" }
        }
    }
}
