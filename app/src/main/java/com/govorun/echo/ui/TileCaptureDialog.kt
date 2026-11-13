package com.govorun.echo.ui

import android.content.Intent
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.govorun.echo.AppEntry
import com.govorun.echo.AppSettings
import com.govorun.echo.CaptureWindows
import com.govorun.echo.R
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingService
import com.govorun.echo.record.RecordingState

/** A real Quick Settings dialog. No activity, launch animation or invisible microphone bootstrap. */
internal class TileCaptureDialog private constructor(private val service: TileService, existingOnly: Boolean) {
    private val dialog = ComponentDialog(service, R.style.Theme_Echo_TileDialog)
    private val owner = Any()
    private var closing by mutableStateOf(false)
    private val window = checkNotNull(dialog.window)
    private val session = CaptureSession(service, dialog.lifecycleScope, existingOnly, RecordingService.MODE_LONG,
        onSettled = { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }, onFinished = { closing = true }, onActivityRequired = {
            if (window.decorView.hasWindowFocus()) {
                val card = AppEntry.recording(service)
                    .putExtra(AppEntry.TILE_RECORDING_EXTRA, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { service.startActivity(card) }
                    .onSuccess { close() }
                    .onFailure { android.util.Log.w("RecordTile", "event=tile_visible_card_refused type=${it.javaClass.simpleName}") }
            }
        })

    private fun show() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.prepareCaptureInsets()
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.setGravity(Gravity.BOTTOM)
        dialog.setContentView(ComposeView(dialog.context).apply {
            setContent {
                EchoTheme(AppSettings.themeMode(service), AppSettings.dynamicColors(service)) {
                    val state by RecordingBus.state.collectAsState()
                    val stopping by RecordingBus.stopping.collectAsState()
                    TileRecordingCard(if (session.attempted) state else RecordingState.Starting, stopping,
                        session.failed, session::stop, ::close, session.result, closeRequested = closing, systemTransition = true,
                        onOpenNote = ::openNote)
                }
            }
        })
        window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            CaptureWindows.focus(owner, focused)
            if (focused) session.begin()
        }
        dialog.setOnDismissListener {
            CaptureWindows.focus(owner, false)
            session.close()
            if (current === this) current = null
        }
        service.showDialog(dialog)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun close() {
        CaptureWindows.focus(owner, false)
        session.close()
        dialog.dismiss()
    }

    private fun openNote(noteId: String) {
        val open = Runnable {
            service.startActivity(AppEntry.note(service, noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            close()
        }
        if (service.isLocked) service.unlockAndRun(open) else open.run()
    }

    companion object {
        private var current: TileCaptureDialog? = null
        fun show(service: TileService, existingOnly: Boolean): Boolean {
            current?.close()
            val next = TileCaptureDialog(service, existingOnly)
            return try {
                next.show(); current = next; true
            } catch (error: RuntimeException) {
                next.close()
                android.util.Log.w("RecordTile", "event=tile_dialog_failed type=${error.javaClass.simpleName}")
                false
            }
        }
    }
}
