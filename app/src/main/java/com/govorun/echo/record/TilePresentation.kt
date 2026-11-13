package com.govorun.echo.record

import android.content.Intent
import android.service.quicksettings.TileService

/** Presentation callbacks for the active app and the native Quick Settings dialog. */
internal object TilePresentation {
    var inApp: ((existingOnly: Boolean, collapse: (Intent) -> Unit) -> Boolean)? = null
    var show: ((TileService, Boolean) -> Boolean)? = null
}
