package com.govorun.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.govorun.echo.smart.GovorunSmart
import com.govorun.echo.smart.Smart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An old account refusal describes that attempt, not whether the person is signed in now. */
internal fun isSmartAccessIssue(reason: String?): Boolean = reason in setOf(
    GovorunSmart.NOT_SIGNED_IN, GovorunSmart.NOT_AUTHORIZED, GovorunSmart.NEEDS_ACCESS,
)

internal fun currentSmartIssue(previous: String?, current: String?): String? = when {
    !isSmartAccessIssue(previous) -> previous
    current == null || current == GovorunSmart.READY -> null
    else -> current
}

/** Read-only local Binder status on entry/return. Never resubmits, removes or starts a task. */
@Composable
internal fun rememberSmartIssue(reason: String?, revision: Int): String? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember(reason, revision) { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(reason, revision) {
        val job = scope.launch {
            if (isSmartAccessIssue(reason)) {
                current = try {
                    withContext(Dispatchers.IO) { Smart.styleStatus(context).state }
                } catch (_: java.io.IOException) {
                    GovorunSmart.UNAVAILABLE
                }
            }
        }
        onPauseOrDispose { job.cancel(); current = null }
    }
    return currentSmartIssue(reason, current)
}
