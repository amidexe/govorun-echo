package com.govorun.echo.hotkey

import android.media.AudioManager

internal enum class AudioConflict(
    val logValue: String,
) {
    Call("call"),
    Communication("communication"),
    ActiveRecording("active_recording"),
}

internal object HotkeyPolicy {
    const val LONG_PRESS_MS = 500L


    fun audioConflict(
        audioMode: Int,
        hasActiveRecording: Boolean,
    ): AudioConflict? =
        when (audioMode) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            -> AudioConflict.Call

            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
            -> AudioConflict.Communication

            else -> if (hasActiveRecording) AudioConflict.ActiveRecording else null
        }

    /** Returns why the key must stay an ordinary volume key, or null to intercept it. */
    fun passThroughReason(
        enabled: Boolean,
        keyguardLocked: Boolean,
        conflict: AudioConflict?,
    ): String? =
        when {
            !enabled -> "disabled"
            !keyguardLocked -> "unlocked"
            conflict != null -> conflict.logValue
            else -> null
        }
}

/** Lets a whole pass-through press (down, repeats, up) reach the system untouched. */
internal class NativePressGate {
    private var inProgress = false

    fun begin() {
        inProgress = true
    }

    fun shouldPassThrough(isUp: Boolean): Boolean {
        if (!inProgress) return false
        if (isUp) inProgress = false
        return true
    }

    fun reset() {
        inProgress = false
    }
}
