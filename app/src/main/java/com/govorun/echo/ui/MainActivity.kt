package com.govorun.echo.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.govorun.echo.AppSettings
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteTrash
import com.govorun.echo.playback.NotePlayer
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingService
import com.govorun.echo.record.TilePresentation
import com.govorun.echo.record.canStartTileCapture

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so permission and Govorun checks re-run after the user comes back. */
    internal var resumes by mutableIntStateOf(0)
    internal val player by lazy(LazyThreadSafetyMode.NONE) { NotePlayer.get(this) }
    internal val fragments by lazy(LazyThreadSafetyMode.NONE) { FragmentHost(this, player) }
    internal var themeMode by mutableIntStateOf(AppSettings.THEME_SYSTEM)
    internal var dynamicColors by mutableStateOf(false)
    internal var commandHistoryOpenVersion by mutableIntStateOf(0)
    internal var commandHistoryId by mutableStateOf<String?>(null)
    internal var asked by mutableStateOf<com.govorun.echo.record.SavedNotice.Open?>(null)
    private var tileStartRequestedAt: Long? = null
    private val presentTile: (Boolean, (Intent) -> Unit) -> Boolean = ::presentTileInApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh start restores from the Google backup and empties the expired trash, so
        // coming back never cancels an undo.
        // An edit of a piece left half-way by a killed app is put back, a finished one whose
        // "Отменить" died with the app is confirmed.
        if (savedInstanceState == null)
            Thread {
                com.govorun.echo.notes.NoteCut.settle(NoteStore.root(this))
                com.govorun.echo.backup.Snapshot.restoreIfEmpty(this)
                NoteTrash.purgeExpired(NoteTrash.root(this))
                com.govorun.echo.notes.EmptyNotes.sweepOnce(this)
                com.govorun.echo.notes.AutoTitles.clearOnce(this)
            }
                .start()
        themeMode = AppSettings.themeMode(this)
        commandHistoryId = intent?.getStringExtra(com.govorun.echo.AppEntry.COMMAND_EXTRA)
        if (commandHistoryId != null) commandHistoryOpenVersion++
        asked = com.govorun.echo.record.SavedNotice.noteOf(intent)
        computerCode(intent)
        dynamicColors = AppSettings.dynamicColors(this)
        setContent {
            EchoTheme(themeMode, dynamicColors) {
                AppLockGate {
                    App()
                    KeepScreenOnWhileTalking()
                    ComputerLink()
                }
                CopyOnLeave()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == RETURN_FROM_TILE) return
        commandHistoryId = intent?.getStringExtra(com.govorun.echo.AppEntry.COMMAND_EXTRA)
        if (commandHistoryId != null) commandHistoryOpenVersion++
        asked = com.govorun.echo.record.SavedNotice.noteOf(intent) ?: asked
        computerCode(intent)
    }

    // Debug builds only: the text of a computer's code from adb (--es echo_pair "…"), as the
    // emulator has no camera to point.
    private fun computerCode(intent: android.content.Intent) = ComputerRequest.accept(intent)

    override fun onResume() {
        super.onResume()
        resumes++
        backupHost.run()
        housekeepOnResume()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        // The shade takes window focus while the current activity stays top-resumed.
        if (isTopResumedActivity) TilePresentation.inApp = presentTile
        else if (TilePresentation.inApp === presentTile) TilePresentation.inApp = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startRequestedTileRecording()
    }

    override fun onStop() {
        tileStartRequestedAt = null
        super.onStop()
    }

    private fun presentTileInApp(existingOnly: Boolean, collapse: (Intent) -> Unit): Boolean {
        if (isFinishing) return false
        collapse(Intent(this, MainActivity::class.java).setAction(RETURN_FROM_TILE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        tileStartRequestedAt = if (existingOnly) null else SystemClock.elapsedRealtime()
        if (hasWindowFocus()) startRequestedTileRecording()
        return true
    }

    private fun startRequestedTileRecording() {
        val requestedAt = tileStartRequestedAt ?: return
        tileStartRequestedAt = null
        if (SystemClock.elapsedRealtime() - requestedAt > TILE_FOCUS_TIMEOUT_MS ||
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false ||
            !canStartTileCapture(RecordingBus.state.value, RecordingBus.stopping.value)) return

        CaptureSession(this, lifecycleScope, existingOnly = false, mode = RecordingService.MODE_LONG,
            onSettled = { failed ->
                if (failed) Toast.makeText(this, R.string.tile_start_failed, Toast.LENGTH_LONG).show()
            }, onFinished = {}).begin()
    }

    internal val exporter = NotesExporter(this)
    internal var hotkeyServiceOn by mutableStateOf(false)
    internal val backupHost = BackupHost(this)
}

private const val RETURN_FROM_TILE = "com.govorun.echo.RETURN_FROM_TILE"
private const val TILE_FOCUS_TIMEOUT_MS = 5_000L

/**
 * How long "continue" waits for the note's text to be finished after a stop before saying so
 * instead.
 */
private const val APPEND_WAIT_MS = 5_000L
