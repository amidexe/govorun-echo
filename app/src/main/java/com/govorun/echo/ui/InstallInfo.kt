package com.govorun.echo.ui

import android.content.Context
import com.govorun.echo.AppSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import com.govorun.echo.NetworkPolicy
import java.net.URL

/**
 * Where to get Govorun 2 and what to tell the owner about it. The built-in values always work;
 * a small JSON file on the website can replace them without shipping a new APK, for example to
 * say that RuStore still has the old version and GitHub has the new one. The app sends nothing
 * about the user: it only downloads this file.
 */
internal data class InstallInfo(
    val rustoreUrl: String,
    val githubUrl: String,
    val siteUrl: String,
    /** Where to leave a review; empty until Echo has a store page of its own, then the row stays hidden. */
    val reviewUrl: String,
    val notice: String,
    /** Enabled only after the store offers a compatible Govorun; controlled by the same public install feed. */
    val rustoreAvailable: Boolean = false,
) {
    companion object {
        const val LEGAL_URL = "https://govorun.club/echo/legal"
        const val INFO_URL = "https://govorun.club/echo/install.json"
        private val ALLOWED_HOSTS = setOf("www.rustore.ru", "rustore.ru", "github.com", "govorun.club", "www.govorun.club")

        val DEFAULT =
            InstallInfo(
                rustoreUrl = "https://www.rustore.ru/catalog/app/com.govorun.lite",
                githubUrl = "https://github.com/amidexe/govorun-lite/releases/tag/v2.1.1",
                siteUrl = "https://govorun.club/",
                reviewUrl = "",
                notice = "",
            )

        /** Applies a downloaded JSON over the defaults; bad or foreign links are ignored. */
        fun parse(json: String?): InstallInfo {
            if (json.isNullOrBlank()) return DEFAULT
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return DEFAULT
            return InstallInfo(
                rustoreUrl = safeUrl(obj.optString("rustoreUrl")) ?: DEFAULT.rustoreUrl,
                githubUrl = compatibleGithubUrl(obj.optString("githubUrl")) ?: DEFAULT.githubUrl,
                siteUrl = safeUrl(obj.optString("siteUrl")) ?: DEFAULT.siteUrl,
                reviewUrl = safeUrl(obj.optString("reviewUrl")).orEmpty(),
                notice = obj.optString("notice").take(300),
                rustoreAvailable = obj.optBoolean("rustoreAvailable", false),
            )
        }

        private fun compatibleGithubUrl(value: String): String? {
            val safe = safeUrl(value) ?: return null
            val url = URL(safe)
            if (url.host == "github.com" && url.path.startsWith("/amidexe/govorun-lite/releases")) {
                val version = Regex("/releases/(?:tag|download)/v([0-9.]+)").find(url.path)?.groupValues?.get(1) ?: return null
                if (com.govorun.echo.recognition.GovorunProvider.requiresUpdate(version)) return null
            }
            return safe
        }

        private fun safeUrl(value: String): String? {
            if (value.isBlank()) return null
            val url = runCatching { URL(value) }.getOrNull() ?: return null
            return value.takeIf { url.protocol == "https" && url.host in ALLOWED_HOSTS }
        }

        fun cached(context: Context): InstallInfo = parse(AppSettings.installInfoJson(context))

        /** Blocking download with short timeouts; keeps the cached copy when anything goes wrong. */
        fun refresh(context: Context): InstallInfo {
            runCatching {
                NetworkPolicy.request(INFO_URL) { connection ->
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.useCaches = false
                run {
                    if (connection.responseCode == 200) {
                        val body = connection.inputStream.use { it.readNBytes(8_192 + 1) }.also { check(it.size <= 8_192) }.toString(Charsets.UTF_8)
                        if (JSONObject(body).length() > 0) AppSettings.setInstallInfoJson(context, body)
                    }
                }
                }
            }
            return cached(context)
        }
    }
}
