package com.product.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * How far a long job got, as a thin line instead of numbers: the app's one progress mark (a file being recognized today, any long
 * work tomorrow). [fraction] from 0 to 1, or null while the amount is unknown; [resting] greys it while the work waits (a phone cooling down, a pause).
 */
@Composable
fun ThinProgress(
    fraction: Float?,
    modifier: Modifier = Modifier,
    resting: Boolean = false,
) {
    val line = modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
    val ink = if (resting) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    if (fraction == null) {
        LinearProgressIndicator(
            modifier = line,
            color = ink,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
        return
    }
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "progress")
    LinearProgressIndicator(
        progress = { shown },
        modifier = line,
        color = ink,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
