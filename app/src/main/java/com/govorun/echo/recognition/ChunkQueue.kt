package com.govorun.echo.recognition

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Recognition may pause while the phone is hot or nearly empty; recording never does. */
internal object WorkGatePolicy {
    const val SEVERE_THERMAL = 3
    const val WARM_THERMAL = 2

    /**
     * Headroom is Android's own measure, the same on every maker's phone: 1.0 is where the system starts throttling and the
     * phone gets slow. Below [COOL] heat costs nothing, so recognition goes at full speed; it eases off only near that point.
     */
    const val COOL = 0.8f

    /** At this a second of sound takes a second (the pace of speech). */
    const val WARM = 0.9f

    /** At this recognition rests until headroom is back under [COOLED]. */
    const val HOT = 0.95f
    const val COOLED = 0.85f

    /**
     * How long to wait before the next second of sound: 0 when cool, growing smoothly to a second at [WARM] and to three at
     * [HOT], null to rest. Without a headroom reading (NaN) the coarse thermal status decides.
     */
    fun chunkDelayMs(
        headroom: Float,
        status: Int,
    ): Long? =
        when {
            headroom.isNaN() -> if (status >= SEVERE_THERMAL) null else if (status >= WARM_THERMAL) 1_000L else 0L
            headroom >= HOT -> null
            headroom >= WARM -> 1_000L + Math.round((headroom - WARM) / (HOT - WARM) * 2_000f)
            headroom >= COOL -> Math.round((headroom - COOL) / (WARM - COOL) * 1_000f).toLong()
            else -> 0L
        }

    /** The battery at 40 °C: the pace of speech; at 45 °C (fast charging in a warm room): rest until it is under 40 again. */
    const val BATTERY_WARM_TENTHS = 400
    const val BATTERY_HOT_TENTHS = 450
    const val MIN_BATTERY_PERCENT = 15

    fun allowed(
        thermalStatus: Int,
        batteryPercent: Int,
        charging: Boolean,
    ): Boolean = thermalStatus < SEVERE_THERMAL && (charging || batteryPercent !in 0 until MIN_BATTERY_PERCENT)
}

/** Why recognition is waiting, so the screen can say so instead of just sitting there. */
object RecognitionStatus {
    const val HOT = "hot"
    const val LOW_BATTERY = "low_battery"

    /** Not paused: going at the pace of speech so a warm phone does not get hot. */
    const val WARM = "warm"

    /** A file waits while a recording goes on: the recording is what the person is doing now. */
    const val RECORDING = "recording"

    /** Waits while [busy] is true, saying so; the reason goes back to none after. */
    suspend fun holdWhile(busy: () -> Boolean) {
        if (!busy()) return
        pausedReason.value = RECORDING
        try {
            while (busy()) kotlinx.coroutines.delay(1_000)
        } finally {
            if (pausedReason.value == RECORDING) pausedReason.value = null
        }
    }
    val pausedReason = MutableStateFlow<String?>(null)
}

internal class WorkGate(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    suspend fun awaitAllowed() {
        try {
            while (true) {
                val thermal = power?.currentThermalStatus ?: 0
                val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
                val charging = battery?.isCharging == true
                if (WorkGatePolicy.allowed(thermal, percent, charging)) return
                val reason = if (thermal >= WorkGatePolicy.SEVERE_THERMAL) RecognitionStatus.HOT else RecognitionStatus.LOW_BATTERY
                if (RecognitionStatus.pausedReason.value == null) {
                    com.govorun.echo.Journal.add(
                        appContext,
                        com.govorun.echo.Journal.RECOGNITION,
                        appContext.getString(if (reason == RecognitionStatus.HOT) com.govorun.echo.R.string.journal_gate_hot else com.govorun.echo.R.string.journal_gate_low),
                    )
                }
                RecognitionStatus.pausedReason.value = reason
                delay(RECHECK_MS)
            }
        } finally {
            RecognitionStatus.pausedReason.value = null
        }
    }

    /**
     * Before each second of a long file goes to Govorun, paced the way games keep a phone cool: by Android's thermal headroom
     * (0 cold, 1 where the system starts throttling), slowing down well before it, not after the phone is hot. Recognition is
     * background work and need not be fast: an hour in a quarter of an hour on a cool phone beats three minutes on a hot one.
     */
    suspend fun paceChunk() {
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val status = power?.currentThermalStatus ?: 0
        if (!WorkGatePolicy.allowed(status, percent, battery?.isCharging == true)) return awaitAllowed()
        val headroom = headroom()
        // Battery Saver is the person asking for less work, and a battery already warm (charging warms it) should not get warmer:
        // recognition goes at the pace of speech then, and rests when the battery is hot.
        val wait =
            WorkGatePolicy.chunkDelayMs(headroom, status)?.let { base ->
                when {
                    batteryTenths() >= WorkGatePolicy.BATTERY_HOT_TENTHS -> null
                    power?.isPowerSaveMode == true || batteryTenths() >= WorkGatePolicy.BATTERY_WARM_TENTHS -> maxOf(base, 1_000L)
                    else -> base
                }
            }
        val now = System.currentTimeMillis()
        if (now - loggedAt > 15_000L) {
            loggedAt = now
            android.util.Log.i("WorkGate", "event=pace headroom=$headroom status=$status battery=${batteryTenths() / 10.0} waitMs=$wait")
        }
        if (wait == null) {
            // Near the limit: rest until it has cooled well below it, so the pace does not flap at the edge.
            RecognitionStatus.pausedReason.value = RecognitionStatus.HOT
            while (headroom().let { it.isNaN() && (power?.currentThermalStatus ?: 0) >= WorkGatePolicy.WARM_THERMAL || it >= WorkGatePolicy.COOLED } || batteryTenths() >= WorkGatePolicy.BATTERY_WARM_TENTHS) delay(RECHECK_MS)
            RecognitionStatus.pausedReason.value = null
            return
        }
        RecognitionStatus.pausedReason.value = if (wait > 0) RecognitionStatus.WARM else null
        if (wait > 0) delay(wait)
    }

    private var loggedAt = 0L

    /** The battery's own temperature in tenths of a degree, from Android's sticky battery broadcast. */
    private fun batteryTenths(): Int =
        runCatching {
            appContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        }.getOrDefault(0)
    private var headroomAt = 0L
    private var lastHeadroom = Float.NaN

    /** Android answers this at most about once a second; asked more often it says NaN, so the last answer is kept for 2 s. */
    private fun headroom(): Float {
        val now = System.currentTimeMillis()
        if (now - headroomAt >= 2_000L) {
            headroomAt = now
            lastHeadroom = runCatching { power?.getThermalHeadroom(10) ?: Float.NaN }.getOrDefault(Float.NaN)
        }
        return lastHeadroom
    }

    private companion object {
        const val RECHECK_MS = 15_000L
    }
}

