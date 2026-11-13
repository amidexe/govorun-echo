package com.govorun.echo.commands

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.govorun.echo.R
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import org.json.JSONObject

data class AndroidCommand(val kind: Kind, val text: String) {
    enum class Kind(val id: String, val sample: Int, val button: Int, val invalid: Int, val preview: Int) {
        TIMER("timer", R.string.commands_timer_test_duration, R.string.commands_timer_test, R.string.commands_timer_invalid, R.string.commands_timer_preview),
        ALARM("alarm", R.string.commands_alarm_example, R.string.commands_alarm_test, R.string.commands_alarm_invalid, R.string.commands_alarm_preview),
        EVENT("event", R.string.commands_event_example, R.string.commands_event_test, R.string.commands_title_required, R.string.commands_event_preview),
        EVENT_DRAFT("event_draft", R.string.commands_event_draft_example, R.string.commands_calendar_test, R.string.commands_event_draft_incomplete, R.string.commands_event_preview),
        REMINDER("reminder", R.string.commands_reminder_example, R.string.commands_reminder_test, R.string.commands_title_required, R.string.commands_reminder_preview),
    }

    internal fun transcript(vars: Map<String, String>): String = Template.render(text, vars).trim()

    internal fun prepare(vars: Map<String, String>, now: ZonedDateTime = ZonedDateTime.now()): Prepared? {
        val value = transcript(vars)
        return when (kind) {
            Kind.TIMER -> ClockTimer.parse(value)?.let { timer ->
                Prepared(timer.seconds, timer.label, timer::intent)
            }
            Kind.ALARM -> ClockTime.parse(value)?.let { alarm ->
                Prepared(alarm.display, alarm.title) { alarm.intent() }
            }
            Kind.EVENT -> value.takeIf(String::isNotBlank)?.let { title ->
                Prepared(title, "") {
                    Intent(Intent.ACTION_INSERT).setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.item/event")
                        .putExtra(CalendarContract.Events.TITLE, title)
                }
            }
            Kind.EVENT_DRAFT -> null // Needs the Smart API; the settings preview is local.
            Kind.REMINDER -> prepareReminder(value, now)
        }
    }

    private fun prepareReminder(value: String, now: ZonedDateTime): Prepared? {
        if (value.isBlank()) return null
        val day = reminderDay.find(value) ?: return reminder(value)
        val clock = ClockTime.parse(value.substring(day.range.last + 1)) ?: return reminder(value)
        if (clock.title.isBlank()) return null
        val offset = when (day.groupValues[1].lowercase(Locale.ROOT)) {
            "завтра" -> 1L
            "послезавтра" -> 2L
            else -> 0L
        }
        val local = now.toLocalDate().plusDays(offset).atTime(clock.time)
        val zoneOffset = now.zone.rules.getValidOffsets(local).singleOrNull() ?: return reminder(value)
        val date = ZonedDateTime.ofStrict(local, zoneOffset, now.zone)
        if (!date.isAfter(now)) return reminder(value)
        return reminder(clock.title, date, "${day.groupValues[1]} ${clock.display}")
    }

    internal data class Prepared(val summary: Any, val detail: String, val intent: () -> Intent)

    companion object {
        private val reminderDay = Regex("^(сегодня|завтра|послезавтра)\\s+в\\s+", RegexOption.IGNORE_CASE)

        internal fun fromJson(action: JSONObject): AndroidCommand? {
            val spec = action.optJSONObject("android")
            if (spec == null) return action.optString("timer").takeIf(String::isNotBlank)?.let { AndroidCommand(Kind.TIMER, it) }
            val kind = Kind.entries.firstOrNull { it.id == spec.optString("kind") }
            requireNotNull(kind) { "Неизвестное действие Android" }
            val text = spec.optString("text")
            require(text.isNotBlank()) { "У действия Android нет text" }
            return AndroidCommand(kind, text)
        }

        private fun reminder(title: String, date: ZonedDateTime? = null, display: String = ""): Prepared = Prepared(title, display) {
            Intent(Intent.ACTION_CREATE_REMINDER).putExtra(Intent.EXTRA_TITLE, title).apply {
                if (date != null) putExtra(Intent.EXTRA_TIME, date.toInstant().toEpochMilli())
            }
        }

        internal fun launch(context: Context, intent: Intent, noteId: String, allowDeferred: Boolean = true): Runner.Result {
            val process = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
            val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            val clockWithoutScreen = (intent.action == AlarmClock.ACTION_SET_TIMER || intent.action == AlarmClock.ACTION_SET_ALARM) &&
                intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if ((locked && !clockWithoutScreen) || (process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && !com.govorun.echo.CaptureWindows.visible)) {
                if (!allowDeferred) return Runner.Result(false, 0, null, context.getString(R.string.commands_smart_visible))
                if (intent.resolveActivity(context.packageManager) == null) {
                    return Runner.Result(false, 0, null, context.getString(R.string.commands_android_unavailable))
                }
                val deferred = Notice.offerOpen(context, noteId, intent)
                val message = if (deferred) R.string.commands_android_deferred else R.string.commands_android_background
                return Runner.Result(false, 0, null, context.getString(message), deferred = deferred)
            }
            Notice.cancelOpen(context, noteId)
            val error = openNow(context, intent)
            return Runner.Result(error == null, 0, null, error)
        }

        internal fun openNow(context: Context, intent: Intent): String? {
            return try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (_: ActivityNotFoundException) {
                context.getString(R.string.commands_android_unavailable)
            } catch (_: SecurityException) {
                context.getString(R.string.commands_android_unavailable)
            }
        }
    }
}

internal data class ClockTime(val time: LocalTime, val title: String) {
    val display: String get() = "%02d:%02d".format(Locale.ROOT, time.hour, time.minute)

    fun intent(): Intent = Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        .apply { if (title.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, title) }

    companion object {
        private val pattern = Regex(
            """^(?:(?:на|в)\s+)?(\d{1,2}|[а-яё]+)(?:(?:\s*[:.]\s*|\s+)(\d{2}))?(?:\s+час(?:а|ов)?)?(?:\s+(утра|дня|вечера|ночи))?(?=$|[\s,.!?—-]|:(?!\s*\d))""",
            RegexOption.IGNORE_CASE,
        )

        fun parse(value: String): ClockTime? {
            val match = pattern.find(value) ?: return null
            val hour = ClockTimer.amount(match.groupValues[1].lowercase(Locale.ROOT)) ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val period = match.groupValues[3].lowercase(Locale.ROOT)
            if (hour !in 0..23 || minute !in 0..59 || (period.isNotEmpty() && hour !in 1..12)) return null
            val adjusted = when (period) {
                "дня", "вечера" -> hour % 12 + 12
                "утра", "ночи" -> hour % 12
                else -> hour
            }
            val suffix = value.substring(match.range.last + 1).trim()
            if (suffix.startsWith('.') && suffix.getOrNull(1)?.isDigit() == true) return null
            val title = suffix.trim(' ', '\t', ',', '.', ':', '!', '?', '—', '-')
            if (suffix.firstOrNull()?.isDigit() == true) return null
            if (suffix.firstOrNull()?.isLetter() == true) {
                val first = title.substringBefore(' ').lowercase(Locale.ROOT)
                if (ClockTimer.amount(first) != null || title.startsWith("с половиной", ignoreCase = true)) return null
            }
            return ClockTime(LocalTime.of(adjusted, minute), title)
        }
    }
}
