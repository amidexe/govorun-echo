package com.govorun.echo.recognition

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.govorun.api.v1.IContinuousRecognitionCallback
import com.govorun.api.v1.IRecognitionCallback
import com.govorun.api.v1.IRecognitionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import com.govorun.echo.notes.NoteStore
import java.util.UUID

/** Result of asking the installed Govorun to turn a PCM file into text. */
sealed interface Outcome {
    data class Text(
        val text: String,
        val phrases: List<com.govorun.echo.notes.NoteStore.Phrase> = emptyList(),
    ) : Outcome

    data object NotInstalled : Outcome

    data object NeedsAuthorization : Outcome

    /** Govorun is serving someone else or the owner is dictating: retry later. */
    data object Busy : Outcome

    data object ModelUnavailable : Outcome

    /** Govorun did not answer: it died, cannot be started (phone settings may stop it), or hangs. The sound is safe; try again later. */
    data object Unreachable : Outcome

    data class Failed(
        val code: Int,
    ) : Outcome
}

/** Anything that can turn a PCM file into text; the real one is Govorun, tests use a fake. */
interface Recognizer {
    suspend fun recognize(file: File): Outcome

    /** The same, reporting how far it got and the text so far, and letting [feed] slow it down or stop it. */
    suspend fun recognize(
        file: File,
        feed: RecognitionFeed,
    ): Outcome = recognize(file)
}

internal fun outcomeForError(code: Int): Outcome =
    when (code) {
        ERROR_UNAUTHORIZED -> Outcome.NeedsAuthorization
        ERROR_BUSY, ERROR_PREEMPTED -> Outcome.Busy
        ERROR_MODEL_UNAVAILABLE -> Outcome.ModelUnavailable
        else -> Outcome.Failed(code)
    }

internal const val ERROR_UNAUTHORIZED = 1
internal const val ERROR_BUSY = 2
internal const val ERROR_MODEL_UNAVAILABLE = 3
internal const val ERROR_PREEMPTED = 9
internal const val FAILED_BIND = -1
internal const val FAILED_PROTOCOL = -2
internal const val FAILED_BINDER = -3
private const val CAPABILITY_CONTINUOUS_PROFILES = 1
private const val CAPABILITY_WORD_TIMESTAMPS = 1 shl 3
private const val CAPABILITY_SHARED_ENGINE = 1 shl 4
private const val CONTINUOUS_FLAG_WORD_TIMESTAMPS = 1 shl 16
private const val MAX_TIMED_FILE_BYTES = 40L * 60 * 32_000

/** A file this short is one phrase for any Govorun; a longer one is made of many and costs Govorun memory unless it is segmented. */
internal const val SHORT_FILE_BYTES = 30L * 32_000

/**
 * A note longer than 30 seconds goes to Govorun as a stream it reads at its own speed: Govorun's own speech detector cuts it into
 * phrases and every phrase comes back with its word times, however long the file. A short one, or one for a Govorun without
 * streams, goes as a file.
 */
internal fun sendsAsStream(
    fileBytes: Long,
    continuousSupported: Boolean,
): Boolean = fileBytes > SHORT_FILE_BYTES && continuousSupported

