package com.govorun.echo.record

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.govorun.echo.AppSettings

/**
 * What the person feels and steers around a recording besides the sound: a firm tap when it starts and two when it is saved
 * (the same taps as Govorun), the recording pause, and a minute before «Остановить, если долго тихо» ends it, one
 * tap and the warning in the notification with «Продолжить». That tap comes in silence, so it does not cover any words.
 * [onChange] redraws the notification.
 */
internal class RecordingCues(
    private val context: Context,
    private val silentForMs: () -> Long,
    private val onChange: () -> Unit,
) {
    private val feedback = Feedback(context) { null }
    private val handler = Handler(Looper.getMainLooper())
    private var warned = false
    private val check =
        object : Runnable {
            override fun run() {
                val warn = StopPolicy.warnsOfSilence(silentForMs(), AppSettings.silenceStopMs(context))
                if (warn != warned) {
                    warned = warn
                    if (warn && taps()) feedback.tap()
                    onChange()
                }
                handler.postDelayed(this, CHECK_MS)
            }
        }

    /** The warning of a silence stop is showing. */
    val warning: Boolean get() = warned

    fun started() {
        if (taps()) feedback.start()
        handler.removeCallbacks(check)
        warned = false
        unpause()
        handler.postDelayed(check, CHECK_MS)
    }

    /** The person's pause: nothing is written meanwhile and silence does not count. */
    fun pause(on: Boolean) {
        val at = RecordingBus.pausedAt.value
        if (on && at == null) RecordingBus.pausedAt.value = System.currentTimeMillis()
        if (!on && at != null) {
            RecordingBus.pausedMs.value += System.currentTimeMillis() - at
            RecordingBus.pausedAt.value = null
        }
        warned = false
        onChange()
    }

    private fun unpause() {
        RecordingBus.pausedAt.value = null
        RecordingBus.pausedMs.value = 0L
    }

    fun saved() {
        stopped()
        if (taps()) feedback.saved()
    }

    fun stopped() {
        handler.removeCallbacks(check)
        warned = false
        unpause()
    }

    /** «Продолжить» on the warning: the silence counts from now on, the warning goes. */
    fun clearWarning() {
        if (!warned) return
        warned = false
        onChange()
    }

    private fun taps() = AppSettings.recordingTaps(context)

    private companion object {
        const val CHECK_MS = 10_000L
    }
}
