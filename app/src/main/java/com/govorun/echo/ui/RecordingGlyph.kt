package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.Color
import com.govorun.echo.R

/** Action symbols are distinct from the bird that identifies the app in notifications. */
@Composable
internal fun recordingGlyph(): ImageVector = ImageVector.vectorResource(R.drawable.ic_action_record)

@Composable
internal fun recordingStopGlyph(): ImageVector = ImageVector.vectorResource(R.drawable.ic_action_stop)

/** The launcher palette stays recognizable independently of the surrounding Material theme. */
internal object RecordingColors {
    val Container = Color(0xFFE9563F)
    val Content = Color(0xFF2D211E)
}
