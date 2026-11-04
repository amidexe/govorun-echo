package com.govorun.echo.record

import android.content.Context
import android.os.PowerManager

/** A partial wake lock that lets go by itself after [timeoutMs], so a forgotten one cannot keep the phone awake. */
internal fun Context.partialWakeLock(
    tag: String,
    timeoutMs: Long,
): PowerManager.WakeLock? =
    getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        ?.also { it.acquire(timeoutMs) }
