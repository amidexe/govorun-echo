package com.govorun.echo.record

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.govorun.echo.AppEntry
import com.govorun.echo.CapturePurpose
import com.govorun.echo.commands.CommandStore

/** The system assistant gesture opens the same short, visible capture as «Сказать команду». */
class AssistActivity : Activity() {
    /** The app is to open once the phone is unlocked; asked for when this window is on screen (see [onWindowFocusChanged]). */
    private var unlockPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != Intent.ACTION_ASSIST ||
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) != true) {
            finish()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            CommandStore(this).commands().isNotEmpty()) return openApp()

        val entry = AppEntry.recording(this, purpose = CapturePurpose.COMMAND)
            .putExtra(AppEntry.TILE_RECORDING_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(entry) }.onFailure { Log.w(TAG, "event=assist_start_rejected", it) }
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
