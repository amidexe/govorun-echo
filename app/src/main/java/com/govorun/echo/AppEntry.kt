package com.govorun.echo

import android.content.Context
import android.content.Intent

/**
 * Echo's front door, as the launcher opens it: what a notification, the tile, a share or a shortcut needs to bring the app up.
 * It comes from the manifest, so the services and activities that open the app do not depend on its screens.
 */
internal object AppEntry {
    fun recording(context: Context): Intent = Intent("com.govorun.echo.RECORD_FROM_TILE").setPackage(context.packageName)

    fun intent(context: Context): Intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) { "no launcher activity" }
}
