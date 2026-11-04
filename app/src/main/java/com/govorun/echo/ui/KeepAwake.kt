package com.govorun.echo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.govorun.echo.AppSettings
import com.govorun.echo.playback.NotePlayer
import com.govorun.echo.record.RecordingBus
import com.govorun.echo.record.RecordingState
import kotlinx.coroutines.delay

/**
 * The screen stays on in two cases, so that what you read matches what you hear.
 *
 * While you talk into a recording: every phrase starts the countdown again, so it stays on as long as you keep dictating with
 * pauses shorter than [TALK_GRACE_MS]; after [TALK_DIM_AFTER_MS] of silence it dims, and after the grace it is let go. (A screen
 * that has gone off cannot be switched back on by the flag, so the grace has to be longer than a pause to think.)
 * While a note plays (a setting, "dim" by default): the screen stays on, dims to the lowest brightness when nobody has
 * touched it for [DIM_AFTER_MS], and lets go for good after [LET_GO_AFTER_MS] without a touch, so a note left playing on a
 * table does not drain the battery. A touch brings the brightness back at once. "Always" holds it until playback stops,
 * "as usual" leaves the screen to the system.
 */
@Composable
internal fun KeepScreenOnWhileTalking() {
    val context = LocalContext.current
    val view = LocalView.current
    val state by RecordingBus.state.collectAsState()
    val playing by remember { NotePlayer.get(context).state }.collectAsState()
    var silentMs by remember { mutableLongStateOf(0L) }
    var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var idleMs by remember { mutableLongStateOf(0L) }
    val mode = remember(playing.playing) { AppSettings.playScreen(context) }
    val recording = state is RecordingState.Recording
    val lifecycle = visibleLifecycle()
    LaunchedEffect(recording) {
        silentMs = 0L
        lifecycle.whileVisible {
            while (recording) {
                if (RecordingBus.speaking.value) silentMs = 0L else silentMs += CHECK_MS
                delay(CHECK_MS)
            }
        }
    }
    val talking = recording && silentMs < TALK_GRACE_MS
    val talkingDim = talking && silentMs >= TALK_DIM_AFTER_MS
    LaunchedEffect(playing.playing, mode) {
        while (playing.playing && mode != AppSettings.SCREEN_SYSTEM) {
            idleMs = SystemClock.elapsedRealtime() - lastTouch
            delay(CHECK_MS)
        }
        idleMs = 0L
    }
    val listening = playing.playing && mode == AppSettings.SCREEN_ALWAYS ||
        playing.playing && mode == AppSettings.SCREEN_DIM && idleMs < LET_GO_AFTER_MS
    val dim = talkingDim || playing.playing && mode == AppSettings.SCREEN_DIM && idleMs in DIM_AFTER_MS until LET_GO_AFTER_MS
    val window = view.context.findActivity()?.window
    DisposableEffect(window) {
        val original = window?.callback
        if (window != null && original != null) {
            window.callback =
                object : Window.Callback by original {
                    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                        lastTouch = SystemClock.elapsedRealtime()
                        idleMs = 0L
                        return original.dispatchTouchEvent(event)
                    }
                }
        }
        onDispose { if (window != null && original != null) window.callback = original }
    }
    DisposableEffect(talking || listening) {
        view.keepScreenOn = talking || listening
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(dim) {
        window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = if (dim) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        onDispose { window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private const val TALK_GRACE_MS = 90_000L
private const val TALK_DIM_AFTER_MS = 20_000L
private const val CHECK_MS = 1_000L
private const val DIM_AFTER_MS = 30_000L
private const val LET_GO_AFTER_MS = 10 * 60_000L
private const val DIM_BRIGHTNESS = 0.03f
