package com.govorun.echo.record

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.govorun.echo.AppEntry

/**
 * Echo as the phone's digital assistant, chosen in the system list next to Gemini or Алиса: holding the power button (or the
 * assistant gesture) starts a long recording and the next hold stops it, as the tile does. Android keeps the power button to
 * itself and never tells an app that it was let go, so "speak while holding" cannot be built on it. The activity shows over
 * the lock screen, so a recording starts without unlocking; when the microphone is not allowed yet it opens the app.
 */
class AssistActivity : Activity() {
    /** The app is to open once the phone is unlocked; asked for when this window is on screen (see [onWindowFocusChanged]). */
    private var unlockPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = RecordingBus.state.value
        when {
            state is RecordingState.Recording || state is RecordingState.Starting -> {
                Log.i(TAG, "event=assist_stop")
                startService(RecordingService.stopIntent(this))
            }
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> return openApp()
            else -> {
                Log.i(TAG, "event=assist_start")
                val start = runCatching { startForegroundService(RecordingService.startIntent(this, RecordingService.MODE_LONG)) }
                start.exceptionOrNull()?.let {
                    Log.w(TAG, "event=assist_start_rejected", it)
                    return openApp()
                }
            }
        }
        finish()
    }

    /** Opens the app. On the lock screen it would wait behind it unseen, so the unlock is asked for first. */
    private fun openApp() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true) {
            runCatching { startActivity(AppEntry.intent(this)) }.onFailure { Log.w(TAG, "event=assist_open_failed", it) }
            finish()
            return
        }
        unlockPending = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !unlockPending) return
        unlockPending = false
        // Asked from onCreate, the PIN pad came up and went at once: this window covering the lock screen a moment later hid it.
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return finish()
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    runCatching { startActivity(AppEntry.intent(this@AssistActivity)) }.onFailure { Log.w(TAG, "event=assist_open_failed", it) }
                    finish()
                }

                override fun onDismissCancelled() {
                    Log.i(TAG, "event=assist_unlock_cancelled")
                    finish()
                }

                override fun onDismissError() {
                    Log.w(TAG, "event=assist_unlock_error")
                    finish()
                }
            },
        )
    }

    private companion object {
        const val TAG = "Assist"
    }
}
