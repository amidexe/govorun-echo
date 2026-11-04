package com.govorun.echo.notes

import android.content.Context
import com.govorun.echo.AppSettings
import org.json.JSONObject
import java.io.File

/**
 * 0.1.54 wrote a name made from a note's own words into every old note without one, marked `titleAuto`. The owner found such names
 * meaningless (22.10.2026): a note has a name only when its owner types one, and names by meaning are left to the smart functions.
 * So the automatic names are taken back once. A name typed by hand carries no mark and stays.
 */
object AutoTitles {
    /** Once on the first start of a version without automatic names; later starts do nothing. */
    fun clearOnce(context: Context) {
        if (AppSettings.autoTitlesCleared(context)) return
        clear(NoteStore.root(context))
        AppSettings.setAutoTitlesCleared(context)
    }

    /** Takes the automatic name off every note that has one; returns how many lost it. */
    fun clear(root: File): Int {
        var cleared = 0
        root.listFiles { file -> file.isDirectory }.orEmpty().forEach { dir ->
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return@forEach
            if (!meta.optBoolean("titleAuto")) return@forEach
            NoteStore.updateMeta(dir) {
                remove("title")
                remove("titleAuto")
            }
            cleared++
        }
        return cleared
    }
}
