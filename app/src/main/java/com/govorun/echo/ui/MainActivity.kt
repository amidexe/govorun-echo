package com.govorun.echo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.govorun.echo.AppSettings
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteTrash
import com.govorun.echo.playback.NotePlayer

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so permission and Govorun checks re-run after the user comes back. */
    internal var resumes by mutableIntStateOf(0)
    internal val player by lazy(LazyThreadSafetyMode.NONE) { NotePlayer.get(this) }
    internal val fragments by lazy(LazyThreadSafetyMode.NONE) { FragmentHost(this, player) }
    internal var themeMode by mutableIntStateOf(AppSettings.THEME_SYSTEM)
    internal var dynamicColors by mutableStateOf(false)
    internal var asked by mutableStateOf<com.govorun.echo.record.SavedNotice.Open?>(null)

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

    internal val exporter = NotesExporter(this)
    internal var hotkeyServiceOn by mutableStateOf(false)
    internal val backupHost = BackupHost(this)
}

/**
 * How long "continue" waits for the note's text to be finished after a stop before saying so
 * instead.
 */
private const val APPEND_WAIT_MS = 5_000L
