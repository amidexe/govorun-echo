package com.govorun.echo.backup

import android.content.Context
import android.util.Log
import com.govorun.echo.NetworkGate
import com.govorun.echo.NetworkPolicy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Runs explicitly enabled exports. No export writes into the note store. */
object BackupFolder {
    private const val TAG = "BackupFolder"
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val waitingForNetwork = AtomicBoolean(false)

    internal fun networkAvailable(context: Context) {
        val config = FolderExports.config(context) ?: return
        if (NetworkPolicy.folderRefusal(config.tree.authority) == null && waitingForNetwork.getAndSet(false)) syncAsync(context)
    }

    fun syncAsync(context: Context, onFinished: () -> Unit = {}) {
        val app = context.applicationContext
        if (!FolderExports.active(app)) return
        again.set(true)
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (again.getAndSet(false)) {
                    val config = FolderExports.config(app) ?: break
                    try {
                        NetworkPolicy.withFolderAccess(config.tree.authority) { checkAccess -> FolderExports.sync(app, config, checkAccess) }
                        FolderExports.result(app, config.revision, null)
                    } catch (_: FolderExports.Stopped) {
                        // The owner disabled or reconfigured this export.
                    } catch (_: NetworkGate.Refused) {
                        waitingForNetwork.set(true)
                        networkAvailable(app)
                    } catch (failure: Exception) {
                        FolderExports.result(app, config.revision, if (failure is SecurityException) "no_access" else failure.message ?: "copy_failed")
                        Log.w(TAG, "event=folder_export_failed", failure)
                    }
                }
            } finally {
                running.set(false)
                onFinished()
                if (again.get()) syncAsync(app)
            }
        }
    }

    private val fileName = Regex("^(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}(?:-\\d+)?)(?:_(\\d+))?\\.(txt|" + com.govorun.echo.audio.AudioFormats.PATTERN + ")$")

    internal fun parseName(name: String): Pair<String, Pair<Int, String>>? =
        fileName.matchEntire(name)?.let { it.groupValues[1] to ((it.groupValues[2].toIntOrNull() ?: 0) to it.groupValues[3]) }
}
