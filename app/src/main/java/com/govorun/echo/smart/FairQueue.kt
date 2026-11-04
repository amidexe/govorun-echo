package com.govorun.echo.smart

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.govorun.echo.notes.NoteFair
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Notes Echo has asked Govorun for: their итоги ([ITOGI]: by themselves when a note is finished, or by the button) and their
 * «Текст» ([FAIR]: by the button only). Govorun keeps every task and tries it again by itself when what stopped it is gone (a
 * network, its server, a new day, the person's step in Govorun), as it does for recognition (protocol 2): Echo keeps no retries
 * of its own. A note stays here from the hand-in until Govorun gives its answer or a final refusal, with Govorun's words for why
 * it waits. Govorun says when a task changed ([TasksChanged]); Echo also asks for its list when Echo or a note is opened ([sync]),
 * collects what is answered under the same task id. Missing results stop visibly; collection never submits again. The file holds note
 * ids, kinds, styles, task ids and Govorun's words, never a text.
 */
internal object FairQueue {
    data class Entry(
        val noteId: String,
        val style: String?,
        val kind: String = FAIR,
        /** The person pressed it; false: Echo's own итоги of a finished note. */
        val asked: Boolean = true,
        /** The note's «Текст» as it stands is improved, not its transcript. */
        val layer: Boolean = false,
        /** When it is handed in (итоги made again wait for the note to rest); [HELD] once Govorun keeps it or cannot be reached. */
        val atMs: Long = 0,
        /** Govorun's task id once handed in. */
        val taskId: String? = null,
        /** Why it waits: Govorun's code, or Echo's own when Govorun could not be reached ([UNREACHABLE]). */
        val wait: String? = null,
        /** Govorun's words for the wait, shown as they are. */
        val message: String = "",
        /** Latest final refusal for this note, until a new ask or success; never a retry timer. */
        val failed: Boolean = false,
        val draftId: String? = null,
    ) {
        val held: Boolean get() = atMs == HELD
        val key: String get() = key(kind, noteId)
    }

    const val FAIR = "fair"
    const val ITOGI = "itogi"

    fun key(
        kind: String,
        noteId: String,
    ) = "$kind:$noteId"

    const val HELD = Long.MAX_VALUE

    /** The note is gone, or has fresh итоги already. */
    const val GONE = "gone"

    /** [SmartFair]'s and [SmartItogi]'s own: the note's text changed while Govorun worked; Govorun's answer did not read as a note. */
    const val CHANGED = "changed"
    const val MODEL = "model"

    /** The note has no text yet: recognition is still on it. */
    const val RECOGNIZING = "recognizing"

    /** Govorun could not be asked at all: the notes wait for Echo or a note to be opened, then go to Govorun. */
    val UNREACHABLE = setOf(GovorunSmart.MISSING, GovorunSmart.UPDATE, GovorunSmart.UNAVAILABLE, GovorunSmart.LOST)

    /** Local prerequisites: retry after the person's step, preserving an already accepted task's identity. */
    val PERSON = setOf(GovorunSmart.NOT_AUTHORIZED, GovorunSmart.NOT_SIGNED_IN, GovorunSmart.NEEDS_ACCESS, GovorunSmart.STYLIZATION_TERMS, GovorunSmart.OFFLINE, GovorunSmart.ROAMING)

    /** The ends of a try that leave the note waiting: the screen shows the wait, not a failure. */
    val KEPT = setOf(GovorunSmart.QUEUED, GovorunSmart.TIMEOUT, CHANGED, SmartAccess.DISABLED, com.govorun.echo.NetworkGate.OFFLINE, com.govorun.echo.NetworkGate.ROAMING, com.govorun.echo.NetworkGate.NO_NETWORK) + UNREACHABLE + PERSON

    /**
     * What a try of [entry] that ended with [reason] (null: the answer is written) leaves of it, or null when nothing is left to
     * wait for. [taskId], [wait] and [message]: Govorun's task, its reason and its words.
     */
    fun next(
        entry: Entry,
        reason: String?,
        now: Long,
        taskId: String? = null,
        wait: String? = null,
        message: String = "",
    ): Entry? =
        when (reason) {
            null, GONE, RECOGNIZING -> null
            SmartAccess.DISABLED, com.govorun.echo.NetworkGate.OFFLINE, com.govorun.echo.NetworkGate.ROAMING, com.govorun.echo.NetworkGate.NO_NETWORK ->
                entry.copy(atMs = now, taskId = null, wait = reason, failed = false)
            // Govorun keeps it and says when it changes: Echo waits for that, never on a timer.
            GovorunSmart.QUEUED -> entry.copy(atMs = HELD, taskId = taskId, wait = wait, message = message, failed = false)
            // No answer within Govorun's own window: the task is still Govorun's, never cancelled from here.
            GovorunSmart.TIMEOUT -> entry.copy(atMs = HELD, taskId = taskId, wait = null, message = "", failed = false)
            in UNREACHABLE -> entry.copy(atMs = HELD, taskId = taskId ?: entry.taskId, wait = reason, message = "", failed = false)
            // Govorun rejects smart_off before accepting a task: the journalled ID is safe to clear.
            GovorunSmart.SMART_OFF -> entry.copy(atMs = HELD, taskId = null, wait = GovorunSmart.NOT_AUTHORIZED, message = "", failed = false)
            in PERSON -> if (entry.failed && entry.taskId != null) {
                // Govorun already reported this accepted task as terminal: preserve its final refusal, never submit it again.
                entry.copy(atMs = HELD, taskId = null, wait = reason, message = message, failed = true)
            } else entry.copy(atMs = HELD, taskId = taskId ?: entry.taskId, wait = reason, message = "", failed = false)
            // The person changed the text under the try: the text as it stands now, at once.
            CHANGED -> entry.copy(atMs = now, taskId = null, wait = null, message = "", failed = false)
            // The latest final refusal stays visible across reopen; no timer or automatic re-hand-in.
            else -> entry.copy(atMs = HELD, taskId = null, wait = reason, message = message, failed = true)
        }

    private val state = MutableStateFlow(0)

    /** Changes of the queue, for the screens that show a note waiting. */
    val changes: StateFlow<Int> get() = state

    @Volatile private var cache: Map<String, Entry>? = null
    @Volatile private var storageFailed = false
    private val lock = Any()

    /** The note's place in the queue for [kind], if it waits; read from memory, loaded once. */
    fun entry(context: Context, noteId: String, kind: String = FAIR): Entry? = entries(context)[key(kind, noteId)]

    private fun entries(context: Context): Map<String, Entry> = cache ?: synchronized(lock) {
        cache ?: try {
            read(file(context)).also { cache = it; storageFailed = false }
        } catch (_: Exception) {
            storageFailed = true
            emptyMap()
        }
    }

    /** A damaged journal pauses paid work, while notes and saved results remain available. */
    fun healthy(context: Context): Boolean { entries(context); return !storageFailed }

    private fun update(context: Context, change: (MutableMap<String, Entry>) -> Unit): Boolean {
        synchronized(lock) {
            val old = entries(context)
            if (storageFailed) return false
            val next = old.toMutableMap().also(change)
            if (next == old) return true
            try { SmartQueueFile.write(file(context), next) } catch (_: Exception) {
                storageFailed = true
                state.value++
                return false
            }
            cache = next
        }
        state.value++
        return true
    }

    /**
     * Asks for [noteId]'s [kind] (a «Текст» in [style], of the note's own «Текст» when [layer], or итоги) at [atMs]: a new ask
     * starts afresh and replaces a waiting one, so a note changed again before its time moves its time on.
     */
    fun add(context: Context, noteId: String, style: String?, kind: String = FAIR, atMs: Long = 0, asked: Boolean = true, layer: Boolean = false, draftId: String? = null): Boolean {
        // Recording a local intention uses no internet; preserve it until the network is allowed again.
        val saved = update(context) { Entry(noteId, style, kind, asked, layer, atMs, draftId = draftId).let { e -> it[e.key] = e } }
        schedule(context)
        return saved
    }

    /** Journal the exact id before handing it in, so process death never turns uncertainty into another submit. */
    fun submitted(context: Context, noteId: String, kind: String, taskId: String): Boolean {
        if (entry(context, noteId, kind) == null) return false
        return update(context) { rows -> rows[key(kind, noteId)]?.let { rows[it.key] = it.copy(taskId = taskId) } }
    }

    /** How a try of [noteId]'s [kind] ended (null: it is written); called once per try, by [FairRuns] and [ItogiRuns]. */
    fun settle(context: Context, noteId: String, reason: String?, kind: String = FAIR, taskId: String? = null, wait: String? = null, message: String = "", draftId: String? = null) {
        val now = System.currentTimeMillis()
        val key = key(kind, noteId)
        update(context) { entries -> entries[key]?.takeIf { it.draftId == draftId }?.let { old -> next(old, reason, now, taskId, wait, message)?.let { entries[key] = it } ?: entries.remove(key) } }
        schedule(context)
    }

    /** A local preflight refused before a task was handed in: keep only the latest reason for the note. */
    fun refuse(context: Context, noteId: String, reason: String, kind: String = FAIR, message: String = "") {
        update(context) { entries ->
            val old = entries[key(kind, noteId)] ?: Entry(noteId, null, kind)
            entries[old.key] = if (reason in PERSON || reason in UNREACHABLE) {
                checkNotNull(next(old.copy(asked = true), reason, System.currentTimeMillis(), message = message))
            } else old.copy(atMs = HELD, taskId = null, wait = reason, message = message, failed = true, asked = true)
        }
        schedule(context)
    }

    /** Discard only this editor preview; summary work and saved content are independent. */
    fun discardDraft(context: Context, noteId: String) {
        update(context) { rows -> rows[key(FAIR, noteId)]?.takeIf { it.draftId != null }?.let { rows.remove(it.key) } }
        schedule(context)
    }

    /** Notes deleted by the person: Govorun stops their tasks and forgets them. */
    suspend fun forget(context: Context, noteIds: Collection<String>) {
        val gone = entries(context).values.filter { it.noteId in noteIds }
        if (gone.isEmpty()) return
        val smart = GovorunSmart(context)
        if (Smart.available) gone.forEach { e -> e.taskId?.let { smart.cancel(it) } }
        update(context) { entries -> gone.forEach { entries.remove(it.key) } }
        schedule(context)
    }

    /** Notes the person deleted, from the list's screen: on a background thread. */
    fun deleted(context: Context, noteIds: List<String>) {
        Thread { kotlinx.coroutines.runBlocking { forget(context.applicationContext, noteIds) } }.start()
    }

    /**
     * Asks Govorun for the tasks it keeps and brings the waiting notes up to date: Govorun's words for a wait; an answer or a
     * refusal to collect under the same task id; a task Govorun no longer has (its data cleared) or never got (it could not be
     * reached) to hand in again; a deleted note's task to cancel. Called when Echo or a note is opened and when Govorun says a
     * task changed.
     */
    suspend fun sync(context: Context) {
        if (storageFailed) synchronized(lock) { cache = null }
        if (!healthy(context)) return
        val waiting = entries(context).values.filter { it.held && !it.failed }
        val tasks = if (waiting.isEmpty()) null else GovorunSmart(context).tasks()?.associateBy { it.taskId }
        if (tasks != null) {
            val notes = NoteStore.list(context).map { it.id }.toSet()
            forget(context, waiting.map { it.noteId }.filter { it !in notes })
            update(context) { entries ->
                entries.replaceAll { _, e ->
                    if (e.noteId !in notes) e else reconcile(e, e.taskId?.let(tasks::get))
                }
            }
        }
        schedule(context)
    }

    /** Read after hand-in as well as on notification: an answer may arrive before the queue records «accepted». */
    fun reconcile(entry: Entry, task: GovorunSmart.Task?): Entry = when {
        !entry.held || entry.failed -> entry
        task?.state == WAITING -> entry.copy(wait = task.wait, message = task.message)
        task?.state in setOf("done", "failed") -> entry.copy(atMs = 0, failed = task?.state == "failed")
        // Missing results or a future unknown state are never permission to execute another paid task.
        task == null && entry.taskId != null -> entry.copy(wait = "result_missing", failed = true)
        task == null -> entry.copy(atMs = 0)
        else -> entry
    }

    /** Echo was opened: called on a background thread. */
    fun opened(context: Context) = kotlinx.coroutines.runBlocking { sync(context) }

    /** The entries due at [now], oldest first: what [FairJob] works through. */
    fun canRun(entry: Entry, enabled: Boolean, auto: Boolean): Boolean = entry.taskId != null ||
        (enabled && (entry.kind != ITOGI || entry.asked || auto))

    private fun allowed(context: Context, entry: Entry): Boolean = canRun(entry, Smart.enabled(), com.govorun.echo.AppSettings.itogiAuto(context))

    fun due(context: Context, now: Long): List<Entry> = entries(context).values.filter { !it.held && it.atMs <= now && allowed(context, it) }.sortedBy { it.atMs }

    /** [FairJob] is working through the queue: scheduling the same job now would stop it, so it schedules itself when done. */
    @Volatile var working = false

    /** [FairJob] at the earliest due time, or none when nothing is to be handed in. */
    fun schedule(context: Context) {
        if (working) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!healthy(context)) { scheduler.cancel(JOB_ID); return }
        val next = entries(context).values.filter { !it.held && allowed(context, it) }.minOfOrNull { it.atMs }
        if (next == null) {
            scheduler.cancel(JOB_ID)
            return
        }
        // No network needed: Govorun takes the task in without one and waits for it itself.
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, FairJob::class.java)).setMinimumLatency((next - System.currentTimeMillis()).coerceAtLeast(0)).build()
        runCatching { scheduler.schedule(job) }
    }

    private fun file(context: Context): File {
        val target = File(context.noBackupFilesDir, "fair-queue.json")
        SmartQueueFile.migrate(File(context.filesDir, "fair-queue.json"), target)
        return target
    }

    fun read(file: File): Map<String, Entry> = SmartQueueFile.read(file)

    fun encode(entries: Map<String, Entry>): String =
        JSONObject().put("notes", JSONArray().apply {
            entries.values.forEach { e ->
                put(
                    JSONObject().put("id", e.noteId).put("kind", e.kind).put("style", e.style ?: JSONObject.NULL).put("asked", e.asked).put("layer", e.layer)
                        .put("at", e.atMs).put("task", e.taskId ?: JSONObject.NULL).put("wait", e.wait ?: JSONObject.NULL).put("message", e.message).put("failed", e.failed).apply { e.draftId?.let { put("draft", it) } },
                )
            }
        }).toString()

    /** The queue as written. */
    fun decode(text: String): Map<String, Entry> {
        val notes = JSONObject(text).getJSONArray("notes")
        fun JSONObject.text(name: String) = optString(name).takeUnless { isNull(name) || it.isBlank() }
        val entries = (0 until notes.length()).map { notes.getJSONObject(it) }.map { o ->
            val id = requireNotNull(o.text("id"))
            val kind = o.optString("kind", FAIR).also { require(it == FAIR || it == ITOGI) }
            Entry(id, o.text("style"), kind, o.optBoolean("asked", true), o.optBoolean("layer"), o.optLong("at"), o.text("task"), o.text("wait"), o.optString("message"), o.optBoolean("failed"), o.text("draft")).let { entry ->
                // Published 0.1.228 stored this pre-acceptance refusal as terminal, although granting access resolves it.
                if (entry.failed && entry.taskId == null && entry.wait == GovorunSmart.SMART_OFF)
                    entry.copy(atMs = HELD, wait = GovorunSmart.NOT_AUTHORIZED, failed = false) else entry
            }
        }
        require(entries.map { it.key }.distinct().size == entries.size)
        return entries.associateBy { it.key }
    }

    /**
     * Hands in what is due, one note at a time, until nothing is; every try moves its entry on or out ([next]). A Govorun that
     * cannot be reached ends the pass and holds the other due notes too, so it costs one try, never one per note.
     */
    suspend fun work(context: Context) {
        while (true) {
            if (!healthy(context)) return
            val entry = due(context, System.currentTimeMillis()).firstOrNull() ?: return
            val note = NoteStore.list(context).firstOrNull { it.id == entry.noteId }
            when {
                note == null -> forget(context, listOf(entry.noteId))
                entry.kind == ITOGI && note.itogi?.fresh == true && !entry.asked -> settle(context, entry.noteId, GONE, entry.kind)
                note.state != NoteStore.STATE_DONE -> settle(context, entry.noteId, RECOGNIZING, entry.kind)
                entry.kind == ITOGI -> ItogiRuns.start(context, note, entry.asked).await()
                else -> {
                    val held = entry.draftId?.let { token -> com.govorun.echo.notes.NoteEditDraft.read(NoteStore.root(context), note.id)?.takeIf { it.token == token } }
                    if (entry.draftId != null && held == null) { settle(context, note.id, GONE, draftId = entry.draftId); continue }
                    val source = held?.input ?: if (entry.layer) NoteFair.read(NoteStore.root(context), note.id, note.text)?.text else null
                    FairRuns.start(context, note, entry.style, {}, source, entry.asked, entry.draftId).outcome.await()
                }
            }
            val after = entries(context)[entry.key]?.takeIf { it.wait in UNREACHABLE } ?: continue
            val now = System.currentTimeMillis()
            update(context) { entries -> entries.replaceAll { _, e -> if (!e.held && e.atMs <= now) next(e, after.wait, now) ?: e else e } }
            return
        }
    }

    private const val WAITING = "waiting"
    private const val JOB_ID = 0x4641_4952
}
