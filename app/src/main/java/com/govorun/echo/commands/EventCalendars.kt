package com.govorun.echo.commands

import android.Manifest
import android.content.ContentValues
import android.content.ContentProviderOperation
import android.provider.CalendarContract
import android.provider.CalendarContract.Reminders
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONArray

internal object EventCalendars {
    const val TARGET = "calendar"
    val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    data class Target(val id: Long, val name: String, val account: String, val key: String, val maximumReminders: Int = 0, val reminderMethods: String = "")
    data class Event(val title: String, val description: String, val start: Instant, val end: Instant, val zone: String, val allDay: Boolean, val reminderMinutes: Int = -1, val free: Boolean = false)

    fun allowed(context: Context): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun writable(context: Context): List<Target> {
        if (!allowed(context)) return emptyList()
        val columns = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.NAME, Calendars.MAX_REMINDERS, Calendars.ALLOWED_REMINDERS)
        return try {
            context.contentResolver.query(
                Calendars.CONTENT_URI, columns,
                "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                Calendars.CALENDAR_DISPLAY_NAME,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val account = cursor.getString(2).orEmpty()
                        // Reject reused row ids without persisting an email or account name in the command.
                        val identity = JSONArray().put(id).put(account).put(cursor.getString(3)).put(cursor.getString(4)).toString()
                        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                        val key = digest.joinToString("") { "%02x".format(it) }
                        val name = cursor.getString(1).orEmpty().ifBlank { cursor.getString(4).orEmpty() }.ifBlank { id.toString() }
                        add(Target(id, name, account, key, cursor.getInt(5), cursor.getString(6).orEmpty()))
                    }
                }
            }.orEmpty()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    /** A retry after insertion finds the same provider row, including after a process restart. */
    @Synchronized
    fun write(context: Context, key: String, noteId: String, event: Event): Boolean {
        val target = writable(context).firstOrNull { it.key == key } ?: return false
        val resolver = context.contentResolver
        val uid = uid(noteId, key)
        return try {
            val exists = alreadyWritten(context, target, noteId) ?: return false
            if (exists) return true
            val methods = target.reminderMethods.split(',').mapNotNull { it.trim().toIntOrNull() }
            val method = if (Reminders.METHOD_ALERT in methods) Reminders.METHOD_ALERT else Reminders.METHOD_DEFAULT
            if (event.reminderMinutes >= 0 && (target.maximumReminders < 1 || (methods.isNotEmpty() && method !in methods))) return false
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, target.id)
                put(Events.UID_2445, uid)
                put(Events.TITLE, event.title)
                put(Events.DESCRIPTION, event.description)
                put(Events.DTSTART, event.start.toEpochMilli())
                put(Events.DTEND, event.end.toEpochMilli())
                put(Events.EVENT_TIMEZONE, event.zone)
                put(Events.ALL_DAY, if (event.allDay) 1 else 0)
                put(Events.AVAILABILITY, if (event.free) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
                put(Events.HAS_ALARM, if (event.reminderMinutes >= 0) 1 else 0)
            }
            val operations = arrayListOf(ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values).build())
            if (event.reminderMinutes >= 0) operations += ContentProviderOperation.newInsert(Reminders.CONTENT_URI)
                .withValueBackReference(Reminders.EVENT_ID, 0)
                .withValue(Reminders.MINUTES, event.reminderMinutes)
                .withValue(Reminders.METHOD, method).build()
            resolver.applyBatch(CalendarContract.AUTHORITY, operations).firstOrNull()?.uri != null

        } catch (_: Exception) {
            false
        }
    }

    fun alreadyWritten(context: Context, target: Target, noteId: String): Boolean? =
        try {
            context.contentResolver.query(
                Events.CONTENT_URI, arrayOf(Events._ID),
                "${Events.CALENDAR_ID} = ? AND ${Events.UID_2445} = ?",
                arrayOf(target.id.toString(), uid(noteId, target.key)), null,
            )?.use { it.moveToFirst() }
        } catch (_: RuntimeException) {
            null
        }

    data class SavedEvent(val id: Long, val startMs: Long, val endMs: Long)

    /** The provider is authoritative even when the acknowledgement was lost locally. */
    fun savedEvent(context: Context, key: String, noteId: String): SavedEvent? = runCatching {
        val target = writable(context).firstOrNull { it.key == key } ?: return@runCatching null
        context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.DTSTART, Events.DTEND),
            "${Events.CALENDAR_ID} = ? AND ${Events.UID_2445} = ?", arrayOf(target.id.toString(), uid(noteId, key)), null)
            ?.use { if (it.moveToFirst()) SavedEvent(it.getLong(0), it.getLong(1), it.getLong(2)) else null }
    }.getOrNull()

    fun eventId(context: Context, key: String, noteId: String): Long? = savedEvent(context, key, noteId)?.id

    private fun uid(noteId: String, key: String): String =
        UUID.nameUUIDFromBytes("event:$noteId:$key".toByteArray(Charsets.UTF_8)).toString()
}
