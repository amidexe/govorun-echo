package com.govorun.echo.ui

import com.govorun.echo.backup.BackupFolder

@androidx.compose.runtime.Composable
internal fun CopyOnLeave() {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { BackupFolder.syncAsync(context) }
}
