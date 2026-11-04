package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

/** Runs [block] only while the screen is on and the app is in front: a recording goes on with the screen off, its drawing does not. */
@Composable
internal fun visibleLifecycle(): Lifecycle = LocalLifecycleOwner.current.lifecycle

internal suspend fun Lifecycle.whileVisible(block: suspend () -> Unit) = repeatOnLifecycle(Lifecycle.State.STARTED) { block() }
