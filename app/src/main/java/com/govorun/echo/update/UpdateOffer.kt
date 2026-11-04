package com.govorun.echo.update

import org.json.JSONObject
import java.net.URL

/** A newer test build announced by the small JSON file on the website. */
internal data class UpdateOffer(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
) {
    companion object {
        private val ALLOWED_HOSTS = setOf("govorun.club", "www.govorun.club")

        /** Returns the offer only when it is newer than [installedCode] and every field is well formed; anything else is ignored. */
        fun parse(json: String?, installedCode: Int): UpdateOffer? {
            val obj = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return null
            val code = obj.optInt("versionCode", 0)
            val sha = obj.optString("sha256").lowercase()
            val apk = obj.optString("apk")
            if (code <= installedCode || !Regex("[0-9a-f]{64}").matches(sha) || !isOurSite(apk)) return null
            return UpdateOffer(code, obj.optString("versionName").take(32), apk, sha, obj.optString("notes").take(400))
        }

        private fun isOurSite(value: String): Boolean {
            val url = runCatching { URL(value) }.getOrNull() ?: return false
            return url.protocol == "https" && url.host in ALLOWED_HOSTS
        }
    }
}
