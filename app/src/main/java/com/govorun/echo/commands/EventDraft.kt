package com.govorun.echo.commands

import android.content.Context
import android.util.Log
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.smart.GovorunSmart
import com.govorun.echo.smart.Smart
import com.govorun.echo.smart.TasksChanged
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

internal object EventDraft {
    const val MAX_INPUT = 1000
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val endFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun send(context: Context, transcript: String, noteId: String, capturedAtMs: Long?, target: String): Runner.Result {
        fun failure(message: Int) = Runner.Result(false, 0, null, context.getString(message))

        if (!EventCalendars.allowed(context)) return failure(R.string.commands_calendar_permission)
        val calendar = EventCalendars.writable(context).firstOrNull { it.key == target }
            ?: return failure(R.string.commands_calendar_target_required)
        if (transcript.isBlank() || capturedAtMs == null || capturedAtMs <= 0) {
            return failure(R.string.commands_event_draft_incomplete)
        }
        when (EventCalendars.alreadyWritten(context, calendar, noteId)) {
            true -> return Runner.Result(true, 0, null, null)
            null -> return failure(R.string.commands_calendar_write_failed)
            false -> Unit
        }
        val zone = ZoneId.systemDefault()
        val capture = Instant.ofEpochMilli(capturedAtMs).atZone(zone)
        val input = JSONObject()
            .put("transcript", transcript)
            .put("capturedAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(capture))
            .put("timeZone", zone.id)
            .toString()
        if (input.codePointCount(0, input.length) > MAX_INPUT) {
            return Runner.Result(false, 0, null, context.getString(R.string.commands_event_draft_too_long, MAX_INPUT))
        }
        if (!Smart.enabled()) return failure(R.string.commands_event_draft_unavailable)
        val smart = GovorunSmart(context)
        val taskId = UUID.nameUUIDFromBytes("event.draft:$noteId:$input".toByteArray(Charsets.UTF_8)).toString()
        val answer = runBlocking {
            val status = smart.status()
            if (!status.ready) return@runBlocking null
            try {
                val result = withTimeoutOrNull(60_000) { fetch(smart, taskId, input) }
                if (result == null) smart.cancel(taskId)
                result
            } catch (refusal: GovorunSmart.Refusal) {
                Log.i("EchoEventDraft", "event=refused code=${refusal.code}")
                // This command has no background consumer. Keep the note and let the person retry.
                if (refusal.code in setOf(GovorunSmart.QUEUED, GovorunSmart.TIMEOUT, GovorunSmart.LOST)) smart.cancel(taskId)
                null
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        } ?: return failure(R.string.commands_event_draft_unavailable)
        val event = prepare(answer, zone) ?: return failure(R.string.commands_event_draft_incomplete)
        if (!NoteStore.noteDir(context, noteId).isDirectory) return failure(R.string.commands_event_draft_unavailable)
        return if (EventCalendars.write(context, target, noteId, event)) Runner.Result(true, 0, null, null)
        else failure(R.string.commands_calendar_write_failed)
    }

    internal suspend fun fetch(smart: GovorunSmart, taskId: String, input: String, operation: String = "event.draft"): String = coroutineScope {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            TasksChanged.results.filter { it == taskId }.collect { changed.trySend(Unit) }
        }
        try {
            try {
                return@coroutineScope smart.textTask(operation, input, "note", taskId, asked = true)
            } catch (refusal: GovorunSmart.Refusal) {
                if (!refusal.waitingForWorker()) throw refusal
            }
            // The background worker may need Android's permission to start. Read its saved answer
            // on the existing change signal, under the same id, without submitting the text again.
            var answer: String? = null
            while (answer == null) {
                try {
                    answer = JSONObject(smart.collect(taskId)).getString("text")
                } catch (refusal: GovorunSmart.Refusal) {
                    if (!refusal.waitingForWorker()) throw refusal
                    changed.receive()
                }
            }
            answer
        } finally {
            listener.cancel()
        }
    }

    private fun GovorunSmart.Refusal.waitingForWorker(): Boolean =
        code == GovorunSmart.QUEUED && (wait == null || wait == "busy")

    fun checkCalendar(context: Context, target: String): Runner.Result {
        val error = when {
            !EventCalendars.allowed(context) -> R.string.commands_calendar_permission
            EventCalendars.writable(context).none { it.key == target } -> R.string.commands_calendar_target_required
            else -> null
        }
        return Runner.Result(error == null, 0, null, error?.let { context.getString(it) })
    }

    internal fun prepare(answer: String, zone: ZoneId): EventCalendars.Event? {
        return try {
            val fields = JSONObject(answer)
            val title = fields.getString("title").trim()
            val description = fields.getString("description").trim()
            if (title.isBlank() || title.codePointCount(0, title.length) > 160 ||
                description.codePointCount(0, description.length) > MAX_INPUT
            ) return null
            val date = LocalDate.parse(fields.getString("date"))
            val allDay = fields.getBoolean("allDay")
            val endText = fields.getString("end")
            val duration = fields.getInt("durationMinutes")
            if (duration !in 0..(31 * 24 * 60) || (endText.isNotEmpty() && duration != 0)) return null
            val start = if (allDay) {
                if (fields.getString("time").isNotEmpty() || endText.isNotEmpty() || duration != 0) return null
                date.atStartOfDay(ZoneOffset.UTC).toInstant()
            } else {
                val time = LocalTime.parse(fields.getString("time"), clockFormat)
                exactInstant(date.atTime(time), zone) ?: return null
            }
            val end = when {
                allDay -> start.plusSeconds(86400)
                endText.isNotEmpty() -> exactInstant(LocalDateTime.parse(endText, endFormat), zone) ?: return null
                duration > 0 -> start.plusSeconds(duration * 60L)
                else -> start
            }
            if (end < start) return null
            EventCalendars.Event(title, description, start, end, if (allDay) "UTC" else zone.id, allDay)
        } catch (_: DateTimeException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private fun exactInstant(time: LocalDateTime, zone: ZoneId): Instant? =
        zone.rules.getValidOffsets(time).singleOrNull()?.let(time::toInstant)

}
