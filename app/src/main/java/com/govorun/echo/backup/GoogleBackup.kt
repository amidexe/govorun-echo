package com.govorun.echo.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/**
 * What Echo knows of Android's own backup to the owner's Google account, to say it in Settings instead of trusting it blindly:
 * when Android last took the copy (Android asks the app for it, so this is seen, not guessed), whether Google refused it as over
 * its 25 MB, and how many notes the snapshot holds. Android takes the copy about once a day, on charge and Wi-Fi, and never sooner
 * than a day after Echo was updated (every update puts it back at the end of the queue, so test builds coming daily keep it from
 * ever happening). Echo cannot start it or ask whether backup is on: it opens the phone's backup screen, whose «Начать копирование»
 * takes it at once.
 */
object GoogleBackup {
    private const val PREFS = "google_backup"

    /** The 25 MB Google keeps for an app. */
    const val QUOTA_BYTES = 25L * 1024 * 1024

    /** Without a copy this long, Settings says to check the phone's backup. */
    const val STALE_MS = 3L * 24 * 60 * 60 * 1000

    class State(
        val lastMs: Long,
        val overQuota: Boolean,
        val notesIn: Int,
        val notesAll: Int,
        val bytes: Long,
    )

    fun state(context: Context): State {
        val p = devicePrefs(context)
        val bytes = Snapshot.file(context).length() + java.io.File(context.filesDir, com.govorun.echo.notes.ActivityDiary.FILE).length()
        return State(p.getLong("last", 0), p.getLong("overQuotaAt", 0) > p.getLong("last", 0), p.getInt("in", 0), p.getInt("all", 0), bytes)
    }

    /** On by default; opting out affects cloud backup only, never a direct phone transfer. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)

    fun setEnabled(
        context: Context,
        value: Boolean,
    ) = prefs(context).edit().putBoolean("enabled", value).apply()

    // Written at once: Android kills the agent's process a few milliseconds after the copy, before a deferred write would land.
    internal fun copied(context: Context) = devicePrefs(context).edit().putLong("last", System.currentTimeMillis()).commit()

    internal fun overQuota(context: Context) = devicePrefs(context).edit().putLong("overQuotaAt", System.currentTimeMillis()).commit()

    /** The phone's own backup screen (Google's on most phones), or its settings where there is none. */
    fun openPhoneBackup(context: Context) {
        val backup = android.content.Intent("com.android.settings.BACKUP_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(backup) }.onFailure {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    internal fun snapshot(
        context: Context,
        notesIn: Int,
        notesAll: Int,
    ) = devicePrefs(context).edit().putInt("in", notesIn).putInt("all", notesAll).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun devicePrefs(context: Context) = context.getSharedPreferences("device_google_backup", Context.MODE_PRIVATE)
}

/** Cloud consent and encryption do not restrict a user-initiated device-to-device transfer. */
class EchoBackupAgent : BackupAgent() {
    private var deviceTransfer = false
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        deviceTransfer = data.transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0
        val encrypted = data.transportFlags and FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED != 0
        if (!deviceTransfer && (!encrypted || !GoogleBackup.enabled(this))) return
        com.govorun.echo.UserFiles.requireSupportedDatabases(this)
        if (deviceTransfer) {
            val preferences = AtomicFile(File(filesDir, TRANSFER_PREFERENCES))
            val stream = preferences.startWrite()
            try {
                stream.write(PortablePreferences.capture(this).toString().toByteArray(Charsets.UTF_8))
                preferences.finishWrite(stream)
            } catch (failure: Throwable) {
                preferences.failWrite(stream)
                throw failure
            }
            try {
                super.onFullBackup(data)
            } finally {
                preferences.delete()
            }
        } else {
            Snapshot.write(this)
            super.onFullBackup(data)
            GoogleBackup.copied(this)
        }
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        val preferences = AtomicFile(File(filesDir, TRANSFER_PREFERENCES))
        if (preferences.baseFile.exists()) {
            val json = preferences.openRead().bufferedReader().use { JSONObject(it.readText()) }
            PortablePreferences.restore(this, json, true)
            preferences.delete()
        }
        // Restore settings before the first Activity reads its theme and tags.
        Snapshot.restoreIfEmpty(this)
    }

    override fun onQuotaExceeded(
        backupDataBytes: Long,
        quotaBytes: Long,
    ) {
        if (!deviceTransfer) GoogleBackup.overQuota(this)
    }

    companion object {
        internal const val TRANSFER_PREFERENCES = "android-transfer-preferences.json"
    }
}
