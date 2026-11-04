package com.govorun.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/** One measured header: the app bar, tags and active filters leave and return together. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollapsingNotesHeader(state: TopAppBarState, content: @Composable () -> Unit) {
    Column(
        Modifier.background(MaterialTheme.colorScheme.background).clipToBounds().layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            val visible = (child.height + state.heightOffset.roundToInt()).coerceIn(0, child.height)
            layout(child.width, visible) { child.placeRelative(0, visible - child.height) }
        },
    ) {
        Column(Modifier.onSizeChanged { state.heightOffsetLimit = -it.height.toFloat() }) { content() }
    }
}

/** Content may pass beneath the clock; a quiet translucent veil keeps system icons readable. */
@Composable
internal fun Modifier.statusBarVeil(): Modifier {
    val height = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
    return drawWithContent {
        drawContent()
        drawRect(color, size = Size(size.width, height))
    }
}
