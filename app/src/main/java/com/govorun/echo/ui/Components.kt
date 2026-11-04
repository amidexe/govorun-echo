package com.govorun.echo.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable

/** Every chip, button and the action button share one fully rounded shape. */
@Composable
internal fun EchoChip(
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
    label: @Composable () -> Unit,
) = FilterChip(selected = selected, onClick = onClick, label = label, leadingIcon = leadingIcon, shape = CircleShape)
