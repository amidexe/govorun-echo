package com.govorun.echo.ui

import com.govorun.echo.notes.NoteStore
import java.time.LocalDate

/** Activity is historical; library metrics describe the notes kept now, including imports and archive. */
internal data class EchoStats(
    val notes: Int,
    val words: Int,
    val streak: Int,
    val bestStreak: Int,
    /** Milliseconds said on each of the last seven days, the oldest first, today last. */
    val week: List<Pair<LocalDate, Long>>,
    /** Said a day on the days anything was said. */
    val perDayMs: Long,
    /** Said over all the diary's days. */
    val spokenMs: Long,
    val libraryMs: Long,
    val longestMs: Long,
    val spokeToday: Boolean,
) {
    companion object {
        fun of(
            notes: List<NoteStore.Entry>,
            diary: Map<LocalDate, com.govorun.echo.notes.ActivityDiary.Day>,
            today: LocalDate = LocalDate.now(),
        ): EchoStats {
            val days = diary.filterValues { it.notes > 0 || it.ms > 0 }.keys.toSortedSet()
            // Days in a row with a note, up to today (or yesterday, so a day not yet spoken does not break it).
            var streak = 0
            var day = if (today in days) today else today.minusDays(1)
            while (day in days) {
                streak++
                day = day.minusDays(1)
            }
            var best = 0
            var run = 0
            var previous: LocalDate? = null
            for (d in days) {
                run = if (previous != null && previous.plusDays(1) == d) run + 1 else 1
                best = maxOf(best, run)
                previous = d
            }
            val byDay = diary.mapValues { it.value.ms }.filterValues { it > 0 }
            val week = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it] ?: 0L) }
            return EchoStats(
                notes = notes.size,
                words = notes.sumOf { n -> n.text.split(Regex("\\s+")).count { it.isNotBlank() } },
                streak = streak,
                bestStreak = best,
                week = week,
                perDayMs = if (byDay.isEmpty()) 0 else byDay.values.sum() / byDay.size,
                spokenMs = byDay.values.sum(),
                libraryMs = notes.sumOf { it.durationMs.coerceAtLeast(0) },
                longestMs = (notes.maxOfOrNull { it.durationMs } ?: 0).coerceAtLeast(0),
                spokeToday = today in days,
            )
        }
    }
}

