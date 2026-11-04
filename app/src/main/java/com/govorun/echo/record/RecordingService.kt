package com.govorun.echo.record

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.govorun.echo.AppSettings
import com.govorun.echo.Journal
import com.govorun.echo.R
import com.govorun.echo.recognition.RecognitionStatus
import com.govorun.echo.recognition.LiveResult
import com.govorun.echo.recognition.LiveSession
import com.govorun.echo.recognition.NoteQueue
import com.govorun.echo.recognition.RecognitionClient
import com.govorun.echo.recognition.Transcriber
import com.govorun.echo.recognition.WorkGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import com.govorun.echo.notes.ContinuousArchive
import com.govorun.echo.notes.NoteImport
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.NoteTrash

/**
 * Foreground microphone service. Records raw PCM in chunks, recognizes finished chunks
 * through Govorun while the recording goes on, and stops itself when nothing is left to do.
 */
class RecordingService : Service() {
    @Volatile private var stopReason: String? = null

    @Volatile private var capMs = NOTE_CAP_MS

    @Volatile private var startedAtWall = System.currentTimeMillis()

    @Volatile private var currentMode = MODE_NOTE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private lateinit var transcriber: Transcriber
    private val finishing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private lateinit var queue: NoteQueue
    private var live: LiveSession? = null

    @Volatile private var speechActive = false

    @Volatile private var appendBaseMs: Long? = null

    @Volatile private var lastSpeechEndedAt = 0L

