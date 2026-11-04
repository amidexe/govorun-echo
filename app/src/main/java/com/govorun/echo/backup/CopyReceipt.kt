package com.govorun.echo.backup

import org.json.JSONObject

/** Write-ahead ownership survives a partially published group of files without adopting unrelated files. */
internal object CopyReceipt {
    fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun accepts(saved: JSONObject?, pending: JSONObject, name: String, actual: String): Boolean =
        listOfNotNull(saved?.optJSONObject("documents"), pending.optJSONObject("previous"), pending.optJSONObject("documents"))
            .any { it.has(name) && it.getString(name) == actual }
}
