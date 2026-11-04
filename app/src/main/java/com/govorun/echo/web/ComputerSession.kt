package com.govorun.echo.web

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.govorun.echo.R
import com.govorun.echo.NetworkPolicy
import com.govorun.echo.NetworkGate
import com.govorun.echo.notes.NoteChanges
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Authorization survives a stopped worker; only actual changes create outgoing work. No idle poll or ping. */
internal object ComputerSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner = Mutex()
    private val requests = NetworkGate().apply { change(null) }
    private val guard = Any()
    private var app: Context? = null
    private var data = ComputerLinkData()
    private var loaded = false
    private var watching = false
    @Volatile private var running = false
    private val state = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = state
    private val opened = MutableStateFlow(0L)
    val since: StateFlow<Long> = opened
    private val details = MutableStateFlow(ComputerLinkData())
    val info: StateFlow<ComputerLinkData> = details
    data class Transfer(val id: String, val sent: Int = 0, val total: Int = 0)
    private val transfer = MutableStateFlow<Transfer?>(null)
    val progress: StateFlow<Transfer?> = transfer

    @OptIn(FlowPreview::class)
    fun attach(context: Context) {
        synchronized(guard) {
            if (app == null) app = context.applicationContext
            if (watching) return
            watching = true
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { NoteChanges.events.debounce(350).collect { offer() } }
        scope.launch { load(); schedule() }
    }

    private fun load() = synchronized(guard) {
        if (!loaded) { data = ComputerLinkFile(requireNotNull(app)).read(); loaded = true; publish() }
    }
    private fun publish() {
        if (data.link == null || data.link?.id != details.value.link?.id) state.value = false
        opened.value = data.link?.openedAt ?: 0; details.value = data
    }
    private fun change(block: (ComputerLinkData) -> ComputerLinkData) = synchronized(guard) {
        load()
        val next = block(data)
        ComputerLinkFile(requireNotNull(app)).write(next)
        data = next; publish()
    }
    private fun current(): ComputerLinkData = synchronized(guard) { load(); data }

    suspend fun start(context: Context, code: WebExport.Pairing, device: String, selection: ComputerSelection = ComputerSelection()): Boolean = withContext(Dispatchers.IO) {
        safely(false) {
        attach(context)
        NetworkPolicy.requireAllowed()
        val link = ComputerLinkRecord(UUID.randomUUID().toString(), code, selection, System.currentTimeMillis())
        change { old -> ComputerLinkData(link, (old.closing.filterNot { it.pairing.room == code.room } + listOfNotNull(old.link?.takeIf { it.pairing.room != code.room }?.copy(checkpoint = null, dirty = false))).distinctBy { it.id }) }
        requests.change(null)
        scope.launch {
            try { deliver(context, device, cleanup = false, foreground = true) } finally { schedule() }
        }
        true
        }
    }

    fun offer() {
        if (app == null) return
        scope.launch { safely(Unit) {
            val link = current().link
            if (link != null) {
                // Private/in-progress-only edits must not create background jobs or checkpoint writes.
                if (!link.dirty && link.checkpoint != null && !link.checkpoint.optBoolean("initial", true)) {
                    val notes = NoteStore.list(requireNotNull(app)).filter(link.selection::includes)
                    if (ComputerOutbox(notes, "", emptyMap(), checkpoint = link.checkpoint).peek() == null) return@safely
                }
                change { d -> d.copy(link = d.link?.copy(dirty = true, revision = d.link.revision + 1)) }
                schedule()
            }
        } }
    }

    fun sendNow() { scope.launch {
        val context = app ?: return@launch
        safely(Unit) {
        change { it.copy(link = it.link?.copy(dirty = true), held = false, failures = 0, nextAt = 0, closingHeld = false, closingFailures = 0, closingNextAt = 0) }
        try { deliver(context, cleanup = current().link == null, foreground = true) } finally { schedule() }
        }
    } }

    /** Called only by visible session controls, never by an idle/background heartbeat. */
    suspend fun check(id: String): WebExport.Result? = withContext(Dispatchers.IO) {
        runner.withLock {
            val link = current().link?.takeIf { it.id == id } ?: return@withLock null
            if (NetworkPolicy.state.value.reason != null) return@withLock null
            val result = send(link, WebExport.signal("ping"))
            result.takeIf { current().link?.id == id }
        }
    }

    fun close() {
        scope.launch {
            var closeFirst: String? = null
            try {
                change { d ->
                    closeFirst = d.link?.id ?: d.closing.firstOrNull()?.id
                    d.copy(link = null, closing = (listOfNotNull(d.link?.copy(checkpoint = null, dirty = false)) + d.closing).distinctBy { it.id }, held = false, failures = 0, nextAt = 0, closingHeld = false, closingFailures = 0, closingNextAt = 0)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Revoking local authorization must survive even a full disk. Original notes are untouched.
                synchronized(guard) {
                    ComputerLinkFile(requireNotNull(app)).clear()
                    data = data.copy(link = null, closing = (data.closing + listOfNotNull(data.link?.copy(checkpoint = null))).distinctBy { it.id }, closingHeld = true)
                    publish()
                }
                app?.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
                requests.change(null)
                android.util.Log.w("ComputerLink", "event=close_checkpoint_failed")
                return@launch
            }
            // Revocation takes priority over an in-flight snapshot or presence check.
            requests.change(null)
            // This is an explicit action, not background maintenance. The durable queue is
            // already revoked locally; send bye now rather than waiting for JobScheduler.
            // The app scope outlives the sheet (and its coroutine) when it is dismissed.
            val context = requireNotNull(app)
            closePending(context, closeFirst)
        }
    }

    /** Reopening the controls explicitly retries an unconfirmed closure, even after backoff paused it. */
    fun retryClosing() {
        val context = app ?: return
        scope.launch {
            val d = current()
            if (d.link == null) d.closing.maxByOrNull { it.openedAt }?.let { closePending(context, it.id) }
        }
    }

    private suspend fun closePending(context: Context, id: String?) {
        schedule() // Retain the durable retry even if the process stops before the immediate attempt.
        try { deliver(context, closeFirst = id, foreground = true) } finally {
            schedule()
        }
    }

    private suspend fun <T> safely(fallback: T, block: suspend () -> T): T = try { block() } catch (error: Exception) {
        if (error is CancellationException) throw error
        synchronized(guard) { data = data.copy(held = true, closingHeld = true); publish() }
        android.util.Log.w("ComputerLink", "event=delivery_paused cause=${error.javaClass.simpleName}")
        fallback
    }

    /** A single sender owns the derived outbox. Each accepted part is checkpointed atomically. */
    suspend fun deliver(context: Context, device: String = android.os.Build.MODEL, cleanup: Boolean = true, closeFirst: String? = null, foreground: Boolean = false): WebExport.Result = withContext(Dispatchers.IO) { runner.withLock {
        running = true
        val notified = foreground && NetworkPolicy.state.value.reason == null && ComputerLinkService.begin(context, 1L)
        try { safely<WebExport.Result>(WebExport.Result.Failed) { sendPending(context, device, cleanup, closeFirst) } } finally {
            if (notified) ComputerLinkService.finish(context, 1L)
            running = false; transfer.value = null
        }
    } }

    private suspend fun sendPending(context: Context, device: String, cleanup: Boolean, closeFirst: String?): WebExport.Result {
        attach(context); load()
        val d = current()
        if (NetworkPolicy.state.value.reason != null) return WebExport.Result.Failed
        if (closeFirst != null) {
            // A stale window's retry/backoff must never block an explicit revocation.
            val target = d.closing.firstOrNull { it.id == closeFirst } ?: return WebExport.Result.Sent
            return if (sendClosure(target)) WebExport.Result.Sent else WebExport.Result.Failed
        }
        val link = d.link
        if (link?.dirty == true && !d.held && d.nextAt <= System.currentTimeMillis()) {
            transfer.value = Transfer(link.id)
            val names = mapOf("original" to context.getString(R.string.fair_tab_transcript), "typed" to context.getString(R.string.fair_tab_fair), "fair" to context.getString(R.string.fair_tab_fair), "brief" to context.getString(R.string.itogi_title))
            fun allowed() = NoteStore.list(context).filter(link.selection::includes)
            val outbox = ComputerOutbox(allowed(), device, names, checkpoint = link.checkpoint)
            while (current().link?.id == link.id) {
                currentCoroutineContext().ensureActive()
                outbox.reconcile(allowed())
                val frame = outbox.peek() ?: break
                transfer.value = Transfer(link.id, frame.body.optInt("part"), frame.body.optInt("parts", 1))
                val payload = org.json.JSONObject(frame.body.toString()).put("selection", link.selection.json())
                val result = send(link, payload)
                if (result != WebExport.Result.Sent) { failed(link.id); return result }
                outbox.accepted(frame)
                change { if (it.link?.id == link.id) it.copy(link = it.link.copy(checkpoint = outbox.checkpoint(), deliveredAt = System.currentTimeMillis()), failures = 0, nextAt = 0) else it }
                yield()
            }
            // Even an empty diff persists the restored baseline and clears a previous network error.
            change { if (it.link?.id == link.id) it.copy(link = it.link.copy(checkpoint = outbox.checkpoint(), dirty = it.link.revision != link.revision), failures = 0, nextAt = 0) else it }
        }
        val closing = current()
        for (old in if (cleanup && !closing.closingHeld && closing.closingNextAt <= System.currentTimeMillis()) closing.closing else emptyList()) {
            currentCoroutineContext().ensureActive()
            if (!sendClosure(old)) { if (link == null) return WebExport.Result.Failed; break }
        }
        return WebExport.Result.Sent
    }

    private fun sendClosure(link: ComputerLinkRecord): Boolean {
        if (send(link, WebExport.signal("bye"), closing = true) != WebExport.Result.Sent) { failed(null); return false }
        change { it.copy(closing = it.closing.filterNot { c -> c.id == link.id }, closingFailures = 0, closingNextAt = 0) }
        return true
    }

    private fun send(link: ComputerLinkRecord, body: org.json.JSONObject, closing: Boolean = false): WebExport.Result =
        runCatching {
            val timeout = if (body.optString("kind") == "ping") 2_000 else if (closing) 5_000 else 60_000
            WebExport.send(link.pairing, body, PhoneKey.get(), timeout) { connection ->
                val lease = requests.begin(connection::disconnect)
                try {
                    if (!closing && current().link?.id != link.id) throw java.io.IOException("revoked")
                    lease.check()
                    lease
                } catch (error: Exception) { lease.close(); throw error }
            }
        }.getOrDefault(WebExport.Result.Failed).also { result ->
            if (!closing) synchronized(guard) {
                if (data.link?.id == link.id) state.value = result == WebExport.Result.Sent
            }
        }

    private fun failed(id: String?) = change { d ->
        if (id != null && d.link?.id != id) d else {
            val failures = (if (id == null) d.closingFailures else d.failures) + 1
            val delay = ComputerRetry.delay(failures)
            val next = delay?.let { System.currentTimeMillis() + it + kotlin.random.Random.nextLong(it / 5 + 1) } ?: 0
            if (id == null) d.copy(closingFailures = failures, closingHeld = delay == null, closingNextAt = next)
            else d.copy(link = d.link?.copy(dirty = true), failures = failures, held = delay == null, nextAt = next)
        }
    }

    fun schedule() {
        val context = app ?: return
        synchronized(guard) {
            load()
            if (running) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (NetworkPolicy.state.value.reason != null) { scheduler.cancel(JOB_ID); return }
            val due = data.nextDue()
            if (due == null) { scheduler.cancel(JOB_ID); return }
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ComputerJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setMinimumLatency((due - System.currentTimeMillis()).coerceAtLeast(0)).build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) android.util.Log.w("ComputerLink", "event=schedule_denied")
        }
    }
    fun networkChanged() { state.value = false; scope.launch { schedule() } }
    fun serviceStopped(id: Long) { /* Transfer checkpoint belongs to the job, not to a notification. */ }
    private const val JOB_ID = 0x454348
}
