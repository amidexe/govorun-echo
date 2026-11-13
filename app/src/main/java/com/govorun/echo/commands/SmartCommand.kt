package com.govorun.echo.commands

import android.content.Context
import com.govorun.echo.NetworkPolicy
import com.govorun.echo.R
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.smart.GovorunSmart
import com.govorun.echo.smart.Smart
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal object SmartCommand {
    fun kind(action: AndroidCommand.Kind): String = when (action) {
        AndroidCommand.Kind.TIMER -> "timer"
        AndroidCommand.Kind.ALARM -> "alarm"
        AndroidCommand.Kind.REMINDER -> "reminder"
        else -> "calendar"
    }

    fun send(context: Context, command: AndroidCommand, vars: Map<String, String>, capturedAtMs: Long?, checkScreenLock: () -> Unit): Runner.Result {
        val id = vars["note_id"].orEmpty()
        val dir = NoteStore.noteDir(context, id)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
            ?: return failure(context, R.string.commands_smart_failed)
        if (NetworkPolicy.state.value.reason != null) return failure(context, R.string.commands_smart_offline)
        val receipt = meta.optJSONObject("command")
        val referenceMs = receipt?.optLong("requestedAtMs")?.takeIf { it > 0 } ?: capturedAtMs ?: 0L
        val expiresAtMs = receipt?.optLong("expiresAtMs")?.takeIf { it > referenceMs }
            ?: (referenceMs + CommandPolicy.current().timeoutSeconds * 1_000L)
        fun expired(): Boolean = System.currentTimeMillis() >= expiresAtMs
        if (referenceMs <= 0 || referenceMs > System.currentTimeMillis() || expired()) {
            return failure(context, R.string.commands_smart_expired)
        }
        val policy = CommandPolicy.refresh()
        if (expired()) return failure(context, R.string.commands_smart_expired)
        val transcript = command.transcript(vars)
        val type = kind(command.kind)
        if (transcript.isBlank()) return incomplete(context, type)
        if (transcript.codePointCount(0, transcript.length) > policy.maximumCharacters || NoteStore.durationOf(dir) > policy.maximumRecordingSeconds * 1000L) {
            return Runner.Result(false, 0, null, context.getString(R.string.commands_smart_limits, policy.maximumCharacters, policy.maximumRecordingSeconds))
        }
        if (!Smart.enabled()) return failure(context, R.string.smart_state_disabled)
        val target = vars["action.${EventCalendars.TARGET}"].orEmpty()
        if (type in setOf("calendar", "reminder")) {
            if (!EventCalendars.allowed(context)) return failure(context, R.string.commands_calendar_permission)
            val calendar = EventCalendars.writable(context).firstOrNull { it.key == target }
                ?: return failure(context, R.string.commands_calendar_target_required)
            if (EventCalendars.alreadyWritten(context, calendar, id) == true) {
                rememberEvent(context, dir, target, id)
                return Runner.Result(true, 0, null, null)
            }
        }
        val zone = ZoneId.systemDefault()
        val input = JSONObject().put("kind", type).put("transcript", transcript)
            .put("capturedAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(referenceMs).atZone(zone)))
            .put("timeZone", zone.id).toString()
        val taskId = UUID.nameUUIDFromBytes("command.parse:$id:$referenceMs".toByteArray()).toString()
        val smart = GovorunSmart(context)
        var refusalCode: String? = null
        var refusalWords = ""
        val answer = runBlocking {
            try {
                val remaining = expiresAtMs - System.currentTimeMillis()
                withTimeoutOrNull(remaining) {
                    val status = smart.status()
                    if (!status.ready) throw GovorunSmart.Refusal(status.state)
                    checkScreenLock()
                    EventDraft.fetch(smart, taskId, input, "command.parse")
                }
            } catch (refusal: GovorunSmart.Refusal) {
                refusalCode = refusal.code
                refusalWords = refusal.words
                null
            } catch (_: java.io.IOException) { null }
            finally { runCatching { smart.cancel(taskId) } }
        } ?: return when {
            expired() -> failure(context, R.string.commands_smart_expired)
            refusalWords.isNotBlank() -> Runner.Result(false, 0, null, refusalWords)
            refusalCode != null -> failure(context, refusalMessage(refusalCode))
            NetworkPolicy.state.value.reason != null -> failure(context, R.string.commands_smart_offline)
            else -> failure(context, R.string.commands_smart_failed)
        }
        if (expired()) return failure(context, R.string.commands_smart_expired)
        if (!dir.isDirectory) return failure(context, R.string.commands_smart_failed)
        val status = runCatching { JSONObject(answer).optString("status") }.getOrDefault("")
        if (status == "unsupported") return failure(context, R.string.commands_smart_unsupported)
        val plan = CommandPlan.parse(answer, type) ?: return incomplete(context, type)
        val execution = Instant.now()
        val latest = runCatching { JSONObject(File(dir, "meta.json").readText()).getJSONObject("command") }.getOrNull()
        if (latest?.optLong("requestedAtMs") != referenceMs) return failure(context, R.string.commands_smart_expired)
        NoteStore.updateMeta(dir) {
            getJSONObject("command").put("parameters", plan.fields).put("detail", plan.detail(zone)).put("phase", "dispatching")
        }
        if (type in setOf("calendar", "reminder")) {
            val event = plan.calendar(zone) ?: return failure(context, R.string.commands_event_draft_incomplete)
            if (!event.start.isAfter(execution)) return failure(context, R.string.commands_time_passed)
            if (expired()) return failure(context, R.string.commands_smart_expired)
            checkScreenLock()
            val saved = EventCalendars.write(context, target, id, event)
            if (saved) rememberEvent(context, dir, target, id)
            return Runner.Result(saved, 0, if (saved) plan.detail(zone) else null, if (saved) null else context.getString(R.string.commands_calendar_write_failed))
        }
        val intent = plan.clock(Instant.ofEpochMilli(referenceMs), execution, zone)
            ?: return failure(context, if (type == "alarm" && plan.fields.optString("date").isNotEmpty()) R.string.commands_alarm_date_unsupported else R.string.commands_time_passed)
        if (expired()) return failure(context, R.string.commands_smart_expired)
        checkScreenLock()
        return AndroidCommand.launch(context, intent, id, allowDeferred = false).copy(reply = plan.detail(zone))
    }

    private fun incomplete(context: Context, type: String): Runner.Result = failure(context, when (type) {
        "timer" -> R.string.commands_timer_incomplete
        "alarm" -> R.string.commands_alarm_incomplete
        else -> R.string.commands_event_draft_incomplete
    })

    private fun rememberEvent(context: Context, dir: File, target: String, id: String) {
        EventCalendars.savedEvent(context, target, id)?.let { event ->
            NoteStore.updateMeta(dir) { getJSONObject("command").put("eventId", event.id)
                .put("eventStartMs", event.startMs).put("eventEndMs", event.endMs) }
        }
    }

    private fun failure(context: Context, message: Int) = Runner.Result(false, 0, null, context.getString(message))

    internal fun refusalMessage(code: String?): Int = when (code) {
        GovorunSmart.NOT_AUTHORIZED, GovorunSmart.SMART_OFF -> R.string.smart_state_not_authorized
        GovorunSmart.NOT_SIGNED_IN -> R.string.smart_state_sign_in
        GovorunSmart.NEEDS_ACCESS -> R.string.smart_state_needs_access
        GovorunSmart.MISSING -> R.string.smart_state_missing
        GovorunSmart.UPDATE -> R.string.smart_state_update
        GovorunSmart.OFFLINE -> R.string.smart_state_offline
        GovorunSmart.NO_NETWORK -> R.string.smart_state_no_network
        GovorunSmart.LIMIT_TODAY -> R.string.smart_state_limit
        GovorunSmart.LIMIT_PERIOD -> R.string.smart_state_limit_period
        GovorunSmart.TIMEOUT -> R.string.commands_smart_failed
        else -> R.string.smart_state_unavailable
    }
}
