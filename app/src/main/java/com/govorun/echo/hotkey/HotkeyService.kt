package com.govorun.echo.hotkey

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.govorun.echo.AppSettings
import com.govorun.echo.record.CaptureActivity
import com.govorun.echo.record.Feedback
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingService
import com.govorun.echo.record.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Hold volume-down on a locked phone to record a note; release to save it.
 * Anywhere else (unlocked, call, disarmed) the key stays an ordinary volume key.
 */
class HotkeyService : AccessibilityService() {
    private enum class Phase { Idle, Pending, Starting, Recording, Stopping }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val gate = NativePressGate()
    private val longPress = Runnable(::onLongPress)
    private var phase = Phase.Idle
    private var releasedWhileStarting = false
    private var swallowUntilUp = false
    private var triedVisibleStart = false
    private var busyHold = false
    private var hapticView: View? = null
    private val feedback by lazy(LazyThreadSafetyMode.NONE) { Feedback(this) { hapticView } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        attachHapticView()
        scope.launch { RecordingBus.state.collect(::onRecordingState) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = reset()

    override fun onDestroy() {
        reset()
        detachHapticView()
        scope.cancel()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val isUp = event.action == KeyEvent.ACTION_UP
        if (gate.shouldPassThrough(isUp)) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onDown(event)
            KeyEvent.ACTION_UP -> onUp()
            else -> false
        }
    }

    private fun onDown(event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return phase != Phase.Idle || swallowUntilUp
        val keyguard = getSystemService(KeyguardManager::class.java)
        val audio = getSystemService(AudioManager::class.java)
        val ownRecording = RecordingBus.state.value.let { it is RecordingState.Recording || it is RecordingState.Starting }
        val conflict =
            audio?.let {
                HotkeyPolicy.audioConflict(
                    it.mode,
                    !ownRecording && runCatching { it.activeRecordingConfigurations.isNotEmpty() }.getOrDefault(false),
                )
            }
        val reason =
            HotkeyPolicy.passThroughReason(
                enabled = AppSettings.hotkeyEnabled(this),
                keyguardLocked = keyguard?.isKeyguardLocked == true,
                conflict = conflict,
            )
        if (reason != null) {
            Log.i(TAG, "event=passthrough reason=$reason")
            gate.begin()
            return false
        }
        if (phase != Phase.Idle) return true
        // A recording from the tile or the app is running: a short press stays an ordinary volume key, a hold says
        // "already recording" with two buzzes and does not change the volume.
        busyHold = ownRecording
        phase = Phase.Pending
        releasedWhileStarting = false
        triedVisibleStart = false
        handler.postDelayed(longPress, HotkeyPolicy.LONG_PRESS_MS)
        return true
    }

    private fun onUp(): Boolean {
        if (swallowUntilUp) {
            swallowUntilUp = false
            return true
        }
        return when (phase) {
            Phase.Pending -> {
                handler.removeCallbacks(longPress)
                phase = Phase.Idle
                replayVolumeDown()
                true
            }

            Phase.Starting -> {
                releasedWhileStarting = true
                true
            }

            Phase.Recording -> {
                phase = Phase.Stopping
                startService(RecordingService.stopIntent(this))
                true
            }

            Phase.Stopping -> true
            Phase.Idle -> false
        }
    }

    private fun onLongPress() {
        if (phase != Phase.Pending) return
        if (busyHold) {
            Log.i(TAG, "event=hold_while_recording")
            busyHold = false
            phase = Phase.Idle
            swallowUntilUp = true
            feedback.busy()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failWhileHeld("no_microphone_permission")
            return
        }
        phase = Phase.Starting
        Log.i(TAG, "event=long_press_start")
        runCatching { startForegroundService(RecordingService.startIntent(this, RecordingService.MODE_NOTE)) }
            .onFailure {
                Log.w(TAG, "event=direct_start_rejected", it)
                launchVisibleStart()
            }
    }

    private fun onRecordingState(state: RecordingState) {
        when (state) {
            is RecordingState.Recording -> {
                if (phase != Phase.Starting) return
                phase = Phase.Recording
                if (releasedWhileStarting) {
                    phase = Phase.Stopping
                    startService(RecordingService.stopIntent(this))
                }
            }

            is RecordingState.Saved, RecordingState.Discarded -> {
                if (phase == Phase.Idle) return
                phase = Phase.Idle
            }

            is RecordingState.Failed -> {
                if (phase == Phase.Idle || phase == Phase.Pending) return
                if (state.needsVisibleStart && phase == Phase.Starting && !triedVisibleStart) {
                    launchVisibleStart()
                } else {
                    failWhileHeld(state.reason)
                }
            }

            else -> Unit
        }
    }

    private fun launchVisibleStart() {
        triedVisibleStart = true
        Log.i(TAG, "event=visible_start_fallback")
        val intent =
            Intent(this, CaptureActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        runCatching { startActivity(intent) }.onFailure { failWhileHeld("visible_start_failed") }
    }

    private fun failWhileHeld(reason: String) {
        Log.w(TAG, "event=failed reason=$reason")
        handler.removeCallbacks(longPress)
        swallowUntilUp = (phase == Phase.Pending || phase == Phase.Starting) && !releasedWhileStarting
        phase = Phase.Idle
        feedback.error()
    }

    private fun reset() {
        handler.removeCallbacks(longPress)
        phase = Phase.Idle
        releasedWhileStarting = false
        swallowUntilUp = false
        busyHold = false
        gate.reset()
    }

    private fun replayVolumeDown() {
        getSystemService(AudioManager::class.java)?.adjustSuggestedStreamVolume(
            AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun attachHapticView() {
        if (hapticView != null) return
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        val view = View(this).apply { isHapticFeedbackEnabled = true }
        val params =
            WindowManager
                .LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    alpha = 0f
                }
        runCatching { windowManager.addView(view, params) }.onSuccess { hapticView = view }
    }

    private fun detachHapticView() {
        val view = hapticView ?: return
        hapticView = null
        runCatching { getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }

    private companion object {
        const val TAG = "Hotkey"
    }
}
