package com.govorun.echo.ui

import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsOut
import com.adamglin.phosphoricons.regular.Microphone
import com.adamglin.phosphoricons.regular.Stop
import com.adamglin.phosphoricons.regular.X
import com.govorun.echo.R
import com.govorun.echo.formatDuration
import com.govorun.echo.record.RecordingBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One window, with the input always at the bottom and the command result above it. */
@Composable
internal fun CaptureStage(
    label: String,
    onClose: () -> Unit,
    detail: String = "",
    result: CaptureResult? = null,
    onStop: (() -> Unit)? = null,
    animateEntrance: Boolean = true,
    showBackdrop: Boolean = true,
    closeRequested: Boolean = false,
    onOpen: (() -> Unit)? = null,
    fromLockedTile: Boolean = false,
    onRequestMicrophone: (() -> Unit)? = null,
) {
    var dismissing by remember { mutableStateOf(false) }
    val closing = dismissing || closeRequested
    val resultFinished = result?.finished == true
    val close by rememberUpdatedState(onClose)
    val view = LocalView.current
    var revealed by remember { mutableStateOf(showBackdrop) }
    var dragging by remember { mutableStateOf(false) }
    var lift by remember { mutableFloatStateOf(0f) }
    val animatedLift by animateFloatAsState(lift, if (dragging) snap() else tween(160), label = "capture lift")
    val open by rememberUpdatedState(onOpen)
    val expandGesture = if (onOpen != null && revealed && !closing) {
        Modifier.pointerInput(Unit) {
            val threshold = 40.dp.toPx()
            detectVerticalDragGestures(
                onDragStart = { dragging = true },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    lift = (lift - amount).coerceIn(0f, threshold * 2)
                },
                onDragEnd = {
                    val expand = lift >= threshold
                    dragging = false
                    lift = 0f
                    if (expand) open?.invoke()
                },
                onDragCancel = {
                    dragging = false
                    lift = 0f
                },
            )
        }
    } else Modifier
    val clock = remember { Animatable(if (animateEntrance) 0f else 400f) }
    val overshoot = remember { OvershootInterpolator(0.75f) }
    LaunchedEffect(closing) {
        if (closing) {
            // Keyguard animates the return with the capture window still on screen.
            if (!fromLockedTile) clock.animateTo(0f, tween(260, easing = LinearEasing))
            close()
        } else if (!showBackdrop) {
            if (!view.hasWindowFocus()) {
                if (fromLockedTile) view.awaitCaptureFocus()
                else withTimeoutOrNull(500L) { view.awaitCaptureFocus() }
            }
            // The shade still blurs its background briefly after this window takes focus.
            delay(120L)
            revealed = true
        } else if (animateEntrance) {
            if (!view.hasWindowFocus()) withTimeoutOrNull(500L) { view.awaitCaptureFocus() }
            withFrameNanos { }
            clock.animateTo(400f, tween(400, easing = LinearEasing))
        }
    }
    BackHandler { dismissing = true }

    Box(Modifier.fillMaxSize().drawBehind {
        if (showBackdrop) drawRect(Color.Black.copy(alpha = 0.14f * captureProgress(clock.value, 0f, 60f)))
    }) {
        if (revealed && !closing) {
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.singleOrNull() ?: break
                        if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        if (change.changedToUp()) {
                            change.consume()
                            dismissing = true
                            break
                        }
                    }
                }
            })
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val resultLimit = (maxHeight - 108.dp).coerceAtLeast(0.dp)
            val travel = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    val t = captureProgress(clock.value, 0f, 380f)
                    translationY = travel * (1f - overshoot.getInterpolation(t)) - animatedLift
                    alpha = if (revealed) captureProgress(t, 0f, 0.25f) else 0f
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth(0.924f).heightIn(max = resultLimit),
                    horizontalAlignment = if (result == null) Alignment.CenterHorizontally else Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(onClick = { dismissing = true }, enabled = revealed && !closing,
                        shape = CircleShape, shadowElevation = 6.dp) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (resultFinished) {
                                Icon(PhosphorIcons.Regular.X, null, Modifier.size(16.dp))
                            } else {
                                Icon(painterResource(R.drawable.ic_capture_minimize), null, Modifier.size(16.dp))
                            }
                            Text(stringResource(if (resultFinished) R.string.action_close else R.string.capture_minimize),
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    AnimatedVisibility(result != null,
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { it / 6 }) {
                        result?.let { QuickCommandResult(it) }
                    }
                }
                Surface(
                    Modifier.widthIn(max = 416.dp).fillMaxWidth(0.81f).heightIn(min = 80.dp)
                        .then(expandGesture),
                    shape = RoundedCornerShape(40.dp), shadowElevation = 10.dp,
                ) {
                    Row(
                        Modifier.background(captureSurfaceBrush()).heightIn(min = 80.dp).padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val primaryAction = onRequestMicrophone ?: onOpen
                        val openAction = if (primaryAction == null) Modifier else Modifier.clickable(
                            enabled = revealed && !closing,
                            onClickLabel = stringResource(if (onRequestMicrophone != null) R.string.action_allow else R.string.capture_open_note),
                            onClick = primaryAction,
                        )
                        Row(
                            Modifier.weight(1f).then(openAction).heightIn(min = 80.dp)
                                .padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(if (onOpen != null) PhosphorIcons.Regular.ArrowsOut else PhosphorIcons.Regular.Microphone,
                                null, Modifier.size(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.titleLarge)
                                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (onStop != null) {
                            FilledTonalIconButton(onClick = onStop, enabled = revealed && !closing) {
                                Icon(PhosphorIcons.Regular.Stop, stringResource(R.string.recording_stop))
                            }
                        } else if (result != null && !resultFinished) {
                            IconButton(onClick = { dismissing = true }, enabled = revealed && !closing) {
                                Icon(PhosphorIcons.Regular.X, stringResource(R.string.action_close))
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun View.awaitCaptureFocus() = suspendCancellableCoroutine { continuation ->
    val observer = viewTreeObserver
    val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
        override fun onWindowFocusChanged(focused: Boolean) {
            if (!focused) return
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
    observer.addOnWindowFocusChangeListener(listener)
    continuation.invokeOnCancellation {
        post { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
    }
}

@Composable
internal fun captureSurfaceBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return Brush.verticalGradient(listOf(colors.surface, colors.surface, colors.surfaceContainerHigh))
}

internal fun captureProgress(time: Float, start: Float, end: Float): Float =
    ((time - start) / (end - start)).coerceIn(0f, 1f)

/** The recording time shown in the capture panel. */
@Composable
internal fun captureElapsed(startedAtMs: Long?): String {
    val elapsed by produceState(0L, startedAtMs) {
        if (startedAtMs != null) while (true) {
            value = RecordingBus.recordedMs(startedAtMs) / 1000
            delay(1_000)
        }
    }
    return if (startedAtMs == null) "" else formatDuration(elapsed * 1000L)
}
