package com.govorun.echo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** One regular Geist role for list metadata, tags, status and small controls. Inherits Echo's font features and theme. */
@Composable
internal fun noteSupportingStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp)
