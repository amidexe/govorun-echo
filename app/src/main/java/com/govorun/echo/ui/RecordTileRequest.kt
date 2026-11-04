package com.govorun.echo.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.widget.Toast
import com.govorun.echo.R
import com.govorun.echo.record.RecordTileService

/** Feedback for this request only; it is never a persistent inventory of the user's tiles. */
internal enum class TileRequestOutcome(val message: Int?) {
    Added(R.string.tile_request_added),
    AlreadyAdded(R.string.tile_request_already_added),
    // Cancellation and Android's automatic denial say nothing reliable about tile presence.
    NotAdded(null),
    Failed(R.string.tile_request_failed);

    companion object {
        fun fromPlatform(result: Int): TileRequestOutcome = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> Added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> AlreadyAdded
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> NotAdded
            else -> Failed
        }
    }
}

/** Offer the system dialog only on a tap, even if this tile was added or removed before. */
internal fun Activity.requestRecordTile() {
    fun finish(outcome: TileRequestOutcome) {
        outcome.message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }
    val manager = getSystemService(StatusBarManager::class.java)
    if (manager == null) {
        finish(TileRequestOutcome.Failed)
        return
    }
    try {
        manager.requestAddTileService(
            ComponentName(this, RecordTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_action_record),
            mainExecutor,
        ) { finish(TileRequestOutcome.fromPlatform(it)) }
    } catch (_: IllegalArgumentException) {
        finish(TileRequestOutcome.Failed)
    } catch (_: SecurityException) {
        finish(TileRequestOutcome.Failed)
    }
}
