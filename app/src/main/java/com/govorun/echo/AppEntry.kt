package com.govorun.echo

import android.content.Context
import android.content.Intent

/**
 * Echo's front door, as the launcher opens it: what a notification, the tile, a share or a shortcut needs to bring the app up.
 * It comes from the manifest, so the services and activities that open the app do not depend on its screens.
 */
internal object AppEntry {
    const val NOTE_EXTRA = "com.govorun.echo" + ".extra.OPEN_NOTE"

    fun note(context: Context, noteId: String, byUser: Boolean = true): Intent = intent(context)
        .putExtra("echo_auto_open", !byUser).putExtra(NOTE_EXTRA, noteId)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    const val COMMAND_EXTRA = "echo_open_command"
    fun command(context: Context, id: String): Intent = intent(context).putExtra(COMMAND_EXTRA, id).setIdentifier("command:$id")
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun recording(context: Context, existingOnly: Boolean = false, purpose: CapturePurpose = CapturePurpose.NOTE): Intent = Intent("com.govorun.echo.RECORD_FROM_TILE")
        .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra("recording_existing_only", existingOnly)
        .putExtra(CapturePurpose.FIELD, purpose.id)

    // The panel owns a separate task so closing it returns to the open screen in Echo.
    fun commandCapture(context: Context): Intent = recording(context, purpose = CapturePurpose.COMMAND)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    const val SHORTCUT_COMMAND_ACTION = "com.govorun.echo.SAY_COMMAND"
    const val TILE_RECORDING_EXTRA = "echo_tile_recording"
    const val LOCKED_SHADE_RECORDING_EXTRA = "echo_locked_shade_recording"
    const val WIDGET_RECORDING_EXTRA = "echo_widget_recording"

    fun intent(context: Context): Intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) { "no launcher activity" }
}
