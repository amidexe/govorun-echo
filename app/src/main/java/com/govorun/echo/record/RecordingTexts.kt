package com.govorun.echo.record

import android.content.Context
import com.govorun.echo.R

/** How a recording is told about in the journal: its length and why it stopped. */
internal object RecordingTexts {
    /** "5 min 20 s" for the journal. */
    fun span(
        context: Context,
        ms: Long,
    ): String {
        val total = ms / 1_000
        return if (total >= 60) context.getString(R.string.journal_span_min, (total / 60).toInt(), (total % 60).toInt()) else context.getString(R.string.journal_span_sec, total.toInt())
    }

    fun stop(
        context: Context,
        reason: String,
    ): String? =
        when (reason) {
            "silence" -> context.getString(R.string.stop_silence, silenceSpan(context, com.govorun.echo.AppSettings.silenceStopMinutes(context)))
            "battery" -> context.getString(R.string.stop_battery)
            "storage" -> context.getString(R.string.stop_storage)
            "cap" -> context.getString(R.string.stop_cap)
            "cancel" -> context.getString(R.string.stop_cancel)
            else -> null
        }

    /** A recognition event ("busy", "file_start:<ms>", ...) in words, or null for one the journal does not show. */
    fun recognitionEvent(
        context: Context,
        event: String,
    ): String? {
        val parts = event.split(":")
        return when (parts[0]) {
            "busy" -> context.getString(R.string.journal_govorun_busy)
            "unreachable" -> context.getString(R.string.journal_govorun_unreachable)
            "file_start" -> context.getString(R.string.journal_file_start, span(context, parts.getOrNull(1)?.toLongOrNull() ?: 0L))
            "file_done" -> context.getString(R.string.journal_file_done, span(context, parts.getOrNull(1)?.toLongOrNull() ?: 0L), parts.getOrNull(2)?.toIntOrNull() ?: 0)
            "live_done" -> context.getString(R.string.journal_live_done, parts.getOrNull(1)?.toIntOrNull() ?: 0)
            else -> null
        }
    }

    /** The lines the journal gets when a recording stops: how long, why, how long it was silenced, how long it paused. */
    fun stopped(
        context: Context,
        durationMs: Long,
        reason: String,
        silencedMs: Long,
        pausedMs: Long,
    ): List<String> =
        listOfNotNull(
            context.getString(R.string.journal_rec_stopped, span(context, durationMs), stop(context, reason) ?: context.getString(R.string.journal_by_user)) +
                (if (silencedMs >= 2_000) context.getString(R.string.journal_silenced, (silencedMs / 1_000).toInt()) else ""),
            if (pausedMs >= 1_000) context.getString(R.string.journal_dictation_pause, (pausedMs / 1_000).toInt()) else null,
        )

    /** "30 минут", "час", "2 часа": how long the silence was. */
    fun silenceSpan(
        context: Context,
        minutes: Int,
    ): String =
        when {
            minutes % 60 != 0 -> context.resources.getQuantityString(R.plurals.minutes, minutes, minutes)
            minutes == 60 -> context.getString(R.string.silence_hour)
            else -> context.resources.getQuantityString(R.plurals.hours, minutes / 60, minutes / 60)
        }
}
