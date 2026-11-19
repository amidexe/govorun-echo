package com.govorun.echo.backup

import org.json.JSONObject
import java.security.MessageDigest

/** Write-ahead ownership survives a partially published group of files without adopting unrelated files. */
internal object CopyReceipt {
    fun hash(bytes: ByteArray): String = fingerprint(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun fingerprint(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }

    fun matches(documents: JSONObject, name: String, actual: String): Boolean =
        documents.has(name) && documents.optString(name) == actual
}