/** Client of the Govorun Recognition API v1 (local Binder, no network). */
class RecognitionClient(
    context: Context,
) : Recognizer {
    private val appContext = context.applicationContext

    override suspend fun recognize(file: File): Outcome =
        when (val outcome = recognizeOnce(file)) {
            // A Govorun that cannot be reached is not a failure of the recording: it is waited for, like a busy one.
            is Outcome.Failed -> if (outcome.code == FAILED_BIND || outcome.code == FAILED_BINDER) Outcome.Unreachable else outcome
            else -> outcome
        }

    override suspend fun recognize(
        file: File,
        feed: RecognitionFeed,
    ): Outcome =
        when (val outcome = recognizeOnce(file, feed)) {
            is Outcome.Failed -> if (outcome.code == FAILED_BIND || outcome.code == FAILED_BINDER) Outcome.Unreachable else outcome
            else -> outcome
        }

    private suspend fun recognizeOnce(
        file: File,
        feed: RecognitionFeed? = null,
    ): Outcome =
        withService({ it }) { service, lost ->
            if (service.protocolVersion != PROTOCOL_VERSION) return@withService Outcome.Failed(FAILED_PROTOCOL)
            if (service.getAuthorizationStatus(appContext.packageName) != AUTHORIZED) {
                return@withService Outcome.NeedsAuthorization
            }
            val capabilities = service.capabilities
            val asStream = sendsAsStream(file.length(), service.supportsContinuousRecognition())
            android.util.Log.i("RecognitionClient", "event=file_path path=${if (asStream) "stream" else "file"} seconds=${file.length() / NoteStore.BYTES_PER_SECOND} capabilities=$capabilities")
            if (asStream) return@withService recognizeAsStream(service, lost, file, capabilities, feed)
            val sessionId = UUID.randomUUID().toString()
            val result = CompletableDeferred<Outcome>()
            val callback =
                object : IRecognitionCallback.Stub() {
                    override fun onFinalResult(
                        sessionId_: String?,
                        text: String?,
                    ) {
                        if (sessionId_ == sessionId) result.complete(Outcome.Text(text.orEmpty()))
                    }

                    override fun onFinalResultTimed(
                        sessionId_: String?,
                        text: String?,
                        wordStartsMs: LongArray?,
                        wordEndsMs: LongArray?,
                        words: Array<String>?,
                    ) {
                        if (sessionId_ != sessionId || wordStartsMs == null || wordEndsMs == null) return
                        result.complete(Outcome.Text(text.orEmpty(), phrasesFromWords(text.orEmpty(), wordStartsMs, wordEndsMs, words)))
                    }

                    override fun onError(
                        sessionId_: String?,
                        code: Int,
                        safeMessage: String?,
                    ) {
                        if (sessionId_ == sessionId) result.complete(outcomeForError(code))
                    }
                }
            // Word times for the file when Govorun can give them (a long recording would exceed its word limit).
            val timed = capabilities and CAPABILITY_WORD_TIMESTAMPS != 0 && file.length() <= MAX_TIMED_FILE_BYTES
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                if (timed) {
                    service.recognizeTimed(appContext.packageName, sessionId, descriptor, SAMPLE_RATE, 1, 16, callback)
                } else {
                    service.recognize(appContext.packageName, sessionId, descriptor, SAMPLE_RATE, 1, 16, callback)
                }
            }
            try {
                // Govorun dying in the middle, or answering never, ends the wait instead of hanging for ever.
                val seconds = file.length() / NoteStore.BYTES_PER_SECOND
                withTimeoutOrNull(ANSWER_BASE_MS + seconds * 1_500L) {
                    kotlinx.coroutines.selects.select<Outcome> {
                        result.onAwait { it }
                        lost.onAwait { Outcome.Unreachable }
                    }
                } ?: run {
                    runCatching { service.cancel(appContext.packageName, sessionId) }
                    Outcome.Unreachable
                }
            } catch (cancelled: CancellationException) {
                runCatching { service.cancel(appContext.packageName, sessionId) }
                throw cancelled
            }
        }

    /**
     * The file goes to Govorun as a stream it reads at its own speed; Govorun cuts it by pauses and returns the phrases in order,
     * each with its word times, so neither a long file nor its words ever travel in one piece.
     */
    private suspend fun recognizeAsStream(
        service: IRecognitionService,
        lost: CompletableDeferred<Unit>,
        file: File,
        capabilities: Int,
        feed: RecognitionFeed?,
    ): Outcome {
        val sessionId = UUID.randomUUID().toString()
        // When something happened last (a second of sound taken, a phrase back): a stall, not the file's length, ends the wait.
        val activeAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        // True while the feed holds the sound back (a hot phone cooling): no stall is counted then.
        val holding = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = CompletableDeferred<Outcome>()
        val transcript = LiveTranscript()
        val timed = java.util.concurrent.ConcurrentSkipListMap<Long, NoteStore.Phrase>()
        val callback =
            object : IContinuousRecognitionCallback.Stub() {
                override fun onReady(sessionId_: String?) = Unit

                override fun onSpeechStateChanged(
                    sessionId_: String?,
                    active: Boolean,
                ) = Unit

                override fun onFinalResult(
                    sessionId_: String?,
                    sequence: Long,
                    text: String?,
                ) {
                    if (sessionId_ == sessionId) {
                        transcript.add(sequence, text.orEmpty())
                        activeAt.set(System.currentTimeMillis())
                        feed?.heard(transcript.text(), timed.values.toList())
                    }
                }

                override fun onFinalResultTimed(
                    sessionId_: String?,
                    sequence: Long,
                    text: String?,
                    wordStartsMs: LongArray?,
                    wordEndsMs: LongArray?,
                    words: Array<String>?,
                ) {
                    if (sessionId_ != sessionId || text.isNullOrBlank() || wordStartsMs == null || wordEndsMs == null || wordStartsMs.isEmpty() || wordEndsMs.isEmpty()) return
                    timed[sequence] = timedPhrase(text, wordStartsMs, wordEndsMs, words)
                    feed?.heard(transcript.text(), timed.values.toList())
                }

                override fun onPrimaryDictation(
                    sessionId_: String?,
                    active: Boolean,
                    elapsedRealtimeMs: Long,
                ) = Unit

                override fun onStopped(sessionId_: String?) {
                    if (sessionId_ == sessionId) result.complete(Outcome.Text(transcript.text(), timed.values.toList()))
                }

                override fun onError(
                    sessionId_: String?,
                    code: Int,
                    safeMessage: String?,
                ) {
                    if (sessionId_ == sessionId) result.complete(outcomeForError(code))
                }
            }
        // With a feed the sound goes through a pipe Echo fills itself; without one Govorun reads the file at its own speed.
        val pipe = if (feed != null) ParcelFileDescriptor.createPipe() else null
        (pipe?.get(0) ?: ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { descriptor ->
            if (capabilities and CAPABILITY_CONTINUOUS_PROFILES != 0) {
                val flag = if (capabilities and CAPABILITY_WORD_TIMESTAMPS != 0) CONTINUOUS_FLAG_WORD_TIMESTAMPS else 0
                service.recognizeContinuousWithProfile(appContext.packageName, sessionId, descriptor, SAMPLE_RATE, 1, 16, flag, callback)
            } else {
                service.recognizeContinuous(appContext.packageName, sessionId, descriptor, SAMPLE_RATE, 1, 16, callback)
            }
        }
        val writer =
            pipe?.let { (_, sink) ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch(start = kotlinx.coroutines.CoroutineStart.DEFAULT) {
                    pump(file, sink, feed!!, activeAt, stopped, holding)
                }
            }
        return try {
            if (writer == null) {
                val seconds = file.length() / NoteStore.BYTES_PER_SECOND
                withTimeoutOrNull(ANSWER_BASE_MS + seconds * 1_500L) {
                    kotlinx.coroutines.selects.select<Outcome> {
                        result.onAwait { it }
                        lost.onAwait { Outcome.Unreachable }
                    }
                } ?: run {
                    runCatching { service.cancel(appContext.packageName, sessionId) }
                    Outcome.Unreachable
                }
            } else {
                awaitFed(result, lost, activeAt, stopped, holding) ?: run {
                    runCatching { service.cancel(appContext.packageName, sessionId) }
                    if (stopped.get()) Outcome.Failed(FAILED_STOPPED) else Outcome.Unreachable
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching { service.cancel(appContext.packageName, sessionId) }
            throw cancelled
        } finally {
            writer?.cancel()
        }
    }

    private var sharedEngineCache: Pair<Long, Boolean>? = null

    /**
     * Whether Govorun serves several sessions at once (a recording and a file together). Asked rarely: the answer changes only when
     * Govorun is updated. When Govorun cannot be asked, false: the careful way is to wait for the recording to end.
     */
    suspend fun supportsSharedEngine(): Boolean {
        val now = System.currentTimeMillis()
        sharedEngineCache?.takeIf { now - it.first < SHARED_ENGINE_CACHE_MS }?.let { return it.second }
        val shared = withService({ false }) { service, _ -> service.capabilities and CAPABILITY_SHARED_ENGINE != 0 }
        sharedEngineCache = now to shared
        return shared
    }

    /** Version of the installed Govorun package, even if it is too old to offer the API; null if absent. */
    fun installedVersion(): String? =
        runCatching { appContext.packageManager.getPackageInfo(PROVIDER_PACKAGE, 0).versionName }.getOrNull()

    /** Whether Govorun is present and has approved this app, without recognizing anything. */
    suspend fun status(): Outcome =
        withService({ it }) { service, _ ->
            when {
                service.protocolVersion != PROTOCOL_VERSION -> Outcome.Failed(FAILED_PROTOCOL)
                service.getAuthorizationStatus(appContext.packageName) != AUTHORIZED -> Outcome.NeedsAuthorization
                else -> Outcome.Text("")
            }
        }

    /** One-shot consent window owned by Govorun; call only from a user action. */
    suspend fun authorizationRequest(): PendingIntent? =
        withService({ null }) { service, _ ->
            if (service.getAuthorizationStatus(appContext.packageName) == AUTHORIZED) {
                null
            } else {
                service.createAuthorizationRequest(appContext.packageName)
            }
        }

    fun launchAuthorization(pending: PendingIntent): Result<Unit> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options =
                    ActivityOptions.makeBasic().apply {
                        pendingIntentBackgroundActivityStartMode =
                            if (Build.VERSION.SDK_INT >= 36) {
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                            } else {
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            }
                    }
                pending.send(appContext, 0, null, null, null, null, options.toBundle())
            } else {
                pending.send()
            }
        }

    private suspend fun <T> withService(
        unavailable: (Outcome) -> T,
        block: suspend (IRecognitionService, CompletableDeferred<Unit>) -> T,
    ): T {
        val component = resolveProvider() ?: return unavailable(Outcome.NotInstalled)
        val bound = CompletableDeferred<IRecognitionService>()
        val lost = CompletableDeferred<Unit>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    bound.complete(IRecognitionService.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    lost.complete(Unit)
                }

                override fun onBindingDied(name: ComponentName?) {
                    lost.complete(Unit)
                    bound.completeExceptionally(IllegalStateException("binding died"))
                }

                override fun onNullBinding(name: ComponentName?) {
                    bound.completeExceptionally(IllegalStateException("null binding"))
                }
            }
        val started =
            runCatching {
                appContext.bindService(
                    Intent(SERVICE_ACTION).setComponent(component),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
        if (!started) return unavailable(Outcome.Failed(FAILED_BIND))
        return try {
            val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { bound.await() } ?: return unavailable(Outcome.Failed(FAILED_BIND))
            block(service, lost)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            unavailable(Outcome.Failed(FAILED_BINDER))
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }

    private fun resolveProvider(): ComponentName? = GovorunProvider.resolve(appContext)

    private companion object {
        const val SERVICE_ACTION = GovorunProvider.SERVICE_ACTION
        const val PROVIDER_PACKAGE = GovorunProvider.PACKAGE
        const val PROTOCOL_VERSION = 1
        const val AUTHORIZED = 1
        const val SAMPLE_RATE = 16_000
        const val BIND_TIMEOUT_MS = 10_000L
        const val SHARED_ENGINE_CACHE_MS = 5L * 60_000L

        /** How long one recognition may take without any answer: 15 minutes plus one and a half times the length of the audio. */
        const val ANSWER_BASE_MS = 15L * 60_000L
    }
}