/** Text streamed during the recording and where each phrase sits in the audio. */
internal class LiveResult(
    val text: String,
    val phrases: List<com.govorun.echo.notes.NoteStore.Phrase>,
)

/** One worker, one note at a time: its live text if the stream ended cleanly, else the whole file. */
internal class NoteQueue(
    scope: CoroutineScope,
    private val transcriber: Transcriber,
    private val waitForGate: suspend () -> Unit,
    private val onChange: () -> Unit,
    private val onNoteDone: (String) -> Unit = {},
) {
    private class Job(
        val noteId: String,
        val live: (() -> LiveResult?)?,
        /** A recording or a short file: never asked to step aside. A long import is, however early it stands in the queue. */
        val quick: Boolean,
    )

    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()
    private val pending = AtomicInteger(0)

    // Fresh recordings first: a note just recorded never waits behind an hour-long import, which steps aside and goes on after it.
    private val urgent = Channel<Job>(Channel.UNLIMITED)
    private val channel = Channel<Job>(Channel.UNLIMITED)

    @Volatile private var working: Job? = null

    @Volatile private var workingUrgent = false

    val isIdle: Boolean get() = pending.get() == 0

    fun isWorking(noteId: String): Boolean = (inFlight[noteId]?.get() ?: 0) > 0

    init {
        scope.launch {
            while (true) {
                val job =
                    urgent.tryReceive().getOrNull()
                        ?: kotlinx.coroutines.selects.select {
                            urgent.onReceive { it }
                            channel.onReceive { it }
                        }
                working = job
                workingUrgent = job.quick
                var again = false
                try {
                    // The sound is made safe and playable first; recognition, live or from the file, comes after.
                    transcriber.archiveEarly(job.noteId)
                    val live = job.live?.invoke()
                    if (live != null) {
                        transcriber.finishWithText(job.noteId, live.text, live.phrases, fromLive = true)
                    } else {
                        waitForGate()
                        again = !transcriber.recognizeNote(job.noteId)
                    }
                    if (!again) onNoteDone(job.noteId)
                } finally {
                    working = null
                    inFlight[job.noteId]?.decrementAndGet()
                    pending.decrementAndGet()
                    // It gave way to a recording: it goes on right after the recordings, before other imports.
                    if (again) enqueue(job.noteId, null, urgent = true, quick = false)
                    onChange()
                }
            }
        }
    }

    /** A note that just stopped recording; [live] waits for the streamed text, null means use the file. It goes first. */
    fun submit(
        noteId: String,
        live: (() -> LiveResult?)? = null,
    ) {
        enqueue(noteId, live, urgent = true)
    }

    private fun enqueue(
        noteId: String,
        live: (() -> LiveResult?)?,
        urgent: Boolean,
        quick: Boolean = urgent,
    ) {
        if (quick) working?.takeIf { !workingUrgent && it.noteId != noteId }?.let { transcriber.yieldTo(it.noteId) }
        inFlight.getOrPut(noteId) { AtomicInteger(0) }.incrementAndGet()
        if (pending.incrementAndGet() > 1) transcriber.markQueued(noteId)
        (if (urgent) this.urgent else channel).trySend(Job(noteId, live, quick))
        onChange()
    }

    /** Retry or crash recovery. */
    fun requeue(
        noteId: String,
        urgent: Boolean = false,
    ) {
        if ((inFlight[noteId]?.get() ?: 0) > 0) return
        enqueue(noteId, null, urgent)
    }
}
