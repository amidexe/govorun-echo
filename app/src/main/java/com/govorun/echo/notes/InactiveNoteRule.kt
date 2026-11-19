package com.govorun.echo.notes

import android.content.Context
import com.govorun.echo.AppSettings

internal enum class InactiveNoteAction { KEEP, ARCHIVE, TRASH }

internal data class InactiveNoteRule(
    val action: InactiveNoteAction = InactiveNoteAction.KEEP,
    val days: Int = 30,
    val sinceMs: Long = 0L,
) {
    companion object {
        val periods = listOf(7, 14, 30, 90, 365)
        private const val ACTION = "inactive_note_action"
        private const val DAYS = "inactive_note_days"
        private const val SINCE = "inactive_note_since"

        fun read(context: Context): InactiveNoteRule = synchronized(NoteStore) {
            val values = AppSettings.prefs(context)
            val saved = values.getString(ACTION, null)
            if (saved != null) {
                val action = InactiveNoteAction.entries.find { it.name == saved } ?: InactiveNoteAction.KEEP
                val days = values.getInt(DAYS, 30)
                val since = values.getLong(SINCE, 0L)
                return@synchronized if (days in periods && since > 0L) InactiveNoteRule(action, days, since)
                    else InactiveNoteRule()
            }

            // Older builds did not track last use. Start a full period at the time of the upgrade.
            val oldDays = values.getInt("retention_days", 0)
            when {
                values.getBoolean("auto_archive", false) -> save(context, InactiveNoteAction.ARCHIVE, 30)
                oldDays in listOf(30, 90, 365) -> save(context, InactiveNoteAction.TRASH, oldDays)
                else -> InactiveNoteRule()
            }
        }

        fun save(context: Context, action: InactiveNoteAction, days: Int): InactiveNoteRule = synchronized(NoteStore) {
            require(days in periods)
            val rule = InactiveNoteRule(action, days, System.currentTimeMillis())
            AppSettings.prefs(context).edit().putString(ACTION, action.name).putInt(DAYS, days)
                .putLong(SINCE, rule.sinceMs).remove("auto_archive").remove("retention_days").apply()
            rule
        }
    }
}
