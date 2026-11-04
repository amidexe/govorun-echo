package com.govorun.echo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Only live capture shows the gently breathing dot; words and controls never blink. */
@Composable
internal fun RecordingIndicator() {
    val transition = rememberInfiniteTransition(label = "recordingIndicator")
    val opacity by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_000), RepeatMode.Reverse),
        label = "recordingOpacity",
    )
    Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.error.copy(alpha = opacity), CircleShape))
}
