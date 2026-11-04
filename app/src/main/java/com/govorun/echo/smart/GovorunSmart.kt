package com.govorun.echo.smart

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.govorun.api.smart.v1.ISmartCallback
import com.govorun.api.smart.v1.ISmartService
import com.govorun.echo.recognition.GovorunProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.govorun.echo.NetworkPolicy

/**
 * Govorun's smart functions on the phone (`ISmartService`, protocol 3): its status, a task, the tasks it keeps for Echo, and the
 * screens of Govorun that settle a state (sign in, tariffs, network). Govorun holds the account, the access, the limits, «Офлайн»,
 * roaming and every wait and retry of a task, as it does for recognition; Echo only hands a task in and collects it. Every call
 * binds, asks and lets go: nothing stays connected and nothing runs on a timer. Status is answered from Govorun's memory.
 */
internal class GovorunSmart(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Govorun's answer to «may Echo ask now?»: a state of section 3 and what the settings show. */
    data class Status(
        val state: String,
        val termsVersion: Int = 0,
        val accessLabel: String = "",
        val usedToday: Int = 0,
        val limitToday: Int = 0,
        /** Govorun lends Echo the lock-screen volume key: it knows how (2.0.36+) and the person gave it. */
        val volumeKeyKnown: Boolean = false,
        val volumeKeyGranted: Boolean = false,
        /** This Govorun improves Echo's notes through its own text improvement. */
        val textTasks: Boolean = false,
        /** Govorun's styles to choose from for an improvement: its own and the person's. */
        val styles: List<Style> = emptyList(),
        /** What this Govorun can do for Echo ([NEEDED] and the like), told once Echo is let in. */
        val capabilities: Set<String> = emptySet(),
        val automaticAuthorized: Boolean = false,
    ) {
        val ready: Boolean get() = state == READY
        val automaticState: String get() = if (ready && !automaticAuthorized) NOT_AUTHORIZED else state

        companion object {
            fun of(json: String): Status =
                runCatching {
                    val o = JSONObject(json)
                    val key = o.optJSONObject("volumeKey")
                    // Govorun's bounds for an improvement, from the person's plan.
                    o.optJSONObject("textLimits")?.let { SmartFair.learn(it.optInt("minChars"), it.optInt("maxChars")) }
                    val capabilities = o.optJSONArray("capabilities")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
                    // Govorun lets Echo in but lacks something Echo needs: its one state is [UPDATE].
                    val improve = o.optBoolean("textTasks")
                    Status(
                        if (o.optString("state") == READY && !capabilities.containsAll(NEEDED)) UPDATE else o.optString("state", UNAVAILABLE), o.optInt("termsVersion"), o.optString("accessLabel"), o.optInt("usedToday"), o.optInt("limitToday"),
                        key != null, key?.optBoolean("granted") == true, improve,
                        o.optJSONArray("styles")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Style(it.optString("value"), it.optString("title")) } }.orEmpty(),
                        capabilities, o.optBoolean("smart"),
                    )
                }.getOrDefault(Status(UNAVAILABLE))
        }
    }

    /**
     * A task not answered with its result: its code (a state, [QUEUED], or a final one: too_short, rejected_input, model_answer…) and
     * Govorun's own words. [QUEUED]: Govorun keeps the task and tries it again by itself; [wait] is its reason from [tasks].
     */
    class Refusal(
        val code: String,
        val retryAfterMs: Long = 0,
        message: String = code,
        val wait: String? = null,
    ) : IOException(message) {
        /** Govorun's words, shown as they are; empty when it gave none. */
        val words: String get() = message?.takeIf { it != code }.orEmpty()
    }

    /** A task Govorun keeps for Echo: [state] waiting, done or failed; [wait] and [message] say why it waits. */
    data class Task(
        val taskId: String,
        val kind: String,
        val state: String,
        val wait: String?,
        val message: String,
    ) {
        companion object {
            fun list(json: String): List<Task> =
                try {
                    val a = org.json.JSONArray(json)
                    (0 until a.length()).map { index ->
                        val o = a.getJSONObject(index)
                        val id = o.getString("taskId").also { require(it.isNotBlank()) }
                        Task(id, o.getString("kind"), o.getString("state"), o.optString("wait").takeUnless { o.isNull("wait") || it.isBlank() }, o.optString("message"))
                    }
                } catch (e: Exception) {
                    throw IOException("invalid_smart_task_list", e)
                }
        }
    }

    /** Whether a Govorun with the smart functions is installed. */
    fun present(): Boolean = GovorunProvider.resolve(appContext, GovorunProvider.SMART_ACTION) != null

    /** A Govorun of an older protocol is [UPDATE]: its calls past createAction are another method each, so none is made. */
    suspend fun status(): Status {
        val installed = GovorunProvider.installedVersion(appContext)
        if (GovorunProvider.requiresUpdate(installed)) return Status(UPDATE)
        return call(anyProtocol = true) { if (it.protocolVersion < PROTOCOL) Status(UPDATE) else Status.of(it.getStatus(appContext.packageName)) }
            ?: Status(if (present()) UNAVAILABLE else if (installed != null) UPDATE else MISSING)
    }

    /** Updates the existing Binder contract without a new service or changing Govorun's global choices. */
    suspend fun syncNetworkPolicy(): Boolean {
        val bound = bind() ?: return false
        return try { networkLock.withLock { pushNetworkPolicy(bound.service) }; true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        finally { bound.release() }
    }

    private suspend fun pushNetworkPolicy(service: ISmartService) {
        val status = Status.of(service.getStatus(appContext.packageName))
        if (CLIENT_NETWORK !in status.capabilities) throw Refusal(UPDATE)
        val choice = NetworkPolicy.state.value
        val answer = CompletableDeferred<Result<String>>()
        service.runTask(appContext.packageName, "network-policy", NETWORK_POLICY,
            JSONObject().put("offline", Smart.clientOffline(choice.offline)).put("noRoaming", choice.noRoaming).toString(), callback(answer) { it })
        (withTimeoutOrNull(5_000) { answer.await() } ?: throw Refusal(TIMEOUT)).getOrThrow()
    }

    /** A screen of Govorun that settles a state: sign_in, subscription, network, authorize, stylization, volume_key. */
    suspend fun action(name: String): PendingIntent? = call(anyProtocol = true) { it.createAction(appContext.packageName, name) }

    /** The tasks Govorun keeps for Echo; null when Govorun cannot be asked (not installed, older, not answering). */
    suspend fun tasks(): List<Task>? {
        // Also runs in an already alive Echo after Govorun restarts: refresh permission before its queue can resume.
        if (!syncNetworkPolicy()) return null
        return call { Task.list(it.getTasks(appContext.packageName)) }
    }

    /** A local read, never an execution fallback on an older Govorun or an expired result. */
    suspend fun collect(taskId: String): String {
        val status = status()
        if (TEXT_TASKS !in status.capabilities) throw Refusal(
            status.state.takeIf { it in setOf(MISSING, UNAVAILABLE, NOT_AUTHORIZED) } ?: UPDATE,
        )
        return run(COLLECT, JSONObject(), taskId)
    }

    /** Govorun forgets the task and stops it: only for a note that was deleted. */
    suspend fun cancel(taskId: String) {
        call { it.cancelTask(appContext.packageName, taskId) }
    }

    /** One of Govorun's styles: [value] goes back with the text, [title] is Govorun's name for it. */
    data class Style(
        val value: String,
        val title: String,
    )

    /** One text transport for present and future operations. Operation acceptance/pricing belongs to the server. */
    suspend fun textTask(operation: String, text: String, requestClass: String, taskId: String, asked: Boolean, style: String? = null): String =
        JSONObject(run(TEXT_TASK, JSONObject().put("operation", operation).put("text", text)
            .put("requestClass", requestClass).put("asked", asked).apply { if (style != null) put("style", style) }, taskId)).getString("text")

    /** Local quote through the common request class; never a model submission. */
    suspend fun taskPrice(operation: String, text: String, requestClass: String): JSONObject =
        JSONObject(run(TASK_PRICE, JSONObject().put("operation", operation).put("text", text).put("requestClass", requestClass)))

    /** Runs a task once more at once when Govorun went away mid-call (killed, updated, crashed): the task is its to keep. */
    suspend fun run(
        kind: String,
        input: JSONObject,
        id: String = UUID.randomUUID().toString(),
    ): String = withContext(Dispatchers.IO) {
        try {
            runOnce(kind, input, id)
        } catch (lost: Refusal) {
            if (lost.code != LOST) throw lost
            runOnce(kind, input, id)
        }
    }

    private suspend fun runOnce(
        kind: String,
        input: JSONObject,
        id: String,
    ): String {
        val done = CompletableDeferred<Result<String>>()
        val asked = System.nanoTime()
        val bound = bind() ?: throw Refusal(if (present()) UNAVAILABLE else MISSING)
        Log.i("GovorunSmart", "event=bound kind=$kind ms=${(System.nanoTime() - asked) / 1_000_000}")
        // Govorun gone mid-task: no answer will come, so the call ends at once.
        bound.lost.invokeOnCompletion { done.complete(Result.failure(Refusal(LOST))) }
        try {
            val send = { bound.service.runTask(appContext.packageName, id, kind, input.toString(), callback(done) { it }) }
            networkLock.withLock {
                pushNetworkPolicy(bound.service)
                if (kind == COLLECT) send() else Smart.submit(send)
            }
            // Govorun answers every try within its own window; past it the task is still Govorun's, never cancelled from here:
            // it tells Echo when it changes, and Echo asks for it again.
            val result = withTimeoutOrNull(if (kind == TASK_PRICE) TASK_TIMEOUT_MS else NOTE_TIMEOUT_MS) { done.await() } ?: throw Refusal(TIMEOUT)
            val refusal = result.exceptionOrNull() as? Refusal
            // Kept by Govorun: why it waits, for the line under the note.
            if (refusal?.code == QUEUED) {
                val wait = runCatching { Task.list(bound.service.getTasks(appContext.packageName)) }.getOrNull()?.firstOrNull { it.taskId == id }?.wait
                throw Refusal(QUEUED, 0, refusal.message ?: QUEUED, wait)
            }
            return result.getOrThrow()
        } catch (_: android.os.TransactionTooLargeException) {
            throw Refusal(TOO_LONG)
        } finally {
            bound.release()
        }
    }

    private fun <T> callback(
        done: CompletableDeferred<Result<T>>,
        map: (String) -> T,
    ) = object : ISmartCallback.Stub() {
        override fun onResult(resultJson: String?) {
            done.complete(Result.success(map(resultJson.orEmpty())))
        }

        override fun onError(
            code: String?,
            message: String?,
            retryAfterMs: Long,
        ) {
            done.complete(Result.failure(Refusal(code ?: SERVER_ERROR, retryAfterMs.coerceAtLeast(0), message?.takeIf { it.isNotBlank() } ?: code ?: SERVER_ERROR)))
        }
    }

    private class Bound(
        val service: ISmartService,
        val release: () -> Unit,
        /** Completed when Govorun's process dies while Echo is bound. */
        val lost: CompletableDeferred<Unit>,
    )

    private suspend fun <T> call(
        anyProtocol: Boolean = false,
        block: (ISmartService) -> T,
    ): T? = withContext(Dispatchers.IO) {
        val bound = bind(anyProtocol) ?: return@withContext null
        try {
            block(bound.service)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w("GovorunSmart", "event=call_failed", e)
            null
        } finally {
            bound.release()
        }
    }

    /** [anyProtocol]: for the calls every protocol has in the same place (its version, status, a screen). */
    private suspend fun bind(anyProtocol: Boolean = false): Bound? {
        val component = GovorunProvider.resolve(appContext, GovorunProvider.SMART_ACTION) ?: return null
        val ready = CompletableDeferred<ISmartService?>()
        val lost = CompletableDeferred<Unit>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    ready.complete(binder?.let { ISmartService.Stub.asInterface(it) })
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    ready.complete(null)
                    lost.complete(Unit)
                }

                override fun onNullBinding(name: ComponentName?) {
                    ready.complete(null)
                }

                override fun onBindingDied(name: ComponentName?) {
                    ready.complete(null)
                    lost.complete(Unit)
                }
            }
        val started = runCatching { appContext.bindService(Intent(GovorunProvider.SMART_ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        val release = { runCatching { appContext.unbindService(connection) }; Unit }
        if (!started) {
            release()
            return null
        }
        val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { ready.await() }
        if (service == null || !anyProtocol && runCatching { service.protocolVersion }.getOrDefault(0) < PROTOCOL) {
            release()
            return null
        }
        return Bound(service, release, lost)
    }

    companion object {
        /** Govorun keeps the tasks (2.0.53+); Echo and Govorun are installed together. */
        const val PROTOCOL = 3
        const val READY = "ready"
        const val NOT_SIGNED_IN = "not_signed_in"
        const val OFFLINE = "offline"
        const val ROAMING = "roaming"
        const val NO_NETWORK = "no_network"
        const val NEEDS_ACCESS = "needs_access"
        const val LIMIT_TODAY = "limit_today"

        /** Echo's allowance for the whole period is spent. */
        const val LIMIT_PERIOD = "limit_period"
        const val UNAVAILABLE = "unavailable"
        const val NOT_AUTHORIZED = "not_authorized"

        const val TEXT_TASK = "text_task"
        const val TASK_PRICE = "text_task_price"
        const val TEXT_TASKS = "textTasks"
        const val COLLECT = "collect"
        const val CLIENT_NETWORK = "clientNetworkPolicy"
        const val NETWORK_POLICY = "client_network_policy"
        private val networkLock = Mutex()
        val NEEDED = setOf(TEXT_TASKS, CLIENT_NETWORK)

        /** Govorun keeps the task and tries it again by itself; its words say why it waits. */
        const val QUEUED = "queued"

        /** A task Echo did not press, before the person once pressed a smart function: no background итоги or improvements. */
        const val SMART_OFF = "smart_off"

        /** The model answered, twice, with something that is no итоги. */
        const val MODEL_ANSWER = "model_answer"
        const val REJECTED_INPUT = "rejected_input"

        /** Govorun's «Улучшение текста»: the terms of online improvement and the person's own styles. */
        const val ACTION_STYLIZATION = "stylization"

        /** Govorun's refusals of an improvement, besides its states. */
        const val TOO_SHORT = "too_short"
        const val TOO_LONG = "too_long"
        const val STYLIZATION_TERMS = "stylization_terms"

        /** Govorun's one window that gives Echo the lock-screen volume key. */
        const val ACTION_VOLUME_KEY = "volume_key"

        /** Echo's own: Govorun is not installed, or has no smart functions at all. */
        const val MISSING = "missing"

        /** Echo's own: Govorun lets Echo in but lacks something of [NEEDED]; it has to be updated. */
        const val UPDATE = "update"

        /** Echo's own: Govorun's process went away mid-task twice in a row; handed in again when Echo or a note is opened. */
        const val LOST = "lost"
        const val SERVER_ERROR = "server_error"
        const val TIMEOUT = "timeout"
        private const val BIND_TIMEOUT_MS = 5_000L
        private const val TASK_TIMEOUT_MS = 90_000L

        /** Govorun ends a try of a note after 140 s and answers then. */
        private const val NOTE_TIMEOUT_MS = 150_000L
    }
}
