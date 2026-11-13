package com.govorun.echo.commands

import android.content.Intent
import android.provider.AlarmClock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/** Validated parameters. A clock request is a handoff; a calendar request yields a provider receipt. */
internal data class CommandPlan(val kind: String, val fields: JSONObject) {
    val title: String get() = fields.getString("title")
    val description: String get() = fields.getString("description")
    val reminderMinutes: Int get() = fields.getInt("reminderMinutes")

    fun calendar(zone: ZoneId): EventCalendars.Event? = runCatching {
        val startLocal = LocalDateTime.of(LocalDate.parse(fields.getString("date")), LocalTime.parse(fields.getString("time")))
        val start = exact(startLocal, zone)
        val endText = fields.getString("end")
        val minutes = fields.getInt("durationMinutes")
        val end = when {
            endText.isNotEmpty() -> exact(LocalDateTime.parse(endText), zone)
            minutes > 0 -> start.plusSeconds(minutes * 60L)
            else -> start
        }
        require(end >= start)
        EventCalendars.Event(title, description, start, end, zone.id, false, reminderMinutes, kind == "reminder")
    }.getOrNull()

    fun clock(reference: Instant, now: Instant, zone: ZoneId): Intent? = runCatching {
        if (kind == "timer") {
            return@runCatching ClockTimer(fields.getInt("durationSeconds"), title).intent()
        }
        require(kind == "alarm")
        val time = LocalTime.parse(fields.getString("time"))
        val days = fields.getJSONArray("alarmDays")
        val today = now.atZone(zone).toLocalDate()
        val next = if (time > now.atZone(zone).toLocalTime()) today else today.plusDays(1)
        val atCapture = reference.atZone(zone)
        val intended = if (time > atCapture.toLocalTime()) atCapture.toLocalDate() else atCapture.toLocalDate().plusDays(1)
        require(days.length() > 0 || intended == next)
        val date = fields.getString("date")
        require(date.isEmpty() || (days.length() == 0 && LocalDate.parse(date) == next))
        ClockTime(time, title).intent().apply {
            if (days.length() > 0) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList((0 until days.length()).map { days.getInt(it) }))
        }
    }.getOrNull()

    fun detail(zone: ZoneId): String = when (kind) {
        "timer" -> {
            val seconds = fields.getInt("durationSeconds")
            com.govorun.echo.formatDuration(seconds * 1000L)
        }
        "alarm" -> fields.getString("time")
        else -> calendar(zone)?.let { event ->
            val start = event.start.atZone(zone)
            val finish = event.end.atZone(zone)
            val format = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, HH:mm", Locale.getDefault())
            val ending = if (finish.toLocalDate() == start.toLocalDate()) "HH:mm" else "EEE, d MMM HH:mm"
            format.format(start) + if (event.end > event.start) " – " + DateTimeFormatter.ofPattern(ending, Locale.getDefault()).format(finish) else ""
        }.orEmpty()
    }

    companion object {
        private fun exact(local: LocalDateTime, zone: ZoneId): Instant {
            val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: error("ambiguous time")
            return local.toInstant(offset)
        }
        fun parse(answer: String, expected: String): CommandPlan? = runCatching {
            val f = JSONObject(answer)
            require(f.getString("kind") == expected && f.getString("status") == "ready")
            require(f.getString("title").codePointCount(0, f.getString("title").length) <= 160)
            require(f.getString("description").length <= 800)
            require(f.getInt("durationSeconds") in 0..604800 && f.getInt("durationMinutes") in 0..44640)
            require(f.getInt("reminderMinutes") in -1..44640)
            require(f.getString("end").isEmpty() || f.getInt("durationMinutes") == 0)
            val days = f.getJSONArray("alarmDays")
            val values = (0 until days.length()).map { days.getInt(it) }
            require(values.size <= 7 && values.toSet().size == values.size && values.all { it in 1..7 })
            if (expected == "timer") require(f.getInt("durationSeconds") > 0)
            if (expected in setOf("calendar", "reminder")) require(f.getString("title").isNotBlank() && days.length() == 0)
            CommandPlan(expected, f)
        }.getOrNull()
    }
}