    private var worker: Thread? = null
    @Volatile private var inputSession: RecordingMicrophone? = null
    private var captureWakeLock: PowerManager.WakeLock? = null
    private var queueWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val root = NoteStore.root(this)
        val client = RecognitionClient(this)
        val gate = WorkGate(this)
        transcriber =
            Transcriber(
                root,
                client,
                keepAudio = { true },
                onFailure = { Journal.add(this, Journal.RECOGNITION, getString(com.govorun.echo.errorLabel(it))) },
                onEvent = { event -> RecordingTexts.recognitionEvent(this, event)?.let { Journal.add(this, Journal.RECOGNITION, it) } },
                onFinished = { SavedNotice.show(this, it) }, pace = { RecognitionStatus.holdWhile { worker != null && RecordingBus.pausedAt.value == null }; gate.paceChunk() },
            )
        queue =
            NoteQueue(
                scope = scope,
                transcriber = transcriber,
                waitForGate = {
                    gate.awaitAllowed()
                    // A recording is the one thing the person is doing now: no file goes on while it records (paused, Govorun is free).
                    RecognitionStatus.holdWhile { worker != null && RecordingBus.pausedAt.value == null }
                },
                onChange = ::onQueueChanged,
                onNoteDone = { noteId -> afterNoteDone(this, noteId) },
            )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_MODE) ?: MODE_NOTE, intent.getStringExtra(EXTRA_APPEND_TO), intent.getStringExtra(EXTRA_TAG))
            ACTION_STOP -> {
                if (worker != null) RecordingBus.stopping.value = true
                else { RecordingBus.state.value = RecordingState.Idle; RecordingBus.stopping.value = false; stopIfIdle() }
                Log.i(TAG, "event=stop_requested")
                stopReason = stopReason ?: STOP_USER
            }
            // «Отмена» is the last word, also over a «Стоп» the recording has not taken up yet.
            ACTION_CANCEL_RECORDING -> if (worker != null) stopReason = STOP_CANCEL.also { RecordingBus.stopping.value = true }
            ACTION_TRANSCRIBE -> intent.getStringExtra(EXTRA_NOTE_ID)?.let(::requeue)
            ACTION_POSTPONE -> intent.getStringExtra(EXTRA_NOTE_ID)?.let { transcriber.postpone(it) }
            ACTION_RESUME -> resumePending()
            ACTION_IMPORT -> intent.data?.let(::importFile)
            ACTION_KEEP -> lastSpeechEndedAt = SystemClock.elapsedRealtime().also { cues.clearWarning() }
            ACTION_PAUSE_RECORDING -> cues.pause(true)
            ACTION_CONTINUE_RECORDING -> lastSpeechEndedAt = SystemClock.elapsedRealtime().also { cues.pause(false) }
        }
        return START_NOT_STICKY
    }

    private fun start(
        mode: String,
        appendTo: String? = null,
        tag: String? = null,
    ) {
        if (worker != null) return
        RecordingBus.stopping.value = false
        RecordingBus.state.value = RecordingState.Starting
        startedAtWall = System.currentTimeMillis()
        if (!RecordingNotification.enterForeground(this, notification(recording = true))) {
            RecordingBus.state.value = RecordingState.Failed("foreground_denied", needsVisibleStart = true)
            stopSelf()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return fail("no_microphone_permission")
        }
        if (filesDir.usableSpace < StopPolicy.MIN_FREE_BYTES) return fail("storage_low")
        stopReason = null
        currentMode = mode
        capMs = if (mode == MODE_NOTE) NOTE_CAP_MS else LONG_CAP_MS
        val input = RecordingMicrophone(this, AppSettings.microphone(this)) {
            Journal.add(this, Journal.RECORDING, getString(R.string.journal_microphone_fallback))
        }
        inputSession = input
        worker = Thread({ beginCapture(input, mode, appendTo, tag) }, "echo-capture").also { it.start() }
    }

    private fun beginCapture(input: RecordingMicrophone, mode: String, appendTo: String?, tag: String?) {
        var record: AudioRecord? = null
        val startingLock = partialWakeLock("echo:input", 10_000L)
        try {
            record = input.open(BUFFER_BYTES) { stopReason != null }
            if (record == null) {
                mainHandler.post {
                    worker = null
                    if (stopReason != null) {
                        RecordingBus.state.value = RecordingState.Discarded
                        RecordingBus.stopping.value = false
                        stopReason = null
                        stopIfIdle()
                    } else fail("microphone_unavailable")
                }
                return
            }
            val prepared = prepareCapture(this, mode, appendTo, tag)
            appendBaseMs = prepared.appendBaseMs
            capturePrepared(record, prepared, input)
        } catch (error: Exception) {
            Log.e(TAG, "event=microphone_start_failed", error)
            live?.cancel()
            mainHandler.post { worker = null; fail("microphone_unavailable") }
        } finally {
            startingLock?.takeIf { it.isHeld }?.release()
            runCatching { record?.stop() }
            runCatching { record?.release() }
            input.close()
            if (inputSession === input) inputSession = null
        }
    }

    private fun capturePrepared(record: AudioRecord, prepared: PreparedCapture, input: RecordingMicrophone) {
        val (noteId, dir, startedAtMs) = prepared
        speechActive = false
        RecordingBus.speaking.value = false
        lastSpeechEndedAt = SystemClock.elapsedRealtime()
        RecordingBus.liveProblem.value = null
        liveCarry = ""
        liveYielded = false
        dictating = false
        live = newLiveSession(dir, interimOnly = false)
        Journal.add(this, Journal.RECORDING, getString(R.string.journal_rec_started, if (currentMode == MODE_NOTE) getString(R.string.journal_mode_note) else getString(R.string.journal_mode_long)))
        captureWakeLock = partialWakeLock("echo:capture", LONG_CAP_MS + WAKE_LOCK_SLACK_MS)
        cues.started()
        capture(record, dir, noteId, startedAtMs, input)
    }

    /** Text of the streams that fell away earlier in this recording: a restarted stream adds to it, nothing shown disappears. */
    @Volatile private var liveCarry = ""

    /** The text stream was let go because another app took the microphone; it comes back when the microphone does. */
    @Volatile private var liveYielded = false

    /** The person dictates with Govorun's bird right now (Govorun said so); the note pauses meanwhile if the setting allows. */
    @Volatile private var dictating = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Another app took the microphone (a call, a voice message, a stream in DayPlan): this recording now gets silence, so its text
     * stream only keeps Govorun busy. It is closed to let the other app recognize, even with a Govorun that serves one session.
     */
    private fun yieldLive() {
        val session = live ?: return
        if (session.failed) return
        liveCarry = listOf(liveCarry, session.textSoFar()).filter { it.isNotBlank() }.joinToString(" ")
        live = null
        liveYielded = true
        session.cancel()
        RecordingBus.liveProblem.value = null
        Journal.add(this, Journal.RECOGNITION, getString(R.string.journal_live_yielded))
    }

    /** The microphone is back: the text stream starts again and goes on from what was already shown. */
    private fun takeLiveBack(dir: File) {
        if (!liveYielded || worker == null) return
        liveYielded = false
        live = newLiveSession(dir, interimOnly = true, carry = liveCarry)
    }

    private fun newLiveSession(
        dir: File,
        interimOnly: Boolean,
        carry: String = "",
    ) = LiveSession(
        this,
        onText = { NoteStore.writeLiveText(dir, listOf(carry, it).filter { part -> part.isNotBlank() }.joinToString(" ")); RecordingBus.liveTextArrived() },
        onSpeech = { active ->
            speechActive = active
            RecordingBus.speaking.value = active
            if (!active) lastSpeechEndedAt = SystemClock.elapsedRealtime()
        },
        onFailed = { reason ->
            // A stream that ended can no longer say when the dictation is over: the note never stays paused because of it.
            dictating = false
            RecordingBus.liveProblem.value = reason
        },
        onDictation = { active -> dictating = active },
        onReady = {
            if (RecordingBus.liveProblem.value != null && interimOnly) Journal.add(this, Journal.RECOGNITION, getString(R.string.journal_live_back))
            RecordingBus.liveProblem.value = null
        },
        interimOnly = interimOnly,
    ).also { it.start() }

    private fun capture(
        record: AudioRecord,
        dir: File,
        noteId: String,
        startedAtMs: Long,
        input: RecordingMicrophone,
    ) {
        RecordingBus.state.value = RecordingState.Recording(noteId, currentMode, startedAtMs)
        val archive = ContinuousArchive(dir)
        val result =
            NoteCapture(
                initialRecord = record,
                dir = dir,
                mode = { currentMode },
                capMs = { capMs },
                stopRequested = { stopReason },
                environment = { recordingEnvironment(this) },
                idleMs = { recordingIdleMs(live, speechActive, lastSpeechEndedAt) },
                feed = { buffer, length -> live?.feed(buffer, length) },
                onRoute = { type -> Journal.add(this, Journal.RECORDING, getString(R.string.journal_route, Microphone.routeName(this, type))) },
                reopen = { input.open(BUFFER_BYTES) { stopReason != null } },
                acceptsInput = input::accepts,
                onMicRecovered = { Journal.add(this, Journal.RECORDING, getString(R.string.journal_mic_recovered)) },
                archive = archive,
                onSilenced = { now -> mainHandler.post { if (now) yieldLive() else takeLiveBack(dir) } },
                paused = { dictating || RecordingBus.pausedAt.value != null },
            ).run(callbackExecutor)
        input.close()
        finish(dir, noteId, startedAtMs, result, archive)
    }

    private fun finish(
        dir: File,
        noteId: String,
        startedAtMs: Long,
        result: NoteCapture.Result,
        archive: ContinuousArchive?,
    ) {
        val durationMs = NoteStore.durationMs(result.bytes)
        captureWakeLock?.takeIf { it.isHeld }?.release()
        captureWakeLock = null
        val session = live
        live = null
        val base = appendBaseMs
        appendBaseMs = null
        val cancelled = result.stopReason == STOP_CANCEL
        if (durationMs < MIN_NOTE_MS || cancelled) {
            session?.cancel()
            if (cancelled && base == null && durationMs >= MIN_NOTE_MS) {
                // «Отмена» on a new recording: its sound is closed as it is, and it waits in the trash unrecognized.
                archive?.finish()
                if (archive != null) ContinuousArchive.seal(dir)
                NoteTrash.moveCancelled(NoteStore.root(this), NoteTrash.root(this), noteId, result.bytes, result.silencedMs)
            } else {
                archive?.discard()
                // Nothing recorded, or a continuation cancelled: a continued note goes back as it was, an empty new one goes.
                if (base != null) NoteStore.putBack(dir) else NoteStore.remove(dir)
            }
            cues.stopped()
            RecordingBus.state.value = if (cancelled) RecordingState.Discarded else RecordingState.Failed("too_short")
            RecordingBus.stopping.value = false
        } else {
            NoteStore.updateMeta(dir) {
                put("durationMs", (base ?: 0L) + durationMs)
                put("bytes", optLong("bytes") * (if (base != null) 1 else 0) + result.bytes)
                put("stopReason", result.stopReason)
                put("silencedMs", optLong("silencedMs") * (if (base != null) 1 else 0) + result.silencedMs)
                put("state", NoteStore.STATE_RECOGNIZING)
            }
            // The screen is told at once; the minute still being compressed is waited for after that, before recognition starts.
            RecordingBus.state.value = RecordingState.Saved(noteId, (base ?: 0L) + durationMs, result.stopReason)
            RecordingBus.stopping.value = false
            Log.i(TAG, "event=state_saved")
            cues.saved()
            // A stream started again mid-recording gives no final text: closed at once, it does not keep Govorun busy for the file.
            if (session?.isInterim == true) session.cancel() else session?.finish()
            archive?.finish()
            if (archive != null) ContinuousArchive.seal(dir)
            queue.submit(
                noteId,
                live = session?.let { s -> { s.awaitText(LIVE_END_TIMEOUT_MS)?.let { text -> LiveResult(text, s.phrases()) } } },
            )
        }
        Log.i(TAG, "event=finished note=$noteId bytes=${result.bytes} reason=${result.stopReason}")
        RecordingTexts.stopped(this, durationMs, result.stopReason, result.silencedMs, result.pausedMs).forEach { Journal.add(this, Journal.RECORDING, it) }
        worker = null
        stopReason = null
        if (!queue.isIdle) RecordingNotification.enterForeground(this, notification(getString(R.string.recognizing_title), recording = false))
        stopIfIdle()
    }

    /** A shared file: its sound is prepared here, then the note waits for Govorun like any other. */
    private fun importFile(uri: android.net.Uri) {
        RecordingNotification.enterForeground(this, notification(getString(R.string.recognizing_title), recording = worker != null))
        // Decoding a long file takes minutes; until its note is in the queue the service has work and must not stop.
        importing.incrementAndGet()
        scope.launch {
            try {
                NoteImport.prepare(this@RecordingService, uri)?.let { queue.requeue(it, urgent = NoteImport.isQuick(NoteStore.noteDir(this@RecordingService, it))) }
            } finally {
                importing.decrementAndGet()
                stopIfIdle()
            }
        }
    }

    /** Shared files whose sound is still being decoded. */
    private val importing = java.util.concurrent.atomic.AtomicInteger(0)

    private fun requeue(noteId: String) {
        RecordingNotification.enterForeground(this, notification(getString(R.string.recognizing_title), recording = worker != null))
        // Busy-waiting: try at once. Otherwise to the queue; a recording or a short file (under 5 min) never waits behind an hour.
        if (queue.isWorking(noteId)) transcriber.retryNow() else queue.requeue(noteId, urgent = NoteImport.isQuick(NoteStore.noteDir(this, noteId)))
    }

    /** After a crash or a reboot: finish whatever was left behind. */
    private fun resumePending() {
        val active = (RecordingBus.state.value as? RecordingState.Recording)?.noteId
        val ids = NoteStore.pendingNoteIds(this, active).filterNot(NoteImport.preparing::contains)
        if (ids.isNotEmpty()) Journal.add(this, Journal.RECOGNITION, getString(R.string.journal_resume, ids.size))
        RecordingNotification.enterForeground(this, notification(getString(R.string.recognizing_title), recording = worker != null))
        ids.forEach { id ->
            if (NoteStore.isAppendable(NoteStore.noteDir(this, id))) {
                // The queue may be compressing this very note right now; only a cut-off one is finished here.
                if (!queue.isWorking(id) && finishing.add(id)) {
                    scope.launch {
                        transcriber.finishArchive(id)
                        finishing.remove(id)
                        stopIfIdle()
                    }
                }
            } else {
                queue.requeue(id, urgent = NoteImport.isQuick(NoteStore.noteDir(this, id)))
            }
        }
        stopIfIdle()
    }

    private fun onQueueChanged() {
        if (queue.isIdle) {
            queueWakeLock?.takeIf { it.isHeld }?.release()
            queueWakeLock = null
            stopIfIdle()
        } else if (queueWakeLock == null) {
            queueWakeLock = partialWakeLock("echo:recognition", QUEUE_WAKE_LOCK_MS)
        }
    }

    private fun stopIfIdle() {
        if (worker != null || !queue.isIdle || importing.get() > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopReason = stopReason ?: STOP_USER
        inputSession?.close()
        live?.cancel()
        scope.cancel()
        callbackExecutor.shutdown()
        captureWakeLock?.takeIf { it.isHeld }?.release()
        queueWakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun fail(reason: String) {
        Journal.add(this, Journal.RECORDING, getString(R.string.journal_record_failed, reason))
        RecordingBus.failed(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(
        title: String = getString(R.string.recording_title),
        recording: Boolean,
    ): Notification = RecordingNotification.build(this, title, recording, startedAtWall, recording && cues.warning)

    private val cues by lazy { RecordingCues(this, { recordingIdleMs(live, speechActive, lastSpeechEndedAt) }) { getSystemService(android.app.NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(recording = true)) } }

    companion object {
        const val ACTION_START = "com.govorun.echo.START"
        const val ACTION_STOP = "com.govorun.echo.STOP"
        const val ACTION_TRANSCRIBE = "com.govorun.echo.TRANSCRIBE"
        const val ACTION_POSTPONE = "com.govorun.echo.POSTPONE"
        const val ACTION_RESUME = "com.govorun.echo.RESUME"
        const val ACTION_IMPORT = "com.govorun.echo.IMPORT"
        const val ACTION_KEEP = "com.govorun.echo.KEEP"
        const val ACTION_PAUSE_RECORDING = "com.govorun.echo.PAUSE"
        const val ACTION_CONTINUE_RECORDING = "com.govorun.echo.CONTINUE"
        const val ACTION_CANCEL_RECORDING = "com.govorun.echo.CANCEL"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_MODE = "mode"
        const val EXTRA_APPEND_TO = "append_to"
        const val EXTRA_TAG = "tag"
        const val MODE_NOTE = "note"
        const val MODE_LONG = "long"
        const val STOP_USER = "user"
        const val STOP_CAP = "cap"
        const val STOP_SILENCE = "silence"
        const val STOP_BATTERY = "battery"
        const val STOP_STORAGE = "storage"
        const val STOP_READ_ERROR = "read_error"
        const val STOP_IO_ERROR = "io_error"
        const val STOP_CANCEL = "cancel"
        const val NOTE_CAP_MS = 3 * 60_000L
        const val LONG_CAP_MS = 12 * 60 * 60_000L
        private const val MIN_NOTE_MS = 400L
        private const val LIVE_END_TIMEOUT_MS = 90_000L
        private const val WAKE_LOCK_SLACK_MS = 60_000L
        private const val QUEUE_WAKE_LOCK_MS = 30 * 60_000L
        const val BUFFER_BYTES = 4_096
        const val NOTIFICATION_ID = 1
        const val OLD_CHANNEL_ID = "recording"
        const val CHANNEL_ID = "recording_active"
        private const val TAG = "Echo"

        fun startIntent(
            context: Context,
            mode: String,
            appendTo: String? = null,
            tag: String? = null,
        ): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode)
                .apply {
                    appendTo?.let { putExtra(EXTRA_APPEND_TO, it) }
                    tag?.let { putExtra(EXTRA_TAG, it) }
                }

        fun stopIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)

        fun pauseIntent(context: Context, on: Boolean): Intent = Intent(context, RecordingService::class.java).setAction(if (on) ACTION_PAUSE_RECORDING else ACTION_CONTINUE_RECORDING)

        fun resumeIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_RESUME)

        fun transcribeIntent(
            context: Context,
            noteId: String,
        ): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_TRANSCRIBE).putExtra(EXTRA_NOTE_ID, noteId)

        fun postponeIntent(context: Context, noteId: String): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_POSTPONE).putExtra(EXTRA_NOTE_ID, noteId)
    }
}
