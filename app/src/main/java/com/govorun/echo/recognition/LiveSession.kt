package com.govorun.echo.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.govorun.api.v1.IContinuousRecognitionCallback
import com.govorun.api.v1.IRecognitionService
import java.util.UUID
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.govorun.echo.notes.estimatedWords

/** Phrases that Govorun's speech detector cut out of the stream, in order. */
internal class LiveTranscript {
    private val segments = ConcurrentSkipListMap<Long, String>()

    fun add(
        sequence: Long,
        text: String,
    ) {
        if (text.isNotBlank()) segments[sequence] = text.trim()
    }

    fun text(): String = segments.values.joinToString(" ")
}

/**
 * Streams the microphone to Govorun while recording. Govorun's own speech detector decides where
 * phrases end and sends their text back, so the app never cuts speech itself. If anything goes
 * wrong (Govorun busy, dictation started in Govorun, slow reader) the session is marked failed and
 * the finished recording is recognized from the file instead; recording is never affected.
 */
internal class LiveSession(
    context: Context,
    private val onText: (String) -> Unit,
    private val onSpeech: (Boolean) -> Unit,
    /** Called once, with the reason, when the stream is given up. */
    private val onFailed: (String) -> Unit = {},
    /** Called when Govorun is ready for the audio. */
    private val onReady: () -> Unit = {},
    /** A session started again in the middle of a recording only shows text on the way; the final text then comes from the file. */
    private val interimOnly: Boolean = false,
    /** Govorun's own dictation (the bird) started or ended while this stream was open; Govorun skips the stream meanwhile. */
    private val onDictation: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val sessionId = UUID.randomUUID().toString()
    private val transcript = LiveTranscript()
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_BUFFERS)
    private val ready = CountDownLatch(1)
    private val ended = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())
    private val waitForReady = Runnable { fail("not_ready") }
    private val fedBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val arrivals = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, String>>()
    private val timed = ConcurrentSkipListMap<Long, com.govorun.echo.notes.NoteStore.Phrase>()
    @Volatile private var service: IRecognitionService? = null
    private val connection = java.util.concurrent.atomic.AtomicReference<ServiceConnection>()
    @Volatile private var output: ParcelFileDescriptor? = null

    private val failure = AtomicBoolean(false)
    val failed: Boolean get() = failure.get()

    @Volatile private var stopped = false

    @Volatile private var closing = false
    @Volatile private var inputEnded = false

    /** Connects in the background; returns at once. */
    fun start() {
        val component = GovorunProvider.resolve(appContext) ?: return fail("not_installed")
        val link =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    Thread({ begin(IRecognitionService.Stub.asInterface(binder)) }, "live-writer").start()
                }

                override fun onServiceDisconnected(name: ComponentName?) = lost()

                override fun onBindingDied(name: ComponentName?) = lost()

                override fun onNullBinding(name: ComponentName?) = fail("null_binding")
            }
        connection.set(link)
        main.postDelayed(waitForReady, READY_TIMEOUT_MS)
        val bound =
            runCatching {
                appContext.bindService(Intent(GovorunProvider.SERVICE_ACTION).setComponent(component), link, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
        if (!bound) fail("bind_failed")
    }

    private fun begin(binder: IRecognitionService) {
        if (failed) return
        try {
            service = binder
            check(binder.protocolVersion == 1) { "protocol" }
            check(binder.getAuthorizationStatus(appContext.packageName) == 1) { "unauthorized" }
            check(binder.supportsContinuousRecognition()) { "no_continuous" }
            val pipe = ParcelFileDescriptor.createPipe()
            output = pipe[1]
            val callback = callback()
            pipe[0].use { input ->
                if (failed) {
                    pipe[1].close()
                    return
                }
                if (binder.capabilities and CAPABILITY_CONTINUOUS_PROFILES != 0) {
                    val flag =
                        (if (binder.capabilities and CAPABILITY_WORD_TIMESTAMPS != 0) FLAG_WORD_TIMESTAMPS else 0) or
                            (if (binder.capabilities and CAPABILITY_DICTATION_SIGNAL != 0) FLAG_DICTATION_SIGNAL else 0)
                    val profile = PROFILE_FAST
                    binder.recognizeContinuousWithProfile(appContext.packageName, sessionId, input, SAMPLE_RATE, 1, 16, profile or flag, callback)
                } else {
                    binder.recognizeContinuous(appContext.packageName, sessionId, input, SAMPLE_RATE, 1, 16, callback)
                }
            }
            if (failed) {
                cancelRemote()
            } else {
                writeLoop()
            }
        } catch (error: Exception) {
            fail(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Never blocks: a full queue means Govorun cannot keep up, and the live text is given up. */
    fun feed(
        buffer: ByteArray,
        length: Int,
    ) {
        if (failed || closing) return
        if (!queue.offer(buffer.copyOf(length))) fail("overflow") else fedBytes.addAndGet(length.toLong())
    }

    /** Tells Govorun the audio is over; its last phrase and the end marker follow. */
    fun finish() {
        closing = true
        if (!failed) queue.offer(END)
    }

    /** The full text when the stream ended cleanly, or null when the file must be recognized instead. */
    fun awaitText(timeoutMs: Long): String? {
        if (interimOnly) return null
        val waitedFrom = System.nanoTime()
        if (!ended.await(timeoutMs, TimeUnit.MILLISECONDS)) fail("finish_timeout")
        Log.i(TAG, "event=live_awaited ms=${(System.nanoTime() - waitedFrom) / 1_000_000} stopped=$stopped failed=$failed")
        val text = if (stopped && !failed) transcript.text() else null
        release()
        return text
    }

    /** A stream started again in the middle of a recording: its text is only shown, the note's text comes from the file. */
    val isInterim: Boolean get() = interimOnly

    /** What Govorun has returned so far, so a restarted stream can go on from it. */
    fun textSoFar(): String = transcript.text()

    /** Phrases with approximate times; the audio position when Govorun answered is the only clock. */
    fun phrases(): List<com.govorun.echo.notes.NoteStore.Phrase> =
        if (timed.isNotEmpty()) timed.values.toList() else phrasesFrom(arrivals.toList(), latencyMs = 900L)

    fun cancel() {
        closing = true
        fail("cancelled")
    }

    private fun writeLoop() {
        val stream = output?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) } ?: return
        try {
            if (!ready.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return fail("not_ready")
            stream.use { out ->
                while (!failed) {
                    val buffer = queue.poll(1, TimeUnit.SECONDS)
                    if (buffer == null) {
                        if (closing) break
                        continue
                    }
                    if (buffer === END) break
                    out.write(buffer)
                }
                if (!failed) inputEnded = true
            }
        } catch (error: Exception) {
            fail("write_${error.javaClass.simpleName}")
        }
    }

    private fun callback() =
        object : IContinuousRecognitionCallback.Stub() {
            override fun onReady(sessionId_: String?) {
                if (sessionId_ == sessionId && !failed) {
                    main.removeCallbacks(waitForReady)
                    ready.countDown()
                    onReady()
                }
            }

            override fun onSpeechStateChanged(
                sessionId_: String?,
                active: Boolean,
            ) {
                if (sessionId_ != sessionId || failed) return
                Log.i(TAG, "event=live_speech active=$active fedMs=${fedBytes.get() * 1_000L / BYTES_PER_SECOND}")
                onSpeech(active)
            }

            override fun onFinalResult(
                sessionId_: String?,
                sequence: Long,
                text: String?,
            ) {
                if (sessionId_ != sessionId || failed) return
                Log.i(TAG, "event=live_phrase seq=$sequence chars=${text.orEmpty().length} fedMs=${fedBytes.get() * 1_000L / BYTES_PER_SECOND}")
                transcript.add(sequence, text.orEmpty())
                if (!text.isNullOrBlank()) arrivals += (fedBytes.get() * 1_000L / BYTES_PER_SECOND) to text.trim()
                onText(transcript.text())
            }

            override fun onFinalResultTimed(
                sessionId_: String?,
                sequence: Long,
                text: String?,
                wordStartsMs: LongArray?,
                wordEndsMs: LongArray?,
                words: Array<String>?,
            ) {
                if (sessionId_ != sessionId || failed || text.isNullOrBlank() || wordStartsMs == null || wordEndsMs == null || wordStartsMs.isEmpty() || wordEndsMs.isEmpty()) return
                timed[sequence] = timedPhrase(text, wordStartsMs, wordEndsMs, words)
            }

            override fun onPrimaryDictation(
                sessionId_: String?,
                active: Boolean,
                elapsedRealtimeMs: Long,
            ) {
                if (sessionId_ != sessionId || failed) return
                Log.i(TAG, "event=primary_dictation active=$active fedMs=${fedBytes.get() * 1_000L / BYTES_PER_SECOND}")
                onDictation(active)
            }

            override fun onStopped(sessionId_: String?) {
                if (sessionId_ != sessionId || failed) return
                if (!inputEnded) return fail("unexpected_stop")
                stopped = true
                ended.countDown()
            }

            override fun onError(
                sessionId_: String?,
                code: Int,
                safeMessage: String?,
            ) {
                if (sessionId_ == sessionId) fail("govorun_$code")
            }
        }

    private fun lost() {
        if (!stopped) fail("disconnected")
    }

    private fun fail(reason: String) {
        if (!failure.compareAndSet(false, true)) return
        Log.i(TAG, "event=live_failed reason=$reason")
        main.removeCallbacks(waitForReady)
        queue.clear()
        queue.offer(END)
        ended.countDown()
        ready.countDown()
        runCatching { output?.close() }
        release()
        onFailed(reason)
        // A failed remote call must never hold up the microphone or the main thread.
        if (service != null) Thread(::cancelRemote, "live-cancel").start()
        if (reason != "cancelled" && !closing && !interimOnly) {
            com.govorun.echo.Journal.add(
                appContext,
                com.govorun.echo.Journal.RECOGNITION,
                appContext.getString(if (reason == "govorun_9") com.govorun.echo.R.string.journal_live_preempted else com.govorun.echo.R.string.journal_live_failed, reason),
            )
        }
    }

    private fun cancelRemote() {
        runCatching { service?.cancel(appContext.packageName, sessionId) }
    }

    private fun release() {
        connection.getAndSet(null)?.let { link ->
            runCatching { appContext.unbindService(link) }
        }
    }

    private companion object {
        const val TAG = "LiveSession"
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SECOND = 32_000L
        const val CAPABILITY_CONTINUOUS_PROFILES = 1
        const val CAPABILITY_WORD_TIMESTAMPS = 1 shl 3
        const val FLAG_WORD_TIMESTAMPS = 1 shl 16

        /** Govorun tells this stream when its own dictation starts and ends, instead of ending the stream (Govorun 2.0.27). */
        const val CAPABILITY_DICTATION_SIGNAL = 1 shl 5
        const val FLAG_DICTATION_SIGNAL = 1 shl 17
        const val PROFILE_DICTATION = 0

        /** Govorun's quick profile: a phrase ends after 0.5 s of silence instead of the pause chosen in Govorun. */
        const val PROFILE_FAST = 1
        const val MAX_QUEUED_BUFFERS = 400
        /** Govorun may need a while to wake its model (a slower phone, a cold start); what is spoken meanwhile waits in the queue (about 50 s). */
        const val READY_TIMEOUT_MS = 45_000L
        val END = ByteArray(0)
    }
}

/**
 * Govorun answers a moment after a phrase ends, so a phrase ends about [latencyMs] before its answer
 * and began roughly as long before that as its text takes to say. Word-level times would need
 * timestamps from Govorun itself; this is only good enough to start playing near a phrase.
 */
internal fun phrasesFrom(
    arrivals: List<Pair<Long, String>>,
    latencyMs: Long = 1_000L,
    msPerChar: Long = 70L,
): List<com.govorun.echo.notes.NoteStore.Phrase> {
    var previousEnd = 0L
    return arrivals.map { (arrivalMs, text) ->
        val end = maxOf(previousEnd, arrivalMs - latencyMs)
        val start = maxOf(previousEnd, end - text.length * msPerChar - 400L)
        previousEnd = end
        com.govorun.echo.notes.NoteStore.Phrase(start, end, text, estimatedWords(text, start, end))
    }
}

/** A phrase of the live stream with the times Govorun gave for its words ([alignedWords]). */
internal fun timedPhrase(
    text: String,
    starts: LongArray,
    ends: LongArray,
    words: Array<String>? = null,
): com.govorun.echo.notes.NoteStore.Phrase =
    com.govorun.echo.notes.NoteStore.Phrase(starts.first(), ends.last(), text.trim(), alignedWords(text, starts, ends, words))
