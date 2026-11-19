package com.govorun.echo.backup

import android.content.ContentResolver
import android.net.Uri

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
            CopyReceipt.fingerprint(digest.digest())
        } ?: error("open_failed")
    }
}
