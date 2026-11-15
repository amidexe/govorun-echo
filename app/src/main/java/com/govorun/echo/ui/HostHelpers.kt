package com.govorun.echo.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import com.govorun.echo.R
import com.govorun.echo.recognition.Outcome
import com.govorun.echo.recognition.FAILED_PROTOCOL
import com.govorun.echo.recognition.RecognitionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun runSelfTest(
    context: android.content.Context,
    client: RecognitionClient,
): String =
    withContext(Dispatchers.IO) {
        val file = java.io.File(context.cacheDir, "selftest.pcm")
        context.assets.open("selftest.pcm").use { input -> file.outputStream().use { input.copyTo(it) } }
        val result = client.recognize(file)
        file.delete()
        if (result is Outcome.Text && result.text.isNotBlank()) {
            context.getString(R.string.onb_selftest_ok, result.text.trim())
        } else {
            context.getString(R.string.onb_selftest_fail)
        }
    }

internal fun govorunStateOf(
    outcome: Outcome,
    installedVersion: String?,
): GovorunState =
    if (com.govorun.echo.recognition.GovorunProvider.requiresUpdate(installedVersion)) GovorunState.TooOld else when (outcome) {
        is Outcome.Text -> GovorunState.Ready
        Outcome.NotInstalled -> if (installedVersion != null) GovorunState.TooOld else GovorunState.NotInstalled
        Outcome.NeedsAuthorization -> GovorunState.NeedsAccess
        Outcome.ModelUnavailable -> GovorunState.ModelUnavailable
        is Outcome.Failed -> if (outcome.code == FAILED_PROTOCOL && installedVersion != null) GovorunState.TooOld else GovorunState.Error
        else -> GovorunState.Error
    }

internal fun android.app.Activity.copyText(text: String) {
    if (text.isBlank()) return
    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("notes", text))
    Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
}

internal fun android.app.Activity.shareText(text: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null))
}

internal fun MainActivity.confirmDialog(
    title: Int,
    text: Int,
    action: Int,
    onConfirm: () -> Unit,
) {
    noteConfirmation?.dismiss()
    val dialog = AlertDialog
        .Builder(this)
        .setTitle(title)
        .setMessage(text)
        .setPositiveButton(action) { _, _ -> onConfirm() }
        .setNegativeButton(R.string.action_cancel, null)
        .create()
    noteConfirmation = dialog
    dialog.setOnDismissListener { if (noteConfirmation === dialog) noteConfirmation = null }
    dialog.show()
}

internal const val UNLOCK_TAPS = 5

/** Turns the hidden settings on (after enough taps on the version) and says so. */
internal fun android.app.Activity.unlockAdvanced(): Boolean {
    com.govorun.echo.AppSettings.setAdvanced(this, true)
    Toast.makeText(this, R.string.settings_advanced_on, Toast.LENGTH_SHORT).show()
    return true
}

/** How long an "Undo" stays on screen. Android makes a snackbar with a button last far longer when an accessibility service is on (ours is), so the time is our own. */
private const val UNDO_MS = 5_000L

/** Shows a message with "Undo" and says whether the button was pressed. A new one replaces the old; leaving the screen removes it. */
internal suspend fun androidx.compose.material3.SnackbarHostState.showUndo(
    message: String,
    action: String,
): Boolean =
    kotlinx.coroutines.coroutineScope {
        currentSnackbarData?.dismiss()
        val timer = launch { delay(UNDO_MS); currentSnackbarData?.dismiss() }
        val result = showSnackbar(message, action, duration = androidx.compose.material3.SnackbarDuration.Indefinite)
        timer.cancel()
        result == androidx.compose.material3.SnackbarResult.ActionPerformed
    }

/** "Archived" with an undo. */
internal suspend fun android.app.Activity.announceArchive(
    snackbar: androidx.compose.material3.SnackbarHostState,
    ids: List<String>,
    archive: Boolean,
    undo: () -> Unit,
) {
    val message =
        when {
            archive && ids.size == 1 -> getString(R.string.archived_one)
            archive -> getString(R.string.archived_many, ids.size)
            ids.size == 1 -> getString(R.string.unarchived_one)
            else -> getString(R.string.unarchived_many, ids.size)
        }
    if (snackbar.showUndo(message, getString(R.string.action_undo))) undo()
}

/** Whether the owner switched on the accessibility service that makes the volume key work. */
internal fun hotkeyServiceEnabled(context: android.content.Context): Boolean {
    val expected = android.content.ComponentName(context, com.govorun.echo.hotkey.HotkeyService::class.java)
    val enabled = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val splitter = android.text.TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
    while (splitter.hasNext()) if (android.content.ComponentName.unflattenFromString(splitter.next()) == expected) return true
    return false
}
