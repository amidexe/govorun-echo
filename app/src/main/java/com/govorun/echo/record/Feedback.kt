package com.govorun.echo.record

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The same system taps as Govorun, by meaning: a firm one when a recording starts, two when it is saved, one when attention is
 * needed, the reject pattern for an error (once) and for "already recording" (twice). Through a view the taps feel native even
 * with touch feedback off; without one (a service) the phone's own predefined click plays as button feedback, as Android's own
 * tap on holding the power button does. Played as touch feedback they never came on phones with touch feedback off; button
 * feedback is silenced only by the phone's vibration switch and by «Вибрация» in Echo.
 */
internal class Feedback(
    private val context: Context,
    private val view: () -> View?,
) {
    private val handler = Handler(Looper.getMainLooper())

    fun start() = pulse(HapticFeedbackConstants.LONG_PRESS, 1)

    fun saved() = pulse(HapticFeedbackConstants.CONFIRM, 2)

    fun tap() = pulse(HapticFeedbackConstants.CONFIRM, 1)

    fun error() = pulse(HapticFeedbackConstants.REJECT, 1)

    fun busy() = pulse(HapticFeedbackConstants.REJECT, 2)

    private fun pulse(
        type: Int,
        count: Int,
    ) {
        repeat(count) { index ->
            handler.postDelayed({ play(type) }, TAP_INTERVAL_MS * index)
        }
    }

    private fun play(type: Int) {
        val target = view()?.takeIf { it.isAttachedToWindow }
        if (target != null) {
            target.performHapticFeedback(type, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        } else {
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() } ?: return
            val effect =
                when (type) {
                    HapticFeedbackConstants.LONG_PRESS -> VibrationEffect.EFFECT_HEAVY_CLICK
                    HapticFeedbackConstants.REJECT -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    else -> VibrationEffect.EFFECT_CLICK
                }
            val click = VibrationEffect.createPredefined(effect)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(click, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(click, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
        }
    }

    private companion object {
        const val TAP_INTERVAL_MS = 130L
    }
}
