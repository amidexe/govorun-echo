package com.govorun.echo.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.govorun.echo.NetworkGate
import com.govorun.echo.R
import com.govorun.echo.backup.BackupFolder

/**
 * «Копия в папку» for the main screen: picking the folder (the system asks for it and Echo keeps the permission), copying now,
 * bringing notes back from it and turning it off. [changed] grows whenever the copy changes, so the settings show it at once.
 */
internal class BackupHost(
    private val activity: ComponentActivity,
) {
    var changed by mutableIntStateOf(0)
        private set

    private val pick =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            BackupFolder.setTree(activity, uri)
            // A folder that already holds Echo's notes is read first: what the phone lacks comes back, then the copy adds the rest.
            restore()
        }

    fun pickFolder() = pick.launch(null)

    fun run() {
        BackupFolder.syncAsync(activity) { activity.runOnUiThread { changed++ } }
        changed++
    }

    fun restore() {
        Thread {
            val message = try {
                activity.getString(R.string.backup_restored, BackupFolder.restore(activity))
            } catch (refused: NetworkGate.Refused) {
                activity.getString(smartStateText(refused.reason).line)
            }
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                changed++
            }
            BackupFolder.syncAsync(activity) { activity.runOnUiThread { changed++ } }
        }.start()
    }

    fun off() {
        BackupFolder.clear(activity)
        changed++
    }
}

/**
 * Leaving Echo makes one copy pass: a tag, a name, a pin or the archive changed on the way reach the folder's `echo-notes.json`
 * then, rather than waiting for the next recording.
 */
@androidx.compose.runtime.Composable
internal fun CopyOnLeave() {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { BackupFolder.syncAsync(context) }
}
