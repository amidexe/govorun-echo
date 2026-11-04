package com.govorun.echo.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.govorun.echo.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import com.govorun.echo.NetworkPolicy
import java.security.MessageDigest

/**
 * Updates inside the app, only in the test build (the store build has an empty feed and never calls the network here).
 * One small JSON file is read when the app is opened or returned to, but at most every six hours (like the desktop apps); the "check"
 * button and the moment before a download always read it, so the newest build is the one installed. Between checks the last answer is
 * remembered, so the card survives a restart. The only thing sent is the request itself, nothing about the user.
 * A downloaded file is installed only if its hash matches and it is signed by the same key as the installed app.
 */
internal object AppUpdates {
    enum class Phase { IDLE, DOWNLOADING, READY, FAILED }

    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    val enabled: Boolean get() = BuildConfig.UPDATE_FEED.isNotEmpty()
    var offer by mutableStateOf<UpdateOffer?>(null)
        private set
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var progress by mutableStateOf(0f)
        private set

    private var file: File? = null

    /**
     * Blocking; call off the main thread. [force] ignores the six-hour limit (the "check" button).
     * Returns true when a newer version is offered, false when there is none, null when the site could not be reached.
     */
    fun checkForUpdate(context: Context, force: Boolean = false): Boolean? {
        if (!enabled) return false
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checked_at", 0L) < CHECK_EVERY_MS) {
            offer = UpdateOffer.parse(prefs.getString("feed_body", null), BuildConfig.VERSION_CODE)
            return offer != null
        }
        val body = runCatching { get(BuildConfig.UPDATE_FEED, 8_192) }.getOrNull() ?: return null
        prefs.edit().putLong("checked_at", now).putString("feed_body", body).apply()
        offer = UpdateOffer.parse(body, BuildConfig.VERSION_CODE)
        return offer != null
    }

    /** Blocking: downloads the file, checks the hash and the signing key, then hands it over to the system installer. */
    fun downloadAndInstall(context: Context) {
        if (offer == null || phase == Phase.DOWNLOADING) return
        // The offer on screen may be older than the feed: read it again so a newer build is never skipped.
        if (checkForUpdate(context, force = true) != true) return
        val wanted = offer ?: return
        phase = Phase.DOWNLOADING
        progress = 0f
        val ok =
            runCatching {
                val target = File(File(context.cacheDir, "updates").apply { mkdirs() }, "echo-update.apk")
                download(wanted, target)
                check(sha256(target) == wanted.sha256 && sameSigner(context, target)) { "rejected" }
                file = target
            }.isSuccess
        phase = if (ok) Phase.READY else Phase.FAILED
        if (ok) install(context)
    }

    /** Opens the system installer; if the system has not yet allowed installs from this app, opens that switch first. */
    fun install(context: Context) {
        val apk = file ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun get(url: String, limit: Int): String = NetworkPolicy.request(url) { connection ->
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.useCaches = false
        run {
            check(connection.responseCode == 200) { "http" }
            connection.inputStream.use { it.readNBytes(limit + 1) }.also { check(it.size <= limit) }.toString(Charsets.UTF_8)
        }
    }

    private fun download(wanted: UpdateOffer, target: File) = NetworkPolicy.request(wanted.apkUrl) { connection ->
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        run {
            check(connection.responseCode == 200) { "http" }
            val total = connection.contentLengthLong.coerceAtLeast(1L)
            var done = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        NetworkPolicy.requireAllowed()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sameSigner(context: Context, apk: File): Boolean {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, flags)?.signingInfo ?: return false
        val installed = context.packageManager.getPackageInfo(context.packageName, flags).signingInfo ?: return false
        return archive.apkContentsSigners.map { it.toByteArray().contentHashCode() }.toSet() ==
            installed.apkContentsSigners.map { it.toByteArray().contentHashCode() }.toSet()
    }
}
